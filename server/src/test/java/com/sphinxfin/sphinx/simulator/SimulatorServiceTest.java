package com.sphinxfin.sphinx.simulator;

import com.sphinxfin.sphinx.simulator.SimulatorService.Outcome;
import com.sphinxfin.sphinx.simulator.SimulatorService.Product;
import com.sphinxfin.sphinx.simulator.SimulatorService.Quote;
import com.sphinxfin.sphinx.simulator.SimulatorService.Series;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-SIM-001 손익 계산 단위 테스트 (P2). 소유: 정세현
 *
 * <p>{@code scripts/test_payoff_reference.py} 17건의 1:1 이식이다. 참조 구현과 같은 케이스가 같은
 * 결과를 내야 한다. 시계열을 합성해서 판정 규칙을 하나씩 고정한다 — 실제 시장 데이터로는
 * "낙인은 터졌는데 만기는 배리어 이상" 같은 경계 조합을 골라 넣을 수 없다.
 *
 * <p>마지막 {@link RealData} 는 참조 구현에 없는 추가분이다. 합성 시계열은 규칙 하나하나를
 * 고정하지만 "포팅 전체가 참조 구현과 같은가"는 검사하지 않는다. 실데이터로 집계까지 대조해야
 * {@code verify_payoff.py} 와 어긋나는 순간 실패한다.
 */
class SimulatorServiceTest {

    /** 오차 없이 일치해야 하는 값들의 허용 오차. 참조 구현과 같은 IEEE 754 연산 순서를 쓴다. */
    private static final double EPS = 1e-9;

    /** 테스트용 상품. 기초자산 2종, 1년/6개월, 배리어 90-80, 낙인 50, 연 10%. */
    private static final Product TOY = new Product(
            "toy", List.of("a", "b"), List.of(6, 12), List.of(0.90, 0.80), 0.10, 0.50, "");

    private static final LocalDate START = LocalDate.of(2020, 1, 1);

    /** points: 날짜→종가. 빠진 날은 종가가 없는 것으로 둔다(휴장일). */
    private static Series seriesFrom(String name, Map<LocalDate, Double> points) {
        return Series.of(name, points);
    }

    /** START 부터 만기까지 매달 1일 같은 값. extra 로 특정 날짜만 덮어쓴다. */
    private static Series flat(String name, double value, Map<LocalDate, Double> extra) {
        Map<LocalDate, Double> points = new LinkedHashMap<>();
        for (int m = 0; m <= 12; m++) {
            points.put(SimulatorService.addMonths(START, m), value);
        }
        points.putAll(extra);
        return seriesFrom(name, points);
    }

    private static Series flat(String name, double value) {
        return flat(name, value, Map.of());
    }

    private static Map<String, Series> pair(Series a, Series b) {
        Map<String, Series> series = new HashMap<>();
        series.put("a", a);
        series.put("b", b);
        return series;
    }

    private static Outcome simulateToy(Map<String, Series> series) {
        Optional<Outcome> out = SimulatorService.simulate(TOY, series, START);
        assertTrue(out.isPresent(), "데이터가 충분한 케이스인데 결과가 비었다");
        return out.get();
    }

    // --- addMonths -------------------------------------------------------------------

    @Test
    void addMonthsBasic() {
        assertEquals(LocalDate.of(2020, 7, 15),
                SimulatorService.addMonths(LocalDate.of(2020, 1, 15), 6));
    }

    @Test
    @DisplayName("1월 31일 + 1개월은 2월 31일이 없으므로 말일로 내린다")
    void addMonthsClampsToLastDayOfShorterMonth() {
        assertEquals(LocalDate.of(2020, 2, 29),
                SimulatorService.addMonths(LocalDate.of(2020, 1, 31), 1));   // 윤년
        assertEquals(LocalDate.of(2021, 2, 28),
                SimulatorService.addMonths(LocalDate.of(2021, 1, 31), 1));
        assertEquals(LocalDate.of(2021, 2, 28),
                SimulatorService.addMonths(LocalDate.of(2020, 8, 31), 6));
    }

    @Test
    void addMonthsCrossesYear() {
        assertEquals(LocalDate.of(2023, 8, 24),
                SimulatorService.addMonths(LocalDate.of(2020, 8, 24), 36));
    }

    // --- Series -----------------------------------------------------------------------

    @Test
    void closeOnOrBeforeUsesPreviousBusinessDay() {
        Series s = seriesFrom("a", Map.of(
                LocalDate.of(2020, 1, 3), 100.0,
                LocalDate.of(2020, 1, 6), 110.0));
        assertEquals(new Quote(LocalDate.of(2020, 1, 3), 100.0),
                s.closeOnOrBefore(LocalDate.of(2020, 1, 5)).orElseThrow());
        assertEquals(new Quote(LocalDate.of(2020, 1, 6), 110.0),
                s.closeOnOrBefore(LocalDate.of(2020, 1, 6)).orElseThrow());
    }

