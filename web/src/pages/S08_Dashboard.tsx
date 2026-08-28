/**
 * S-08 오해 지도 대시보드 — F-DSH-001 의 UI 몫. 소유: 오준서.
 * 집계 파이프라인은 정세현(`AggregateService`, 아직 TODO). 화면은 집계하지 않는다.
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
 */
import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { ApiRequestError, get } from "../api/client";
import type { HeatmapCell, HeatmapResponse, ProductSummary } from "../api/types";
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

const SCOPE_LABEL: Record<HeatmapResponse["scope"], string> = {
  branch: "자기 지점",
  org: "전사",
};

export default function S08Dashboard() {
  const [data, setData] = useState<HeatmapResponse | null>(null);
  const [product, setProduct] = useState("");
  const [ageBand, setAgeBand] = useState("");
  const [channel, setChannel] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  /** 403 은 오류가 아니라 시연 대상이다(설계 판단 ③). 에러와 따로 들고 있다. */
  const [blocked, setBlocked] = useState(false);
  /** 집계는 상품 id 만 준다. 이름은 이미 있는 `/products` 로 푼다 — 새 계약이 필요 없다.
      못 풀면 id 를 그대로 보여준다(합성·구 상품이면 목록에 없을 수 있다). */
  const [names, setNames] = useState<Record<string, string>>({});
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

  const load = useCallback(async () => {
    setLoading(true);
    const qs = new URLSearchParams();
    if (product) qs.set("product", product);
    if (ageBand) qs.set("ageBand", ageBand);
    if (channel) qs.set("channel", channel);
    const q = qs.toString();
    try {
      const res = await get<HeatmapResponse>(`/dashboard/heatmap${q ? `?${q}` : ""}`);
      setData(res);
      setBlocked(false);
      setError(null);
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 403) {
        setBlocked(true);
        setData(null);
      } else {
        setError(describe(e));
      }
    } finally {
      setLoading(false);
    }
  }, [product, ageBand, channel]);

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
    const ranked = [...byItem.entries()]
      .map(([item, v]) => ({ item, n: v.n, rate: v.mis / v.n }))
      .sort((a, b) => b.rate - a.rate);

    return {
      products: ps, items: its, byKey: map,
      stats: { weighted, nAll, nShown, maskedCells: cells.filter((c) => c.masked).length,
               cellCount: cells.length },
      ranked,
    };
  }, [data]);

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
            오해 지도는 준법감시(COMPL)와 관리자(MGR)만 볼 수 있습니다. 판매 조직은
            집계에 접근할 수 없습니다.
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
              text={data ? SCOPE_LABEL[data.scope] : "—"}
              tip={"이 수치가 어느 범위의 세션을 센 것인지입니다. 요청자 역할이 정합니다 — " +
                   "관리자(MGR)는 자기 지점, 준법감시(COMPL)는 전사입니다."}
            />
            {data?.synthetic && (
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
          sub={ranked[0]?.item ?? "값이 있는 칸 없음"}
          tipId="tip-top"
          tip="값이 있는 칸만 놓고 항목별로 합쳤을 때 오해율이 가장 높은 항목입니다."
        />
      </section>

      {/* ── 항목별 순위 ───────────────────────────────────────────────────
          히트맵은 "어느 상품의 어느 항목" 을 보는 표라 항목 단위 비교가 눈으로 안 된다.
          같은 데이터를 한 축(항목)으로 접어 막대로 세운다. */}
      {ranked.length > 0 && (
        <section className="s08__rank" aria-label="항목별 오해율">
          <h2 className="s08__panel-title">항목별 오해율</h2>
          <ol className="s08__rank-list">
            {ranked.map((r) => (
              <li key={r.item} className="s08__rank-row">
                <span className="s08__rank-name">{r.item}</span>
                <span className="s08__rank-track">
                  <span className="s08__rank-bar" style={{ width: `${Math.max(r.rate * 100, 1)}%` }} />
                </span>
                <span className="s08__rank-val">{Math.round(r.rate * 100)}%</span>
                <span className="s08__rank-n">{r.n.toLocaleString()}건</span>
              </li>
            ))}
          </ol>
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
                {items.map((it) => <th key={it} scope="col">{it}</th>)}
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
                              <b>{names[p] ?? p}</b>{it}
                              <span className="s08__tip-val">
                                표본 {c.n}건 — 30건 미만이라 값을 가렸습니다
                              </span>
                            </>
                          ))}
                          onMouseLeave={hideTip}
                          onFocus={(e) => showTip(e.currentTarget, (
                            <>
                              <b>{names[p] ?? p}</b>{it}
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
                            <b>{names[p] ?? p}</b>{it}
                            <span className="s08__tip-val">오해율 {pct}% · 표본 {c.n}건</span>
                          </>
                        ))}
                        onMouseLeave={hideTip}
                        onFocus={(e) => showTip(e.currentTarget, (
                          <>
                            <b>{names[p] ?? p}</b>{it}
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

function describe(e: unknown): string {
  if (e instanceof ApiRequestError) return `${e.message} (${e.code})`;
  return "집계를 불러오지 못했습니다.";
}
