package com.sphinxfin.sphinx.aggregate;

import com.sphinxfin.sphinx.core.persistence.JpaAuditingConfig;
import com.sphinxfin.sphinx.core.session.CreateSessionCommand;
import com.sphinxfin.sphinx.core.session.Session;
import com.sphinxfin.sphinx.domain.Channel;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.security.AccessPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 오해 지도 집계. 소유: 정세현
 *
 * <p>여기서 지키는 것 셋. <b>① 마스킹은 셀을 지우지 않는다</b> — 지우면 화면이 "데이터 없음"
 * 과 "가려짐" 을 구분할 수 없고, 감사 관점에서는 가려졌다는 사실 자체가 마스킹이 동작한
 * 증거다. <b>② 범위는 호출자가 준다</b> — 여기서 다시 판정하면 인가가 두 곳에서 나온다.
 * <b>③ 집계 화면에 개인 식별자가 없다</b>(기획서 7-4 · ADR-001).
 */
@DataJpaTest
// JpaAuditingConfig 가 있어야 createdAt 이 채워진다 — 추이 집계가 그 값으로 주를 가른다.
// @DataJpaTest 는 @EnableJpaAuditing 을 자동으로 켜지 않는다.
@Import({AggregateService.class, JpaAuditingConfig.class})
@DisplayName("AggregateService — 오해 지도 집계")
class AggregateServiceTest {

    private static final String PRODUCT = "doc-els-kiwoom-4181";
    private static final String ITEM = "ELS-PRINCIPAL-LOSS-WARNING";

    @Autowired
    private AggregateService aggregate;
    @Autowired
    private TestEntityManager em;

    private static Judgment judgment(String itemId, Grade grade) {
        return new Judgment(itemId, grade, new BigDecimal("0.9"),
                new Judgment.Evidence("원금은 지켜지죠", "원금손실 조건"), "사유", null);
    }

    /** 항목 하나에 등급 하나를 기록한 세션. 세션 하나가 표본 하나다. */
    private void seed(String product, String branchId, String sellerId, String ageBand,
                      Channel channel, String itemId, Grade grade) {
        Session session = Session.create(new CreateSessionCommand(
                product, channel, ageBand, null, null, null, "s02-survey-v1", Map.of(),
                sellerId, branchId));
        session.recordJudgment(judgment(itemId, grade));
        em.persist(session);
    }

    private void seedMany(int count, String branchId, Grade grade) {
        for (int i = 0; i < count; i++) {
            seed(PRODUCT, branchId, "seller-" + i, "60대", Channel.FACE_TO_FACE, ITEM, grade);
        }
    }

    private AggregateService.HeatmapView orgHeatmap() {
        em.flush();
        em.clear();
        return aggregate.heatmap(AccessPolicy.Scope.ORG, null, AggregateService.Filters.none());
    }

    @Nested
    @DisplayName("마스킹 — 셀을 지우지 않는다")
    class Masking {

        @Test
        @DisplayName("❗소표본 셀도 목록에 남는다 — misrate 만 null 이고 n 은 내려간다")
        void maskedCellStaysWithItsSampleSize() {
            seedMany(MIN_CELL_SAMPLE_MINUS_ONE, "BR-1", Grade.U4);

            List<AggregateService.Cell> cells = orgHeatmap().cells();

            assertThat(cells)
                    .as("셀을 지우면 화면이 '데이터 없음' 과 '가려짐' 을 구분할 수 없다 — "
                            + "가려졌다는 사실 자체가 마스킹이 동작한 증거다")
                    .hasSize(1);
            assertThat(cells.get(0).masked()).isTrue();
            assertThat(cells.get(0).misrate()).isNull();
            assertThat(cells.get(0).n()).isEqualTo(MIN_CELL_SAMPLE_MINUS_ONE);
        }

        @Test
        @DisplayName("❗경계는 30이다 — 29는 가리고 30은 낸다")
        void thresholdIsExactlyThirty() {
            seedMany(AggregateService.MIN_CELL_SAMPLE, "BR-1", Grade.U4);

            AggregateService.Cell cell = orgHeatmap().cells().get(0);

            assertThat(cell.masked())
                    .as("계약이 n<30 으로 못박았다(openapi HeatmapCell.masked). "
                            + "30 은 미만이 아니다")
                    .isFalse();
            assertThat(cell.misrate()).isEqualByComparingTo("1.0000");
        }

