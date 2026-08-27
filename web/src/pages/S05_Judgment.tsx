/**
 * S-05 판정 결과 (판매자 화면) — F-GTE-001 의 UI 몫. 소유: 오준서.
 * 판정은 서버 룰 엔진(강희진)이 한다. 화면은 계산하지도 결정하지도 않는다.
 *
 * ── 명세가 화면에 건 제약 ────────────────────────────────────────────────────
 *
 * ① **근거 문장 표시 필수** (역할표 §화면 S-05)
 *    항목마다 고객 발화 인용(`utteranceQuote`)과 매칭된 루브릭 조항(`rubricClause`)을
 *    함께 보인다. 등급만 보이면 판매자는 "왜 미이해인지" 를 모른 채 재설명해야 하고,
 *    그러면 재설명이 항목이 아니라 사람을 향한다. 인용이 있어야 무엇을 다시 말해야
 *    하는지가 정해진다.
 *
 * ② **항목 등급은 판정이 아니다** (P1 · 계약 `/judgments` 주석)
 *    계약이 항목별 `signal` 을 **싣지 않는다**고 못박았다 — 게이트 판정은 `/judge` 의
 *    세션 단위 `signal` 이 단독으로 소유한다. 그래서 이 화면은 항목 카드에 신호등 색을
 *    쓰지 않고 등급(U1~U4) 자체를 라벨로 보인다. `grade → 색` 은 표시 관례일 뿐이라
 *    카드 테두리 정도로만 쓰고, 신호등 3색은 **세션 배너 한 곳에서만** 쓴다.
 *    이 구분이 흐려지면 화면이 "항목마다 적색"을 말하게 되고, 그건 룰 엔진이 하지 않은
 *    판정을 화면이 지어내는 것이다.
 *
 * ③ **`/judge` 는 되돌릴 수 없다** (계약 409 설명)
 *    JUDGED 는 CLOSE 외에 나가는 전이가 없다. 그래서 버튼 하나로 확정하지 않고,
 *    확정 전에는 `/gate-preview` 로만 신호를 보여준다. 확정은 별도 확인 단계를 거친다 —
 *    답변 0건 세션이 버튼 오작동 한 번으로 끝나는 것이 계약이 409 로 막는 상황인데,
 *    화면에서도 같은 사고를 막아야 한다.
 *
 * ④ **`ruleTrace` 를 숨기지 않는다**
 *    발화한 룰 ID 는 감사 대상이다(계약 `GateResult` 주석). 판매자에게도 "무엇 때문에
 *    적색인가" 의 근거가 되고, 심사에서는 룰 엔진이 실재한다는 실물 증거가 된다.
 *
 * ⑤ **색만으로 구분하지 않는다** (tokens.css 규칙 3)
 *    신호등에는 항상 라벨을 병기한다. 이 화면은 판매를 막는 판정을 표시하므로
 *    색각 이상에서 적/녹이 구분되지 않는 상태를 허용할 수 없다.
 */
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiRequestError, get, post } from "../api/client";
import type { GateResult, Grade, Judgment, RiskItem, SessionResponse, Signal } from "../api/types";
import "./S05_Judgment.css";

/** 등급 라벨. 명세서 0.5 의 4단계 — 색이 아니라 말로 읽힌다. */
const GRADE_LABEL: Record<Grade, string> = {
  U1: "이해",
  U2: "부분 이해",
  U3: "미이해",
  U4: "오해",
};

/** 신호등 라벨. 색과 **반드시** 함께 나간다(tokens.css 규칙 3). */
const SIGNAL_LABEL: Record<Signal, string> = {
  GREEN: "통과",
  YELLOW: "보완 필요",
  RED: "보류",
};

const SIGNAL_DESC: Record<Signal, string> = {
  GREEN: "계약을 진행할 수 있습니다.",
  YELLOW: "재설명이 필요한 항목이 있습니다.",
  RED: "이 상태로는 계약을 진행할 수 없습니다.",
};

