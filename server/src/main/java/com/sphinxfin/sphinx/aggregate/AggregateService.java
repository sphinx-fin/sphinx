package com.sphinxfin.sphinx.aggregate;

import com.sphinxfin.sphinx.catalog.RiskItemCatalog;
import com.sphinxfin.sphinx.core.session.CoachingScoreService;
import com.sphinxfin.sphinx.core.session.Session;
import com.sphinxfin.sphinx.core.session.SessionRepository;
import com.sphinxfin.sphinx.domain.OverrideStatus;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.Signal;
import com.sphinxfin.sphinx.security.AccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 오해 지도 집계. 소유: 정세현 (F-DSH-001~002 데이터 공급)
 * 상품×항목 단위만. 개인 식별자·세션ID는 대시보드로 전달하지 않는다. n&lt;30 셀 마스킹.
 *
 * <h2>데이터 범위는 여기서 정하지 않는다 — 호출자가 준다</h2>
 *
 * <p>{@code scope} 를 인자로 받는다. 이 클래스가 {@link AccessPolicy} 를 다시 물어보면
 * <b>인가 판단이 두 곳에서 나오고</b>, 한쪽에만 있는 분기가 생기는 순간 갈린다. 같은 결함을
 * 이슈 #136 에서 봤다 — 채점에 쓴 질문과 기록에 남긴 질문을 따로 구해서 폴백 경로에서
 * 어긋났다. 컨트롤러의 {@code @PreAuthorize} 가 이미 판정한 값을 그대로 받는다.
 *
 * <p>그래서 {@code OWN_SESSION} 이 들어오면 <b>거부한다.</b> 집계에 그 범위를 주는 그랜트가
 * 정책에 없으므로(SELLER 는 집계 접근 불가 — ADR-001 · 기획서 7-4), 여기 닿았다는 것은
 * 배선이 틀렸다는 뜻이다. 조용히 전체를 주면 역이용 방지가 그 경로에서만 사라진다.
 *
 * <h2>합성 데이터 표기는 켜고 끄는 값이 아니다</h2>
 *
 * <p>{@code synthetic} 은 항상 {@code true} 다. 기획서가 그렇게 정했다 —
 * <i>"고객 실데이터는 한 건도 쓰지 않는다. 오해 지도 대시보드는 (…) 합성 세션으로 구성하며,
 * 화면과 문서에 '합성 데이터'임을 표기한다"</i>({@code docs/proposal.md}). 이 시스템에 실고객
 * 데이터가 들어오는 경로 자체가 없다.
 *
 * <p>설정값으로 뺄 수도 있었지만 그러면 <b>끄는 것이 가능해진다.</b> 잘못 켜면 불필요한
 * 워터마크가 뜨고(무해), 잘못 끄면 합성 수치가 실측으로 보인다(기획서 "연출 금지" 위반).
 * 두 오류의 무게가 다르므로 상수로 둔다 — 바꾸려면 기획을 다시 봐야 한다.
 */
@Service
public class AggregateService {

    /**
     * 이 수 미만인 셀은 값을 가린다. 계약이 정한 값이다({@code openapi.yaml HeatmapCell.masked}).
     *
     * <p><b>셀을 지우지 않는다.</b> 지우면 화면이 "데이터 없음" 과 "가려짐" 을 구분할 수 없고,
     * 감사 관점에서는 <b>가려졌다는 사실 자체가 마스킹이 동작한 증거</b>다.
     */
    static final int MIN_CELL_SAMPLE = 30;

