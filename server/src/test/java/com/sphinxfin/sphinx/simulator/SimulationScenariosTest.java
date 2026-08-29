package com.sphinxfin.sphinx.simulator;

import com.sphinxfin.sphinx.core.simulator.SimulatorProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-SIM-001 REST 매핑. 소유: 정세현 (이슈 #45 · #54 ④)
 *
 * <p>계산 자체는 {@link SimulatorServiceTest} 가 참조 구현과 대조해 고정한다. 여기서 재는 것은
 * <b>그 결과에서 3열을 어떻게 고르고 계약 모양으로 옮기는가</b>다.
 *
 * <p>실데이터로 돈다. {@code data/timeseries/} 는 레포에 커밋돼 있으므로 <b>건너뛰지 않는다</b> —
 * P2 결정론 검산이 안 돌았다는 사실이 초록으로 덮이면 안 된다(이슈 #37 · 결정 10.26).
 */
@DisplayName("SimulationScenarios — 역사 시뮬레이션 3열")
class SimulationScenariosTest {

    /** Gradle 테스트 작업 디렉토리는 {@code server/} 다. */
    private static final String TIMESERIES_DIR = "../data/timeseries";

    private static final long FIFTY_MILLION = 50_000_000L;

    private final SimulationScenarios scenarios =
            new SimulationScenarios(new SimulatorProperties(TIMESERIES_DIR));

    private SimulationScenarios.SimulationView view() {
        assertThat(Path.of(TIMESERIES_DIR)).as("커밋된 디렉토리다 — 없으면 작업 디렉토리가 server/ 가 아니다")
                .matches(Files::isDirectory);
        return scenarios.view(FIFTY_MILLION);
    }

    private SimulationScenarios.Scenario severity(String key) {
        return view().scenarios().stream().filter(s -> s.severity().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError(key + " 칸이 없다"));
    }

    @Nested
    @DisplayName("3열의 모양 — 계약이 정확히 3건을 요구한다")
    class Shape {

        @Test
        @DisplayName("severity 셋이 worst·mid·best 순서로 정확히 한 번씩 온다")
        void exactlyThreeInPlacementOrder() {
            List<SimulationScenarios.Scenario> cards = view().scenarios();

            assertThat(cards).hasSize(3);
            assertThat(cards).extracting(SimulationScenarios.Scenario::severity)
                    .as("화면이 3열을 이 순서로 세운다 — 계약 배열 순서가 곧 배치다")
                    .containsExactly("worst", "mid", "best");
        }

        /**
         * ❗같은 전개를 두 칸에 세우면 3열이 <b>다른 것을 비교하지 못한다.</b> 화면에는
         * "최악과 중간이 같다" 로 보이는데 그건 데이터가 아니라 선택 규칙의 결함이다.
         */
        @Test
        @DisplayName("❗세 칸의 전개가 서로 다르다 — 같으면 비교할 것이 없다")
        void theThreeOutcomesDiffer() {
            assertThat(view().scenarios()).extracting(SimulationScenarios.Scenario::result)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("productName 과 timeseriesVersion 이 실물에서 온다")
        void envelopeCarriesItsProvenance() {
            SimulationScenarios.SimulationView v = view();

            assertThat(v.productName()).isEqualTo(SimulatorService.KIWOOM_4181.name());
            assertThat(v.timeseriesVersion())
                    .as("data/timeseries/VERSION 의 snapshot — 상수로 두면 CSV 를 갈아도 "
                            + "화면이 옛 스냅샷을 말한다")
                    .matches("\\d{4}-\\d{2}-\\d{2}");
        }
    }

    @Nested
    @DisplayName("대표를 고르는 규칙")
    class Selection {

        /**
         * ❗<b>평균도 중앙값도 아니다.</b> 기획서 4절이 요구하는 것이 "최선만 강조하는 관행의
         * 정반대" 이므로 최악 칸은 역사상 실제로 있었던 가장 나쁜 계약일이어야 한다.
         */
        @Test
        @DisplayName("❗worst 는 역사상 최악의 계약일이다 — 5천만 원이 25,328,448 원으로 돌아온다")
        void worstIsTheActualWorstDayInHistory() {
            SimulationScenarios.Scenario worst = severity("worst");

            assertThat(worst.result()).isEqualTo("loss");
            assertThat(worst.pathMeta().startDate()).hasToString("2007-07-04");
            assertThat(worst.payout())
                    .as("SimulatorServiceTest 가 참조 구현과 대조해 고정한 값과 같아야 한다")
                    .isEqualTo(25_328_448L);
            assertThat(worst.pnl()).isEqualTo(25_328_448L - FIFTY_MILLION);
            assertThat(worst.pathMeta().knockedIn()).isTrue();
        }

        /**
         * ❗{@code mid} 는 <b>가장 자주 일어난 전개</b>이지 중앙값이 아니다. 스텝다운은 조기상환
         * 시점과 무관하게 연 수익률이 같고 총액만 달라서, 가장 흔한 전개가 금액으로 중간에 선다 —
         * 기획서 7-2 표가 평가어를 버리고 금액 순으로 간 근거가 그것이다.
         */
        @Test
        @DisplayName("❗mid 는 가장 흔한 전개다 — 4,110건 중 85.72% 가 첫 조기상환이다")
        void midIsTheMostFrequentOutcome() {
            SimulationScenarios.Scenario mid = severity("mid");

            assertThat(mid.result()).isEqualTo("early_1");
            assertThat(mid.share()).isEqualByComparingTo("0.8572");
            assertThat(mid.name()).isEqualTo("6개월 뒤 첫 조기상환");
        }

        @Test
        @DisplayName("best 는 만기까지 간 최대 수익이다 — 3년 치 쿠폰 33%")
        void bestIsTheFullCoupon() {
            SimulationScenarios.Scenario best = severity("best");

            assertThat(best.result()).isEqualTo("maturity");
            assertThat(best.payout()).isEqualTo(66_500_000L);   // 5천만 × 1.33
            assertThat(best.share()).isEqualByComparingTo("0.0151");
            assertThat(best.pathMeta().startDate())
                    .as("만기 상환 62건이 전부 +33% 동률이다 — 이른 계약일로 끊는다. "
                            + "min·max 에 같은 비교기를 쓰면 여기서 늦은 쪽이 나온다")
                    .hasToString("2008-01-15");
        }

        @Test
        @DisplayName("share 는 그 전개가 역사에서 차지한 비중이다 — 손실은 2.87%")
        void shareIsTheHistoricalFrequency() {
            assertThat(severity("worst").share())
                    .as("118 / 4110")
                    .isEqualByComparingTo("0.0287");
        }
    }

    @Nested
    @DisplayName("금액과 결정론")
    class AmountAndDeterminism {

        @Test
        @DisplayName("금액은 상환금액을 비례로만 바꾼다 — 어느 전개가 최악인지는 안 바뀐다")
        void amountScalesPayoutOnly() {
            SimulationScenarios.SimulationView small = scenarios.view(FIFTY_MILLION);
            SimulationScenarios.SimulationView big = scenarios.view(FIFTY_MILLION * 2);

            assertThat(big.scenarios()).extracting(SimulationScenarios.Scenario::result)
                    .isEqualTo(small.scenarios().stream()
                            .map(SimulationScenarios.Scenario::result).toList());
            assertThat(big.scenarios().get(0).payout())
                    .isEqualTo(small.scenarios().get(0).payout() * 2);
        }

        /** P2 — 같은 스냅샷이면 같은 답이다. 손익률 동률(만기 62건이 전부 +33%)에서 갈릴 수 있다. */
        @Test
        @DisplayName("❗두 번 불러도 같다 — 동률 계약일이 있어도 순서가 흔들리지 않는다")
        void repeatedCallsGiveTheSameAnswer() {
            assertThat(scenarios.view(FIFTY_MILLION)).isEqualTo(scenarios.view(FIFTY_MILLION));
        }

        @Test
        @DisplayName("금액이 0 이하면 거절한다 — 계약이 minimum: 1 이다")
        void nonPositiveAmountIsRejected() {
            assertThatThrownBy(() -> scenarios.view(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("양수");
        }
    }

    @Nested
    @DisplayName("역사 구간 라벨 (PathMeta) — P2 재현성의 실물 근거")
    class Provenance {

        @Test
        @DisplayName("세 칸 모두 실제 구간을 가리킨다 — 상환일이 계약일보다 뒤다")
        void everyCardPointsAtARealWindow() {
            for (SimulationScenarios.Scenario card : view().scenarios()) {
                SimulationScenarios.PathMeta meta = card.pathMeta();
                assertThat(meta.endDate()).as(card.severity()).isAfter(meta.startDate());
                assertThat(meta.underlyings())
                        .isEqualTo(SimulatorService.KIWOOM_4181.underlyings());
                assertThat(meta.worstUnderlying()).as(card.severity())
                        .isIn(SimulatorService.KIWOOM_4181.underlyings());
                assertThat(meta.worstFinal()).as(card.severity()).isPositive();
            }
        }

        /**
         * ❗조기상환 칸도 {@code worstFinal} 을 낸다. 계약이 {@code required} 로 두고, 화면이
         * <i>"그때 얼마였나"</i> 를 그린다 — 만기 전용으로 두면 3열 중 한 칸만 빈다.
         */
        @Test
        @DisplayName("❗조기상환 칸도 상환일의 최저 종목 비율을 낸다")
        void earlyRedemptionAlsoReportsItsWorstRatio() {
            SimulationScenarios.PathMeta mid = severity("mid").pathMeta();

            assertThat(mid.worstFinal())
                    .as("첫 조기상환은 배리어 85% 이상이라 그 이상이어야 한다")
                    .isGreaterThanOrEqualTo(0.85);
            assertThat(mid.knockedIn())
                    .as("조기상환은 낙인 판정 전에 끝난다")
                    .isFalse();
        }
    }
}
