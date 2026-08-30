package com.sphinxfin.sphinx.simulator;

import com.sphinxfin.sphinx.core.simulator.SimulatorProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F-SIM-001 REST 노출 — {@link SimulatorService} 의 계산을 계약({@code SimulateResponse}) 모양으로
 * 옮긴다. 소유: 정세현 (이슈 #45 · #54 ④)
 *
 * <h2>왜 엔진과 갈랐나</h2>
 *
 * <p>{@code severity}(worst·mid·best)는 <b>화면 배치 키</b>이지 계산 결과가 아니다. 역사
 * 4,110개 계약일을 굴리면 전개가 7종 나오는데 화면은 3열이므로 <b>누구를 대표로 세울지</b>를
 * 정해야 하고, 그건 표시 판단이다. 엔진에 두면 계산기가 화면 배치를 알게 된다.
 *
 * <h2>대표 셋을 고르는 규칙</h2>
 *
 * <pre>
 * worst  손익률 최소인 계약일           역사상 최악. 평균이나 중앙값이 아니다
 * best   손익률 최대인 계약일           역사상 최선
 * mid    가장 자주 일어난 전개의 대표    "흔한 것"이지 "중간값"이 아니다
 * </pre>
 *
 * <p><b>{@code mid} 를 중앙값으로 두지 않은 이유</b>는 기획서 7-2 표가 평가어를 버리고 금액
 * 순으로 간 근거와 같다 — 스텝다운은 조기상환 시점과 무관하게 연 수익률이 같고 총액만
 * 달라서, <b>가장 자주 일어나는 전개(85.7%)가 금액으로는 중간</b>이다. 그 사실을 보이는 것이
 * 이 화면의 목적이고, 중앙값을 세우면 우연히 같은 칸이 나와도 <i>"왜 이게 중간인가"</i> 를
 * 설명하지 못한다.
 *
 * <p>동률은 <b>계약일이 이른 쪽</b>으로 끊는다. P2(결정론)가 요구하는 것은 같은 스냅샷이면
 * 같은 답이라는 것인데, 손익률이 같은 계약일이 여럿이면(만기 상환은 62건이 전부 +33%다)
 * 순서가 정해지지 않은 채로는 실행마다 달라질 수 있다.
 *
 * <h2>금액은 곱하기다 — 그래서 선택은 한 번만 한다</h2>
 *
 * <p>가입금액은 상환금액을 <b>비례로만</b> 바꾼다. 어느 전개가 최악인지는 금액과 무관하므로
 * 4,110회 시뮬레이션은 <b>한 번만</b> 돌리고 결과를 들고 있는다. 슬라이더를 움직일 때마다
 * 다시 굴리면 응답이 느려지는데 답은 같다.
 *
 * <p>적재는 <b>기동이 아니라 첫 호출</b>에 한다. 기동에서 읽으면 시계열이 없는 환경
 * ({@code @DataJpaTest} · 데이터 볼륨 미마운트)에서 무관한 기능까지 못 뜬다 —
 * {@code SimulatorProperties} 가 디렉토리 부재를 경고로만 두는 것과 같은 판단이다. 대신
 * <b>부르면 그때 확실히 실패한다.</b>
 */
@Service
public class SimulationScenarios {

    /** 계약({@code PathMeta}). 어느 실제 지수 구간을 썼는지 — P2 재현성의 실물 근거다. */
    public record PathMeta(LocalDate startDate, LocalDate endDate, List<String> underlyings,
                           String worstUnderlying, double worstFinal, boolean knockedIn) {}

    /** 계약({@code SimScenario}). {@code severity} 는 배치 키이고 사람이 읽는 것은 {@code name} 이다. */
    public record Scenario(String severity, String result, String name, long payout, long pnl,
                           BigDecimal share, PathMeta pathMeta) {}

    /** 계약({@code SimulateResponse}). */
    public record SimulationView(List<Scenario> scenarios, String timeseriesVersion,
                                 String productName) {}

    /**
     * 조기상환 회차 문면. 계약의 {@code name} 은 사람이 읽는 값이라 "early_1" 을 그대로 쓰지 않는다.
     */
    private static final String[] ORDINALS = {"첫", "두 번째", "세 번째", "네 번째", "다섯 번째"};

    /**
     * {@code share} 소수 자릿수. 히트맵 {@code misrate} 와 같은 4자리다.
     *
     * <p>{@code double} 을 그대로 실으면 {@code 0.8571776155115572} 가 나가고, 화면이 반올림해
     * 그리므로 <b>보이는 값은 같은데 계약 필드만 지저분해진다.</b> 자릿수를 서버에서 고정하면
     * 응답이 스냅샷마다 같은 모양이 되어 회귀 비교가 쉬워진다.
     */
    private static final int SHARE_SCALE = 4;

    private final SimulatorProperties properties;

    /** 첫 호출에 한 번 계산하고 들고 있는다. 금액과 무관한 값이다. */
    private volatile Selection selection;

    public SimulationScenarios(SimulatorProperties properties) {
        this.properties = properties;
    }

    /**
     * 가입금액을 넣으면 계약 모양의 응답. 시나리오는 정확히 3건이고 {@code severity} 가 겹치지 않는다.
     */
    public SimulationView view(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("가입금액은 양수다: " + amount);
        }
        Selection picked = selection();
        List<Scenario> scenarios = new ArrayList<>();
        picked.picks().forEach((severity, outcome) ->
                scenarios.add(scenario(severity, outcome, amount, picked.shareOf(outcome))));
        // ❗`sourceName` 이 아니라 `displayName` 이다. 이 값은 S-04 머리말에 그대로 찍힌다
        // (`web/src/pages/S04_Simulator.tsx`) — 기획서의 가명 규약이 걸리는 자리다(#202 리뷰).
        return new SimulationView(List.copyOf(scenarios), picked.snapshot(),
                SimulatorService.KIWOOM_4181.displayName());
    }

    private Scenario scenario(String severity, SimulatorService.Outcome outcome, long amount,
                              BigDecimal share) {
        long payout = outcome.payout(amount);
        return new Scenario(severity, outcome.result(), displayName(outcome), payout,
                payout - amount, share,
                new PathMeta(outcome.startDate(), outcome.endDate(),
                        SimulatorService.KIWOOM_4181.underlyings(), outcome.worstUnderlying(),
                        outcome.worstFinal(), outcome.knockedIn()));
    }

    /**
     * 사람이 읽는 문면. 손실 문면에 <b>낙인 수치를 상품에서 읽어</b> 넣는다 — 하드코딩하면
     * 상품 조건이 바뀔 때 화면만 옛 숫자를 말한다.
     */
    private static String displayName(SimulatorService.Outcome outcome) {
        SimulatorService.Product product = SimulatorService.KIWOOM_4181;
        if (outcome.isLoss()) {
            return "낙인 %d%% 하회 후 만기 손실".formatted(Math.round(product.knockIn() * 100));
        }
        if ("maturity".equals(outcome.result())) {
            return "조기상환 없이 만기 상환";
        }
        int nth = Integer.parseInt(outcome.result().substring("early_".length()));
        return "%d개월 뒤 %s 조기상환"
                .formatted(product.observationMonths().get(nth - 1), ORDINALS[nth - 1]);
    }

    private Selection selection() {
        Selection local = selection;
        if (local == null) {
            synchronized (this) {
                local = selection;
                if (local == null) {
                    local = compute();
                    selection = local;
                }
            }
        }
        return local;
    }

    private Selection compute() {
        Path dir = properties.timeseriesDir();
        Map<String, SimulatorService.Series> series = new LinkedHashMap<>();
        for (String key : SimulatorService.KIWOOM_4181.underlyings()) {
            series.put(key, SimulatorService.loadSeries(dir, key));
        }

        List<SimulatorService.Outcome> outcomes = new ArrayList<>();
        for (LocalDate start : SimulatorService.startDates(SimulatorService.KIWOOM_4181, series)) {
            SimulatorService.simulate(SimulatorService.KIWOOM_4181, series, start)
                    .ifPresent(outcomes::add);
        }
        if (outcomes.isEmpty()) {
            throw new IllegalStateException(
                    "시뮬레이션 결과가 없다 — " + dir.toAbsolutePath().normalize()
                    + " 의 시계열이 상품 만기(" + SimulatorService.KIWOOM_4181.maturityMonths()
                    + "개월)를 덮지 못한다");
        }
        return new Selection(outcomes, snapshot(dir));
    }

    /**
     * {@code data/timeseries/VERSION} 의 {@code snapshot}. <b>상수로 두지 않는다</b> — CSV 를 다시
     * 받으면 이 값이 바뀌는데, 코드에 굽혀 있으면 화면이 <i>"이 수치는 이 스냅샷에서 나왔다"</i>
     * 를 <b>틀리게</b> 말한다. 재현성 근거로 보이는 값이 실제 데이터와 어긋나는 것이 없는 것보다 나쁘다.
     */
    private static String snapshot(Path dir) {
        Path version = dir.resolve("VERSION");
        try {
            for (String line : Files.readAllLines(version)) {
                if (line.startsWith("snapshot:")) {
                    return line.substring("snapshot:".length()).trim();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("시계열 VERSION 을 읽을 수 없다: "
                    + version.toAbsolutePath().normalize(), e);
        }
        throw new IllegalStateException("시계열 VERSION 에 snapshot 항목이 없다: "
                + version.toAbsolutePath().normalize());
    }

    /**
     * 고른 대표 셋과 전개별 비중. {@code picks} 는 <b>삽입 순서</b>가 worst·mid·best 다 —
     * 계약이 배열이고 화면이 3열을 그 순서로 세운다.
     */
    private record Selection(Map<String, SimulatorService.Outcome> picks,
                             Map<String, BigDecimal> shareByResult, String snapshot) {

        Selection(List<SimulatorService.Outcome> outcomes, String snapshot) {
            this(pick(outcomes), shares(outcomes), snapshot);
        }

        BigDecimal shareOf(SimulatorService.Outcome outcome) {
            return shareByResult.get(outcome.result());
        }

        private static Map<String, SimulatorService.Outcome> pick(
                List<SimulatorService.Outcome> outcomes) {
            // ❗min 과 max 가 같은 비교기를 쓰면 동률 처리가 반대로 간다 — max 는 계약일이
            // **늦은** 쪽을 고른다. 양쪽 다 이른 계약일로 끊으려면 max 쪽에서 날짜를 뒤집는다.
            Comparator<SimulatorService.Outcome> earliestOnTie =
                    Comparator.comparingDouble(SimulatorService.Outcome::pnlRate)
                            .thenComparing(SimulatorService.Outcome::startDate);
            Comparator<SimulatorService.Outcome> latestLosesOnTie =
                    Comparator.comparingDouble(SimulatorService.Outcome::pnlRate)
                            .thenComparing(SimulatorService.Outcome::startDate,
                                    Comparator.reverseOrder());

            SimulatorService.Outcome worst = outcomes.stream().min(earliestOnTie).orElseThrow();
            SimulatorService.Outcome best = outcomes.stream().max(latestLosesOnTie).orElseThrow();

            Map<String, SimulatorService.Outcome> picks = new LinkedHashMap<>();
            picks.put("worst", worst);
            picks.put("mid", mid(outcomes, worst, best));
            picks.put("best", best);
            return picks;
        }

        /**
         * 가장 자주 일어난 전개의 대표. worst·best 가 이미 가져간 전개는 건너뛴다 —
         * 같은 전개를 두 칸에 세우면 3열이 <b>다른 것을 비교하지 못한다.</b>
         *
         * <p>전개가 셋도 안 되면 <b>던진다.</b> 계약이 {@code minItems: 3} 이라 억지로 채우면
         * 같은 칸이 두 번 나가고, 그건 화면에서 <i>"최악과 중간이 같다"</i> 로 읽힌다 —
         * 데이터가 모자란 사실이 조용히 그럴듯한 표로 덮이는 쪽이 나쁘다.
         */
        private static SimulatorService.Outcome mid(List<SimulatorService.Outcome> outcomes,
                                                    SimulatorService.Outcome worst,
                                                    SimulatorService.Outcome best) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (SimulatorService.Outcome o : outcomes) {
                counts.merge(o.result(), 1L, Long::sum);
            }
            Optional<String> chosen = counts.entrySet().stream()
                    .filter(e -> !e.getKey().equals(worst.result()))
                    .filter(e -> !e.getKey().equals(best.result()))
                    .max(Map.Entry.<String, Long>comparingByValue()
                            .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                    .map(Map.Entry::getKey);

            String result = chosen.orElseThrow(() -> new IllegalStateException(
                    "전개가 " + counts.keySet() + " 뿐이라 3열을 채울 수 없다 — "
                    + "시계열이 짧아 최악·최선 말고 다른 전개가 안 나온다"));

            return outcomes.stream()
                    .filter(o -> o.result().equals(result))
                    .min(Comparator.comparing(SimulatorService.Outcome::startDate))
                    .orElseThrow();
        }

        /** 전개별 비중 — 역사 전 구간에서 그 전개가 몇 번 나왔나. */
        private static Map<String, BigDecimal> shares(List<SimulatorService.Outcome> outcomes) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (SimulatorService.Outcome o : outcomes) {
                counts.merge(o.result(), 1L, Long::sum);
            }
            Map<String, BigDecimal> shares = new LinkedHashMap<>();
            counts.forEach((result, n) -> shares.put(result, BigDecimal.valueOf(n)
                    .divide(BigDecimal.valueOf(outcomes.size()), SHARE_SCALE, RoundingMode.HALF_UP)));
            return shares;
        }
    }
}