        private static final int MIN_CELL_SAMPLE_MINUS_ONE = AggregateService.MIN_CELL_SAMPLE - 1;
    }

    @Nested
    @DisplayName("오해율은 U4 만 센다")
    class MisrateDefinition {

        @Test
        @DisplayName("❗미이해(U3)는 오해가 아니다 — 섞으면 지도가 이름과 다른 것을 말한다")
        void notUnderstoodIsNotMisunderstood() {
            seedMany(AggregateService.MIN_CELL_SAMPLE, "BR-1", Grade.U3);

            assertThat(orgHeatmap().cells().get(0).misrate())
                    .as("모르는 것은 설명을 더 하면 되고, 잘못 아는 것은 설명서 문장을 고쳐야 "
                            + "한다 — 기획서가 이 화면의 쓸모로 든 것이 후자다")
                    .isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("U4 비율이 그대로 오해율이다")
        void misrateIsTheU4Share() {
            seedMany(10, "BR-1", Grade.U4);
            seedMany(30, "BR-2", Grade.U1);

            assertThat(orgHeatmap().cells().get(0).misrate())
                    .isEqualByComparingTo("0.2500");   // 10 / 40
        }
    }

    @Nested
    @DisplayName("등급 분포 (이슈 #177)")
    class GradeDistribution {

        /**
         * ❗이 이슈의 본체다. {@code misrate} 만 있으면 화면이 <b>"이해했는가"</b> 를 못 말한다.
         *
         * <p>41% 를 본 사람은 <i>"59% 는 이해했다"</i> 로 읽는데, 그 59% 안에 부분이해도
         * 미이해도 섞여 있다. 오해율이 낮다는 이유로 <i>"설명이 잘 통했다"</i> 는 결론이
         * 나가면 기획 4절이 비판하는 그 관행을 우리가 지표로 재생산하는 것이 된다.
         */
        @Test
        @DisplayName("❗이해(U1)가 0건이어도 오해율은 낮게 나온다 — 그 사실이 분포에만 보인다")
        void aLowMisrateDoesNotMeanTheyUnderstood() {
            seedMany(25, "BR-1", Grade.U3);
            seedMany(5, "BR-2", Grade.U4);

            AggregateService.Cell cell = orgHeatmap().cells().get(0);

            assertThat(cell.misrate())
                    .as("오해율만 보면 17% — '83% 는 이해했다' 로 읽힌다")
                    .isEqualByComparingTo("0.1667");
            assertThat(cell.grades().u1())
                    .as("실제로는 이해가 한 건도 없다. 이 사실이 misrate 에는 안 나타난다")
                    .isZero();
            assertThat(cell.grades().u3()).isEqualTo(25);
        }

        @Test
        @DisplayName("분포 합이 n 과 같다 — 건수로 주는 이유가 이 검산이다")
        void theDistributionAddsUpToTheSampleSize() {
            seedMany(12, "BR-1", Grade.U1);
            seedMany(8, "BR-1", Grade.U2);
            seedMany(6, "BR-2", Grade.U3);
            seedMany(4, "BR-2", Grade.U4);

            AggregateService.Cell cell = orgHeatmap().cells().get(0);
            AggregateService.Grades g = cell.grades();

            assertThat(g.u1() + g.u2() + g.u3() + g.u4())
                    .as("비율로 내리면 반올림 때문에 이 검산이 사라진다")
                    .isEqualTo(cell.n());
            assertThat(g).isEqualTo(new AggregateService.Grades(12, 8, 6, 4));
            assertThat(cell.misrate())
                    .as("U4 건수가 그대로 misrate 의 분자다")
                    .isEqualByComparingTo("0.1333");   // 4 / 30
        }

