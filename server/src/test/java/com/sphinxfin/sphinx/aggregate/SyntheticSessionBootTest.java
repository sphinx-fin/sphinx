package com.sphinxfin.sphinx.aggregate;

import com.sphinxfin.sphinx.core.session.SessionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 합성 세션이 <b>실제 기동 경로</b>로 들어가는가. 소유: 정세현
 *
 * <h2>왜 별도 파일인가 — {@code SyntheticSessionLoaderTest} 가 못 잡은 것이 있다</h2>
 *
 * <p>그쪽은 {@code loader.load(NOW)} 를 <b>직접</b> 부른다. {@code @SpringBootTest} 가 테스트
 * 메서드에 트랜잭션을 걸어 주므로 그때는 트랜잭션이 이미 있다. 실물은 {@code run()} 을 거치고,
 * {@code @Transactional} 이 {@code load()} 에만 붙어 있으면 <b>자기 호출이라 프록시를 안 지나</b>
 * 트랜잭션이 안 걸린다.
 *
 * <pre>
 * jakarta.persistence.TransactionRequiredException:
 *   No EntityManager with actual transaction available ... cannot reliably process 'flush' call
 *     at SyntheticSessionLoader.load(...)
 *     at SyntheticSessionLoader.run(...)
 * </pre>
 *
 * <p>179줄짜리 테스트가 전부 초록인데 <b>서버가 기동 실패했다</b>(PR #194 리뷰, 강희진 실측).
 * 그래서 여기서는 {@code sphinx.demo.synthetic-sessions=true} 로 컨텍스트를 띄워
 * <b>{@code ApplicationRunner} 가 실제로 도는 경로</b>를 잰다.
 *
 * <p>❗이 파일이 없으면 다음에 누가 {@code run()} 의 애노테이션을 지워도 아무도 모른다 —
 * 나머지 테스트는 그 경로를 안 타므로 전부 초록으로 남는다.
 */
@SpringBootTest(properties = {
        "sphinx.demo.synthetic-sessions=true",
        "sphinx.demo.synthetic-sessions-file=build/boot-synth-sessions.json",
})
@DisplayName("F-DSH-003 합성 세션 — 실제 기동 경로 (PR #194 리뷰)")
class SyntheticSessionBootTest {

    @Autowired private SessionRepository sessions;
    @Autowired private AggregateService aggregate;

    /**
     * 컨텍스트가 뜨기 <b>전에</b> 픽스처가 있어야 한다 — 러너가 기동 중에 읽는다.
     *
     * <p>{@code data/synth_sessions/sessions.json} 을 읽지 않는 이유는
     * {@code SyntheticSessionLoaderTest} 와 같다: {@code .gitignore} 가 그 파일을 막고 있어
     * CI 에는 없다.
     */
    @BeforeAll
    static void writeFixture() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 31; i++) {
            rows.append(rows.length() == 0 ? "" : ",\n").append("""
                    {"sessionId": "synth-%04d", "productId": "ELS", "branchId": "BR-001",
                     "sellerId": "synth-seller-01", "ageBand": "60대", "channel": "FACE_TO_FACE",
                     "weeksAgo": %d, "dayOfWeek": 2, "hour": 10,
                     "judgments": {"ELS-PRINCIPAL-LOSS-WARNING": "%s"}}"""
                    .formatted(i, i % 3, i % 4 == 0 ? "U4" : "U1"));
        }
        Path out = Path.of("build/boot-synth-sessions.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, """
                {"generator": "test", "params": "test", "paramsVersion": 1, "seed": 0,
                 "synthetic": true, "sessions": [%s]}""".formatted(rows));
    }

    @Test
    @DisplayName("❗기동만으로 세션이 들어간다 — run() 이 트랜잭션 없이 돌면 여기서 컨텍스트가 안 뜬다")
    void theRunnerLoadsOnBoot() {
        assertThat(sessions.count())
                .as("ApplicationRunner 가 기동 중에 적재한다. 트랜잭션이 안 걸리면 "
                        + "TransactionRequiredException 으로 컨텍스트 자체가 실패한다")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("❗기동으로 들어간 세션이 대시보드를 채운다 — 적재만 되고 안 세이면 뜻이 없다")
    void theDashboardHasSomethingToShow() {
        var cells = aggregate.heatmap(
                com.sphinxfin.sphinx.security.AccessPolicy.Scope.ORG, null,
                new AggregateService.Filters(null, null, null)).cells();

        assertThat(cells).isNotEmpty();
        assertThat(cells).anySatisfy(c -> {
            assertThat(c.masked()).isFalse();
            assertThat(c.n()).isGreaterThanOrEqualTo(30);
        });
    }
}