    /**
     * 이상치로 볼 직전 대비 상승폭(비율. 0.15 = 15%p).
     *
     * <p>❗<b>이 값에는 아직 측정 근거가 없다.</b> 기획서 화면 예시가 <i>"직전 4주 대비
     * +18%p"</i> 를 들지만 그건 형태를 보이려는 가상 수치다 — 같은 문단이 <i>"실측값이
     * 아니다"</i> 라고 적고 있다. 근거를 만들려면 합성 세션 100건(F-DSH-003, 미구현)이 먼저다.
     *
     * <p>값을 고르는 대신 <b>이상치를 아예 안 내는 것</b>도 선택지였는데, 계약에 배열이 있어
     * 형태를 맞췄다. 그래서 이 상수는 <b>임시</b>다 — F-DSH-003 이 들어오면 분포에서 다시
     * 도출한다. 그전까지 이 숫자로 무엇이 걸러졌는지 해석하면 안 된다.
     */
    static final BigDecimal OUTLIER_DELTA_MIN = new BigDecimal("0.15");

    /** 직전 몇 구간의 평균과 비교하는가. 기획서 예시의 "직전 4주" 를 따른다. */
    static final int OUTLIER_BASELINE_PERIODS = 4;

    /** 기획서 4절 — 실데이터는 쓰지 않는다. 클래스 주석 참고. */
    private static final boolean SYNTHETIC = true;

    /** 집계 축. <b>데이터 범위와 다른 개념이다</b> — 계약이 이름을 갈라 뒀다. */
    public enum GroupBy { BRANCH, SELLER, ITEM }

    /** 히트맵 필터. 전부 nullable — 없으면 안 거른다. */
    public record Filters(String product, String ageBand, String channel) {
        public static Filters none() {
            return new Filters(null, null, null);
        }
    }

    /**
     * 셀의 등급 분포. <b>비율이 아니라 건수다</b>(이슈 #177).
     *
     * <p>건수로 주면 화면이 비율·합계를 다 만들 수 있고, 무엇보다 <b>{@code u1+u2+u3+u4 == n}
     * 이라는 검산이 성립한다.</b> 비율로 내리면 반올림 때문에 그 검산이 사라지고, 어느 칸이
     * 몇 건인지 되짚을 수 없다.
     *
     * <p>개인 식별자는 여전히 나가지 않는다 — 등급별 건수는 집계 수치다(기획 7-4 · ADR-001).
     */
    public record Grades(long u1, long u2, long u3, long u4) {}

    /**
     * 히트맵 한 칸. {@code product}·{@code item} 은 <b>축의 키</b>이고
     * {@code productName}·{@code itemName} 은 <b>사람이 읽는 이름</b>이다 — 둘을 같이 내는
     * 이유는 키가 감사에 필요하기 때문이다. 화면이 이름을 크게, 키를 작게 그린다.
     *
     * <p>❗<b>이름은 없을 수 있고 그건 정상이다</b>({@code null}). 카탈로그에 없는 항목이면
     * 화면이 키를 그대로 그린다 — 라벨이 없다고 집계를 멈추지 않는다. {@code productName} 은
     * <b>상품 유형 축에서만</b> 찬다: 실제 상품ID 로 집계되는 축의 이름은
     * {@code GET /products} 가 이미 주므로 여기서 비워 두 벌이 되는 것을 막는다
     * (자세한 근거: {@link RiskItemCatalog#productName}).
     */
    public record Cell(String product, String item, String productName, String itemName,
                       BigDecimal misrate, long n, boolean masked, Grades grades) {}

    /**
     * 취약/비취약 한 줄. {@code band} 는 {@code "vulnerable"} · {@code "other"} 둘뿐이다.
     *
     * <p><b>두 줄을 항상 함께 낸다</b> — 한쪽이 소표본이라 가려져도 자리를 남긴다. 없는 줄을
     * 빼면 화면이 "비교" 를 못 그리고, 가려졌다는 사실이 화면에서 사라진다(마스킹을 셀 제거로
     * 하지 않는 것과 같은 이유다).
     */
    public record ContrastRow(String band, BigDecimal misrate, long n, boolean masked,
                              Grades grades) {}

    public record ContrastView(boolean synthetic, String scope, List<ContrastRow> rows) {}

