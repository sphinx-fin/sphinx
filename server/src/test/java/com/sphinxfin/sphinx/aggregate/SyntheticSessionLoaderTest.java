package com.sphinxfin.sphinx.aggregate;

import com.sphinxfin.sphinx.core.session.Session;
import com.sphinxfin.sphinx.core.session.SessionRepository;
import com.sphinxfin.sphinx.security.AccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-DSH-003 합성 세션이 대시보드를 실제로 채우는가. 소유: 정세현
 *
 * <p>이 파일이 재는 것은 <b>"파일이 읽힌다"</b> 가 아니라 <b>"대시보드가 보여줄 것을 갖는다"</b>
 * 다. 이슈 #179 가 물은 것이 그것이고, 목을 걷은 뒤(#188) 화면이 빈다는 사실이 그 계기다.
 *
 * <h2>❗픽스처를 여기서 만든다 — 커밋된 산출물을 읽지 않는다</h2>
 *
 * <p>{@code .gitignore} 가 첫 커밋부터 {@code data/synth_sessions/*.json} 을 무시한다.
 * 산출물은 배포 때 {@code scripts/gen_synth_sessions.py} 가 만든다 —
 * {@code data/timeseries} 를 {@code fetch_timeseries.py} 로 받아오는 것과 같은 모양이다.
 *
 * <p>그래서 이 테스트가 커밋된 파일을 읽으면 <b>CI 에서 전부 실패한다.</b> 처음에 그렇게
 * 썼다가 로컬에만 남아 있던 생성물 때문에 초록으로 보였다 — {@code BUILD SUCCESSFUL} 을
 * 통과로 읽으면 안 되는 그 함정이다(#188 리뷰에서 같은 것을 짚었다).
 *
 * <p>여기서 만드는 픽스처는 <b>생성기와 같은 스키마</b>이고 칸 수도 같은 조건
 * (30 이상 · 30 미만 둘 다)을 만든다. 실제 분포가 그 조건을 만족하는지는 생성기의
 * {@code --check} 와 PR 실측이 본다.
 */
@SpringBootTest(properties = {
        "sphinx.demo.synthetic-sessions=false",     // 러너는 끄고 테스트가 직접 부른다
        "sphinx.demo.synthetic-sessions-file=build/test-synth-sessions.json",
})
@DisplayName("F-DSH-003 합성 세션 (이슈 #179)")
class SyntheticSessionLoaderTest {

    /** 시각을 고정한다 — 주 경계에 걸린 실행이 다른 결과를 내면 하루에 한 번 빨개진다. */
    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");

    @Autowired private SyntheticSessionLoader loader;
    @Autowired private SessionRepository sessions;
    @Autowired private AggregateService aggregate;

    @BeforeEach
    void seed() throws Exception {
        sessions.deleteAll();
        writeFixture();
        loader.load(NOW);
    }

    /**
     * 생성기 산출물과 같은 스키마의 최소 픽스처.
     *
     * <p>칸 둘을 만든다 — 하나는 {@code n >= 30}(값이 보인다), 하나는 그 미만(가려진다).
     * 주도 둘로 흩어 추이가 한 칸이 아니게 한다.
     */
    private void writeFixture() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 34; i++) {
            String items = i <= 8
                    ? "\"ELS-PRINCIPAL-LOSS-WARNING\": \"U4\", \"ELS-NO-LISTING\": \"U1\""
                    : "\"ELS-PRINCIPAL-LOSS-WARNING\": \"U1\"";
            rows.append(rows.length() == 0 ? "" : ",\n").append("""
                    {"sessionId": "synth-%04d", "productId": "ELS", "branchId": "BR-00%d",
                     "sellerId": "synth-seller-01", "ageBand": "60대", "channel": "FACE_TO_FACE",
                     "weeksAgo": %d, "dayOfWeek": 2, "hour": 10, "judgments": {%s}}"""
                    .formatted(i, (i % 2) + 1, i % 3, items));
        }
        Path out = Path.of("build/test-synth-sessions.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, """
                {"generator": "test", "params": "test", "paramsVersion": 1, "seed": 0,
                 "synthetic": true, "sessions": [%s]}""".formatted(rows));
    }

    private AggregateService.HeatmapView heatmap() {
        return aggregate.heatmap(AccessPolicy.Scope.ORG, null, new AggregateService.Filters(null, null, null));
    }

    @Test
    @DisplayName("❗값이 보이는 칸이 있다 — 이게 없으면 화면이 빈 표다")
    void thereAreCellsWithVisibleValues() {
        var visible = heatmap().cells().stream().filter(c -> !c.masked()).toList();

        assertThat(visible)
                .as("합성 세션의 존재 이유가 이것이다. 손으로 만드는 수십 건은 상품×항목으로 "
                        + "쪼개지면 전부 MIN_CELL_SAMPLE(30) 아래로 떨어진다(#179)")
                .isNotEmpty();
        assertThat(visible).allSatisfy(c -> {
            assertThat(c.n()).isGreaterThanOrEqualTo(30);
            assertThat(c.misrate()).as("가려지지 않은 칸은 값을 낸다").isNotNull();
        });
    }

    @Test
    @DisplayName("❗가려진 칸도 남는다 — 마스킹이 동작하는 것을 보여줘야 한다")
    void maskedCellsRemainAndKeepTheirSampleSize() {
        var masked = heatmap().cells().stream().filter(AggregateService.Cell::masked).toList();

        assertThat(masked)
                .as("전부 30 이상으로 채우면 '소표본 마스킹이 동작한다' 를 심사에서 못 보여준다. "
                        + "distribution.yaml 의 under_sampled 가 일부러 남기는 칸이다")
                .isNotEmpty();
        assertThat(masked).allSatisfy(c -> {
            assertThat(c.misrate()).as("가려진 칸은 값을 안 낸다").isNull();
            assertThat(c.n())
                    .as("❗n 은 그대로 내려간다 — 계약이 '셀을 제거하지 않는다' 로 못박았다. "
                            + "화면이 '데이터 없음' 과 '가려짐' 을 구별하는 근거다")
                    .isGreaterThan(0);
        });
    }

    @Test
    @DisplayName("❗주별 추이가 한 칸이 아니다 — created_at 을 소급하지 않으면 이번 주에 몰린다")
    void sessionsSpreadAcrossWeeks() {
        Set<String> periods = sessions.findAll().stream()
                .map(s -> AggregateService.periodOf(s.createdAt()))
                .collect(Collectors.toSet());

        assertThat(periods)
                .as("@CreatedDate 가 적재 시각을 찍으므로 네이티브 UPDATE 로 소급한다. "
                        + "안 하면 선행지표가 한 칸짜리가 되고 추이라고 부를 것이 없다")
                .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("❗판매자 id 가 실제 계정이 아니다 — 그러면 그 판매자가 합성 세션을 열 수 있다")
    void sellersAreNotRealAccounts() {
        Set<String> sellers = sessions.findAll().stream()
                .map(Session::sellerId).collect(Collectors.toSet());

        assertThat(sellers).allSatisfy(s -> assertThat(s).startsWith("synth-seller-"));
        assertThat(sellers)
                .as("demo_accounts.yaml 의 id 를 쓰면 own_session 으로 열린다. 집계의 판매자 "
                        + "축은 어차피 대체키라 잃는 것이 없다")
                .doesNotContain("seller-01", "seller-02", "seller-03");
    }

    @Test
    @DisplayName("지점이 명부와 같은 코드다 — 다르면 MGR 의 branch 범위가 아무것도 못 센다")
    void branchesMatchTheRoster() {
        Set<String> used = sessions.findAll().stream()
                .map(Session::branchId).collect(Collectors.toSet());

        assertThat(used).isSubsetOf("BR-001", "BR-002");
        assertThat(aggregate.heatmap(AccessPolicy.Scope.BRANCH, "BR-001",
                        new AggregateService.Filters(null, null, null)).cells())
                .as("branch 로 좁혀도 셀이 나와야 MGR 화면이 빈 표가 아니다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("두 번 적재해도 안 쌓인다 — 재기동마다 n 이 불어나면 수치가 거짓이 된다")
    void loadingTwiceIsIdempotent() {
        long before = sessions.count();

        assertThat(loader.load(NOW)).isZero();
        assertThat(sessions.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("❗합성이라는 사실이 응답에 붙는다 — 연출 금지 (결정 5.16)")
    void theViewSaysItIsSynthetic() {
        assertThat(heatmap().synthetic())
                .as("화면은 '합성이다' 를, distribution.yaml 은 '어떤 분포의 합성인가' 를 말한다")
                .isTrue();
    }
}
