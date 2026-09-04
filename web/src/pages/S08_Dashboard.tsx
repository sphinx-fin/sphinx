/**
 * S-08 오해 지도 대시보드 — F-DSH-001 · **F-DSH-002** 의 UI 몫. 소유: 오준서.
 * 집계 파이프라인은 정세현(`AggregateService`). 화면은 집계하지 않는다.
 *
 * ── 이 화면은 세 가지를 "보이게" 하려고 있다 ────────────────────────────────
 *
 * ① **합성 데이터임을 숨기지 않는다** (F-DSH-003 연출 금지)
 *    `synthetic: true` 면 표식을 **상시** 노출한다. 접거나 각주로 내리지 않는다 —
 *    심사에서 "이 수치 진짜인가"가 나왔을 때 화면이 먼저 답하고 있어야 한다.
 *    실데이터로 바뀌면 표식이 저절로 사라진다(값으로 판단하므로).
 *
 *    문장("합성 세션 기반 — 실제 고객 데이터가 아닙니다")이었던 것을 **칩 한 개**로
 *    줄였다. 대시보드는 수치를 읽는 곳이라 설명 문장이 값을 밀어낸다. 요건은 "노출"
 *    이지 "문장"이 아니므로 표식으로 만족한다 — 뜻은 ⓘ 에 붙어 있다.
 *
 * ② **가려진 셀과 데이터 없는 셀을 구분한다** (소표본 마스킹)
 *    계약이 소표본(n<30) 셀을 **제거하지 않고** `masked: true` + `misrate: null` 로
 *    내린다. 제거하면 화면이 두 상태를 구분할 수 없고, **마스킹이 동작했다는 증거가
 *    화면에서 사라진다.** 기획서 7-4 의 실물 근거가 바로 그 증거라, 여기서는 가려진 셀을
 *    빈칸이 아니라 **"가려짐 · n=12"** 로 적극적으로 그린다.
 *
 * ③ **차단됨을 그린다** (ADR-001 · 기획서 7-4)
 *    계약이 403 을 *"화면이 '차단됨'을 그려야 하므로 계약에 선언한다"* 고 적었다.
 *    SELLER·CUST·ADMIN 은 집계에 접근할 수 없다 — 역할 부재가 이 제품의 논지이고,
 *    데모에서 실제로 시연하는 항목이다. 그래서 403 은 오류가 아니라 **정상 결과**로
 *    그린다: 빨간 에러 배너가 아니라 차단 설명 화면이다.
 *
 * ④ **수치가 무엇의 비율인지 화면이 먼저 말한다.**
 *    `misrate` 는 그 항목에서 **오해(U4)로 판정된 비율**이다(`AggregateService.Tally`).
 *    분자에 U4 만 들어가므로 **나머지가 다 이해한 것이 아니다** — 부분이해(U2)·미이해(U3)
 *    는 이 수치 어디에도 없다. 정의를 각주로 내리면 "41%" 를 본 사람이 "59% 는 이해했다"
 *    로 읽고, 그건 이 제품이 제일 하면 안 되는 종류의 오독이다. 그래서 정의와 **말할 수
 *    없는 것**을 표 바로 위에 둔다.
 *
 *    같은 이유로 이 화면은 **개인을 말하지 않는다.** "이 고객이 아는가" 의 답은 세션별
 *    이해 기록(S-07)에 있고, 여기 있는 것은 집계뿐이다(계약상 세션ID조차 안 내려온다).
 *
 * `scope`(branch·org)를 헤더에 박는다. 무엇을 보고 있는지가 안 보이면 MGR 이 자기 지점
 * 수치를 전사 수치로 착각한다 — 집계 축(`groupBy`)과 다른 개념이라 특히 헷갈린다.
 *
 * ── ⑤ 선행지표 뷰 (F-DSH-002) ──────────────────────────────────────────────
 *
 * 히트맵은 **한 시점의 단면**이라 *"지난주보다 나빠졌는가"* 를 못 말한다. 그 질문에
 * 답하는 것이 이 뷰이고, 명세 8절이 S-08 의 요소로 "선행지표 뷰" 를 따로 적어 둔 이유다.
 * 같은 화면 안의 **다른 뷰**로 둔다 — 지점·판매자·항목이 다른 라우트로 흩어지면 필터와
 * 범위 표식을 두 벌로 유지해야 하고, 그러면 한쪽만 갱신되는 사고가 난다.
 *
 * **선은 안 쓰고 막대를 쓴다.** 계약이 값 없는 주도 자리를 남기라고 했고(`n=0`·`masked`),
 * 선으로 이으면 **없는 주를 지나가며 값을 지어낸다** — 가려진 구간을 통과하는 추세선은
 * 이 제품이 제일 하면 안 되는 종류의 그림이다. 막대는 그 자리를 비워 둘 수 있다.
 *
 * **계열마다 색을 주지 않는다.** 지점이 늘면 색이 늘고, 그러면 판정 3색과 경쟁한다
 * (tokens.css 규칙 1). 대신 계열을 **행으로 갈라** 한 축(시간)만 그리고, 값의 크기는
 * 히트맵과 같은 한 가지 잉크의 높이로 말한다. 행 이름이 곧 범례라 범례 상자도 없다.
 *
 * **표로 그린다.** 행=계열 · 열=주 인 표 안에 막대를 넣으면, 화면을 못 보는 사람에게도
 * 같은 데이터가 그대로 읽힌다(별도 "표 보기" 를 만들 필요가 없다).
 *
 * **이상치는 서버가 판단한 것만 표시한다.** 화면이 임계값을 다시 계산하면 두 벌이 되고,
 * 어긋나는 날 화면이 서버가 하지 않은 판단을 말한다(P1 과 같은 결). 그래서 `reason`
 * 문장을 그대로 낸다. 색으로 소리치지도 않는다 — 이상치는 **판정이 아니다.**
 */
import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { ApiRequestError, get } from "../api/client";
import type {
  ContrastResponse, ContrastRow, DecisionResponse, GradeDistribution, HeatmapCell,
  HeatmapResponse, IndicatorAxis, IndicatorPoint, LeadingIndicatorResponse, OverrideCount,
  ProductSummary, ReexplainEffect, RiskItem, Signal, SignalCount, UnmeasuredCount,
} from "../api/types";
import { AGE_BANDS, CHANNELS } from "../lib/sessionAttrs";
import "./S08_Dashboard.css";

/**
 * 칸을 칠하는 잉크의 상한(%). 오해율 100% 가 이 농도로 찍힌다.
 *
 * 100 이 아닌 이유는 대비다. 잉크가 60% 를 넘으면 먹색 글자가 4.5:1 아래로 떨어지고,
 * 흰 글자로 뒤집자니 흰 글자는 잉크 70% 는 돼야 4.5:1 이 된다 — 그 사이가 **두 색 다
 * 못 미치는 골짜기**다(kohl-70 → surface 램프 실측). 상한을 60 으로 두면 모든 칸이 한
 * 가지 글자색으로 5:1 이상을 유지하고 색을 뒤집는 분기가 없어진다.
 *
 * 범례 눈금도 **같은 상한**을 쓴다. 다르면 눈금이 거짓말을 한다.
 */
const INK_MAX = 60;

/**
 * 「설명 자료 개선 대상」 표시의 **개수 상한** (이슈 #321 의 5번).
 *
 * ❗이것은 *"몇 개까지가 나쁜 항목인가"* 가 아니라 *"한 번에 몇 개를 먼저 보게 할 것인가"*
 * 다. 잘린 항목도 목록에 그대로 남고 오해율이 옆에 적혀 있으므로 이 상한은 아무것도 감추지
 * 않는다. 값은 화면 문면에 그대로 적힌다 — 안 적으면 조용한 정책이 된다.
 *
 * 전체 평균 초과만으로 고르면 실측에서 **15개 중 8개**에 표시가 붙었다(alpha, 필터 없음).
 * 절반에 붙는 표시는 우선순위가 아니라 순위를 한 번 더 적은 것이다.
 */
const FOCUS_MAX = 3;

/**
 * 취약 대비 두 줄의 라벨 (이슈 #321 의 1번).
 *
 * **"비취약" 이라고 쓰지 않는다.** 취약의 반대말을 만들면 그쪽이 하나의 집단처럼 읽히는데,
 * 실제로는 *"임계값을 넘지 않은 나머지"* 일 뿐이다 — 판정이 아니라 잔여다.
 */
const BAND_LABEL: Record<ContrastRow["band"], string> = {
  vulnerable: "취약 고객",
  other: "그 외",
};

/**
 * 등급 라벨과 그리는 순서. **U1 → U4** 로 고정한다 — 왼쪽이 "이해", 오른쪽으로 갈수록
 * 나쁜 쪽이라 막대의 방향 자체가 뜻을 갖는다. 잉크 농도도 그 순서로 짙어진다.
 */
const GRADE_ORDER: readonly (keyof GradeDistribution)[] = ["u1", "u2", "u3", "u4"];
const GRADE_LABEL: Record<keyof GradeDistribution, string> = {
  u1: "이해",
  u2: "부분이해",
  u3: "미이해",
  u4: "오해",
};