export default function S05Judgment() {
  const { sid = "" } = useParams();
  const navigate = useNavigate();

  const [session, setSession] = useState<SessionResponse | null>(null);
  const [judgments, setJudgments] = useState<Judgment[]>([]);
  const [items, setItems] = useState<RiskItem[]>([]);
  const [gate, setGate] = useState<GateResult | null>(null);
  /** 확정된 판정인가, 아직 미리보기인가. 화면 문구가 여기서 갈린다. */
  const [settled, setSettled] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  /* ── 적재 ────────────────────────────────────────────────────────────────
   * 세션·판정·항목명을 함께 받는다. 항목명이 필요한 이유는 판정이 `itemId` 만 들고
   * 오기 때문이다 — 화면에 `ELS-KNOCK-IN` 을 그대로 보이면 판매자가 못 읽는다.       */
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const s = await get<SessionResponse>(`/sessions/${sid}`);
      const [js, ri] = await Promise.all([
        get<{ judgments: Judgment[] }>(`/sessions/${sid}/judgments`),
        get<{ items: RiskItem[] }>(`/products/${s.productId}/risk-items`),
      ]);
      setSession(s);
      setJudgments(js.judgments ?? []);
      setItems(ri.items ?? []);

      // 이미 확정된 세션이면 미리보기를 부르지 않는다. JUDGED 이후의 gate-preview 는
      // 같은 값을 돌려주지만, 화면이 "미리보기" 문구를 띄우면 확정 사실이 흐려진다.
      const done = s.state === "JUDGED" || s.state === "CLOSED";
      setSettled(done);
      const g = await get<GateResult>(`/sessions/${sid}/gate-preview`);
      setGate(g);
      setError(null);
    } catch (e) {
      setError(describe(e));
    } finally {
      setLoading(false);
    }
  }, [sid]);

  useEffect(() => { void load(); }, [load]);

  /* ── 확정 ────────────────────────────────────────────────────────────────
   * 되돌릴 수 없으므로 확인 단계를 거친다(설계 판단 ③).                          */
  async function settle() {
    setBusy(true);
    try {
      const g = await post<GateResult>(`/sessions/${sid}/judge`, {});
      setGate(g);
      setSettled(true);
      setConfirming(false);
      setError(null);
    } catch (e) {
      setError(describe(e));
      setConfirming(false);
    } finally {
      setBusy(false);
    }
  }

  const nameOf = (itemId: string) =>
    items.find((i) => i.itemId === itemId)?.name ?? itemId;

  if (loading) {
    return <main className="s05"><p className="s05__loading">판정을 불러오는 중입니다…</p></main>;
  }

  return (
    <main className="s05">
      <header className="s05__head">
        <h1>판정 결과</h1>
        <p className="s05__sid">
          세션 <code>{sid}</code>
          {session?.contractRef && <> · 계약건 <code>{session.contractRef}</code></>}
        </p>
      </header>

      {error && <p className="s05__error" role="alert">{error}</p>}

      {/* ── 세션 신호등. 이 화면에서 신호등 3색을 쓰는 유일한 자리다(설계 판단 ②) ── */}
      {gate && (
        <section
          className={`s05__banner s05__banner--${gate.signal.toLowerCase()}`}
          aria-live="polite"
        >
          <div className="s05__banner-main">
            {/* 색과 라벨을 함께 낸다 — 색만으로 구분하지 않는다(설계 판단 ⑤) */}
            <span className="s05__signal">{SIGNAL_LABEL[gate.signal]}</span>
            <p className="s05__signal-desc">{SIGNAL_DESC[gate.signal]}</p>
          </div>
          <p className="s05__settled">
            {settled
              ? "확정된 판정입니다."
              : "아직 확정되지 않은 미리보기입니다. 아래에서 확정합니다."}
          </p>
        </section>
      )}

      {/* ── 룰 트레이스. 감사 대상이라 접어두지 않는다(설계 판단 ④) ────────────── */}
      {gate && gate.ruleTrace.length > 0 && (
        <section className="s05__trace">
          <h2>발화한 룰</h2>
          <ul>
            {gate.ruleTrace.map((r) => <li key={r}><code>{r}</code></li>)}
          </ul>
          <p className="s05__trace-note">
            이 신호를 만든 룰입니다. 판정 근거로 기록에 남습니다.
          </p>
        </section>
      )}

      {/* ── 항목별 판정. 신호등 색을 쓰지 않는다(설계 판단 ②) ─────────────────── */}
      <section className="s05__items">
        <h2>항목별 이해도 <span className="s05__count">{judgments.length}건</span></h2>

        {judgments.length === 0 ? (
          <p className="s05__empty">
            아직 판정된 항목이 없습니다. 고객 응답이 들어오면 여기에 쌓입니다.
          </p>
        ) : (
          <ul className="s05__list">
            {judgments.map((j) => (
              <li key={j.itemId} className={`s05__item s05__item--${j.grade.toLowerCase()}`}>
                <div className="s05__item-head">
                  <h3>{nameOf(j.itemId)}</h3>
                  <span className="s05__grade">{GRADE_LABEL[j.grade]}</span>
                </div>

                <p className="s05__reason">{j.reason}</p>

                {/* 근거 문장 — 이 화면의 존재 이유다(설계 판단 ①) */}
                <div className="s05__evidence">
                  <div className="s05__quote">
                    <span className="s05__evidence-label">고객 발화</span>
                    <blockquote>{j.evidence.utteranceQuote}</blockquote>
                  </div>
                  <div className="s05__rubric">
                    <span className="s05__evidence-label">채점 기준</span>
                    <p>{j.evidence.rubricClause}</p>
                  </div>
                </div>

                <div className="s05__item-foot">
                  {j.misconceptionType && (
                    <span className="s05__misconception">
                      오해 유형 <code>{j.misconceptionType}</code>
                    </span>
                  )}
                  {/* 신뢰도를 숨기지 않는다 — 낮으면 게이트가 R-05 로 황색 강등하므로
                      판매자가 "왜 등급보다 신호가 낮은가"를 여기서 읽을 수 있어야 한다. */}
                  <span className="s05__confidence">
                    신뢰도 {(j.confidence * 100).toFixed(0)}%
                  </span>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* ── 다음 행동 ─────────────────────────────────────────────────────── */}
      <footer className="s05__actions">
        {!settled && (
          confirming ? (
            <div className="s05__confirm" role="alertdialog" aria-label="판정 확정 확인">
              <p>
                <strong>확정하면 되돌릴 수 없습니다.</strong> 확정 이후에는 재설명·재검증으로
                돌아갈 수 없고 종료만 가능합니다.
              </p>
              <div className="s05__confirm-buttons">
                <button type="button" className="s05__btn" onClick={() => setConfirming(false)} disabled={busy}>
                  취소
                </button>
                <button type="button" className="s05__btn s05__btn--primary" onClick={settle} disabled={busy}>
                  {busy ? "확정 중…" : "확정합니다"}
                </button>
              </div>
            </div>
          ) : (
            <button
              type="button"
              className="s05__btn s05__btn--primary"
              onClick={() => setConfirming(true)}
              disabled={judgments.length === 0}
            >
              판정 확정
            </button>
          )
        )}

        {/* 적색이면 오버라이드 요청 경로를 연다. 승인은 MGR 이 S-06 에서 한다. */}
        {gate?.signal === "RED" && (
          <button type="button" className="s05__btn" onClick={() => navigate(`/override/${sid}`)}>
            적색 진행 요청(오버라이드)
          </button>
        )}

        {settled && (
          <button type="button" className="s05__btn" onClick={() => navigate(`/report/${sid}`)}>
            이해 기록 리포트
          </button>
        )}

        <button type="button" className="s05__btn s05__btn--quiet" onClick={() => void load()}>
          새로고침
        </button>
      </footer>
    </main>
  );
}

/** 봉투에서 풀린 에러를 사람이 읽는 문장으로. 코드까지 보이면 판매자가 옮겨 적을 수 있다. */
function describe(e: unknown): string {
  if (e instanceof ApiRequestError) return `${e.message} (${e.code})`;
  return "판정을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
}
