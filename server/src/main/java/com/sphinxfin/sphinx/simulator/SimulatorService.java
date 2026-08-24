package com.sphinxfin.sphinx.simulator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * F-SIM-001 손실 시뮬레이터. 소유: 정세현
 *
 * <p>{@code scripts/payoff_reference.py} 의 Java 포팅이다. 참조 구현이 발행사 공시 모의실험
 * 수치와 대조를 통과했으므로(간이투자설명서 p11~p12), 이 클래스는 <b>같은 입력에 같은 결과</b>를
 * 내야 한다. 어긋나면 포팅이 틀린 것이다 — {@code SimulatorServiceTest} 가 실데이터로 검사한다.
 *
 * <h2>원칙</h2>
 * <ul>
 *   <li><b>순수 함수.</b> LLM 미개입(P2). 같은 입력이면 항상 같은 출력. 계산 경로에 I/O 가 없다 —
 *       시계열 적재는 {@link #loadSeries} 로 분리돼 있고 계산은 주입된 {@link Series} 만 본다.
 *   <li><b>종가만 쓴다.</b> 상품 문서가 조기상환·낙인 판정을 전부 종가로 정의한다
 *       (p7 "각 기초자산 종가", p10 "종가가 어느 하나라도 낙인구간 미만으로 하락한 적이 있고").
 *       시가·고가·저가는 판정에 들어가지 않는다.
 *   <li><b>없는 종가를 만들지 않는다.</b> 지수마다 휴장일이 다르므로 평가일에 종가가 없으면 그
 *       이전 최근 영업일 종가를 쓴다(직전 영업일 관행). 보간하거나 앞값으로 채워 새 숫자를
 *       만들지 않는다. 데이터가 아예 부족하면 추정하지 않고 빈 값을 낸다.
 * </ul>
 *
 * <h2>REST 응답 형태와 시계열 패키징은 여기서 정하지 않는다</h2>
 * 스텁에 있던 {@code simulate(long, Map, String)} 자리표시자를 뺐다. {@link Outcome} 을 REST 로
 * 어떻게 노출할지는 {@code contracts/openapi.yaml}(소유: 강희진) 등재와 함께 결정할 사항이고,
 * 데모 표 라벨("최선/중간/최악")도 미결이다 — 지금 코드로 굳히면 그 결정을 앞질러 못박는다.
 * 같은 이유로 CSV 를 클래스패스 리소스로 복사하지 않았다: {@code /server/build.gradle} 이
 * 강희진 소유여서 복사 태스크를 넣을 수 없고, {@code data/timeseries/} 를 한 벌 더 커밋하면
 * 18,089 줄이 중복되면서 sha256 으로 고정한 원본과 조용히 갈라질 수 있다. 적재 경로를 호출자가
 * 정하게 두고, 배포 시 패키징은 API 등재 PR 에서 함께 정한다.
 */
public final class SimulatorService {

    private SimulatorService() {
    }

    // --- 시계열 -----------------------------------------------------------------------

    /** 평가일에 실제로 쓰인 종가와 그 종가의 날짜. 휴장일이면 날짜가 평가일보다 앞선다. */
    public record Quote(LocalDate date, double close) {
    }

    /** 한 지수의 일별 종가. 날짜 오름차순. */
    public static final class Series {

        private final String name;
        private final List<LocalDate> dates;
        private final double[] closes;

        /** rows 는 날짜 오름차순·중복 없음이어야 한다. {@link #of} 를 쓰면 정렬은 알아서 된다. */
        private Series(String name, List<LocalDate> dates, double[] closes) {
            this.name = name;
            this.dates = List.copyOf(dates);
            this.closes = closes;
        }

        /** 날짜→종가 매핑에서 만든다. 순서는 여기서 정렬한다. */
        public static Series of(String name, Map<LocalDate, Double> points) {
            List<LocalDate> dates = new ArrayList<>(points.keySet());
            Collections.sort(dates);
            double[] closes = new double[dates.size()];
            for (int i = 0; i < dates.size(); i++) {
                closes[i] = points.get(dates.get(i));
            }
            return new Series(name, dates, closes);
        }

        public String name() {
            return name;
        }

        public int size() {
            return dates.size();
        }

        public LocalDate first() {
            return dates.get(0);
        }

        public LocalDate last() {
            return dates.get(dates.size() - 1);
        }

        /** target 보다 뒤인 첫 원소의 인덱스. Python {@code bisect.bisect_right} 와 같다. */
        private int bisectRight(LocalDate target) {
            int lo = 0;
            int hi = dates.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (dates.get(mid).isAfter(target)) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            return lo;
        }

        /** 평가일에 휴장이면 직전 영업일 종가를 쓴다. 그 이전 데이터가 없으면 빈 값. */
        public Optional<Quote> closeOnOrBefore(LocalDate target) {
            int i = bisectRight(target) - 1;
            if (i < 0) {
                return Optional.empty();
            }
            return Optional.of(new Quote(dates.get(i), closes[i]));
        }

        /**
         * {@code (start, end]} 구간의 최저 종가. 낙인 관찰용 — 매 영업일 종가를 본다.
         * 계약일 종가는 제외한다(그날이 기준가격이므로).
         */
        public OptionalDouble minCloseBetween(LocalDate start, LocalDate end) {
            int lo = bisectRight(start);
            int hi = bisectRight(end);
            if (lo >= hi) {
                return OptionalDouble.empty();
            }
            double min = closes[lo];
            for (int i = lo + 1; i < hi; i++) {
                min = Math.min(min, closes[i]);
            }
            return OptionalDouble.of(min);
        }
    }

    /**
     * {@code data/timeseries/<key>.csv} 를 읽는다. 헤더는 {@code date,close}.
     *
     * <p>계산 경로 밖이다 — 어느 디렉토리를 읽을지는 호출자가 정한다. 버전 고정의 근거는
     * {@code data/timeseries/VERSION} 의 sha256 이다.
     */
    public static Series loadSeries(Path timeseriesDir, String key) {
        Path path = timeseriesDir.resolve(key + ".csv");
        if (!Files.exists(path)) {
            throw new IllegalStateException(
                    path + " 가 없다. python3 scripts/fetch_timeseries.py");
        }
        Map<LocalDate, Double> points = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                throw new IllegalStateException(path + " 가 비어 있다");
            }
            // 첫 줄은 헤더
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                int comma = line.indexOf(',');
                if (comma < 0) {
                    throw new IllegalStateException(path + " 의 행 형식이 date,close 가 아니다: " + line);
                }
                points.put(LocalDate.parse(line.substring(0, comma).trim()),
                        Double.parseDouble(line.substring(comma + 1).trim()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("시계열 CSV 를 읽을 수 없다: " + path, e);
        }
        return Series.of(key, points);
    }

    // --- 상품 조건 ---------------------------------------------------------------------

    /**
     * 스텝다운 ELS 조건.
     *
     * @param underlyings       기초자산 키. worst-of 판정 순서에 쓰인다
     * @param observationMonths 조기상환·만기 평가 시점(계약일로부터 개월). 마지막 항목이 만기평가일
     * @param barriers          각 평가일의 배리어(최초기준가격 대비 비율). observationMonths 와 길이가 같다
     * @param couponAnnual      연 쿠폰율. i번째 평가일 지급률 = couponAnnual × months/12 (스텝다운 관행)
     * @param knockIn           낙인 배리어. null 이면 노낙인형
     */
    public record Product(String name, List<String> underlyings, List<Integer> observationMonths,
                          List<Double> barriers, double couponAnnual, Double knockIn, String note) {

        public Product {
            if (observationMonths.size() != barriers.size()) {
                throw new IllegalArgumentException("observationMonths와 barriers의 길이가 다르다");
            }
            underlyings = List.copyOf(underlyings);
            observationMonths = List.copyOf(observationMonths);
            barriers = List.copyOf(barriers);
        }

        public int maturityMonths() {
            return observationMonths.get(observationMonths.size() - 1);
        }

        /** i번째 평가일의 지급률. 조기상환 시점과 무관하게 연율은 같고 총액만 다르다. */
        public double payoutRate(int obsIndex) {
            return couponAnnual * observationMonths.get(obsIndex) / 12;
        }
    }

    /**
     * 키움증권 제4181회 파생결합증권(주가연계증권) — 데모 대상 ELS.
     *
     * <p>조건 근거: 간이투자설명서 p7(평가일·손익구조), p10(낙인 45%·만기 손실조건 70%),
     * 금투협 청약정보 요약("[스텝다운] 3년/6개월/85-85-85-80-75-70/KI45", 연 11.00%).
     */
    public static final Product KIWOOM_4181 = new Product(
            "키움증권 제4181회 ELS",
            List.of("sp500", "nikkei225", "eurostoxx50"),
            List.of(6, 12, 18, 24, 30, 36),
            List.of(0.85, 0.85, 0.85, 0.80, 0.75, 0.70),
            0.11,
            0.45,
            "3년/6개월 스텝다운, 원화, 최대손실률 -100%");

    // --- 손익 계산 ---------------------------------------------------------------------

    /**
     * 한 번의 시뮬레이션 결과.
     *
     * @param result      {@code "early_1".."early_5"} | {@code "maturity"} | {@code "loss"}
     * @param pnlRate     원금 대비 손익률. 손실이면 음수
     * @param worstFinal  만기까지 간 경우 만기평가일의 최저 종목 비율. 조기상환이면 null
     * @param initial     최초기준가격. 기초자산 키 → 종가
     */
    public record Outcome(LocalDate startDate, String result, double pnlRate, Double worstFinal,
                          boolean knockedIn, Map<String, Double> initial) {

        public Outcome {
            initial = Map.copyOf(initial);
        }

        public boolean isLoss() {
            return "loss".equals(result);
        }

        /** 가입금액을 넣으면 상환금액(원). 원 단위 절사 — 표시용이므로 보수적으로. */
        public long payout(long amount) {
            return (long) (amount * (1 + pnlRate));
        }
    }

    /**
     * 계약일 + n개월. 말일 처리는 "해당 월에 그 날짜가 없으면 말일".
     *
     * <p>{@code LocalDate.plusMonths} 가 이미 같은 규칙으로 내림한다(1월 31일 + 1개월 = 2월 말일).
     * 참조 구현이 날짜를 하루씩 줄이며 찾는 것과 결과가 같다.
     */
    public static LocalDate addMonths(LocalDate d, int months) {
        return d.plusMonths(months);
    }

    /**
     * start 를 최초기준가격평가일로 보고 상품을 굴린다. 데이터가 부족하면 빈 값 — 추정하지 않는다.
     */
    public static Optional<Outcome> simulate(Product product, Map<String, Series> series,
                                             LocalDate start) {
        Map<String, Double> initial = new LinkedHashMap<>();
        for (String key : product.underlyings()) {
            Optional<Quote> got = series.get(key).closeOnOrBefore(start);
            if (got.isEmpty()) {
                return Optional.empty();
            }
            initial.put(key, got.get().close());
        }

        LocalDate maturity = addMonths(start, product.maturityMonths());
        // 만기 평가일이 보유 데이터 끝을 넘으면 아직 결과를 모른다 — 굴리지 않는다.
        for (String key : product.underlyings()) {
            if (maturity.isAfter(series.get(key).last())) {
                return Optional.empty();
            }
        }

        // 조기상환 판정: 모든 기초자산이 배리어 이상이어야 한다.
        for (int i = 0; i < product.observationMonths().size() - 1; i++) {
            LocalDate evalDate = addMonths(start, product.observationMonths().get(i));
            double worst = Double.POSITIVE_INFINITY;
            for (String key : product.underlyings()) {
                Optional<Quote> got = series.get(key).closeOnOrBefore(evalDate);
                if (got.isEmpty()) {
                    return Optional.empty();
                }
                worst = Math.min(worst, got.get().close() / initial.get(key));
            }
            if (worst >= product.barriers().get(i)) {
                return Optional.of(new Outcome(start, "early_" + (i + 1), product.payoutRate(i),
                        null, false, initial));
            }
        }

        // 만기 판정
        double worstFinal = Double.POSITIVE_INFINITY;
        for (String key : product.underlyings()) {
            Optional<Quote> got = series.get(key).closeOnOrBefore(maturity);
            if (got.isEmpty()) {
                return Optional.empty();
            }
            worstFinal = Math.min(worstFinal, got.get().close() / initial.get(key));
        }

        boolean knockedIn = false;
        if (product.knockIn() != null) {
            for (String key : product.underlyings()) {
                OptionalDouble low = series.get(key).minCloseBetween(start, maturity);
                if (low.isPresent() && low.getAsDouble() / initial.get(key) < product.knockIn()) {
                    knockedIn = true;
                    break;
                }
            }
        }

        double maturityBarrier = product.barriers().get(product.barriers().size() - 1);
        double fullCoupon = product.payoutRate(product.observationMonths().size() - 1);

        // 문서 p10: 낙인이 발생했고(and) 만기평가일 최저 종목이 만기배리어 미만인 경우에만 손실.
        // 둘 중 하나만 해당하면 쿠폰을 받는다 — 노낙인 보호가 여기서 작동한다.
        if (knockedIn && worstFinal < maturityBarrier) {
            return Optional.of(new Outcome(start, "loss", worstFinal - 1.0, worstFinal, true, initial));
        }

        return Optional.of(new Outcome(start, "maturity", fullCoupon, worstFinal, knockedIn, initial));
    }

    /** 굴릴 수 있는 모든 계약일. 기준이 되는 지수는 데이터가 가장 늦게 시작하는 것. */
    public static List<LocalDate> startDates(Product product, Map<String, Series> series) {
        LocalDate latestStart = null;
        LocalDate earliestLast = null;
        Series pivot = null;
        for (String key : product.underlyings()) {
            Series s = series.get(key);
            if (latestStart == null || s.first().isAfter(latestStart)) {
                latestStart = s.first();
            }
            if (earliestLast == null || s.last().isBefore(earliestLast)) {
                earliestLast = s.last();
            }
            // 동률이면 앞선 기초자산이 이긴다 — 참조 구현의 max(key=...) 와 같은 tie-break.
            if (pivot == null || s.first().isAfter(pivot.first())) {
                pivot = s;
            }
        }

        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d : pivot.dates) {
            if (d.isBefore(latestStart)) {
                continue;
            }
            if (addMonths(d, product.maturityMonths()).isAfter(earliestLast)) {
                break;
            }
            out.add(d);
        }
        return out;
    }
}