        /**
         * ❗분포를 남기면 <b>마스킹이 뚫린다</b> — U4 건수 ÷ n 으로 {@code misrate} 가 그대로
         * 복원된다. 소표본을 가리는 이유가 셀 하나가 몇 사람인지 드러나지 않게 하는 것이므로,
         * 같은 셀의 다른 필드로 되돌릴 수 있으면 가린 것이 아니다.
         */
        @Test
        @DisplayName("❗가려진 칸은 분포도 안 준다 — 남기면 U4÷n 으로 오해율이 복원된다")
        void maskedCellsHideTheDistributionToo() {
            seedMany(AggregateService.MIN_CELL_SAMPLE - 1, "BR-1", Grade.U4);

            AggregateService.Cell cell = orgHeatmap().cells().get(0);

            assertThat(cell.masked()).isTrue();
            assertThat(cell.misrate()).isNull();
            assertThat(cell.grades())
                    .as("misrate 를 가려도 분포를 주면 같은 값이 복원된다 — 가린 것이 아니다")
                    .isNull();
            assertThat(cell.n())
                    .as("n 은 그대로 내려간다 — misrate 와 같은 규칙이다")
                    .isEqualTo(AggregateService.MIN_CELL_SAMPLE - 1);
        }

        @Test
        @DisplayName("등급 넷을 다 세지 않으면 합이 n 과 어긋난다 — 한 등급만 세는 구현을 막는다")
        void everyGradeIsCounted() {
            for (Grade grade : Grade.values()) {
                seedMany(10, "BR-" + grade, grade);
            }

            AggregateService.Grades g = orgHeatmap().cells().get(0).grades();

            assertThat(List.of(g.u1(), g.u2(), g.u3(), g.u4()))
                    .as("Grade 에 값이 늘면 여기서 먼저 어긋난다")
                    .containsExactly(10L, 10L, 10L, 10L);
        }
    }

    @Nested
    @DisplayName("범위는 호출자가 준다")
    class ScopeComesFromTheCaller {

        @Test
        @DisplayName("❗own_session 은 거부한다 — 정책에 그 그랜트가 없으므로 배선 오류다")
        void ownSessionIsRejected() {
            assertThatThrownBy(() -> aggregate.heatmap(
                    AccessPolicy.Scope.OWN_SESSION, "BR-1", AggregateService.Filters.none()))
                    .as("조용히 전체를 주면 역이용 방지가 이 경로에서만 사라진다")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("own_session");
        }

        @Test
        @DisplayName("❗branch 인데 지점을 모르면 빈 결과다 — 전체로 새지 않는다")
        void branchWithoutBranchIdSeesNothing() {
            seedMany(AggregateService.MIN_CELL_SAMPLE, "BR-1", Grade.U4);
            em.flush();
            em.clear();

            assertThat(aggregate.heatmap(AccessPolicy.Scope.BRANCH, null,
                    AggregateService.Filters.none()).cells())
                    .as("'막고 있다' 가 아니라 '판단할 수 없다' 다(결정 10.5). "
                            + "전체를 주면 branch 가 org 로 샌다")
                    .isEmpty();
        }

        @Test
        @DisplayName("branch 는 자기 지점만 센다")
        void branchCountsOnlyItsOwn() {
            seedMany(30, "BR-1", Grade.U4);
            seedMany(30, "BR-2", Grade.U1);
            em.flush();
            em.clear();

            AggregateService.HeatmapView view = aggregate.heatmap(
                    AccessPolicy.Scope.BRANCH, "BR-1", AggregateService.Filters.none());

            assertThat(view.scope()).isEqualTo("branch");
            assertThat(view.cells().get(0).n()).isEqualTo(30);
            assertThat(view.cells().get(0).misrate()).isEqualByComparingTo("1.0000");
        }
    }

    @Nested
    @DisplayName("집계 화면에 개인 식별자가 없다 (기획 7-4)")
    class NoIdentifiers {

        @Test
        @DisplayName("❗판매자 ID 원문이 응답 어디에도 없다")
        void sellerIdNeverLeaves() {
            seed(PRODUCT, "BR-1", "seller-kim-01", "60대", Channel.FACE_TO_FACE, ITEM, Grade.U4);
            em.flush();
            em.clear();

            AggregateService.IndicatorView view = aggregate.leadingIndicators(
                    AccessPolicy.Scope.ORG, null, AggregateService.GroupBy.SELLER, 8,
                    Instant.now());

            assertThat(view.toString())
                    .as("집계 화면에 로그인 ID 가 뜨면 그 목록이 곧 '실적이 나쁜 판매자 명단' "
                            + "이다 — 기획서 7-4 가 막으려는 것")
                    .doesNotContain("seller-kim-01");
            assertThat(view.series()).singleElement()
                    .extracting(AggregateService.Series::key)
                    .asString().startsWith("S-");
        }