    public record HeatmapView(boolean synthetic, String scope, List<Cell> cells) {}

    /** 게이트 신호 하나의 건수와 비율. {@code share} 는 판정된 세션 대비다. */
    public record SignalCount(String signal, long n, BigDecimal share) {}

    /**
     * 오버라이드 — 요청과 승인을 가른다. 요청만 하고 승인 안 된 것이 그 자체로 신호다.
     *
     * <p>{@code judged} 가 분모다 — 오버라이드는 RED 판정 뒤에만 생기므로 <b>진행 중
     * 세션은 분모가 아니다.</b> 가림 판단도 같은 값으로 한다: 둘이 갈리면 화면이 그리는
     * 비율의 분모와 가렸는지 정한 표본이 다른 값이 된다(#362 리뷰).
     */
    public record OverrideCount(long requested, long approved, long judged, boolean masked) {}

    /**
     * ★ 재설명 효과 — <b>재설명을 거친 항목 중 최종 이해에 도달한 비율.</b>
     *
     * <p>이 제품이 실제로 이해를 올렸다는 유일한 정량 근거다. 오해율은 문제 제기이고
     * 이건 성과다.
     */
    public record ReexplainEffect(long items, long resolved, BigDecimal rate, boolean masked) {}

    /** 못 잰 항목이 있는 채로 판정된 세션 — R-00 이 무는 자리다. */
    public record UnmeasuredCount(long sessions, long judged, BigDecimal share, boolean masked) {}

    public record DecisionView(boolean synthetic, String scope, List<SignalCount> gate,
                               OverrideCount override, ReexplainEffect reexplain,
                               UnmeasuredCount unmeasured) {}

    public record Point(String period, BigDecimal misrate, long n, boolean masked) {}

    public record Series(String groupBy, String key, List<Point> points) {}

    public record Outlier(String groupBy, String key, String reason, BigDecimal delta) {}

    public record IndicatorView(boolean synthetic, String scope,
                                List<Series> series, List<Outlier> outliers) {}

    private final SessionRepository sessions;

    /**
     * ❗<b>취약 여부를 여기서 다시 정의하지 않는다.</b> {@code vulnerability_weights.yaml} 이
     * 유일한 근거이고 그것을 읽는 것은 {@link CoachingScoreService} 다. 연령대만으로
     * "50대 이상은 취약" 같은 판정을 이 클래스에 적으면 <b>정의가 두 벌이 되고</b>, 실제 정의는
     * 연령·가입금액대·투자경험·채널 네 요인의 합이라 두 값이 처음부터 다르다.
     *
     * <p>같은 함정이 web 쪽에도 있다 — {@code lib/sessionAttrs.ts} 의 {@code weighted} 는
     * <b>연령만 보는 근사</b>다. 화면 표시용이고 집계 기준이 아니다.
     */
    private final CoachingScoreService coaching;

    /**
     * 이해항목 표시명. <b>집계에는 루브릭 이름을 쓴다</b>(결정 5.42) — 추출 이름
     * ({@code 낙인 배리어 45%})은 이 회차 값이라, 상품을 넘어 합치는 이 축에 쓰면 같은
     * 항목이 회차마다 다른 줄로 갈라진다. 그건 {@code #317} 이 고치려던 것과 같은 종류의
     * 틀린 축이다.
     */
    private final RiskItemCatalog catalog;

    public AggregateService(SessionRepository sessions, CoachingScoreService coaching,
                            RiskItemCatalog catalog) {
        this.sessions = sessions;
        this.coaching = coaching;
        this.catalog = catalog;
    }