/**
 * 신호등 라벨과 그리는 순서 (이슈 #321 의 2번).
 *
 * ❗**라벨 문면은 S-05 와 같아야 한다.** 판매자가 개별 세션에서 보는 말과 집계에서 보는 말이
 * 다르면 같은 판정이 두 이름을 갖는다 — `S05_Judgment.tsx` 의 `SIGNAL_LABEL` 이 정본이고
 * 여기는 그것을 옮겨 적은 것이다. **갈리면 아무것도 안 말한다**(`INDEX_LABEL` 과 같은 종류의
 * 사본이라 결정 10.59 의 대상이다 — 계약이 표시명을 실으면 둘 다 지운다).
 *
 * ❗**자리는 배열 순서가 아니라 이 표가 정한다.** 서버가 정렬을 바꾸면 막대가 에러 없이
 * 뒤바뀐다 — `lib/scenarios.ts` 가 `severity` 로 자리를 잡는 것과 같은 이유다.
 * 통과 → 보완 → 보류 순서라 **막대의 방향 자체가 뜻을 갖는다.**
 */
const SIGNAL_ORDER: readonly Signal[] = ["GREEN", "YELLOW", "RED"];
const SIGNAL_LABEL: Record<Signal, string> = {
  GREEN: "통과",
  YELLOW: "보완 필요",
  RED: "보류",
};

const SCOPE_LABEL: Record<HeatmapResponse["scope"], string> = {
  branch: "자기 지점",
  org: "전사",
};

/** 집계 축 라벨. **범위(scope)와 다른 개념**이라 화면에서도 말을 갈라 쓴다. */
const AXIS_LABEL: Record<IndicatorAxis, string> = {
  branch: "지점",
  seller: "판매자",
  item: "이해항목",
};

/** 화면에 보이는 두 뷰. 라우트를 가르지 않는 이유는 파일 상단 ⑤에 적었다. */
type View = "heatmap" | "indicator";