        @Test
        @DisplayName("같은 판매자는 같은 대체키를 받는다 — 편차를 보려면 묶여야 한다")
        void pseudonymIsStable() {
            seed(PRODUCT, "BR-1", "seller-kim-01", "60대", Channel.FACE_TO_FACE, ITEM, Grade.U4);
            seed(PRODUCT, "BR-1", "seller-kim-01", "50대", Channel.MOBILE, ITEM, Grade.U1);
            em.flush();
            em.clear();

            assertThat(aggregate.leadingIndicators(AccessPolicy.Scope.ORG, null,
                    AggregateService.GroupBy.SELLER, 8, Instant.now()).series())
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("추이와 이상치")
    class Indicators {

        @Test
        @DisplayName("값이 없는 주도 자리를 남긴다 — 빼면 화면이 끊김을 못 그린다")
        void emptyPeriodsKeepTheirSlot() {
            seed(PRODUCT, "BR-1", "seller-1", "60대", Channel.FACE_TO_FACE, ITEM, Grade.U4);
            em.flush();
            em.clear();

            List<AggregateService.Point> points = aggregate.leadingIndicators(
                    AccessPolicy.Scope.ORG, null, AggregateService.GroupBy.ITEM, 8,
                    Instant.now()).series().get(0).points();

            assertThat(points).hasSize(8);
            assertThat(points.subList(0, 7)).allSatisfy(point -> {
                assertThat(point.n()).isZero();
                assertThat(point.masked()).isTrue();
            });
        }

        @Test
        @DisplayName("❗가려진 최신 구간으로는 이상치를 말하지 않는다")
        void maskedLatestYieldsNoOutlier() {
            List<AggregateService.Point> points = List.of(
                    point("2026-W28", "0.10", 40), point("2026-W29", "0.10", 40),
                    point("2026-W30", "0.10", 40), point("2026-W31", "0.10", 40),
                    new AggregateService.Point("2026-W32", null, 3, true));

            assertThat(AggregateService.outlier("item", ITEM, points))
                    .as("표본이 적어 가린 값으로 급등을 말하면 마스킹이 무의미해진다")
                    .isEmpty();
        }

        @Test
        @DisplayName("직전 평균 대비 임계 이상 오르면 이상치다 — delta 와 사유가 같이 온다")
        void riseOverBaselineIsAnOutlier() {
            List<AggregateService.Point> points = List.of(
                    point("2026-W28", "0.10", 40), point("2026-W29", "0.10", 40),
                    point("2026-W30", "0.10", 40), point("2026-W31", "0.10", 40),
                    point("2026-W32", "0.30", 40));

            assertThat(AggregateService.outlier("item", ITEM, points)).hasValueSatisfying(out -> {
                assertThat(out.delta()).isEqualByComparingTo("0.2000");
                assertThat(out.reason())
                        .as("계약이 reason 을 사람이 읽는 사유로, delta 를 기계 판독용으로 "
                                + "갈라 뒀다 — 둘 다 채운다")
                        .contains("직전 4구간").contains("20.0");
            });
        }

        @Test
        @DisplayName("임계 미만은 이상치가 아니다")
        void smallRiseIsNotAnOutlier() {
            List<AggregateService.Point> points = List.of(
                    point("2026-W31", "0.10", 40), point("2026-W32", "0.20", 40));

            assertThat(AggregateService.outlier("item", ITEM, points)).isEmpty();
        }

        private static AggregateService.Point point(String period, String misrate, long n) {
            return new AggregateService.Point(period, new BigDecimal(misrate), n, false);
        }
    }

    @Nested
    @DisplayName("합성 데이터 표기")
    class SyntheticWatermark {

        @Test
        @DisplayName("❗두 뷰 모두 synthetic 이 true 다 — 기획서가 상시 표기를 요구한다")
        void bothViewsAreMarkedSynthetic() {
            assertThatCode(() -> {
                assertThat(orgHeatmap().synthetic()).isTrue();
                assertThat(aggregate.leadingIndicators(AccessPolicy.Scope.ORG, null,
                        AggregateService.GroupBy.ITEM, 8, Instant.now()).synthetic()).isTrue();
            }).doesNotThrowAnyException();
        }
    }
}
