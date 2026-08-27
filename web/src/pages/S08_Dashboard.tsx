/**
 * S-08 오해 지도 대시보드 — F-DSH-001 의 UI 몫. 소유: 오준서.
 * 집계 파이프라인은 정세현(`AggregateService`, 아직 TODO). 화면은 집계하지 않는다.
 *
 * ── 이 화면은 세 가지를 "보이게" 하려고 있다 ────────────────────────────────
 *
 * ① **합성 데이터임을 숨기지 않는다** (F-DSH-003 연출 금지)
 *    `synthetic: true` 면 워터마크를 **상시** 노출한다. 접거나 각주로 내리지 않는다 —
 *    심사에서 "이 수치 진짜인가"가 나왔을 때 화면이 먼저 답하고 있어야 한다.
 *    실데이터로 바뀌면 워터마크가 저절로 사라진다(값으로 판단하므로).
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
 * `scope`(branch·org)를 헤더에 박는다. 무엇을 보고 있는지가 안 보이면 MGR 이 자기 지점
 * 수치를 전사 수치로 착각한다 — 집계 축(`groupBy`)과 다른 개념이라 특히 헷갈린다.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import { ApiRequestError, get } from "../api/client";
import type { HeatmapCell, HeatmapResponse } from "../api/types";
import "./S08_Dashboard.css";

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

  /* ── 표 축 ──────────────────────────────────────────────────────────────
   * 셀 목록에서 축을 뽑는다. 서버가 축을 따로 주지 않고, 축을 화면에 하드코딩하면
   * 상품·항목이 늘 때 조용히 빠진다.                                              */
  const { products, items, byKey } = useMemo(() => {
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
    return { products: ps, items: its, byKey: map };
  }, [data]);

  if (loading) {
    return <main className="s08"><p className="s08__loading">집계를 불러오는 중입니다…</p></main>;
  }

  /* ── 차단됨 — 오류가 아니라 정상 결과다(설계 판단 ③) ────────────────────── */
  if (blocked) {
    return (
      <main className="s08">
        <h1>오해 지도</h1>
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
          <h1>오해 지도</h1>
          <p className="s08__scope">
            데이터 범위 <strong>{data ? SCOPE_LABEL[data.scope] : "—"}</strong>
            <span className="s08__scope-note"> · 요청자 역할이 정합니다</span>
          </p>
        </div>
        {/* 설계 판단 ① — 상시 노출. 접지 않는다. */}
        {data?.synthetic && (
          <p className="s08__watermark" role="note">
            합성 세션 기반 — 실제 고객 데이터가 아닙니다
          </p>
        )}
      </header>

      {error && <p className="s08__error" role="alert">{error}</p>}

      <section className="s08__filters">
        <label>
          <span>상품</span>
          <input value={product} onChange={(e) => setProduct(e.target.value)} placeholder="전체" />
        </label>
        <label>
          <span>연령대</span>
          <input value={ageBand} onChange={(e) => setAgeBand(e.target.value)} placeholder="전체" />
        </label>
        <label>
          <span>채널</span>
          <input value={channel} onChange={(e) => setChannel(e.target.value)} placeholder="전체" />
        </label>
      </section>

      {products.length === 0 ? (
        <p className="s08__empty">집계된 셀이 없습니다.</p>
      ) : (
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
                  <th scope="row">{p}</th>
                  {items.map((it) => {
                    const c = byKey.get(`${p}\u0000${it}`);
                    if (!c) {
                      // 셀 자체가 없다 = 데이터 없음. 가려진 것과 다르다(설계 판단 ②).
                      return <td key={it} className="s08__cell s08__cell--absent"><span className="sr-only">데이터 없음</span>—</td>;
                    }
                    if (c.masked) {
                      // 가려짐 — 빈칸으로 두지 않는다. 마스킹이 동작한 증거다.
                      return (
                        <td key={it} className="s08__cell s08__cell--masked">
                          <span className="s08__masked-label">가려짐</span>
                          <span className="s08__n">n={c.n}</span>
                        </td>
                      );
                    }
                    const pct = Math.round((c.misrate ?? 0) * 100);
                    return (
                      <td
                        key={it}
                        className="s08__cell"
                        // 명도만으로 강도를 낸다 — 판정 3색을 여기서 쓰면 집계가 판정처럼 보인다.
                        style={{ background: `color-mix(in srgb, var(--kohl-70) ${pct}%, var(--surface))` }}
                      >
                        <span className={pct >= 55 ? "s08__pct s08__pct--strong" : "s08__pct"}>{pct}%</span>
                        <span className="s08__n">n={c.n}</span>
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="s08__legend">
        진할수록 오해율이 높습니다. <strong>가려짐</strong>은 표본이 30건 미만이라 개인이
        역추정되지 않도록 값을 감춘 셀입니다 — 데이터가 없는 것과 다릅니다.
      </p>
    </main>
  );
}

function describe(e: unknown): string {
  if (e instanceof ApiRequestError) return `${e.message} (${e.code})`;
  return "집계를 불러오지 못했습니다.";
}
