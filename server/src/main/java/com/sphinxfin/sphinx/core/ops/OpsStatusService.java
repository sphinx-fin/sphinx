package com.sphinxfin.sphinx.core.ops;

import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.core.ops.OpsStatus.Component;
import com.sphinxfin.sphinx.core.ops.OpsStatus.Fact;
import com.sphinxfin.sphinx.core.ops.OpsStatus.Health;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 운영 콘솔(S-09)이 읽는 실측 — <b>「떠 있는데 못 하는 상태」를 갈라 준다</b> (이슈 #522).
 * 소유: 강희진
 *
 * <h2>왜 서버가 모아야 하나</h2>
 *
 * <p>브라우저가 닿을 수 있는 것은 {@code /api} 뿐이다 — ai-service·MySQL 은 구조상 안 보인다
 * (P3 네트워크 격리가 그렇게 세워 둔 것이다). 그래서 상태를 모으는 일은 server 가 한다.
 *
 * <h2>ai-service 는 셋으로 실패하는데 겉모습이 같았다</h2>
 *
 * <pre>
 * ① 안 떠 있다                        → AI_SERVICE_UNAVAILABLE
 * ② 떴는데 LLM 키가 없다               → AI_SERVICE_UNAVAILABLE
 * ③ 떴고 키도 있는데 공유 시크릿이 어긋나 401 → AI_SERVICE_UNAVAILABLE
 * </pre>
 *
 * <p>셋이 같은 502 하나였다. {@code walk_demo_session.sh} 가 ①②를 갈라 주지만 그건 CLI 이고
 * <b>시연 중에 터미널을 여는 것이 곧 사고</b>다. 이 서비스가 그 구별을 화면 값으로 만든다.
 *
 * <h2>❗{@code /actuator/health} 에 얹지 않는다</h2>
 *
 * <p>{@code scripts/deploy_ec2.sh} 의 {@code wait_healthy()} 가 그 경로를 300초 기다리고
 * 안 되면 <b>새 색을 내리고 배포를 실패</b>시킨다. 상류 검사를 그리 넣으면 <b>LLM 키 하나가
 * 비면 컷오버가 통째로 실패</b>한다. 헬스체크는 <i>"나를 재시작·컷오버해도 되는가"</i> 를
 * 답하는 자리이고 콘솔은 <i>"지금 무엇이 안 되는가"</i> 를 답하는 자리다 — 같은 질문이
 * 아니다. 게다가 그 경로는 무인증으로 열려 있어({@code SecurityConfig.PUBLIC_PATHS}) 상세를
 * 켜면 DB 접속 정보가 그대로 나간다.
 *
 * <h2>캐시하지 않는다</h2>
 *
 * <p>매 호출이 실측이다. 캐시하면 화면의 시각과 값이 갈려서 <i>"방금 고쳤는데 화면이
 * 안 바뀐다"</i> 가 된다 — 이 화면이 존재하는 이유와 정면으로 어긋난다.
 */
@Service
@Slf4j
public class OpsStatusService {

    /** 이 JVM 이 뜬 시각. 스프링 컨텍스트가 이 빈을 만드는 시점이라 기동 시각과 같다. */
    private final Instant startedAt = Instant.now();

    private final AiServiceClient aiServiceClient;
    private final DataSource dataSource;
    private final Environment environment;
    private final Path timeseriesDir;
    private final Path documentsDataDir;
    private final String stack;

    public OpsStatusService(AiServiceClient aiServiceClient,
                            DataSource dataSource,
                            Environment environment,
                            @Value("${sphinx.simulator.timeseries-dir}") String timeseriesDir,
                            @Value("${sphinx.documents.data-dir}") String documentsDataDir,
                            // compose 가 넘긴다. **로컬은 빈 문자열이 정상**이라 기본값을 둔다 —
                            // SimulatorProperties 처럼 세우면 로컬에서 기동이 안 된다.
                            @Value("${sphinx.deployment.stack:}") String stack) {
        this.aiServiceClient = aiServiceClient;
        this.dataSource = dataSource;
        this.environment = environment;
        this.timeseriesDir = Path.of(timeseriesDir);
        this.documentsDataDir = Path.of(documentsDataDir);
        this.stack = stack == null ? "" : stack.trim();
    }

    /** 지금 상태를 잰다. 넷을 순서대로 — 화면이 그 순서로 카드를 놓는다. */
    public OpsStatus measure() {
        Instant now = Instant.now();
        return new OpsStatus(
                now.toString(),
                new OpsStatus.Deployment(activeProfile(), stack, startedAt.toString(),
                        Duration.between(startedAt, now).toSeconds()),
                List.of(isolated("server", "API 서버", this::server),
                        isolated("database", "데이터베이스", this::database),
                        isolated("ai-service", "AI 서비스", this::aiService),
                        isolated("data-volumes", "데이터 볼륨", this::dataVolumes)));
    }

    /**
     * ❗<b>한 카드의 측정이 터져도 나머지를 낸다.</b>
     *
     * <p>이 엔드포인트가 약속하는 것이 <i>"상태를 그리는 경로는 상태 때문에 죽지 않는다"</i>
     * 인데, 측정 하나가 예외를 내면 응답 전체가 500 이 되어 <b>화면이 아무것도 못 그린다</b> —
     * 정작 무언가 잘못된 순간에 콘솔이 같이 눕는다.
     *
     * <p>실제로 그렇게 났다({@code EnvelopeContractTest} 가 잡았다). ai-service 측정이
     * 예상 못 한 null 을 만나 NPE 였고, 그 하나 때문에 <b>DB·마운트 카드까지 사라졌다</b>.
     * 그 상황에서 사람이 봐야 하는 것은 «AI 카드가 이상하다» 인데 화면은 «콘솔이 고장났다»
     * 를 보여줬다.
     *
     * <p>그래서 터진 카드만 {@code DOWN} + <i>"측정 실패"</i> 로 낸다. 예외를 삼키지 않고
     * <b>로그에 스택트레이스를 남긴다</b> — 응답에는 한 줄만 가고(#522 요청), 원인은 로그에서 본다.
     */
    private Component isolated(String id, String name, java.util.function.Supplier<Component> measure) {
        try {
            return measure.get();
        } catch (RuntimeException e) {
            log.error("운영 상태 측정 실패: component={} — 나머지 카드는 그대로 낸다", id, e);
            return new Component(id, name, Health.DOWN, null,
                    "상태를 재지 못했다(" + oneLine(e.toString()) + ") — 서버 로그를 본다",
                    List.of());
        }
    }

    /* ── server ─────────────────────────────────────────────────────────────
     * 항상 UP 이다 — 이 코드가 돌고 있다는 것이 증거다. 카드를 두는 이유는 상태가 아니라
     * **facts** 다: 어느 프로파일로 떴고 인가가 켜져 있는가.                          */
    private Component server() {
        boolean enforce = environment.getProperty("sphinx.security.enforce", Boolean.class, false);
        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact("프로파일", activeProfile()));
        // ❗인가 스위치를 싣는다. 꺼져 있으면 «막고 있다» 가 성립하지 않는데(ADR-001 시연이
        //   그 위에 서 있다) 기동 로그 한 줄로만 남아서 뜬 뒤에 볼 사람이 없다.
        facts.add(new Fact("접근 통제", enforce ? "켜짐" : "꺼짐 — @PreAuthorize 가 통과만 시킨다"));
        return new Component("server", "API 서버", Health.UP, null,
                enforce ? null : "인가가 꺼져 있다 — 역할 차단이 시연되지 않는다", facts);
    }

    /* ── database ───────────────────────────────────────────────────────────
     * 연결 + isValid. H2 는 **DEGRADED 로 두지 않는다** — 로컬에서 상시 노랑이면 노랑이
     * 아무 뜻도 없어진다(#522 판단). note 로만 적는다.                              */
    private Component database() {
        long startedNanos = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            int elapsedMs = (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
            DatabaseMetaData meta = connection.getMetaData();
            String product = meta.getDatabaseProductName();
            List<Fact> facts = List.of(
                    new Fact("종류", product + " " + meta.getDatabaseProductVersion()),
                    // ❗`?` 앞까지만. 지금 거기 비밀이 없지만, 누가 파라미터로 자격증명을
                    //   붙이는 날 화면이 그걸 그린다.
                    new Fact("접속", withoutQuery(meta.getURL())),
                    new Fact("스키마 관리", environment.getProperty(
                            "spring.jpa.hibernate.ddl-auto", "(미설정)")));
            boolean inMemory = product != null && product.toUpperCase().contains("H2");
            return new Component("database", "데이터베이스",
                    valid ? Health.UP : Health.DOWN,
                    elapsedMs,
                    inMemory ? "인메모리 — 재기동하면 세션·감사 기록이 사라진다"
                             : valid ? null : "연결은 됐지만 isValid 가 거짓이다",
                    facts);
        } catch (SQLException e) {
            log.warn("운영 상태: DB 연결 실패 — {}", e.getMessage());
            return new Component("database", "데이터베이스", Health.DOWN, null,
                    oneLine(e.getMessage()), List.of());
        }
    }

    /* ── ai-service ─────────────────────────────────────────────────────────
     * 이 카드가 이 엔드포인트의 요점이다. 셋을 가른다.                              */
    private Component aiService() {
        AiServiceClient.HealthProbe probe = aiServiceClient.health();
        if (probe == null || probe.report() == null) {
            // ① 안 떠 있다 · 연결 실패 · non-2xx
            return new Component("ai-service", "AI 서비스", Health.DOWN,
                    probe == null ? null : probe.latencyMs(),
                    oneLine(probe == null ? null : probe.error()), List.of());
        }
        AiServiceClient.AiHealth report = probe.report();

        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact("모델", blankToDash(report.llmModel())));
        // 프롬프트·오해 라이브러리 버전은 **이미지 세대**를 잡는다 — started_at 이 못 잡는
        // 것이다(#522 리뷰). 같은 시각에 뜬 두 컨테이너도 이미지가 다르면 갈린다.
        if (report.promptVersions() != null) {
            report.promptVersions().forEach((feature, version) ->
                    facts.add(new Fact("프롬프트 " + feature, version)));
        }
        if (report.misconceptionLibraryVersion() != null) {
            facts.add(new Fact("오해 라이브러리", "v" + report.misconceptionLibraryVersion()));
        }
        if (report.startedAt() != null) {
            // ❗로컬은 `uvicorn --reload` 라 이 값이 컨테이너가 아니라 **워커**의 기동 시각이다
            //   — 파일을 고칠 때마다 바뀐다(#522 리뷰). 문면에 그 조건을 같이 낸다.
            facts.add(new Fact("기동", report.startedAt()));
        }
        facts.add(new Fact("내부 인증", internalAuthFact(report)));
        facts.add(new Fact("데이터 디렉토리",
                blankToDash(report.dataDir()) + " (" + blankToDash(report.dataDirEnv()) + ")"));
        // 적용된 값과 요청값이 다르면 오타가 있었다는 뜻이다(#121 리뷰) — 그 자체가 근거다.
        facts.add(new Fact("로그 레벨", logLevelFact(report)));

        List<String> problems = new ArrayList<>();
        if (!Boolean.TRUE.equals(report.llmConfigured())) {
            // ② 떴는데 키가 없다 — 채점(F-SCR-001)이 502 로 떨어진다. 이것이 UP 으로 보이던
            //    자리다: /healthz 는 200 이고 컨테이너도 healthy 다.
            problems.add("LLM 키가 없다 — 채점(F-SCR-001)이 502 로 떨어진다");
        }
        if (report.internalAuthEnabled() != aiServiceClient.hasInternalToken()) {
            // ③ 한쪽만 인증이 켜졌다. 서버가 토큰 없이 부르면 /internal/* 이 전부 401 이고,
            //    반대면 인증이 선언만 된 상태다 — 둘 다 «떠 있는데 못 하는» 자리다.
            problems.add(report.internalAuthEnabled()
                    ? "ai-service 는 내부 인증을 요구하는데 서버에 토큰이 없다 — /internal/* 이 전부 401 이다"
                    : "서버는 토큰을 보내는데 ai-service 가 인증을 끄고 있다 — 마지막 방어선이 안 돈다");
        }
        if (!logLevelMatches(report)) {
            problems.add("로그 레벨 요청값과 적용값이 다르다 — 환경변수에 오타가 있다");
        }
        Health health = problems.isEmpty() ? Health.UP : Health.DEGRADED;
        return new Component("ai-service", "AI 서비스", health, probe.latencyMs(),
                problems.isEmpty() ? null : String.join(" · ", problems), facts);
    }

    /* ── data-volumes ───────────────────────────────────────────────────────
     * #37 이 지적한 실패 양식이 그대로 남아 있다 — *"컨테이너에 data/ 가 없는데 조용히
     * 넘어가는 것"*. SimulatorProperties 가 기동 로그 **한 줄**로만 남기는데 뜬 뒤에 그 줄을
     * 다시 볼 사람이 없다.                                                            */
    private Component dataVolumes() {
        Path documents = documentsDataDir.resolve("documents");
        boolean timeseriesThere = Files.isDirectory(timeseriesDir);
        boolean documentsThere = Files.isDirectory(documents);

        List<Fact> facts = List.of(
                new Fact("지수 시계열", mountFact(timeseriesDir, timeseriesThere)),
                new Fact("상품 원문", mountFact(documents, documentsThere)));

        Health health;
        String note;
        if (timeseriesThere && documentsThere) {
            health = Health.UP;
            note = null;
        } else if (timeseriesThere || documentsThere) {
            health = Health.DEGRADED;
            // 무엇이 죽는지를 적는다 — «마운트가 없다» 만으로는 다음 행동이 안 정해진다.
            note = timeseriesThere
                    ? "상품 원문이 없다 — 원문 조회(S-02 모달)와 추출이 죽는다"
                    : "지수 시계열이 없다 — 시뮬레이터(F-SIM-001)가 죽는다";
        } else {
            health = Health.DOWN;
            note = "읽기 전용 마운트가 둘 다 없다 — 컨테이너에 data/ 가 안 붙었다(#37)";
        }
        return new Component("data-volumes", "데이터 볼륨", health, null, note, facts);
    }

    private String mountFact(Path path, boolean there) {
        return path.toAbsolutePath().normalize() + (there ? " — 있음" : " — 없음");
    }

    private String activeProfile() {
        String[] active = environment.getActiveProfiles();
        // 프로파일이 없으면 기본 프로파일이다. 빈 문자열로 내면 화면이 «모른다» 로 그린다.
        return active.length == 0 ? "default" : String.join(",", active);
    }

    private String internalAuthFact(AiServiceClient.AiHealth report) {
        String ai = report.internalAuthEnabled() ? "ai-service 켜짐" : "ai-service 꺼짐";
        String server = aiServiceClient.hasInternalToken() ? "서버 토큰 있음" : "서버 토큰 없음";
        String required = Boolean.TRUE.equals(report.internalAuthRequired())
                ? " · 기동 시 필수" : "";
        return ai + " / " + server + required;
    }

    private String logLevelFact(AiServiceClient.AiHealth report) {
        String applied = blankToDash(report.logLevel());
        return logLevelMatches(report) ? applied
                : applied + " (요청값 " + blankToDash(report.logLevelRequested()) + ")";
    }

    /** 요청값이 없으면(기본값으로 떴으면) 어긋난 것이 아니다 — null 을 불일치로 세지 않는다. */
    private boolean logLevelMatches(AiServiceClient.AiHealth report) {
        String requested = report.logLevelRequested();
        if (requested == null || requested.isBlank()) {
            return true;
        }
        return requested.equalsIgnoreCase(report.logLevel());
    }

    /** JDBC URL 의 {@code ?} 앞까지. 쿼리 파라미터에 자격증명이 붙는 날을 대비한다. */
    private static String withoutQuery(String url) {
        if (url == null) {
            return "-";
        }
        int question = url.indexOf('?');
        return question < 0 ? url : url.substring(0, question);
    }

    /** 예외 문면은 한 줄만. 스택트레이스는 서버 로그로 간다(#522 요청). */
    private static String oneLine(String message) {
        if (message == null || message.isBlank()) {
            return "원인을 알 수 없다 — 서버 로그를 본다";
        }
        String first = message.lines().findFirst().orElse(message).trim();
        return first.length() > 300 ? first.substring(0, 300) + "…" : first;
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
