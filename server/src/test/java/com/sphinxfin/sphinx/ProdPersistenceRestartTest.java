package com.sphinxfin.sphinx;

import com.sphinxfin.sphinx.evidence.HashChain;
import com.sphinxfin.sphinx.evidence.ImmutableStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * prod 영속화(#399)의 전제를 실측한다 — <b>불변 기록은 재시작을 살아남아야 한다.</b>
 * 소유: 강희진 (영속 설정), 지분: 정세현 (evidence 가 지키려는 성질이 시험 대상이다)
 *
 * <p>인메모리 H2 + ddl-auto:create 였던 이전 prod 는 재기동마다 세션·판정·해시 체인이
 * 통째로 사라졌다 — "append-only 불변 기록"(F-GTE-004·F-CMN-002)이 재시작 전까지만
 * 성립하는 것이라 전제 자체가 거짓이었다. 이 테스트는 prod 의 영속 3종(파일 H2 ·
 * Flyway · ddl-auto:validate)으로 <b>전체 애플리케이션을 두 번 순차 기동</b>해서 같은
 * 파일을 다시 열고, 기록과 체인 연속성이 남아 있는지를 실제 JPA 경로로 확인한다.
 *
 * <p>이 한 판이 세 가지를 동시에 잠근다:
 * <ol>
 *   <li><b>스키마 대조</b> — validate 모드 기동이 성공한다는 것은 V1__baseline.sql 이
 *       @Entity 전부와 맞는다는 뜻이다. 엔티티를 고치고 마이그레이션을 안 쓰면 여기서
 *       기동이 실패한다(prod 가 죽기 전에 CI 가 죽는다).</li>
 *   <li><b>생존</b> — 1차 기동에서 적재한 기록이 2차 기동에서 그대로 재생된다.</li>
 *   <li><b>연속성</b> — 재시작 뒤의 append 가 재시작 전의 머리 hash 에 이어 붙고,
 *       닻(꼬리 절단 탐지) 포함 전체 검증이 통과한다.</li>
 * </ol>
 *
 * <p><b>prod 프로파일 자체를 켜지 않는 이유</b>: prod 는 SPHINX_API_USER 등 배포 환경변수가
 * 없으면 기동을 거부한다(SecurityConfig — 그건 그 나름대로 맞는 동작이다). 여기서는
 * application-prod.yml 이 덮어쓰는 <b>영속 설정만</b> 같은 값으로 얹는다. 인증 설정과
 * 영속 설정은 직교라 이 축소가 시험 대상을 훼손하지 않는다.
 *
 * <p>DB URL 에 DB_CLOSE_DELAY 를 안 붙이는 것이 요지다(prod URL 도 같다) — 붙이면 1차
 * 컨텍스트가 닫혀도 H2 인스턴스가 JVM 에 살아남아, 2차 기동이 디스크가 아니라 <b>열려
 * 있던 그 DB</b>를 다시 잡는다. 그러면 인메모리였어도 통과하는 가짜 재시작이 된다.
 */
@DisplayName("prod 영속화 — 불변 기록이 재시작을 살아남는다 (#399)")
class ProdPersistenceRestartTest {

    private static final String STREAM = "report:restart-proof";

    @TempDir
    static Path dbDir;

    private ConfigurableApplicationContext boot() {
        // ❗run() 의 인자(커맨드라인 아규먼트)로 넘긴다. builder.properties(...) 는
        // **defaultProperties** 라 application.yml 이 이긴다 — 처음에 그렇게 썼더니
        // 오버라이드가 전부 무시되고 인메모리(DB_CLOSE_DELAY=-1)로 돌면서, JVM 에 살아남은
        // DB 덕에 **재시작 없이도 통과하는 가짜 초록**이 됐다. 아래 URL 단정이 그 재발을 막는다.
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(SphinxApplication.class)
                .run(
                        // 포트 충돌 방지 — 이 테스트는 HTTP 를 쓰지 않는다.
                        "--server.port=0",
                        // prod 영속 3종 (application-prod.yml 과 같은 값)
                        "--spring.datasource.url=jdbc:h2:file:" + dbDir.resolve("sphinx"),
                        "--spring.flyway.enabled=true",
                        "--spring.jpa.hibernate.ddl-auto=validate");
        assertThat(ctx.getEnvironment().getProperty("spring.datasource.url"))
                .as("오버라이드가 무시되면 이 테스트 전체가 가짜다")
                .startsWith("jdbc:h2:file:");
        return ctx;
    }

    private static Map<String, Object> payload(String itemId, String grade) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", itemId);
        m.put("grade", grade);
        m.put("confidence", new BigDecimal("0.91"));
        return m;
    }

    @Test
    @DisplayName("파일 H2 + Flyway + validate 로 두 번 기동해도 기록·해시 체인이 이어진다")
    void evidenceSurvivesRestartAndChainContinues() {
        String headBeforeRestart;
        int countBeforeRestart;

        // ── 1차 기동: validate 로 뜨고(스키마 대조), 실제 JPA 경로로 적재한다 ──
        try (ConfigurableApplicationContext first = boot()) {
            ImmutableStore store = first.getBean(ImmutableStore.class);
            store.append(STREAM, payload("A", "U1"));
            store.append(STREAM, payload("B", "U3"));
            store.append(STREAM, payload("C", "U4"));

            HashChain.Verification v = store.verify(STREAM);
            assertThat(v.ok()).as("적재 직후 검증: %s", v).isTrue();
            headBeforeRestart = store.head(STREAM);
            countBeforeRestart = v.checked();
        }
        assertThat(headBeforeRestart).isNotEqualTo(HashChain.GENESIS);
        assertThat(countBeforeRestart).isEqualTo(3);
        // 재시작 사이에 기록이 실제로 **디스크에** 있다 — 인메모리로 새는 회귀를 물리적으로 잡는다.
        assertThat(dbDir.resolve("sphinx.mv.db")).exists();

        // ── 2차 기동: 같은 파일을 다시 연다 — 예전 prod(인메모리)라면 여기서 전부 없다 ──
        try (ConfigurableApplicationContext second = boot()) {
            ImmutableStore store = second.getBean(ImmutableStore.class);

            // (b) 생존 — 기록이 그대로 재생되고, 닻 포함 전체 검증이 통과한다.
            HashChain.Verification v = store.verify(STREAM);
            assertThat(v.ok()).as("재시작 후 검증: %s", v).isTrue();
            assertThat(v.checked()).isEqualTo(countBeforeRestart);
            assertThat(store.head(STREAM)).isEqualTo(headBeforeRestart);

            // (c) 연속성 — 재시작 뒤의 append 가 재시작 전의 머리에 이어 붙는다.
            //     체인이 끊겼다면 새 항목의 prevHash 가 옛 머리와 다르거나 검증이 깨진다.
            HashChain.ChainEntry appended = store.append(STREAM, payload("D", "U2"));
            assertThat(appended.prevHash()).isEqualTo(headBeforeRestart);
            assertThat(appended.seq()).isEqualTo(HashChain.FIRST_SEQ + countBeforeRestart);

            HashChain.Verification after = store.verify(STREAM);
            assertThat(after.ok()).as("재시작 후 append 검증: %s", after).isTrue();
            assertThat(after.checked()).isEqualTo(countBeforeRestart + 1);

            // 재생 내용까지 대조 — 1차 기동에서 적은 payload 가 바이트가 아니라 값으로 남았다.
            List<HashChain.ChainEntry> replayed = new ArrayList<>();
            store.replay(STREAM).forEach(replayed::add);
            assertThat(replayed).hasSize(4);
            assertThat(replayed.get(0).payload().toString()).contains("U1");
        }
    }
}