export default function S08Dashboard() {
  const [view, setView] = useState<View>("heatmap");
  const [data, setData] = useState<HeatmapResponse | null>(null);
  const [indicator, setIndicator] = useState<LeadingIndicatorResponse | null>(null);
  /**
   * 취약 고객 대비 (이슈 #321 의 1번).
   *
   * 히트맵과 **같은 권한·같은 필터**라 같은 시점에 같이 받는다. 따로 받으면 필터가 한
   * 요청에만 걸리는 순간 두 패널이 다른 모집단을 그리는데, 화면에는 아무 표시도 안 난다.
   *
   * `null` 은 "아직 못 받았다" 이고 `rows` 가 빈 것과 다르다 — 서버는 표본이 0 이어도
   * 두 줄을 낸다(계약 `minItems: 2`). 그래서 이 값이 `null` 이면 데이터가 없는 것이
   * 아니라 **요청이 실패한 것**이고, 화면도 그렇게 말한다.
   */
  const [contrast, setContrast] = useState<ContrastResponse | null>(null);
  /** 대비만 실패했는가. 히트맵이 떴는데 이것만 없으면 침묵하지 않고 그 자리에 적는다. */
  const [contrastFailed, setContrastFailed] = useState(false);
  /**
   * 게이트가 무엇을 결정했는가 (이슈 #321 의 2·3·4번).
   *
   * 대비와 **같은 규칙**으로 다룬다 — 같은 권한·같은 필터라 같은 시점에 받고, 이것만 죽으면
   * 조용히 사라지게 두지 않는다. 규칙을 하나 더 만들지 않는 이유는 두 패널이 갈리는 날
   * 어느 쪽이 맞는 처리인지 알 수 없어지기 때문이다.
   */
  const [decisions, setDecisions] = useState<DecisionResponse | null>(null);
  /** 결정 패널만 실패했는가. */
  const [decisionsFailed, setDecisionsFailed] = useState(false);
  /** 집계 축. 계약 기본값과 같은 `branch` 로 연다 — 화면이 서버와 다른 기본을 갖지 않는다. */
  const [axis, setAxis] = useState<IndicatorAxis>("branch");
  const [product, setProduct] = useState("");
  const [ageBand, setAgeBand] = useState("");
  const [channel, setChannel] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  /** 403 은 오류가 아니라 시연 대상이다(설계 판단 ③). 에러와 따로 들고 있다. */
  const [blocked, setBlocked] = useState(false);
  /** 집계는 상품 id 만 준다. 이름은 이미 있는 `/products` 로 푼다 — 새 계약이 필요 없다.
      못 풀면 id 를 그대로 보여준다(합성·구 상품이면 목록에 없을 수 있다). */
  /** 상품ID → 상품명. `GET /products` 가 준다. */
  const [names, setNames] = useState<Record<string, string>>({});
  /**
   * 이해항목ID → 항목명. **`names` 와 섞지 않는다**(이슈 #317).
   *
   * 한 맵에 담으면 상품과 항목의 키 공간이 한 곳에 뭉쳐서, 폴백(`?? id`)이 어느 축의
   * 것인지 문면으로 구분이 안 된다. 실제로 그래서 항목 축이 `names` 로 그려지고 있었고
   * — `names` 는 `productId → name` 이라 항목ID 는 언제나 폴백으로 떨어졌다 —
   * `ELS-MATURITY-LOSS-CONDITION` 이 심사위원 화면에 스무 줄 깔렸다. 에러가 아니라
   * 폴백이라 아무것도 안 말한다.
   */
  const [itemNames, setItemNames] = useState<Record<string, string>>({});
  /**
   * 툴팁 하나를 화면 좌표로 띄운다.
   *
   * ❗카드 안에 `position:absolute` 로 두면 **가려진다.** 두 가지가 겹쳐서다 —
   *   ① 카드마다 `animation` 이 걸려 있어 각 카드가 **스태킹 컨텍스트**가 된다.
   *      그러면 카드 안의 z-index 가 카드 밖으로 못 나가고, DOM 뒤에 오는 카드가
   *      앞 카드의 툴팁을 덮는다.
   *   ② 표는 `overflow-x: auto` 라 칸 툴팁이 그 상자에서 **잘린다.**
   * 그래서 위치를 트리거의 화면 좌표에서 계산해 `position: fixed` 로 띄운다 —
   * 어떤 상자에도 안 갇힌다.
   */
  const [tip, setTip] = useState<
    { x: number; y: number; below: boolean; node: ReactNode } | null
  >(null);

  const showTip = useCallback((el: HTMLElement | null, node: ReactNode) => {
    if (!el) return;
    const r = el.getBoundingClientRect();
    // 표 맨 윗줄·첫 타일은 위로 띄우면 화면 밖으로 나간다. 자리가 없으면 아래로 뒤집는다.
    const below = r.top < 140;
    setTip({ x: r.left + r.width / 2, y: below ? r.bottom : r.top, below, node });
  }, []);
  const hideTip = useCallback(() => setTip(null), []);

  /* 스크롤하면 `fixed` 툴팁만 제자리에 남아 트리거와 떨어진다 — 그냥 닫는다. */
  useEffect(() => {
    if (!tip) return;
    window.addEventListener("scroll", hideTip, true);
    return () => window.removeEventListener("scroll", hideTip, true);
  }, [tip, hideTip]);

  /* 두 뷰가 한 함수를 쓴다. 403 처리·워터마크·범위 표식이 둘 다 같아서, 갈라 두면
     한쪽만 고치는 사고가 난다(파일 상단 ⑤). 뷰가 바뀌면 다시 받는다 — 대시보드에서
     낡은 수치를 보여 주는 것이 한 번의 요청보다 비싸다. */
  const load = useCallback(async () => {
    setLoading(true);
    try {
      if (view === "heatmap") {
        const qs = new URLSearchParams();
        if (product) qs.set("product", product);
        if (ageBand) qs.set("ageBand", ageBand);
        if (channel) qs.set("channel", channel);
        const q = qs.toString();
        const suffix = q ? `?${q}` : "";
        /* 대비는 **따로 실패시킨다.** 히트맵이 이 화면의 본체라 그쪽 실패만 에러·차단으로
           올린다 — `Promise.all` 로 묶으면 대비 하나가 죽을 때 히트맵까지 안 그려진다.
           대신 조용히 사라지게 두지도 않는다(`contrastFailed`). 권한이 같은 엔드포인트라
           히트맵이 떴는데 이쪽만 죽는 것은 정상 상태가 아니고, 그 사실이 보여야 한다. */
        const [heat, cont, dec] = await Promise.all([
          get<HeatmapResponse>(`/dashboard/heatmap${suffix}`),
          get<ContrastResponse>(`/dashboard/vulnerability-contrast${suffix}`)
            .then((r) => r, () => null),
          get<DecisionResponse>(`/dashboard/decisions${suffix}`)
            .then((r) => r, () => null),
        ]);
        setData(heat);
        setContrast(cont);
        setContrastFailed(cont === null);
        setDecisions(dec);
        setDecisionsFailed(dec === null);
      } else {
        // `periods` 는 보내지 않는다 — 계약이 기본 8주를 갖고 있고, 화면이 같은 숫자를
        // 다시 들면 두 벌이 된다. 바꿀 일이 생기면 계약이 먼저 바뀐다.
        setIndicator(await get<LeadingIndicatorResponse>(
          `/dashboard/leading-indicators?groupBy=${axis}`));
      }
      setBlocked(false);
      setError(null);
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 403) {
        setBlocked(true);
        setData(null);
        setIndicator(null);
        /* 차단은 대비에도 똑같이 걸린다(권한이 같은 action 이다). 남겨 두면 차단 화면
           뒤에 직전 수치가 살아 있다가 뷰를 되돌릴 때 튀어나온다. */
        setContrast(null);
        setContrastFailed(false);
        setDecisions(null);
        setDecisionsFailed(false);
      } else {
        setError(describe(e));
      }
    } finally {
      setLoading(false);
    }
  }, [view, axis, product, ageBand, channel]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const list = await get<ProductSummary[]>("/products");
        if (!alive) return;
        setNames(Object.fromEntries((list ?? []).map((p) => [p.productId, p.name])));
      } catch {
        /* 이름은 있으면 좋은 것이지 없으면 화면이 못 뜨는 것이 아니다 — id 로 그린다. */
      }
    })();
    return () => { alive = false; };
  }, []);

  /* ── 표 축 ──────────────────────────────────────────────────────────────
   * 셀 목록에서 축을 뽑는다. 서버가 축을 따로 주지 않고, 축을 화면에 하드코딩하면
   * 상품·항목이 늘 때 조용히 빠진다.                                              */
  const { products, items, byKey, stats, ranked } = useMemo(() => {
    const cells = data?.cells ?? [];
    const ps: string[] = [];
    const its: string[] = [];
    // 키 구분자는 U+0000 이다. 상품·항목 id 에 절대 나타날 수 없어 `"a b"+"c"` 와
    // `"a"+"b c"` 가 같은 키가 되는 충돌이 원천적으로 없다. **이스케이프로 적는다** —
    // 원시 NUL 을 소스에 넣으면 git 이 파일을 바이너리로 보고 diff 를 안 낸다(실측).
    const map = new Map<string, HeatmapCell>();
    for (const c of cells) {
      if (!ps.includes(c.product)) ps.push(c.product);
      if (!its.includes(c.item)) its.push(c.item);
      map.set(`${c.product}\u0000${c.item}`, c);
    }
    /* ── 요약 지표 ────────────────────────────────────────────────────────
     * 가중 평균이다. 셀 평균을 내면 표본 12건짜리 셀과 900건짜리 셀이 같은 무게가 된다.
     * **가려진 셀은 분자에서 빠진다** — 값이 없기 때문이고, 그래서 표본 합계도 두 개를
     * 따로 센다(전체 n / 값이 있는 n). 하나로 합치면 "몇 건을 근거로 한 수치인가" 를
     * 화면이 틀리게 말한다. */
    const shown = cells.filter((c) => !c.masked && c.misrate != null);
    const nAll = cells.reduce((a, c) => a + c.n, 0);
    const nShown = shown.reduce((a, c) => a + c.n, 0);
    const weighted = nShown === 0
      ? null
      : shown.reduce((a, c) => a + (c.misrate ?? 0) * c.n, 0) / nShown;

    // 항목별 순위 — 같은 항목을 상품 넘어 합친다. 화면이 답해야 하는 질문이
    // "어느 설명이 안 통하는가" 라서 축은 항목이다.
    const byItem = new Map<string, { n: number; mis: number }>();
    for (const c of shown) {
      const cur = byItem.get(c.item) ?? { n: 0, mis: 0 };
      cur.n += c.n;
      cur.mis += (c.misrate ?? 0) * c.n;
      byItem.set(c.item, cur);
    }
    /* ── 다음 행동 (이슈 #321 의 5번) ──────────────────────────────────────
     * 순위만 있으면 화면이 *"그래서 뭘 하라는 건가"* 에 답하지 않는다. 상위 항목을
     * **설명 자료 개선 대상**으로 따로 뽑아 순위를 보고서에서 도구로 바꾼다.
     *
     * 조건이 둘이고, **둘 다 순위 밑에 문면으로 적는다** — 적지 않으면 그게 아무도
     * 합의하지 않은 조용한 정책이 된다.
     *
     * ① **전체 오해율(표본 가중 평균)을 넘을 것.** 이 화면이 이미 크게 적고 있는 수치라
     *    근거가 화면 안에 있고, 데이터가 바뀌면 선도 같이 움직인다. 이 조건이 있어서
     *    **전부 낮으면 아무것도 표시되지 않는다** — 목록이 없는 급함을 지어내지 않는다.
     * ② **그중 상위 `FOCUS_MAX` 개까지.** ①만 쓰면 평균 초과가 곧 절반이라 실측에서
     *    15개 중 8개에 표시가 붙었다(alpha, 필터 없음). 절반에 붙는 표시는 우선순위가
     *    아니라 순위를 한 번 더 적은 것이고, *"먼저 무엇을 고치나"* 에 답하지 못한다.
     *
     * 표본 하한은 따로 안 둔다 — **이미 걸려 있다.** `shown` 이 마스킹된 칸을 뺐고
     * 서버가 n<30 을 마스킹하므로, 여기 올라온 항목은 전부 30건 이상을 근거로 한다.
     * 여기서 상수를 또 쓰면 마스킹 임계값이 web 에 두 벌이 된다.
     *
     * ❗**사람을 가리키지 않는다.** 축이 이해항목이라 이 표시가 가리키는 것은 *"이 항목의
     * 설명 자료"* 이지 *"이 항목을 많이 놓치는 창구"* 가 아니다. 후자는 기획 7-4(역이용
     * 방지)와 정면으로 부딪친다 — 판매자 축에는 이 표시를 절대 붙이지 않는다. */
    const sorted = [...byItem.entries()]
      .map(([item, v]) => ({ item, n: v.n, rate: v.mis / v.n }))
      .sort((a, b) => b.rate - a.rate);
    const focused = new Set(
      weighted == null
        ? []
        : sorted.filter((r) => r.rate > weighted).slice(0, FOCUS_MAX).map((r) => r.item),
    );
    const ranked = sorted.map((r) => ({ ...r, focus: focused.has(r.item) }));

    return {
      products: ps, items: its, byKey: map,
      stats: { weighted, nAll, nShown, maskedCells: cells.filter((c) => c.masked).length,
               cellCount: cells.length },
      ranked,
    };
  }, [data]);

  /* ── 이해항목 표시명 (이슈 #317) ─────────────────────────────────────────
   * 히트맵 응답에는 항목명이 없다. `GET /products/{id}/risk-items` 가 주는 `name` 이
   * 유일한 출처이고, S-02·S-03 이 "원금손실 조건" 을 그리는 것도 그 값이다.
   *
   * ❗**web 에 항목명 표를 두지 않는다.** 그러면 `INDEX_LABEL`(결정 10.59)처럼 표가 두
   * 벌이 되고, web 에는 러너가 없어 갈려도 아무것도 안 말한다 — 그때는 CI 대조 스텝을
   * 따로 붙여야 했다. 항목은 상품보다 자주 늘어서 그쪽이 더 나쁘다.
   *
   * 계약에 표시명을 싣는 쪽이 방향으로는 맞지만(같은 이슈에 적었다) 그건 강희진 승인이
   * 필요하다. 그때까지는 화면이 긁는다 — 축에 **실제로 뜬 상품만** 부르므로 요청 수가
   * 표의 행 수를 안 넘는다.
   *
   * 실패해도 화면은 뜬다. 상품명(`names`)과 같은 규칙으로 id 폴백이다.                */
  const productKey = products.join("\u0000");
  useEffect(() => {
    if (products.length === 0) return;
    let alive = true;
    (async () => {
      const entries = await Promise.all(products.map(async (pid) => {
        try {
          const res = await get<{ items: RiskItem[] }>(`/products/${pid}/risk-items`);
          return (res.items ?? []).map((i) => [i.itemId, i.name] as const);
        } catch {
          return [];
        }
      }));
      if (!alive) return;
      setItemNames((prev) => ({ ...prev, ...Object.fromEntries(entries.flat()) }));
    })();
    return () => { alive = false; };
    // products 는 매 렌더 새 배열이라 내용으로 비교한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [productKey]);

  /* ── 선행지표 축 ────────────────────────────────────────────────────────
   * 계열 순서를 서버 순서(키 사전순)로 두지 않는다 — 8주 × N행 표에서 **먼저 봐야 하는
   * 행이 어디 있는지** 가 순서로만 드러난다. 이상치를 위로, 그다음 최신 값이 큰 순.
   * 값이 없는(가려진) 계열은 맨 아래다. 이건 표시 순서일 뿐 판단이 아니다 — 이상치 여부는
   * 서버가 정한 것을 그대로 쓴다(파일 상단 ⑤).                                        */
  const { rows, periods, outlierKeys } = useMemo(() => {
    const series = indicator?.series ?? [];
    // 계약상 모든 계열이 같은 창(최근 N주)을 갖지만, 그 가정에 기대 열을 그리면 어긋나는
    // 날 조용히 밀린다. 가장 긴 계열의 구간을 열로 삼는다.
    const longest = series.reduce<IndicatorPoint[]>(
      (best, s) => (s.points.length > best.length ? s.points : best), []);
    const keys = new Set((indicator?.outliers ?? []).map((o) => o.key));
    const latest = (points: IndicatorPoint[]) => points[points.length - 1]?.misrate ?? null;
    const sorted = [...series].sort((a, b) => {
      const byOutlier = Number(keys.has(b.key)) - Number(keys.has(a.key));
      if (byOutlier !== 0) return byOutlier;
      return (latest(b.points) ?? -1) - (latest(a.points) ?? -1);
    });
    return { rows: sorted, periods: longest.map((p) => p.period), outlierKeys: keys };
  }, [indicator]);

  /* 범위·합성 표식은 두 뷰가 같이 쓴다 — 뜻이 같은 필드이고, 표식을 뷰마다 따로 그리면
     한쪽에서 워터마크가 빠지는 사고가 난다(그게 F-DSH-003 이 막으려는 것이다). */
  const shown: { scope: HeatmapResponse["scope"]; synthetic: boolean } | null =
    view === "heatmap" ? data : indicator;

  if (loading) {
    return <main className="s08"><p className="s08__loading">집계를 불러오는 중입니다…</p></main>;
  }

  /* ── 차단됨 — 오류가 아니라 정상 결과다(설계 판단 ③) ────────────────────── */
  if (blocked) {
    return (
      <main className="s08">
        <h1>대시보드</h1>
        <section className="s08__blocked">
          <h2>이 역할에는 집계가 열리지 않습니다</h2>
          <p>
            오해 지도와 선행지표는 준법감시(COMPL)와 관리자(MGR)만 볼 수 있습니다.
            판매 조직은 집계에 접근할 수 없습니다.
          </p>
          <p className="s08__blocked-why">
            개인의 이해도 데이터가 영업 관리 지표로 되돌아가면 이 제품은 고객을 보호하는
            대신 고객을 압박하는 도구가 됩니다. 그래서 <strong>역할 자체를 만들지
            않았고</strong>, 집계 접근도 열지 않습니다.
          </p>
        </section>
      </main>
    );
  }

  return (
    <main className="s08">
      <header className="s08__head">
        <div>
          <h1>대시보드</h1>
          {/* 범위와 합성 여부는 문장이 아니라 **표식**으로 남긴다. 둘 다 명세가 요구하는
              것이라(F-DSH-001 표시 · 연출 금지) 지우지 않고 최소 형태로 줄였다. */}
          <p className="s08__tags">
            <Tag
              show={showTip} hide={hideTip} id="tip-scope"
              text={shown ? SCOPE_LABEL[shown.scope] : "—"}
              tip={"이 수치가 어느 범위의 세션을 센 것인지입니다. 요청자 역할이 정합니다 — " +
                   "관리자(MGR)는 자기 지점, 준법감시(COMPL)는 전사입니다."}
            />
            {shown?.synthetic && (
              <Tag
                show={showTip} hide={hideTip} id="tip-synth" strong
                text="합성 데이터"
                tip={"실제 고객 세션이 아니라 만들어 낸 세션을 집계한 수치입니다. " +
                     "합성을 실측처럼 보이게 하지 않으려고 항상 표시합니다 — 실데이터로 " +
                     "바뀌면 이 표식이 저절로 사라집니다."}
              />
            )}
          </p>
        </div>
      </header>

      {error && <p className="s08__error" role="alert">{error}</p>}

      {/* ── 뷰 전환 ────────────────────────────────────────────────────────
          히트맵은 단면, 선행지표는 추이다. 두 질문이 다르므로 화면을 겹쳐 그리지 않고
          자리를 바꾼다 — 한 화면에 표 두 개를 세우면 어느 수치를 읽고 있는지 흐려진다.
          `aria-pressed` 로 낸다: 누르는 동작이 상태를 바꾸는 버튼 두 개다. */}
      <div className="s08__views" role="group" aria-label="뷰 선택">
        <button
          type="button" className="s08__view" aria-pressed={view === "heatmap"}
          onClick={() => setView("heatmap")}
        >
          오해 지도
        </button>
        <button
          type="button" className="s08__view" aria-pressed={view === "indicator"}
          onClick={() => setView("indicator")}
        >
          선행지표
        </button>
      </div>

      {view === "heatmap" && (<>
      {/* 자유 입력이 아니라 선택이다. "60대" 와 "60세" 를 손으로 치면 후자는 아무 셀도
          안 걸리는데 화면은 그냥 빈 표를 보여준다 — 오타와 "해당 없음" 이 구분되지 않는다.
          허용값은 세션이 실제로 보내는 값과 **같은 곳**(lib/sessionAttrs)에서 온다. */}
      <section className="s08__filters">
        <label>
          <span>상품</span>
          <select className="s08__select" value={product} onChange={(e) => setProduct(e.target.value)}>
            <option value="">전체</option>
            {/* 고른 상품에 셀이 하나도 없으면 그 값이 목록에서 사라져 필터가 풀린 것처럼
                보인다 — 실제로는 걸려 있다. 선택값은 목록에 없어도 남긴다. */}
            {(products.includes(product) || !product ? products : [product, ...products]).map((id) => (
              <option key={id} value={id}>{names[id] ?? id}</option>
            ))}
          </select>
        </label>
        <label>
          <span>연령대</span>
          <select className="s08__select" value={ageBand} onChange={(e) => setAgeBand(e.target.value)}>
            <option value="">전체</option>
            {AGE_BANDS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>
        <label>
          <span>채널</span>
          <select className="s08__select" value={channel} onChange={(e) => setChannel(e.target.value)}>
            <option value="">전체</option>
            {CHANNELS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>
      </section>

      {/* ── 요약 타일 ─────────────────────────────────────────────────────
          정의·주의는 화면에 문장으로 깔지 않는다. 대시보드는 수치를 보여주는 곳이고,
          "이게 무슨 비율인가" 는 ⓘ 에 붙여 둔다(마우스 hover · 키보드 포커스 둘 다). */}
      <section className="s08__kpis" aria-label="요약">
        <Kpi
          show={showTip} hide={hideTip}
          label="오해율"
          value={stats.weighted == null ? "—" : `${Math.round(stats.weighted * 100)}%`}
          sub={stats.nShown ? `표본 ${stats.nShown.toLocaleString()}건` : "표본 부족"}
          tipId="tip-misrate"
          tip={"그 항목을 오해(U4)로 판정받은 세션의 비율입니다. 표본으로 가중한 평균이고, " +
               "가려진 셀은 값이 없어 빠집니다. 나머지가 이해했다는 뜻이 아닙니다 — " +
               "부분이해·미이해는 이 수치에 들어가지 않습니다."}
        />
        <Kpi
          show={showTip} hide={hideTip}
          label="표본"
          value={stats.nAll.toLocaleString()}
          sub={`판정 ${stats.cellCount}칸`}
          tipId="tip-n"
          tip="필터를 통과한 세션의 항목별 판정 건수 합계입니다. 개인은 식별되지 않습니다."
        />
        <Kpi
          show={showTip} hide={hideTip}
          label="가려진 칸"
          value={String(stats.maskedCells)}
          sub="표본 30건 미만"
          tipId="tip-masked"
          tip={"표본이 30건 미만인 칸은 개인이 역추정될 수 있어 값을 감춥니다. " +
               "칸을 지우지는 않습니다 — 가려졌다는 사실 자체가 마스킹이 동작한 증거입니다."}
        />
        <Kpi
          show={showTip} hide={hideTip}
          label="최다 오해 항목"
          value={ranked[0] ? `${Math.round(ranked[0].rate * 100)}%` : "—"}
          sub={ranked[0] ? (itemNames[ranked[0].item] ?? ranked[0].item) : "값이 있는 칸 없음"}
          tipId="tip-top"
          tip="값이 있는 칸만 놓고 항목별로 합쳤을 때 오해율이 가장 높은 항목입니다."
        />
      </section>

      {/* ── 게이트가 무엇을 결정했는가 (이슈 #321 의 2·3·4번) ────────────────
          ❗**측정보다 먼저 온다.** 이 아래는 전부 오해율이고 그건 *"고객이 무엇을 모르는가"*
          다 — 이 제품이 하는 일은 그다음이다(막았는가 · 되돌렸는가 · 예외를 뒀는가).
          P1 이 *"AI 는 측정, 룰은 결정"* 인데 화면에는 **결정 쪽이 통째로 없었다.**

          ❗**이 패널에서만 3색을 쓴다.** `tokens.css` 규칙 1 이 채도 있는 색을 판정에만
          허용하는데, 게이트 신호 분포는 바로 그 판정이다. 대신 규칙 3 도 같이 걸린다 —
          **색만으로 구분하지 않고 라벨을 반드시 병기한다**(적록색약에게 말라카이트와
          카넬리안은 명도가 1.01:1 이라 같은 회색이다). 아래 막대 조각은 전부
          `aria-hidden` 이고 사실은 그 밑 문장에 있다. */}
      {(decisions || decisionsFailed) && (
        <section className="s08__decide" aria-label="게이트 결정">
          <h2 className="s08__panel-title">게이트가 무엇을 결정했는가</h2>
          {decisions ? (<>
            <GateBar gate={decisions.gate} />
            <div className="s08__dstats">
              <ReexplainStat effect={decisions.reexplain} />
              <OverrideStat count={decisions.override} />
              <UnmeasuredStat count={decisions.unmeasured} />
            </div>
            <p className="s08__panel-note">
              신호 분포는 <b>판정이 끝난 세션만</b> 셉니다 — 진행 중인 세션은 아직 결정이
              아니라서 넣으면 분모가 부풀고 모든 비율이 낮아집니다. 비율이 <b>0 이 아니라
              「가려짐」</b>인 칸은 표본이 30건에 못 미쳐 값을 감춘 것이고,
              <b> 「데이터 없음」</b>은 해당하는 건이 아예 없는 것입니다 — 둘은 다른 사실입니다.
            </p>
          </>) : (
            /* 대비 패널과 같은 규칙이다 — 권한이 같은 엔드포인트라 히트맵이 떴는데 이쪽만
               죽는 것은 정상 상태가 아니고, 빈 자리는 "결정이 없었다" 로 읽힌다. */
            <p className="s08__contrast-fail">
              결정 수치를 불러오지 못했습니다. 오해 지도는 정상이므로 이 칸만 다시 받으면
              됩니다 — 필터를 바꾸면 다시 시도합니다.
            </p>
          )}
        </section>
      )}

      {/* ── 항목별 순위 ───────────────────────────────────────────────────
          히트맵은 "어느 상품의 어느 항목" 을 보는 표라 항목 단위 비교가 눈으로 안 된다.
          같은 데이터를 한 축(항목)으로 접어 막대로 세운다. */}
      {ranked.length > 0 && (
        <section className="s08__rank" aria-label="항목별 오해율">
          <h2 className="s08__panel-title">항목별 오해율</h2>
          <ol className="s08__rank-list">
            {ranked.map((r) => (
              <li key={r.item} className={`s08__rank-row${r.focus ? " s08__rank-row--focus" : ""}`}>
                <span className="s08__rank-name">
                  {itemNames[r.item] ?? r.item}
                  {/* 표식은 색이 아니라 **말**이다. 이 화면에서 3색은 판정 전용이고
                      (tokens.css 규칙 1) 이건 판정이 아니라 우선순위다 — 색을 쓰면
                      집계가 게이트 신호처럼 읽힌다. */}
                  {r.focus && <span className="s08__rank-flag">설명 자료 개선 대상</span>}
                </span>
                <span className="s08__rank-track">
                  <span className="s08__rank-bar" style={{ width: `${Math.max(r.rate * 100, 1)}%` }} />
                </span>
                <span className="s08__rank-val">{Math.round(r.rate * 100)}%</span>
                <span className="s08__rank-n">{r.n.toLocaleString()}건</span>
              </li>
            ))}
          </ol>
          {/* 기준선을 문면이 그대로 적는다 — 적지 않으면 그게 조용한 정책이 된다.
              선이 없는 경우(전부 가려짐)는 표식도 없으므로 이 문장도 안 낸다. */}
          {stats.weighted != null && (
            <p className="s08__panel-note">
              전체 오해율(<b>{Math.round(stats.weighted * 100)}%</b>)을 넘는 항목 중
              <b> 상위 {FOCUS_MAX}개</b>를 <b>설명 자료 개선 대상</b>으로 표시했습니다.
              기준은 이 화면의 오해율 그대로라 데이터가 바뀌면 같이 움직이고, 개수 상한은
              <b> 먼저 볼 것을 좁히려는 것</b>이지 나머지가 괜찮다는 뜻이 아닙니다 — 표시가
              없는 항목도 오해율은 옆에 그대로 적혀 있습니다. <b>가려진 칸은 순위에
              들어가지 않습니다</b> — 여기 있는 항목은 모두 표본 30건 이상을 근거로 합니다.
            </p>
          )}
        </section>
      )}

      {/* ── 취약 고객 대비 (이슈 #321 의 1번) ─────────────────────────────────
          연령대는 지금까지 **필터**였다. 70·80대만 걸러 볼 수는 있었는데 *"나머지보다
          얼마나 높은가"* 가 안 보였고, 그래서 `vulnerability_weights.yaml` 이 왜 가중을
          매기는지가 화면에서 증명되지 않았다. 두 줄을 나란히 놓는 것이 그 증명이다. */}
      {(contrast || contrastFailed) && (
        <section className="s08__contrast" aria-label="취약 고객 대비">
          <h2 className="s08__panel-title">취약 고객 대비</h2>
          {contrast ? (<>
            <div className="s08__contrast-rows">
              {contrast.rows.map((row) => (
                <ContrastBand key={row.band} row={row} />
              ))}
            </div>
            <ContrastGap rows={contrast.rows} />
            <p className="s08__panel-note">
              취약 여부는 <b>서버가 정합니다</b> — 연령대만이 아니라 가입금액대·투자경험·
              채널까지 네 요인의 합입니다(<code>vulnerability_weights.yaml</code>). 같은
              연령대 안에서도 두 줄이 갈립니다. 위 필터는 <b>두 줄에 똑같이</b> 걸리고,
              표본 30건 미만인 줄은 히트맵 칸과 같은 규칙으로 가려집니다.
            </p>
          </>) : (
            /* 조용히 사라지게 두지 않는다 — 히트맵과 권한이 같은 엔드포인트라 여기만
               죽는 것은 정상 상태가 아니다. 빈 자리는 "데이터가 없다" 로 읽힌다. */
            <p className="s08__contrast-fail">
              대비 수치를 불러오지 못했습니다. 오해 지도는 정상이므로 이 칸만 다시 받으면
              됩니다 — 필터를 바꾸면 다시 시도합니다.
            </p>
          )}
        </section>
      )}

      {products.length === 0 ? (
        <p className="s08__empty">집계된 셀이 없습니다.</p>
      ) : (
        <section className="s08__matrix" aria-label="상품별 이해항목 오해율">
          <h2 className="s08__panel-title">상품 × 이해항목</h2>
          <div className="s08__table-wrap">
          <table className="s08__table">
            <thead>
              <tr>
                <th scope="col">상품 \ 이해항목</th>
                {items.map((it) => (
                  <th key={it} scope="col">
                    {itemNames[it] ?? it}
                    {itemNames[it] && <span className="s08__row-id">{it}</span>}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {products.map((p) => (
                <tr key={p}>
                  <th scope="row">
                    {names[p] ?? p}
                    {names[p] && <span className="s08__row-id">{p}</span>}
                  </th>
                  {items.map((it) => {
                    const c = byKey.get(`${p}\u0000${it}`);
                    if (!c) {
                      // 셀 자체가 없다 = 데이터 없음. 가려진 것과 다르다(설계 판단 ②).
                      return <td key={it} className="s08__cell s08__cell--absent"><span className="sr-only">데이터 없음</span>—</td>;
                    }
                    if (c.masked) {
                      // 가려짐 — 빈칸으로 두지 않는다. 마스킹이 동작한 증거다.
                      return (
                        <td
                          key={it}
                          className="s08__cell s08__cell--masked"
                          tabIndex={0}
                          onMouseEnter={(e) => showTip(e.currentTarget, (
                            <>
                              <b>{names[p] ?? p}</b>{itemNames[it] ?? it}
                              <span className="s08__tip-val">
                                표본 {c.n}건 — 30건 미만이라 값을 가렸습니다
                              </span>
                            </>
                          ))}
                          onMouseLeave={hideTip}
                          onFocus={(e) => showTip(e.currentTarget, (
                            <>
                              <b>{names[p] ?? p}</b>{itemNames[it] ?? it}
                              <span className="s08__tip-val">
                                표본 {c.n}건 — 30건 미만이라 값을 가렸습니다
                              </span>
                            </>
                          ))}
                          onBlur={hideTip}
                        >
                          <span className="s08__masked-label">가려짐</span>
                          <span className="s08__n">{c.n}건</span>
                          <span className="sr-only">
                            표본이 30건 미만({c.n}건)이라 값을 가렸습니다
                          </span>
                        </td>
                      );
                    }
                    const pct = Math.round((c.misrate ?? 0) * 100);
                    return (
                      <td
                        key={it}
                        className="s08__cell s08__cell--data"
                        tabIndex={0}
                        onMouseEnter={(e) => showTip(e.currentTarget, (
                          <>
                            <b>{names[p] ?? p}</b>{itemNames[it] ?? it}
                            <span className="s08__tip-val">오해율 {pct}% · 표본 {c.n}건</span>
                          </>
                        ))}
                        onMouseLeave={hideTip}
                        onFocus={(e) => showTip(e.currentTarget, (
                          <>
                            <b>{names[p] ?? p}</b>{itemNames[it] ?? it}
                            <span className="s08__tip-val">오해율 {pct}% · 표본 {c.n}건</span>
                          </>
                        ))}
                        onBlur={hideTip}
                        // 명도만으로 강도를 낸다 — 판정 3색을 여기서 쓰면 집계가 판정처럼 보인다.
                        //
                        // ❗잉크는 INK_MAX 까지만 쓴다. 100% 까지 칠하면 진한 칸에서 글자를
                        //   흰색으로 뒤집어야 하는데, 뒤집는 구간(대략 55~70%)이 **두 색 다
                        //   4.5:1 에 못 미치는 골짜기**다(실측). 상한을 두면 어느 칸에서도
                        //   같은 글자색으로 5:1 이상이 남고 분기 자체가 사라진다.
                        style={{ background: `color-mix(in srgb, var(--kohl-70) ${(pct * INK_MAX) / 100}%, var(--surface))` }}
                      >
                        <span className="s08__pct">{pct}%</span>
                        <span className="s08__n">{c.n}건 중</span>
                        <span className="sr-only">
                            {c.n}건 중 {pct}퍼센트가 이 항목을 오해로 판정받았습니다
                        </span>
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        </section>
      )}
      </>)}

      {/* ── 선행지표 뷰 (F-DSH-002) ───────────────────────────────────────── */}
      {view === "indicator" && (<>
        <section className="s08__filters">
          <label>
            <span>집계 축</span>
            <select
              className="s08__select"
              value={axis}
              onChange={(e) => setAxis(e.target.value as IndicatorAxis)}
            >
              {(Object.keys(AXIS_LABEL) as IndicatorAxis[]).map((a) => (
                <option key={a} value={a}>{AXIS_LABEL[a]}</option>
              ))}
            </select>
          </label>
          {/* 축과 범위를 화면이 먼저 갈라 말한다 — 계약이 이름을 가른 이유가 이것이고,
              둘을 같은 것으로 읽으면 MGR 이 지점 추이를 전사 추이로 본다. */}
          <p className="s08__axis-note">
            상품·연령대·채널 필터는 오해 지도에만 걸립니다. 여기서 고르는 것은
            <b> 집계 축</b>이고, 보이는 <b>범위</b>는 역할이 정합니다.
          </p>
        </section>

        {/* 이상치 — 서버가 판단한 것만. 화면은 임계값을 다시 계산하지 않는다. */}
        {(indicator?.outliers.length ?? 0) > 0 && (
          <section className="s08__outliers" aria-label="이상치">
            <h2 className="s08__panel-title">
              이상치 <span className="s08__count">{indicator?.outliers.length}건</span>
            </h2>
            <ul className="s08__out-list">
              {/* 키 구분자는 데이터에 못 들어가는 문자라야 겹치지 않는다. 다만 리터럴 NUL 을
                  파일에 박으면 rg·grep 이 이 파일을 바이너리로 보고 통째로 건너뛴다 — 검색이
                  0건을 내고, 에러가 아니라 침묵으로 틀린다(이슈 #318). 같은 값을 이스케이프로 적는다. */}
              {indicator?.outliers.map((o) => (
                <li key={`${o.groupBy}\u0000${o.key}`} className="s08__out-row">
                  <span className="s08__out-key">{o.key}</span>
                  {/* 사유 문장은 서버 것을 그대로 낸다 — 화면이 고쳐 쓰면 근거가 갈린다. */}
                  <span className="s08__out-reason">{o.reason}</span>
                </li>
              ))}
            </ul>
            <p className="s08__panel-note">
              직전 구간 평균과 견준 값입니다. <b>가려진 구간으로는 이상치를 말하지 않습니다</b> —
              표본이 모자란 주는 판단에서 빠집니다.
            </p>
          </section>
        )}

        {rows.length === 0 ? (
          <p className="s08__empty">집계된 계열이 없습니다.</p>
        ) : (
          <section className="s08__trend" aria-label={`${AXIS_LABEL[axis]}별 주간 오해율 추이`}>
            <h2 className="s08__panel-title">{AXIS_LABEL[axis]}별 주간 추이</h2>
            <div className="s08__table-wrap">
              <table className="s08__table s08__trend-table">
                <thead>
                  <tr>
                    <th scope="col">{AXIS_LABEL[axis]} \ 주</th>
                    {periods.map((p, i) => (
                      <th
                        key={p}
                        scope="col"
                        className={i === periods.length - 1 ? "s08__col-latest" : undefined}
                      >
                        {/* 화면에는 주 번호만, 스크린리더에는 연도까지. 열이 8개라
                            연도를 여덟 번 반복하면 그게 표를 가린다. */}
                        <span aria-hidden="true">{shortWeek(p)}</span>
                        <span className="sr-only">{p}</span>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((s) => {
                    const total = s.points.reduce((a, p) => a + p.n, 0);
                    return (
                      <tr key={s.key}>
                        <th scope="row">
                          {s.key}
                          {/* 이상치 표식은 색이 아니라 **말**이다 — 신호등 3색은 판정
                              전용이고(tokens.css 규칙 1) 이건 판정이 아니다. */}
                          {outlierKeys.has(s.key) && (
                            <span className="s08__out-flag">이상치</span>
                          )}
                          <span className="s08__row-id">표본 {total.toLocaleString()}건</span>
                        </th>
                        {s.points.map((pt, i) => (
                          <TrendCell
                            key={pt.period}
                            point={pt}
                            seriesKey={s.key}
                            latest={i === s.points.length - 1}
                            show={showTip}
                            hide={hideTip}
                          />
                        ))}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <p className="s08__panel-note">
              막대 높이가 그 주의 오해율입니다. <b>값이 없는 주는 막대를 그리지 않습니다</b> —
              판정이 없었던 주와 표본이 모자라 가린 주는 0% 와 다릅니다. 숫자는 가장 최근
              주에만 적습니다.
            </p>
          </section>
        )}
      </>)}

      {/* 화면 좌표에 뜨는 단 하나의 툴팁. `position: fixed` 라 카드의 스태킹 컨텍스트도,
          표의 overflow 도 통과한다. 트리거가 여럿이어도 실체는 하나다. */}
      {tip && (
        <div
          className={`s08__floattip ${tip.below ? "s08__floattip--below" : ""}`}
          role="presentation"
          style={{ left: tip.x, top: tip.y }}
        >
          {tip.node}
        </div>
      )}
    </main>
  );
}

/**
 * 머리말 표식. 짧은 말로 두고 뜻은 hover·포커스에 붙인다 — 문장으로 깔면 값을 밀어낸다.
 *
 * `span` 이 아니라 `button` 인 이유는 **키보드로도 열려야** 하기 때문이다. 누르는 동작이
 * 없는 버튼이지만, 포커스를 받을 수 있는 표준 요소가 이것이다.
 */
function Tag({ text, tip, id, strong, show, hide }: {
  text: string; tip: string; id: string; strong?: boolean;
  show: (el: HTMLElement | null, node: ReactNode) => void; hide: () => void;
}) {
  return (
    <>
      <button
        type="button"
        className={`s08__tag ${strong ? "s08__tag--synth" : ""}`}
        aria-describedby={id}
        onMouseEnter={(e) => show(e.currentTarget, tip)}
        onMouseLeave={hide}
        onFocus={(e) => show(e.currentTarget, tip)}
        onBlur={hide}
      >
        {text}
      </button>
      <span id={id} className="sr-only">{tip}</span>
    </>
  );
}

/**
 * 요약 타일. 값이 주인공이고 라벨은 작다 — 대시보드는 문장이 아니라 수치를 읽는 곳이다.
 *
 * 정의·주의는 ⓘ 에 붙인다. hover 와 **키보드 포커스** 둘 다에서 열려야 해서 버튼이고,
 * `aria-describedby` 로 묶어 스크린리더에서는 열지 않아도 읽힌다.
 */
function Kpi({ label, value, sub, tip, tipId, show, hide }: {
  label: string; value: string; sub: string; tip: string; tipId: string;
  show: (el: HTMLElement | null, node: ReactNode) => void; hide: () => void;
}) {
  return (
    <article className="s08__kpi">
      <p className="s08__kpi-label">
        {label}
        <button
          type="button"
          className="s08__info"
          aria-describedby={tipId}
          onMouseEnter={(e) => show(e.currentTarget, tip)}
          onMouseLeave={hide}
          onFocus={(e) => show(e.currentTarget, tip)}
          onBlur={hide}
        >
          <span aria-hidden="true">i</span>
          <span className="sr-only">{label} 설명</span>
        </button>
        {/* 눈에 보이는 툴팁은 화면 좌표로 따로 뜬다(가려짐 방지). 이건 스크린리더용 —
            `aria-describedby` 가 가리키는 실체라 hover 없이도 읽힌다. */}
        <span id={tipId} className="sr-only">{tip}</span>
      </p>
      <p className="s08__kpi-value">{value}</p>
      <p className="s08__kpi-sub">{sub}</p>
    </article>
  );
}

/**
 * 값 한 줄의 세 상태를 **한 곳에서만** 정한다 — 값 · 가려짐 · 데이터 없음.
 *
 * ❗**`masked` 로는 못 가른다.** 계약이 표본 30건 미만이면 `masked` 이고 **0 도 그 안**이라
 * 서버가 둘에 같은 값을 준다. 그래서 모집단(`n`)을 먼저 보고, 그다음 비율이 `null` 인지를
 * 본다. 이 순서를 뒤집으면 *"해당하는 건이 없다"* 가 *"30건 미만이라 가렸다"* 로 나가고,
 * 그건 **없는 것을 숨겼다고 말하는 것**이라 마스킹이 일하는 자리를 못 보여준다(#347 리뷰).
 *
 * 세 군데(`ContrastBand` · `TrendCell` · 이 패널)가 같은 규칙을 쓰는데, 앞의 둘은 그리는
 * 모양이 서로 달라 각자 갖고 있다. 여기서 새로 만드는 두 칸은 모양이 같으므로 한 벌로 둔다.
 */
type StatState = "none" | "masked" | "value";
function statState(n: number, ratio: number | null): StatState {
  if (n === 0) return "none";
  return ratio == null ? "masked" : "value";
}

/**
 * 게이트 신호 분포 (이슈 #321 의 2번) — *"이 시스템이 무엇을 막았는가"*.
 *
 * ❗**머리 문장이 보류 건수를 든다.** 분포만 그리면 통과가 제일 큰 조각이라 화면이
 * *"대부분 통과했습니다"* 로 읽힌다. 이 제품의 산출물은 **막은 것**이고, 기획서 4절이
 * *"최선만 강조하는 관행의 정반대"* 로 못박은 자리가 정확히 여기다.
 *
 * ❗**막대는 장식이고 사실은 그 밑 문장에 있다**(`ContrastBand` 와 같은 규칙). 색만으로
 * 구분하지 않으려면 조각마다 라벨이 붙어야 하는데, 조각 안에 글자를 넣으면 작은 조각에서
 * 잘린다 — 그래서 막대는 `aria-hidden` 이고 라벨·건수는 아래 줄이 전부 적는다.
 */
function GateBar({ gate }: { gate: SignalCount[] }) {
  /* 자리는 `SIGNAL_ORDER` 가 정한다 — 서버 배열 순서에 기대지 않는다. 계약은 셋을 항상
     내지만, 빠진 값을 0 으로 지어내지는 않는다(없는 것과 0 건은 다른 사실이다). */
  const rows = SIGNAL_ORDER
    .map((s) => gate.find((g) => g.signal === s))
    .filter((g): g is SignalCount => g != null);
  const total = rows.reduce((a, g) => a + g.n, 0);
  const red = rows.find((g) => g.signal === "RED") ?? null;

  if (total === 0) {
    return (
      <div className="s08__gate">
        <p className="s08__gate-head s08__cband-value--none">데이터 없음</p>
        <p className="s08__cband-sub">이 필터에 해당하는 판정이 없습니다</p>
      </div>
    );
  }

  const redPct = red && red.share != null ? Math.round(red.share * 100) : null;
  const breakdown = rows
    .map((g) => `${SIGNAL_LABEL[g.signal]} ${g.n.toLocaleString()}건`)
    .join(" · ");

  return (
    <div className="s08__gate">
      <p className="s08__gate-head">
        판정 <b>{total.toLocaleString()}건</b> 가운데{" "}
        <b className="s08__gate-red">보류 {red ? red.n.toLocaleString() : "—"}건</b>
        {/* 비율은 있을 때만 붙인다. 없는데 0% 로 적으면 "한 번도 안 막았다" 가 된다. */}
        {redPct != null && <>{` (${redPct}%)`}</>}
      </p>
      <span className="s08__gbar s08__gbar--signal" aria-hidden="true">
        {rows.map((g) => (
          g.n === 0 ? null : (
            <span
              key={g.signal}
              className={`s08__gseg s08__gseg--${g.signal.toLowerCase()}`}
              style={{ flexGrow: g.n }}
            />
          )
        ))}
      </span>
      <p className="s08__cband-sub">왼쪽부터 {breakdown}</p>
    </div>
  );
}

/**
 * ★ 재설명 효과 (이슈 #321 의 3번) — **이 패널에서 제일 센 숫자다.**
 *
 * 나머지 수치는 전부 *"오해가 몇 %였다"* 이고 그건 문제 제기다. 이것만이 *"그래서 이해가
 * 올라갔다"* 를 말한다 — 재설명을 거친 항목 중 최종 이해(U1)에 도달한 비율.
 */
function ReexplainStat({ effect }: { effect: ReexplainEffect }) {
  const state = statState(effect.items, effect.rate);
  return (
    <article className="s08__dstat s08__dstat--lead">
      <p className="s08__dstat-label">재설명 뒤 이해 도달</p>
      {state === "none" ? (<>
        <p className="s08__dstat-value s08__cband-value--none">데이터 없음</p>
        <p className="s08__cband-sub">재설명을 거친 항목이 없습니다</p>
      </>) : state === "masked" ? (<>
        <p className="s08__dstat-value s08__cband-value--masked">가려짐</p>
        <p className="s08__cband-sub">표본 30건 미만이라 값을 가렸습니다 (재설명 {effect.items.toLocaleString()}건)</p>
      </>) : (<>
        <p className="s08__dstat-value">{Math.round((effect.rate ?? 0) * 100)}%</p>
        <p className="s08__cband-sub">
          재설명을 거친 {effect.items.toLocaleString()}건 가운데{" "}
          <b>{effect.resolved.toLocaleString()}건</b>이 최종 이해(U1)에 도달했습니다
        </p>
      </>)}
    </article>
  );
}

/**
 * 적색 오버라이드 (이슈 #321 의 4번) — 게이트를 사람이 뚫은 기록.
 *
 * ❗**요청과 승인을 한 수로 합치지 않는다.** 합치면 ADR-002(요청자 ≠ 승인자)가 실제로
 * 작동했는지가 화면에서 사라진다 — **요청만 하고 승인 안 된 건수가 그 자체로 신호**다.
 *
 * ⚠️ **이 칸에는 「가려짐」 상태가 없다.** 계약 `OverrideCount` 는 비율 필드가 없고 건수 셋이
 * 전부 non-nullable 이라 `masked` 가 **가릴 대상을 안 갖는다.** 서버가 보낸 값을 화면이
 * 임의로 지우면 계약에 없는 규칙을 화면이 만드는 것이라, 건수는 그대로 적고 표본이 작다는
 * 사실만 덧붙인다. 계약 쪽에 물어 둔 자리다(#362 리뷰).
 */
function OverrideStat({ count }: { count: OverrideCount }) {
  const pending = count.requested - count.approved;
  return (
    <article className="s08__dstat">
      <p className="s08__dstat-label">적색 오버라이드</p>
      {count.sessions === 0 ? (<>
        <p className="s08__dstat-value s08__cband-value--none">데이터 없음</p>
        <p className="s08__cband-sub">이 필터에 해당하는 판정이 없습니다</p>
      </>) : (<>
        <p className="s08__dstat-value">
          {count.requested.toLocaleString()}<span className="s08__dstat-unit">건 요청</span>
        </p>
        <p className="s08__cband-sub">
          승인 <b>{count.approved.toLocaleString()}건</b> · 미승인{" "}
          <b>{Math.max(pending, 0).toLocaleString()}건</b>
          {/* 승인자는 적지 않는다 — 이 화면은 개인을 말하지 않는다(설계 판단 4). */}
          {count.masked && " · 표본 30건 미만"}
        </p>
      </>)}
    </article>
  );
}

/**
 * 미측정을 안은 채 판정된 세션 — `gate_rules` R-00 이 무는 자리.
 *
 * ❗**낮은 것이 좋다는 뜻으로 그리지 않는다.** 이 값은 성적이 아니라 **R-00 이 실재한다는
 * 증거**다. 그래서 분모(`judged`)를 반드시 같이 적는다 — 비율만 있으면 *"몇 건 중"* 이
 * 사라져서 0% 와 "판정 자체가 없었다" 가 같아 보인다.
 */
function UnmeasuredStat({ count }: { count: UnmeasuredCount }) {
  const state = statState(count.judged, count.share);
  return (
    <article className="s08__dstat">
      <p className="s08__dstat-label">미측정 항목을 안은 판정</p>
      {state === "none" ? (<>
        <p className="s08__dstat-value s08__cband-value--none">데이터 없음</p>
        <p className="s08__cband-sub">이 필터에 해당하는 판정이 없습니다</p>
      </>) : state === "masked" ? (<>
        <p className="s08__dstat-value s08__cband-value--masked">가려짐</p>
        <p className="s08__cband-sub">표본 30건 미만이라 값을 가렸습니다 (판정 {count.judged.toLocaleString()}건)</p>
      </>) : (<>
        <p className="s08__dstat-value">{Math.round((count.share ?? 0) * 100)}%</p>
        <p className="s08__cband-sub">
          판정 {count.judged.toLocaleString()}건 가운데{" "}
          <b>{count.sessions.toLocaleString()}건</b>에 못 잰 항목이 있었습니다
        </p>
      </>)}
    </article>
  );
}

/**
 * 취약 대비의 한 줄 (이슈 #321 의 1번).
 *
 * 오해율 하나만 그리면 이 패널은 히트맵이 이미 하는 말을 반복한다. 그래서 **등급 분포를
 * 같이 그린다** — `misrate` 는 U4 비율이라 *"나머지가 이해했다"* 를 말하지 못하는데
 * (이슈 #177), 취약 대비는 정확히 *"이해했는가"* 를 묻는 자리다.
 *
 * ❗**가려진 줄도 자리를 지운다.** 표본이 30건 미만이면 값과 분포가 둘 다 없지만 줄은
 * 남긴다 — 없애면 화면이 "대비" 를 못 그리고, 가려졌다는 사실 자체가 사라진다(히트맵
 * 칸과 같은 규칙). 그래서 빈칸이 아니라 **"가려짐 · n=12"** 로 적극적으로 그린다.
 *
 * ❗**줄의 상태는 셋이다 — 값 · 가려짐 · 데이터 없음.** 설계 판단 ② 가 가르라고 한 둘을
 * 여기서 합쳐 뒀었다: 필터를 좁혀 표본이 0 이 되면 *"30건 미만이라 가렸습니다"* 가 떴다.
 * **`masked` 로는 못 가른다** — 계약이 `n < 30` 이면 `masked` 이고 0 도 그 안이라 서버가
 * 둘에 같은 값을 준다. `n` 을 봐야 하고, 계약이 *"`n` 은 masked 여도 내려간다"* 고 못
 * 박은 이유가 이 구분이다(추이 표 `TrendCell` 이 같은 방식으로 셋을 가른다).
 * 없는 것을 "숨겼다" 로 말하면 **마스킹이 일하는 자리를 못 보여주면서** 데이터를 감춘
 * 화면처럼 읽힌다 — 소표본 마스킹은 기획 7-4 의 실물 근거라 그 오독의 대가가 크다.
 */
function ContrastBand({ row }: { row: ContrastRow }) {
  const label = BAND_LABEL[row.band];
  const pct = row.misrate == null ? null : Math.round(row.misrate * 100);
  const grades = row.grades;
  const total = grades ? GRADE_ORDER.reduce((a, g) => a + grades[g], 0) : 0;
  /* 분포는 문장 하나로만 만든다 — 막대와 문면이 각자 숫자를 들면 한쪽만 고치는 날
     보는 것과 읽히는 것이 갈린다. 여기서는 문면이 사실이고 막대는 그 그림이다. */
  const breakdown = grades
    ? GRADE_ORDER.map((g) => `${GRADE_LABEL[g]} ${grades[g].toLocaleString()}건`).join(" · ")
    : null;

  return (
    <article className="s08__cband">
      <p className="s08__cband-head">
        <span className="s08__cband-label">{label}</span>
        <span className="s08__cband-n">표본 {row.n.toLocaleString()}건</span>
      </p>
      {row.n === 0 ? (
        <>
          <p className="s08__cband-value s08__cband-value--none">데이터 없음</p>
          <p className="s08__cband-sub">이 필터에 해당하는 판정이 없습니다</p>
        </>
      ) : pct == null ? (
        <>
          <p className="s08__cband-value s08__cband-value--masked">가려짐</p>
          <p className="s08__cband-sub">표본 30건 미만이라 값을 가렸습니다</p>
        </>
      ) : (<>
        <p className="s08__cband-value">{pct}%</p>
        {/* ❗오해율 막대를 따로 그리지 않는다. `misrate` 는 정의상 `u4 / n` 이라 아래
            등급 막대의 **마지막 조각이 곧 그 값**이다 — 둘 다 그리면 같은 수치를 두 번
            그리는 것이고, 보는 사람은 다른 두 가지로 읽는다. 두 줄의 막대 폭이 같아서
            오해(U4) 조각끼리 바로 견줘지고, 그게 이 패널이 시키려는 비교다. */}
        {grades && total > 0 && (<>
          {/* 등급 막대. 색이 아니라 **한 가지 잉크의 농도**로 낸다 — 이 화면에서 3색은
              판정 전용이고(tokens.css 규칙 1), 등급은 판정이 아니라 측정이다.
              U1(옅음) → U4(짙음) 순서라 막대의 방향 자체가 뜻을 갖는다.

              ❗**막대는 장식이고 사실은 아래 줄에 있다.** 그래서 `aria-hidden` 이고 포커스도
              안 받는다. 처음에는 툴팁 + `.sr-only` 로 숫자를 숨겨 뒀는데, 그러면 ⓐ 마우스를
              올려야만 읽히고 ⓑ 옅은 조각(U1)이 트랙과 1.34:1 이라 **막대만으로는 비율조차
              안 읽힌다.** 숫자를 그냥 아래에 적으면 셋 다 없어진다 — 툴팁도, 두 벌 문면도,
              스크롤 컨테이너 안 `.sr-only` 도(그게 #312 의 원인이었다). */}
          <span className="s08__gbar" aria-hidden="true">
            {GRADE_ORDER.map((g) => (
              grades[g] === 0 ? null : (
                <span
                  key={g}
                  className={`s08__gseg s08__gseg--${g}`}
                  style={{ flexGrow: grades[g] }}
                />
              )
            ))}
          </span>
          {/* 범례와 값이 한 줄이다. 범례 상자를 따로 두면 막대보다 범례가 커지고, 값을
              툴팁에만 두면 **검산(u1+u2+u3+u4 = 표본)이 화면에서 안 된다** — 건수로 받는
              이유가 그 검산인데(계약 `GradeDistribution`) 숨기면 받은 뜻이 없어진다. */}
          <p className="s08__cband-sub">왼쪽부터 {breakdown}</p>
        </>)}
      </>)}
    </article>
  );
}

/**
 * 두 줄의 차이 한 문장 (이슈 #321 의 1번).
 *
 * ❗**화면에 적힌 두 수의 차이로 낸다.** 원값(`misrate`)끼리 빼면 41% 와 41% 가 나란히
 * 있는데 *"1%p 높습니다"* 가 붙는 일이 생긴다 — 보는 사람이 검산을 못 하고, 그 순간
 * 이 패널의 두 수치가 의심받는다. 반올림한 값끼리 빼면 화면 안에서 산수가 맞는다.
 *
 * 한쪽이라도 가려지면 **차이를 말하지 않는다.** 없는 값으로 뺄셈을 하는 것보다,
 * 왜 못 내는지 적는 편이 낫다 — 가려졌다는 사실이 이 화면에서는 증거다.
 *
 * ❗**못 내는 이유는 둘이고 문면도 둘이다.** 표본이 아예 0 인 것과 30건에 못 미쳐 가린
 * 것은 다른 사실이라(`ContrastBand` 와 같은 규칙), 한 문장으로 뭉치면 두 줄이 "데이터
 * 없음" 인 화면 아래에 *"30건 미만이라"* 가 붙는다.
 */
function ContrastGap({ rows }: { rows: ContrastRow[] }) {
  const v = rows.find((r) => r.band === "vulnerable");
  const o = rows.find((r) => r.band === "other");
  if (!v || !o) return null;

  if (v.n === 0 || o.n === 0) {
    return (
      <p className="s08__cgap s08__cgap--none">
        이 필터에 해당하는 판정이 없습니다.
      </p>
    );
  }

  if (v.misrate == null || o.misrate == null) {
    return (
      <p className="s08__cgap s08__cgap--none">
        한쪽 표본이 30건 미만이라 대비를 낼 수 없습니다.
      </p>
    );
  }

  const gap = Math.round(v.misrate * 100) - Math.round(o.misrate * 100);
  if (gap === 0) {
    return <p className="s08__cgap">두 줄의 오해율이 같습니다.</p>;
  }
  return (
    <p className="s08__cgap">
      취약 고객의 오해율이 <b>{Math.abs(gap)}%p</b> {gap > 0 ? "높습니다" : "낮습니다"}.
    </p>
  );
}

/**
 * 추이 표의 한 칸 = 한 주 (F-DSH-002).
 *
 * ❗**값이 없으면 막대를 그리지 않는다.** 0% 와 *"그 주에 판정이 없었다"*, *"표본이 모자라
 * 가렸다"* 는 서로 다른 사실인데 높이 0 인 막대는 셋을 같아 보이게 한다 — 히트맵이
 * 빈칸과 "가려짐" 을 가르는 것과 같은 규칙이다(설계 판단 ②).
 *
 * 값이 있을 때는 최소 높이를 조금 준다. 1% 가 선 하나로도 안 보이면 **"값이 있다" 자체가
 * 화면에서 사라진다** — 정확한 수치는 hover·포커스와 스크린리더 문장이 준다.
 */
function TrendCell({ point, seriesKey, latest, show, hide }: {
  point: IndicatorPoint; seriesKey: string; latest: boolean;
  show: (el: HTMLElement | null, node: ReactNode) => void; hide: () => void;
}) {
  const pct = point.misrate == null ? null : Math.round(point.misrate * 100);
  const label = pct == null
    ? (point.n === 0
        ? "그 주에는 판정이 없습니다"
        : `표본 ${point.n}건 — 30건 미만이라 값을 가렸습니다`)
    : `오해율 ${pct}% · 표본 ${point.n}건`;
  const tip = (
    <>
      <b>{seriesKey}</b>{point.period}
      <span className="s08__tip-val">{label}</span>
    </>
  );

  return (
    <td
      className={`s08__tcell${pct == null ? " s08__tcell--none" : ""}`
        + (latest ? " s08__col-latest" : "")}
      tabIndex={0}
      onMouseEnter={(e) => show(e.currentTarget, tip)}
      onMouseLeave={hide}
      onFocus={(e) => show(e.currentTarget, tip)}
      onBlur={hide}
    >
      {/* 있든 없든 같은 트랙에 앉힌다 — 기준선(0%)이 한 줄로 이어져야 여덟 칸이
          서로 견줘진다. 값이 없는 주는 그 자리에 낮은 빗금 조각만 남는다. */}
      <span className="s08__tbar-track" aria-hidden="true">
        {pct == null
          ? <span className="s08__tslot" />
          : <span className="s08__tbar" style={{ height: `${Math.max(pct, 2)}%` }} />}
      </span>
      {/* 숫자는 최근 주에만 적는다 — 여덟 칸 모두에 적으면 표가 숫자 벽이 되고
          추이(모양)를 읽으라는 이 표의 목적이 사라진다. */}
      {latest && pct != null && <span className="s08__tval">{pct}%</span>}
      <span className="sr-only">{point.period} {label}</span>
    </td>
  );
}

/** `2026-W32` → `W32`. 열이 여덟 개라 연도를 여덟 번 반복하면 그게 표를 가린다. */
function shortWeek(period: string): string {
  const i = period.indexOf("-W");
  return i >= 0 ? period.slice(i + 1) : period;
}

function describe(e: unknown): string {
  if (e instanceof ApiRequestError) return `${e.message} (${e.code})`;
  return "집계를 불러오지 못했습니다.";
}
