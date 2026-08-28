package com.sphinxfin.sphinx.aggregate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sphinxfin.sphinx.core.session.Session;
import com.sphinxfin.sphinx.core.session.SessionRepository;
import com.sphinxfin.sphinx.domain.Channel;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-DSH-003 합성 세션 적재. 소유: 정세현
 *
 * <p>기획서 F-DSH-001 이 <i>"MVP에서는 <b>합성 세션 데이터로 구동</b>"</i> 이라고 적은 그것이다.
 * {@code MIN_CELL_SAMPLE = 30} 이라 데모에서 손으로 만드는 수십 건은 상품×항목으로 쪼개지면
 * <b>거의 모든 칸이 가려진다</b> — 화면은 정상 동작인데 보여줄 것이 없다(이슈 #179).
 *
 * <h2>생성하지 않고 읽기만 한다</h2>
 *
 * <p>분포는 {@code scripts/gen_synth_sessions.py} 가 만들고 산출물이
 * {@code data/synth_sessions/sessions.json} 으로 커밋돼 있다. 여기서 생성하지 않는 이유는
 * 오해율의 근거가 <b>루브릭의 {@code related_misconceptions}</b> 인데 그 파일이
 * {@code ai-service/app/rubrics/} 에 있기 때문이다 — 서버가 거기를 읽으면 모듈 경계를 넘고,
 * 매핑을 복제하면 두 벌이 되어 갈린다.
 *
 * <h2>시각은 적재 시점에 계산한다</h2>
 *
 * <p>산출물에는 절대 시각이 없고 {@code weeksAgo}·{@code dayOfWeek}·{@code hour} 만 있다.
 * 절대 시각을 구우면 <b>몇 주 뒤에 선행지표의 최근 창 밖으로 나가</b> 대시보드가 다시 빈다.
 * 언제 적재해도 "최근 N주" 가 되도록 여기서 환산한다.
 *
 * <p>{@code createdAt} 은 {@code @CreatedDate} 라 JPA 가 적재 시각을 찍는다. 주별 추이를
 * 만들려면 소급해야 하므로 저장 뒤 네이티브 UPDATE 로 덮는다 — 감사 필드를 우회하는 것이
 * 맞는 유일한 자리이고, <b>합성 세션에만</b> 적용된다.
 *
 * <h2>기본은 꺼져 있다</h2>
 *
 * <p>{@code sphinx.demo.synthetic-sessions=true} 일 때만 돈다. 켜지 않으면 아무 일도 없다 —
 * 테스트·운영이 이 데이터를 우연히 갖게 되면 <b>집계 수치의 출처를 알 수 없게 된다.</b>
 *
 * <p>❗<b>판매자 id 가 실제 계정이 아니다</b>({@code synth-seller-*}). {@code demo_accounts.yaml}
 * 의 id 를 쓰면 그 판매자가 {@code own_session} 으로 합성 세션을 열 수 있다. 집계의 판매자
 * 축은 어차피 대체키로 바뀌므로 잃는 것이 없다.
 */
@Slf4j
@Component
public class SyntheticSessionLoader implements ApplicationRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 합성 세션 id 접두어. 멱등 판단과 식별에 쓴다. */
    static final String ID_PREFIX = "synth-";

    private final SessionRepository sessions;
    private final EntityManager em;
    private final boolean enabled;
    private final Path file;

    public SyntheticSessionLoader(SessionRepository sessions, EntityManager em,
                                  @Value("${sphinx.demo.synthetic-sessions:false}") boolean enabled,
                                  @Value("${sphinx.demo.synthetic-sessions-file:../data/synth_sessions/sessions.json}") String file) {
        this.sessions = sessions;
        this.em = em;
        this.enabled = enabled;
        this.file = Path.of(file);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        load(Instant.now());
    }

    /**
     * 적재한 세션 수. 이미 들어 있으면 0 을 내고 아무것도 하지 않는다(멱등).
     *
     * <p>{@code now} 를 받는 이유는 테스트가 시각을 고정하기 위해서다 — 주 경계에 걸린
     * 실행이 다른 결과를 내면 그 테스트는 하루에 한 번 빨개진다.
     */
    @Transactional
    public int load(Instant now) {
        if (sessions.existsById(ID_PREFIX + "0001")) {
            log.info("합성 세션이 이미 있다 — 건너뛴다");
            return 0;
        }
        JsonNode root = read();
        LocalDate monday = now.atZone(ZoneOffset.UTC).toLocalDate()
                .with(DayOfWeek.MONDAY);

        List<Session> built = new ArrayList<>();
        Map<String, Instant> backdate = new LinkedHashMap<>();
        for (JsonNode s : root.get("sessions")) {
            String id = s.get("sessionId").asText();
            Session session = Session.builder()
                    .id(id)
                    .productId(s.get("productId").asText())
                    .channel(Channel.valueOf(s.get("channel").asText()))
                    .ageBand(s.get("ageBand").asText())
                    .sellerId(s.get("sellerId").asText())
                    .branchId(s.get("branchId").asText())
                    .judgmentsByItem(judgments(s.get("judgments")))
                    .build();
            built.add(session);
            backdate.put(id, monday
                    .minusWeeks(s.get("weeksAgo").asLong())
                    .plusDays(s.get("dayOfWeek").asLong())
                    .atTime(s.get("hour").asInt(), 0)
                    .toInstant(ZoneOffset.UTC));
        }

        sessions.saveAll(built);
        em.flush();
        backdate.forEach(this::backdate);

        log.info("합성 세션 {}건 적재 — 생성 파라미터 {} (seed {})",
                built.size(), root.path("params").asText(), root.path("seed").asLong());
        return built.size();
    }

    /**
     * {@code created_at} 을 소급한다.
     *
     * <p>{@code @CreatedDate} 가 적재 시각을 찍어 두므로 JPA 로는 못 바꾼다. 선행지표가
     * {@code createdAt} 으로 주를 가르므로(`AggregateService.periodOf`) 소급하지 않으면
     * <b>모든 세션이 이번 주에 몰려</b> 추이가 한 칸짜리가 된다.
     */
    private void backdate(String id, Instant at) {
        em.createNativeQuery("UPDATE sessions SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, at)
                .setParameter(2, id)
                .executeUpdate();
    }

    private static Map<String, Judgment> judgments(JsonNode node) {
        Map<String, Judgment> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            String itemId = e.getKey();
            out.put(itemId, new Judgment(
                    itemId,
                    Grade.valueOf(e.getValue().asText()),
                    // 합성이라 신뢰도에 의미가 없다. 게이트의 신뢰도 강등 경계(gate_rules.yaml)
                    // 를 우연히 건드리지 않도록 넉넉한 값으로 고정한다.
                    new BigDecimal("0.90"),
                    new Judgment.Evidence("합성 세션 — 발화 없음", "합성 세션 — 루브릭 조항 없음"),
                    "F-DSH-003 합성 세션(대시보드 구동용). 실제 판정이 아니다.",
                    null));
        });
        return out;
    }

    private JsonNode read() {
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (IOException e) {
            // 켜 놓고 파일이 없으면 조용히 빈 대시보드가 된다 — 그게 이 기능이 없앤 상태다.
            throw new UncheckedIOException(
                    "합성 세션 파일을 못 읽었다: " + file.toAbsolutePath()
                            + " — scripts/gen_synth_sessions.py 로 만든다", e);
        }
    }
}