    /**
     * 상품×항목 오해율. 필터는 세션 속성(상품·연령대·채널)에 걸린다.
     *
     * <p>셀이 하나도 없으면 빈 목록을 낸다 — <b>오류가 아니다.</b> 판정이 쌓이기 전의 정상
     * 상태이고, 화면은 그것을 "데이터 없음" 으로 그린다.
     */
    @Transactional(readOnly = true)
    public HeatmapView heatmap(AccessPolicy.Scope scope, String branchId, Filters filters) {
        Map<CellKey, Tally> byCell = new TreeMap<>();
        for (Session session : visible(scope, branchId)) {
            if (!matches(session, filters)) {
                continue;
            }
            session.judgmentsByItem().forEach((itemId, judgment) ->
                    byCell.computeIfAbsent(new CellKey(session.productId(), itemId),
                            k -> new Tally()).add(judgment));
        }

        List<Cell> cells = new ArrayList<>();
        byCell.forEach((key, tally) -> cells.add(new Cell(
                key.product(), key.item(),
                catalog.productName(key.product()), catalog.itemName(key.item()),
                tally.misrateOrNull(), tally.n(), tally.masked(),
                tally.gradesOrNull())));
        return new HeatmapView(SYNTHETIC, label(scope), cells);
    }

    /**
     * 게이트가 <b>무엇을 결정했는가</b> (F-DSH-001 · 이슈 #321 2·3·4).
     *
     * <h2>왜 필요한가 — 지금 대시보드는 「측정」만 낸다</h2>
     *
     * <p>히트맵·추이·취약 대비가 전부 <b>오해율</b>이다. 그건 <i>"고객이 무엇을 모르는가"</i>
     * 이고, 이 제품이 하는 일은 그다음이다 — <b>막았는가 · 되돌렸는가 · 예외를 뒀는가.</b>
     * 심사에서 물을 수 있는 것이 *"그래서 이 게이트가 실제로 무엇을 했나요"* 인데 지금은
     * 화면에 답이 없다.
     *
     * <h2>★ 재설명 효과가 이 뷰의 본체다</h2>
     *
     * <p>지금까지 낼 수 있던 숫자는 <i>"오해가 29% 였다"</i> 뿐이고 <b>그건 문제 제기이지
     * 성과가 아니다.</b> 재설명을 거친 항목 중 최종 이해(U1)에 도달한 비율은 <b>이 제품이
     * 실제로 이해를 올렸다</b>는 유일한 정량 근거다.
     *
     * <p>❗<b>세션 테이블만으로 계산된다.</b> {@code reverifyCounts} 가 그 항목이 재설명을
     * 거쳤는지 알고, {@code judgmentsByItem} 이 최종 등급을 안다 — 불변 기록을 안 열어도
     * 된다. {@code evidence/} 가 인메모리라({@code #327}) 거기 기대면 재기동마다 사라진다.
     *
     * <h2>마스킹</h2>
     *
     * <p>히트맵과 같은 규칙({@link #MIN_CELL_SAMPLE})을 블록마다 따로 적용한다. 표본이
     * 적으면 값을 가리되 <b>줄을 지우지 않는다</b> — 가려졌다는 사실 자체가 마스킹이
     * 동작한 증거다.
     */
    public DecisionView decisions(AccessPolicy.Scope scope, String branchId, Filters filters) {
        Map<String, Long> bySignal = new TreeMap<>();
        long judged = 0;
        long overrideRequested = 0;
        long overrideApproved = 0;
        long unmeasuredSessions = 0;
        long reexplained = 0;
        long resolved = 0;

        for (Session session : visible(scope, branchId)) {
            if (!matches(session, filters)) {
                continue;
            }
            // ❗**판정된 세션만 센다 — 네 지표 전부.** 진행 중 세션은 결정이 아니다.
            // 재설명 효과가 이 규칙 밖에 있었을 때 방향이 한쪽이었다: 재설명 직후 항목은
            // 재채점 전이라 아직 U3 이고, 그게 전부 분모에 실패로 들어간다 — **면담을
            // 진행할수록 성과 지표가 내려갔다**(#362 리뷰). 오버라이드도 같다. 그쪽은
            // 분모만 어긋나 있었는데(전체 매칭 / 가림 판단은 judged), 그러면 화면이
            // 그리는 비율의 분모와 가림에 쓴 표본이 다른 값이 된다.
            if (session.gateSignal() == null) {
                continue;
            }
            judged++;
            bySignal.merge(session.gateSignal().name(), 1L, Long::sum);
            if (session.gateUnmeasured() > 0) {
                unmeasuredSessions++;
            }
            if (session.overrideStatus() != OverrideStatus.NONE) {
                overrideRequested++;
                if (session.overrideStatus() == OverrideStatus.APPROVED) {
                    overrideApproved++;
                }
            }
            for (var entry : session.judgmentsByItem().entrySet()) {
                if (session.reverifyCount(entry.getKey()) > 0) {
                    reexplained++;
                    if (entry.getValue().grade() == Grade.U1) {
                        resolved++;
                    }
                }
            }
        }

        List<SignalCount> signals = new ArrayList<>();
        for (Signal signal : Signal.values()) {
            long n = bySignal.getOrDefault(signal.name(), 0L);
            signals.add(new SignalCount(signal.name(), n, share(n, judged, judged)));
        }
        return new DecisionView(
                SYNTHETIC, label(scope), signals,
                new OverrideCount(overrideRequested, overrideApproved, judged,
                        judged < MIN_CELL_SAMPLE),
                new ReexplainEffect(reexplained, resolved, share(resolved, reexplained, reexplained),
                        reexplained < MIN_CELL_SAMPLE),
                new UnmeasuredCount(unmeasuredSessions, judged,
                        share(unmeasuredSessions, judged, judged), judged < MIN_CELL_SAMPLE));
    }