    @Test
    void closeOnOrBeforeIsEmptyBeforeHistory() {
        Series s = seriesFrom("a", Map.of(LocalDate.of(2020, 1, 3), 100.0));
        assertTrue(s.closeOnOrBefore(LocalDate.of(2019, 12, 31)).isEmpty());
    }

    @Test
    @DisplayName("낙인 관찰 구간은 계약일을 제외하고 만기를 포함한다")
    void minCloseBetweenExcludesStartIncludesEnd() {
        Series s = seriesFrom("a", Map.of(
                LocalDate.of(2020, 1, 1), 10.0,
                LocalDate.of(2020, 1, 2), 20.0,
                LocalDate.of(2020, 1, 3), 30.0));
        // 계약일 종가(10)는 낙인 관찰 대상이 아니다 — 그날이 기준가격이므로.
        assertEquals(20.0,
                s.minCloseBetween(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 3)).orElseThrow(),
                EPS);
        assertEquals(OptionalDouble.empty(),
                s.minCloseBetween(LocalDate.of(2020, 1, 3), LocalDate.of(2020, 1, 3)));
    }

    // --- 조기상환 ---------------------------------------------------------------------

    @Test
    void earlyRedemptionWhenAllUnderlyingsClearBarrier() {
        Outcome out = simulateToy(pair(flat("a", 100.0), flat("b", 100.0)));
        assertEquals("early_1", out.result());
        assertEquals(0.05, out.pnlRate(), EPS);          // 연 10% × 6/12
        assertEquals(52_500_000L, out.payout(50_000_000L));
    }

    @Test
    @DisplayName("하나만 미달해도 조기상환이 안 된다 — worst-of 구조")
    void noEarlyRedemptionWhenOneUnderlyingMisses() {
        Outcome out = simulateToy(pair(
                flat("a", 100.0),
                flat("b", 100.0, Map.of(SimulatorService.addMonths(START, 6), 89.0))));  // 배리어 90 미달
        assertNotEquals("early_1", out.result());
    }

    @Test
    void secondObservationCanRedeemAfterFirstMisses() {
        Outcome out = simulateToy(pair(
                flat("a", 100.0, Map.of(SimulatorService.addMonths(START, 6), 80.0)),
                flat("b", 100.0, Map.of(SimulatorService.addMonths(START, 6), 80.0))));
        assertEquals("maturity", out.result());          // 12개월은 배리어 80, 종가 100 → 충족
        assertEquals(0.10, out.pnlRate(), EPS);
    }

    // --- 만기·낙인 (문서 p10의 and 조건) -----------------------------------------------

    @Test
    @DisplayName("낙인이 터지고 만기 최저종목이 배리어 미만 — 이때만 손실이다")
    void lossRequiresBothKnockInAndFinalBelowBarrier() {
        Outcome out = simulateToy(pair(
                flat("a", 100.0, Map.of(
                        SimulatorService.addMonths(START, 6), 40.0,      // 낙인 50 하회
                        SimulatorService.addMonths(START, 12), 60.0)),   // 만기 배리어 80 미만
                flat("b", 100.0, Map.of(SimulatorService.addMonths(START, 6), 85.0))));
        assertEquals("loss", out.result());
        assertTrue(out.knockedIn());
        assertEquals(-0.40, out.pnlRate(), EPS);         // 최저종목 60% → -40%
        assertEquals(30_000_000L, out.payout(50_000_000L));
    }

    @Test
    @DisplayName("낙인이 터졌어도 만기에 배리어 이상으로 회복하면 쿠폰을 받는다")
    void knockInButRecoveredAboveBarrierPaysCoupon() {
        Outcome out = simulateToy(pair(
                flat("a", 100.0, Map.of(
                        SimulatorService.addMonths(START, 6), 40.0,      // 낙인 하회
                        SimulatorService.addMonths(START, 12), 95.0)),   // 만기 배리어 80 이상
                flat("b", 100.0, Map.of(SimulatorService.addMonths(START, 6), 85.0))));
        assertEquals("maturity", out.result());
        assertTrue(out.knockedIn());
        assertEquals(0.10, out.pnlRate(), EPS);
    }

    @Test
    @DisplayName("낙인을 안 건드렸으면 만기에 배리어 미만이어도 쿠폰이다 — 노낙인 보호")
    void noKnockInPaysCouponEvenBelowFinalBarrier() {
        Outcome out = simulateToy(pair(
                flat("a", 100.0, Map.of(
                        SimulatorService.addMonths(START, 6), 85.0,      // 낙인 50은 안 건드림
                        SimulatorService.addMonths(START, 12), 60.0)),   // 만기 배리어 80 미만
                flat("b", 100.0, Map.of(SimulatorService.addMonths(START, 6), 85.0))));
        assertEquals("maturity", out.result());
        assertFalse(out.knockedIn());
        assertEquals(0.10, out.pnlRate(), EPS);
    }

    @Test
    @DisplayName("낙인은 평가일이 아니라 매 영업일 종가로 관찰한다 (문서 p10)")
    void knockInWatchedOnEveryTradingDayNotJustEvaluationDates() {
        LocalDate mid = LocalDate.of(2020, 3, 17);       // 어떤 평가일도 아닌 날
        Map<LocalDate, Double> extraA = new LinkedHashMap<>();
        extraA.put(mid, 40.0);
        extraA.put(SimulatorService.addMonths(START, 6), 85.0);
        extraA.put(SimulatorService.addMonths(START, 12), 60.0);
        Outcome out = simulateToy(pair(
                flat("a", 100.0, extraA),
                flat("b", 100.0, Map.of(SimulatorService.addMonths(START, 6), 85.0))));
        assertTrue(out.knockedIn());
        assertEquals("loss", out.result());
    }

    // --- P2 재현성 --------------------------------------------------------------------

    @Test
    void sameInputGivesSameOutput100Times() {
        Map<String, Series> series = pair(
                flat("a", 100.0, Map.of(
                        SimulatorService.addMonths(START, 6), 40.0,
                        SimulatorService.addMonths(START, 12), 60.0)),
                flat("b", 100.0, Map.of(SimulatorService.addMonths(START, 6), 85.0)));
        Outcome first = simulateToy(series);
        for (int i = 0; i < 100; i++) {
            Outcome again = simulateToy(series);
            assertEquals(first.result(), again.result());
            assertEquals(first.pnlRate(), again.pnlRate(), 0.0);
            assertEquals(first.worstFinal(), again.worstFinal());
            assertEquals(first.knockedIn(), again.knockedIn());
        }
    }

    // --- 데이터 부족 -------------------------------------------------------------------

    @Test
    @DisplayName("만기가 보유 데이터 끝을 넘으면 결과를 모른다 — 추정하지 않는다")
    void isEmptyWhenMaturityBeyondData() {
        Map<LocalDate, Double> points = Map.of(START, 100.0, LocalDate.of(2020, 6, 1), 100.0);
        Map<String, Series> series = pair(seriesFrom("a", points), seriesFrom("b", points));
        assertTrue(SimulatorService.simulate(TOY, series, START).isEmpty());
    }

    // --- 상품 조건 무결성 ---------------------------------------------------------------

    @Test
    void productRejectsMismatchedBarriers() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Product("bad", List.of("a"), List.of(6, 12), List.of(0.9), 0.1, 0.5, ""));
        assertTrue(e.getMessage().contains("길이가 다르다"), e.getMessage());
    }

    @Test
    @DisplayName("조건이 조용히 바뀌면 데모 표 수치가 근거를 잃는다 — 문서 값으로 고정한다")
    void kiwoom4181TermsMatchDocument() {
        Product p = SimulatorService.KIWOOM_4181;
        assertEquals(List.of("sp500", "nikkei225", "eurostoxx50"), p.underlyings());
        assertEquals(List.of(6, 12, 18, 24, 30, 36), p.observationMonths());
        assertEquals(List.of(0.85, 0.85, 0.85, 0.80, 0.75, 0.70), p.barriers());
        assertEquals(0.11, p.couponAnnual(), 0.0);
        assertEquals(0.45, p.knockIn(), 0.0);
        // 문서 p7 손익구조의 지급률과 일치해야 한다
        double[] expected = {0.055, 0.11, 0.165, 0.22, 0.275, 0.33};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], p.payoutRate(i), EPS, "지급률 " + i);
        }
    }

    // --- 참조 구현 대조 (참조 구현에 없는 추가분) ----------------------------------------

    /**
     * {@code scripts/verify_payoff.py} 가 낸 집계와 대조한다. 어긋나면 포팅이 틀린 것이다.
     *
     * <p>기대값은 참조 구현 출력이고, 그 출력은 발행사 공시 모의실험(간이투자설명서 p11~p12)과
     * 분포 형태가 재현되는 것으로 이미 검증됐다.
     *
     * <p><b>데이터가 없으면 건너뛰지 않고 실패한다.</b> 예전에는 `fetch_timeseries.py`를 안 돌린
     * 체크아웃을 막지 않으려고 {@code Assumptions}로 건너뛰었는데, `data/timeseries/`(VERSION +
     * CSV 3개)가 레포에 커밋된 뒤로 그 전제가 사라졌다 — 이제 이 디렉토리가 없다는 것은
     * "아직 안 받았다"가 아니라 "뭔가 잘못됐다"다.
     *
     * <p>건너뛰기가 위험한 이유는 <b>초록으로 남기 때문</b>이다. 이건 P2(시뮬레이터는 결정론적
     * 순수 함수) 검산이고, 시뮬레이터는 고객에게 금액을 보여주는 기능이다. 검산이 조용히 안 도는
     * 것이 여기서 가장 나쁜 실패 양식이다 — 특히 CI 로그에서는 아무도 눈치채지 못한다.
     * (이슈 #73 · decision-log 10.26 · 오준서 #37 코멘트 ②)
     */
    @Nested
    @DisplayName("실데이터 — 참조 구현 집계와 대조")
    class RealData {

        /** Gradle 테스트 작업 디렉토리는 {@code server/} 다. */
        private static final Path TIMESERIES_DIR = Path.of("..", "data", "timeseries");

        private Map<String, Series> load() {
            assertTrue(Files.isDirectory(TIMESERIES_DIR),
                    "data/timeseries/ 가 없다. 이 디렉토리는 레포에 커밋돼 있으므로 체크아웃만 하면 있어야 한다 "
                            + "— 없다면 작업 디렉토리가 server/ 가 아니거나 컨테이너 마운트가 빠진 것이다. "
                            + "건너뛰지 않는다: P2 결정론 검산이 안 돌았다는 사실이 초록으로 덮이면 안 된다.");
            Map<String, Series> series = new LinkedHashMap<>();
            for (String key : SimulatorService.KIWOOM_4181.underlyings()) {
                assertTrue(Files.exists(TIMESERIES_DIR.resolve(key + ".csv")),
                        key + ".csv 가 없다 — data/timeseries/ 가 VERSION 이 가리키는 스냅샷과 다르다");
                series.put(key, SimulatorService.loadSeries(TIMESERIES_DIR, key));
            }
            return series;
        }

        @Test
        @DisplayName("계약일 수와 결과 분포가 참조 구현과 같다")
        void resultDistributionMatchesReferenceImplementation() {
            Map<String, Series> series = load();
            Product p = SimulatorService.KIWOOM_4181;

            List<LocalDate> starts = SimulatorService.startDates(p, series);
            assertEquals(4110, starts.size(), "계약일 수");
            assertEquals(LocalDate.of(2007, 3, 30), starts.get(0), "첫 계약일");
            assertEquals(LocalDate.of(2023, 8, 21), starts.get(starts.size() - 1), "마지막 계약일");

            Map<String, Integer> counts = new LinkedHashMap<>();
            List<Outcome> outcomes = new ArrayList<>();
            for (LocalDate d : starts) {
                Outcome o = SimulatorService.simulate(p, series, d).orElseThrow(
                        () -> new AssertionError("굴릴 수 있다고 판정한 계약일인데 결과가 비었다: " + d));
                counts.merge(o.result(), 1, Integer::sum);
                outcomes.add(o);
            }

            // scripts/verify_payoff.py 출력과 같아야 한다.
            assertEquals(3523, counts.getOrDefault("early_1", 0), "1차 조기상환");
            assertEquals(185, counts.getOrDefault("early_2", 0), "2차 조기상환");
            assertEquals(118, counts.getOrDefault("early_3", 0), "3차 조기상환");
            assertEquals(56, counts.getOrDefault("early_4", 0), "4차 조기상환");
            assertEquals(48, counts.getOrDefault("early_5", 0), "5차 조기상환");
            assertEquals(62, counts.getOrDefault("maturity", 0), "만기상환");
            assertEquals(118, counts.getOrDefault("loss", 0), "손실");

            Outcome worst = outcomes.stream().filter(Outcome::isLoss)
                    .min((x, y) -> Double.compare(x.pnlRate(), y.pnlRate())).orElseThrow();
            assertEquals(LocalDate.of(2007, 7, 4), worst.startDate(), "최악 계약일");
            assertEquals(-0.49343103722212, worst.pnlRate(), 1e-12, "최악 손익률");
            assertEquals(25_328_448L, worst.payout(50_000_000L), "최악 상환금액(5천만 기준)");
        }

        @Test
        @DisplayName("2009년 이후 계약만 보면 손실이 0건이 된다 — 분석기간 축소의 실측")
        void shorteningObservationWindowRemovesAllLosses() {
            Map<String, Series> series = load();
            Product p = SimulatorService.KIWOOM_4181;
            LocalDate cutoff = LocalDate.of(2009, 1, 1);

            int total = 0;
            int losses = 0;
            for (LocalDate d : SimulatorService.startDates(p, series)) {
                if (d.isBefore(cutoff)) {
                    continue;
                }
                total++;
                if (SimulatorService.simulate(p, series, d).orElseThrow().isLoss()) {
                    losses++;
                }
            }
            assertEquals(3672, total, "2009-01 이후 계약일 수");
            assertEquals(0, losses, "2009-01 이후 손실 건수");
        }
    }
}
