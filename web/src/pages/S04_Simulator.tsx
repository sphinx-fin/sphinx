/**
 * S-04 손실 시뮬레이터 (고객 화면) — F-SIM-001 의 UI 몫. 소유: 오준서.
 * 계산 엔진은 정세현(`simulator/SimulatorService`). 화면은 계산하지 않는다.
 *
 * ── 명세가 화면에 건 제약 ────────────────────────────────────────────────────
 *
 * ① **3열 동일 시각 비중** (F-SIM-001 UI, 기획서 4절)
 *    "최선만 강조 금지". 카드 폭·구조·타이포가 셋 다 같다(`grid-template-columns: repeat(3,1fr)`).
 *    색은 손익의 방향만 알리고 크기로 차등을 두지 않는다.
 *
 * ② **확률이 아니라 금액** (기획서 4절)
 *    "사람은 확률을 잘 이해하지 못하지만 금액은 이해한다." 화면 어디에도 %를 주인공으로
 *    쓰지 않고 "고객님의 5,000만 원 → 3,200만 원" 으로 읽어준다.
 *
 * ③ **계산은 서버에서만** (P2)
 *    슬라이더가 움직여도 화면은 금액을 직접 계산하지 않는다. 클라이언트에서 한 번 더
 *    계산하면 결정론 계산이 두 벌이 되고, 어느 쪽이 리포트에 남은 값인지 알 수 없게 된다.
 *    대신 디바운스로 재계산 호출을 줄인다.
 *
 * ④ **E-SIM-01 — 조건 불완전이면 비활성**
 *    추출 실패 항목이 하나라도 있으면 상품 조건 파라미터가 불완전하므로 시뮬레이터를
 *    끄고 "조건 확인 필요"를 표시한다. 불완전한 조건으로 임의 계산하지 않는다.
 *
 * ⑤ **열람 완료가 재검증의 전제** (F-SIM-001 연동)
 *    "시뮬레이터 열람 완료 이벤트 → 해당 항목 재검증 트리거. 열람 없이 재검증 진입 불가."
 *    세 카드가 실제로 화면에 보였는지를 IntersectionObserver 로 확인해 CTA 를 연다.
 *    체크박스 한 번으로 통과시키지 않는 이유는, 그게 이 서비스가 비판하는 형식 절차이기 때문이다.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiRequestError, get, post } from "../api/client";
import type { RiskItem, SessionResponse, SimScenario, SimulateResponse } from "../api/types";
import { useElderlyMode } from "../hooks/useElderlyMode";
import { formatKrw, formatPnl } from "../lib/money";
import { cardId, orderScenarios } from "../lib/scenarios";
import "./S04_Simulator.css";

const MIN_AMOUNT = 1_000_000;
const MAX_AMOUNT = 200_000_000;
const STEP = 1_000_000;
const DEFAULT_AMOUNT = 50_000_000;   // 기획서 7-2 데모 기준값
/** 재계산 디바운스. 비기능 요구 "시뮬레이터 재계산 ≤200ms" 안에 들도록 짧게 잡는다. */
const DEBOUNCE_MS = 150;