    /**
     * 비율. <b>표본이 적으면 {@code null}</b> 이고 분모가 0 이어도 {@code null} 이다.
     *
     * <p>❗0 을 돌려주면 <i>"한 번도 없었다"</i> 와 <i>"셀 수 없다"</i> 가 같아진다 —
     * 히트맵이 {@code misrateOrNull} 로 세운 규칙과 같다.
     */
    private static BigDecimal share(long part, long total, long sample) {
        if (total == 0 || sample < MIN_CELL_SAMPLE) {
            return null;
        }
        return BigDecimal.valueOf(part).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    /**
     * 지점·판매자·항목 단위 주별 추이와 이상치. 마스킹 규칙은 히트맵과 같다.
     *
     * <p>구간은 <b>최근 {@code periods} 주</b>이고 <b>값이 없는 주도 자리를 남긴다</b>
     * ({@code n=0} · {@code masked=true}). 빈 주를 빼면 화면이 추이의 끊김을 못 그리고,
     * 직전 평균 비교도 구간 수가 계열마다 달라진다.
     */
    @Transactional(readOnly = true)
    public IndicatorView leadingIndicators(AccessPolicy.Scope scope, String branchId,
                                           GroupBy groupBy, int periods, Instant now) {
        List<String> window = recentPeriods(now, periods);
        Map<String, Map<String, Tally>> byKey = new TreeMap<>();

        for (Session session : visible(scope, branchId)) {
            String period = periodOf(session.createdAt());
            if (!window.contains(period)) {
                continue;
            }
            session.judgmentsByItem().forEach((itemId, judgment) -> {
                String key = keyOf(session, itemId, groupBy);
                if (key == null) {
                    return;     // 축 값을 모르는 세션은 그 축의 집계에 넣지 않는다 (10.5 전)
                }
                byKey.computeIfAbsent(key, k -> new TreeMap<>())
                        .computeIfAbsent(period, p -> new Tally())
                        .add(judgment);
            });
        }

        List<Series> series = new ArrayList<>();
        List<Outlier> outliers = new ArrayList<>();
        byKey.forEach((key, byPeriod) -> {
            List<Point> points = new ArrayList<>();
            for (String period : window) {
                Tally tally = byPeriod.getOrDefault(period, new Tally());
                points.add(new Point(period, tally.misrateOrNull(), tally.n(), tally.masked()));
            }
            series.add(new Series(label(groupBy), key, points));
            outlier(label(groupBy), key, points).ifPresent(outliers::add);
        });
        return new IndicatorView(SYNTHETIC, label(scope), series, outliers);
    }

    /**
     * 취약 고객과 나머지의 오해율 대비 (F-DSH-001 · 이슈 #321 의 1번).
     *
     * <h3>왜 필터가 아니라 대비인가</h3>
     *
     * <p>화면에는 이미 연령대 <b>필터</b>가 있다. 70대만 걸러 볼 수는 있는데 그 값 하나로는
     * <i>"나머지보다 얼마나 높은가"</i> 를 알 수 없고, 사람은 <b>29%</b> 라는 수를 보면 그것이
     * 높은지 낮은지 판단할 기준을 화면 밖에서 가져온다. 두 줄을 나란히 놓으면 그 기준이
     * 화면 안에 있다 — {@code eval} 리포트가 모델 점수 옆에 <b>평가자 간 일치도(상한)</b> 를
     * 같이 내는 것과 같은 이유다.
     *
     * <p>그리고 이 대비가 곧 {@code vulnerability_weights.yaml} 이 존재하는 이유다. 지금은
     * 그 가중이 코칭 스코어 안에서만 쓰여 <b>화면 어디에도 근거가 안 보인다.</b>
     *
     * <h3>취약의 정의는 여기 없다</h3>
     *
     * <p>{@link CoachingScoreService#score} 가 그대로 답한다. {@code suitabilityMismatch} 도
     * 세션이 들고 있는 실제 값을 넘긴다 — 여기서 {@code false} 로 뭉개면 모순이 있던 세션의
     * 가산점(+2)이 빠져서 <b>같은 세션이 코칭 경로와 집계 경로에서 다르게 분류된다.</b>
     *
     * <h3>마스킹</h3>
     *
     * <p>히트맵과 같은 규칙({@code MIN_CELL_SAMPLE})이다. <b>두 줄은 언제나 둘 다 낸다</b> —
     * 한쪽이 가려져도 자리를 지운다면 화면이 대비를 못 그리고, 무엇보다 <b>가려졌다는 사실이
     * 증거</b>이므로 지우면 안 된다.
     */
    @Transactional(readOnly = true)
    public ContrastView vulnerabilityContrast(AccessPolicy.Scope scope, String branchId,
                                              Filters filters) {
        Tally vulnerable = new Tally();
        Tally other = new Tally();

        for (Session session : visible(scope, branchId)) {
            if (!matches(session, filters)) {
                continue;
            }
            Tally bucket = coaching.score(session, session.suitabilityMismatch()).vulnerable()
                    ? vulnerable : other;
            session.judgmentsByItem().forEach((itemId, judgment) -> bucket.add(judgment));
        }

        return new ContrastView(SYNTHETIC, label(scope), List.of(
                row("vulnerable", vulnerable),
                row("other", other)));
    }

    private static ContrastRow row(String band, Tally tally) {
        return new ContrastRow(band, tally.misrateOrNull(), tally.n(), tally.masked(),
                tally.gradesOrNull());
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    /** 상품×항목. {@code TreeMap} 키라 정렬이 결정론적이다 — 같은 입력이 같은 순서를 낸다. */
    private record CellKey(String product, String item) implements Comparable<CellKey> {
        @Override
        public int compareTo(CellKey other) {
            int byProduct = product.compareTo(other.product);
            return byProduct != 0 ? byProduct : item.compareTo(other.item);
        }
    }

    private List<Session> visible(AccessPolicy.Scope scope, String branchId) {
        if (scope == AccessPolicy.Scope.OWN_SESSION) {
            throw new IllegalArgumentException(
                    "집계에 own_session 범위가 들어왔다 — 정책에 그런 그랜트가 없으므로"
                    + "(ADR-001 · 기획서 7-4) 배선이 틀린 것이다. 어느 action 으로 왔는지 확인한다");
        }
        List<Session> all = sessions.findAll();
        if (scope == AccessPolicy.Scope.ORG) {
            return all;
        }
        if (branchId == null) {
            // 지점을 모르면 branch 범위를 만들 수 없다. 전체를 주면 org 로 새므로 빈 목록이다 —
            // "막고 있다" 가 아니라 "판단할 수 없다" 다(결정 10.5. AccessPolicy 와 같은 취급).
            return List.of();
        }
        return all.stream().filter(session -> branchId.equals(session.branchId())).toList();
    }

    private static boolean matches(Session session, Filters filters) {
        return (filters.product() == null || filters.product().equals(session.productId()))
                && (filters.ageBand() == null || filters.ageBand().equals(session.ageBand()))
                && (filters.channel() == null
                        || filters.channel().equals(String.valueOf(session.channel())));
    }

    private static String keyOf(Session session, String itemId, GroupBy groupBy) {
        return switch (groupBy) {
            case BRANCH -> session.branchId();
            case SELLER -> pseudonym(session.sellerId());
            case ITEM -> itemId;
        };
    }

    /**
     * 판매자 대체키. 계약이 <i>"판매자는 비식별 대체키"</i> 를 요구한다 — 집계 화면에 로그인
     * ID 가 그대로 뜨면 그게 곧 개인 식별자이고, 그 목록이 <b>"실적이 나쁜 판매자 명단"</b>이
     * 된다(기획서 7-4 가 막으려는 것).
     *
     * <p>같은 판매자가 같은 키를 갖도록 결정론적으로 만든다. 대시보드가 답해야 하는 것은
     * <b>누구인가</b>가 아니라 <b>편차가 있는가</b>다.
     */
    private static String pseudonym(String sellerId) {
        if (sellerId == null) {
            return null;
        }
        return "S-" + Integer.toHexString(sellerId.hashCode()).toUpperCase();
    }

    /** 패키지 가시성 — 주 단위 데이터를 JPA 로 만들기 어려워 테스트가 직접 구간을 준다. */
    static Optional<Outlier> outlier(String groupBy, String key, List<Point> points) {
        Point latest = points.get(points.size() - 1);
        if (latest.masked() || latest.misrate() == null) {
            return Optional.empty();        // 가려진 구간으로 이상치를 말하지 않는다
        }
        List<Point> baseline = points
                .subList(Math.max(0, points.size() - 1 - OUTLIER_BASELINE_PERIODS),
                        points.size() - 1)
                .stream().filter(point -> !point.masked() && point.misrate() != null).toList();
        if (baseline.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal mean = baseline.stream().map(Point::misrate)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(baseline.size()), 4, RoundingMode.HALF_UP);
        BigDecimal delta = latest.misrate().subtract(mean);
        if (delta.compareTo(OUTLIER_DELTA_MIN) < 0) {
            return Optional.empty();
        }
        String reason = "직전 %d구간 평균 대비 +%s%%p".formatted(baseline.size(),
                delta.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                        .toPlainString());
        return Optional.of(new Outlier(groupBy, key, reason, delta));
    }

    /** ISO 주(계약 예시 {@code 2026-W32}). <b>최신이 목록의 끝</b>이다. */
    static List<String> recentPeriods(Instant now, int periods) {
        List<String> out = new ArrayList<>();
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        for (int back = periods - 1; back >= 0; back--) {
            out.add(format(day.minusWeeks(back)));
        }
        return out;
    }

    static String periodOf(Instant at) {
        return format(at.atZone(ZoneOffset.UTC).toLocalDate());
    }

    private static String format(LocalDate day) {
        return "%d-W%02d".formatted(day.get(IsoFields.WEEK_BASED_YEAR),
                day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private static String label(AccessPolicy.Scope scope) {
        return scope == AccessPolicy.Scope.ORG ? "org" : "branch";
    }

    private static String label(GroupBy groupBy) {
        return groupBy.name().toLowerCase();
    }

    /**
     * 한 셀의 표본.
     *
     * <h3>오해율은 U4 만 센다</h3>
     *
     * <p>{@link Grade} 가 <i>U1 이해 / U2 부분이해 / U3 미이해 / U4 오해</i> 로 갈라 두었고,
     * 이 화면의 이름이 <b>오해 지도</b>다. U3(미이해)를 섞으면 지도가 이름과 다른 것을 말한다.
     *
     * <p>둘은 대응이 다르다 — <b>모르는 것</b>은 설명을 더 하면 되고, <b>잘못 아는 것</b>은
     * 설명서 문장을 고쳐야 한다. 기획서가 이 화면의 쓸모로 든 것이 후자다
     * (<i>"어떤 표현이 오해를 만드는지 (…) 이 문장을 이렇게 바꾸면 오해율이 내려간다"</i>).
     *
     * <p>미이해율이 따로 필요하면 <b>같은 셀의 뜻을 넓히는 것이 아니라 지표를 하나 더 두는
     * 것</b>이 맞다. 한 숫자에 두 뜻을 담으면 나중에 어느 쪽으로 읽어야 하는지 알 수 없다 —
     * {@code confidence} 가 프롬프트 버전마다 뜻이 달라 이슈 #136 이 된 것과 같은 모양이다.
     *
     * <p><b>이슈 #177 이 정확히 그 길로 갔다.</b> {@code misrate} 의 정의는 그대로 두고
     * {@link Grades} 를 따로 실었다. 화면에 41% 만 있으면 <i>"59% 는 이해했다"</i> 로 읽히는데
     * 그 59% 안에 부분이해·미이해가 섞여 있고, <b>이해(U1)가 한 건도 없어도 41% 가 나온다.</b>
     * 오해율이 낮다는 이유로 <i>"설명이 잘 통했다"</i> 는 결론이 나가면 기획 4절이 비판하는
     * 그 관행을 지표로 재생산하는 것이 된다 — 문면으로 막을 일이 아니라 데이터로 막을 일이다.
     */
    private static final class Tally {
        private long total;
        private long misunderstood;
        private final EnumMap<Grade, Long> byGrade = new EnumMap<>(Grade.class);

        void add(Judgment judgment) {
            total++;
            byGrade.merge(judgment.grade(), 1L, Long::sum);
            if (judgment.grade() == Grade.U4) {
                misunderstood++;
            }
        }

        long n() {
            return total;
        }

        boolean masked() {
            return total < MIN_CELL_SAMPLE;
        }

        /**
         * 등급 분포. <b>마스킹 규칙이 {@code misrate} 와 같다</b> — 가려진 칸은 분포도 안 준다.
         *
         * <p>둘을 다르게 두면 마스킹이 뚫린다. {@code misrate} 를 가려도 분포를 주면
         * <b>U4 건수 ÷ n 으로 그 값이 그대로 복원된다.</b> 소표본을 가리는 이유가 셀 하나가
         * 몇 사람인지 드러나지 않게 하는 것이므로, 같은 셀의 다른 필드로 되돌릴 수 있으면
         * 가린 것이 아니다.
         */
        Grades gradesOrNull() {
            if (masked() || total == 0) {
                return null;
            }
            return new Grades(count(Grade.U1), count(Grade.U2), count(Grade.U3), count(Grade.U4));
        }

        private long count(Grade grade) {
            return byGrade.getOrDefault(grade, 0L);
        }

        /** 마스킹된 셀은 값을 안 낸다 — {@code n} 은 그대로 내려간다. */
        BigDecimal misrateOrNull() {
            if (masked() || total == 0) {
                return null;
            }
            return BigDecimal.valueOf(misunderstood)
                    .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        }
    }
}