export default function S04Simulator() {
  const { sid = "" } = useParams();
  const navigate = useNavigate();
  const { elderly, toggle } = useElderlyMode();

  const [amount, setAmount] = useState(DEFAULT_AMOUNT);
  const [scenarios, setScenarios] = useState<SimScenario[] | null>(null);
  const [pending, setPending] = useState(false);
  const [blocked, setBlocked] = useState<string | null>(null);   // E-SIM-01
  const [error, setError] = useState<string | null>(null);
  const [seen, setSeen] = useState<Set<string>>(new Set());

  const cardRefs = useRef(new Map<string, HTMLElement>());

  /* ── E-SIM-01: 조건 불완전 여부 확인 ─────────────────────────────────────── */
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const session = await get<SessionResponse>(`/sessions/${sid}`);
        const res = await get<{ items: RiskItem[] }>(`/products/${session.productId}/risk-items`);
        if (!alive) return;
        const failed = (res.items ?? []).filter((i) => i.status === "extraction_failed");
        if (failed.length > 0) {
          setBlocked(failed.map((i) => i.name).join(", "));
        }
      } catch (e) {
        if (alive) setError(describe(e));
      }
    })();
    return () => { alive = false; };
  }, [sid]);

  /* ── 재계산 (디바운스) ───────────────────────────────────────────────────── */
  const recalc = useCallback(
    async (won: number, signal: AbortSignal) => {
      setPending(true);
      try {
        const res = await post<SimulateResponse>(`/sessions/${sid}/simulate?amount=${won}`);
        if (signal.aborted) return;
        setScenarios(res.scenarios ?? []);
        setError(null);
      } catch (e) {
        if (!signal.aborted) setError(describe(e));
      } finally {
        if (!signal.aborted) setPending(false);
      }
    },
    [sid],
  );

  useEffect(() => {
    if (blocked) return;                       // 조건 불완전이면 계산 자체를 하지 않는다
    const ctrl = new AbortController();
    const t = setTimeout(() => void recalc(amount, ctrl.signal), DEBOUNCE_MS);
    return () => { clearTimeout(t); ctrl.abort(); };
  }, [amount, blocked, recalc]);

  /* ── 열람 완료 관측 ──────────────────────────────────────────────────────── */
  const ordered = useMemo(
    () => (scenarios ? orderScenarios(scenarios) : null),
    [scenarios],
  );

  useEffect(() => {
    if (!ordered || ordered.length === 0) return;
    const io = new IntersectionObserver(
      (entries) => {
        const shown = entries.filter((e) => e.isIntersecting).map((e) => e.target.id);
        if (shown.length > 0) setSeen((prev) => new Set([...prev, ...shown]));
      },
      { threshold: 0.6 },
    );
    for (const el of cardRefs.current.values()) io.observe(el);
    return () => io.disconnect();
  }, [ordered]);

  const allSeen = !!ordered && ordered.length > 0 && ordered.every((s) => seen.has(cardId(s)));

  /* ── 렌더 ────────────────────────────────────────────────────────────────── */
  if (blocked) {
    return (
      <main className="sm">
        <div className="sm__shell">
          <h1 className="sm__title">조건 확인이 필요합니다</h1>
          <p className="sm__alert sm__alert--warn" role="alert" style={{ marginTop: "var(--space-4)" }}>
            <b>상품 조건을 아직 다 읽어내지 못했습니다: {blocked}</b>
            조건이 불완전한 상태로 금액을 계산해 보여드리면 잘못된 숫자를 믿게 됩니다.
            담당자가 조건을 확인한 뒤에 이용해 주세요.
          </p>
        </div>
      </main>
    );
  }

  return (
    <main className="sm">
      <div className="sm__shell">
        <header className="sm__head">
          <h1 className="sm__title">가입하시려는 금액이 어떻게 될 수 있는지 보시겠어요?</h1>
          <p className="sm__sub">
            아래 세 가지는 기초자산이 과거에 실제로 움직였던 구간을 그대로 대입한 결과입니다.
            {" "}예상이 아니라 <b>과거에 있었던 일</b>입니다.
          </p>
        </header>

        <section className="sm__amount">
          <div className="sm__amount-top">
            <label className="sm__amount-label" htmlFor="amount">가입 예정 금액</label>
            <output className="sm__amount-value" htmlFor="amount">{formatKrw(amount)}</output>
          </div>
          <input
            id="amount"
            className="sm__slider"
            type="range"
            min={MIN_AMOUNT}
            max={MAX_AMOUNT}
            step={STEP}
            value={amount}
            onChange={(e) => setAmount(Number(e.target.value))}
            aria-valuetext={formatKrw(amount)}
          />
          <div className="sm__ticks">
            <span>{formatKrw(MIN_AMOUNT)}</span>
            <span>{formatKrw(MAX_AMOUNT)}</span>
          </div>
          <button type="button" className="sm__pending" onClick={toggle} style={{ marginTop: "var(--space-3)", background: "none", border: "none", padding: 0, cursor: "pointer", textDecoration: "underline" }}>
            {elderly ? "큰 글씨 끄기" : "큰 글씨로 보기"}
          </button>
        </section>

        {error && (
          <p className="sm__alert sm__alert--error" role="alert">
            <b>계산 결과를 불러오지 못했습니다</b>{error}
          </p>
        )}

        {!ordered ? (
          <p className="sm__state">계산 중…</p>
        ) : (
          <>
            <div className="sm__grid">
              {ordered.map((s) => {
                const loss = s.pnl < 0;
                const id = cardId(s);
                return (
                  <article
                    key={id}
                    id={id}
                    ref={(el) => { if (el) cardRefs.current.set(id, el); }}
                    className={`sm__card ${loss ? "sm__card--loss" : "sm__card--gain"}`}
                  >
                    <h2 className="sm__card-name">{s.name}</h2>
                    <p className="sm__card-path">
                      {/* TODO(정세현): 응답에 pathMeta(사용한 지수 구간)가 오면 여기에 표시한다 */}
                    </p>
                    <p className="sm__flow">
                      <span className="sm__flow-from">{formatKrw(amount)}</span>
                      <span className="sm__flow-arrow" aria-hidden="true">→</span>
                      <span className="sr-only">이(가) 다음 금액이 됩니다:</span>
                      <span className="sm__flow-to">{formatKrw(s.payout)}</span>
                    </p>
                    <p className={`sm__pnl ${loss ? "sm__pnl--loss" : "sm__pnl--gain"}`}>
                      {formatPnl(s.pnl)}
                    </p>
                  </article>
                );
              })}
            </div>

            <div className="sm__foot">
              {pending && <p className="sm__pending" role="status">금액에 맞춰 다시 계산하는 중…</p>}

              <p className="sm__alert sm__alert--info">
                세 가지 모두 실제로 있었던 지수 구간을 대입한 결과입니다. 어느 쪽이 될지는
                아무도 알 수 없고, <b>가장 나쁜 경우도 똑같이 일어날 수 있습니다.</b>
              </p>

              <button
                type="button"
                className="sm__btn"
                disabled={!allSeen}
                onClick={() => navigate(`/interview/${sid}`)}
              >
                {allSeen ? "확인했습니다, 계속하기" : "세 가지를 모두 확인해 주세요"}
              </button>
            </div>
          </>
        )}
      </div>
    </main>
  );
}

function describe(e: unknown): string {
  if (e instanceof ApiRequestError) {
    if (e.code === "NOT_FOUND") return "세션을 찾을 수 없습니다. 담당자에게 알려 주세요.";
    return "잠시 후 다시 시도해 주세요.";
  }
  return "알 수 없는 오류입니다. 담당자에게 알려 주세요.";
}
