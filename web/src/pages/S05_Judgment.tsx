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
 *
 * ⑥ **미리보기 신호는 확정 신호가 아니다** (계약 `GatePreview` · #132 리뷰)
 *    `/gate-preview` 는 모순을 평가하지 않으므로 `suitabilityStatus` 가 보통
 *    `NOT_EVALUATED` 고, 그 GREEN 은 `/judge` 에서 YELLOW·RED 로 갈릴 수 있다. 그대로
 *    다른 GREEN 처럼 그리면 판매자가 재설명 루프를 건너뛰고 확정으로 갔다가 거기서
 *    막힌다 — 나쁜 방향이다. 신호는 그대로 두고 "적합성 미확인" 을 병기한다.
 *    확정 여부도 세션 상태가 아니라 응답의 `recorded` 로 판단한다. 지금은 두 출처가
 *    우연히 일치하지만, 근거는 응답 안에 있다.
 *
 * ⑦ **재설명은 여기서 시작한다** (명세 8절 S-05 "재설명 실행 버튼" · F-INT-004)
 *    `POST /re-explain` 은 `session:interview` 라 **판매자만** 부를 수 있다. 문면을 읽는
 *    것은 고객(S-03)이지만 시작 버튼이 판매자 화면에 있는 이유가 그것이고, 명세가 이
 *    화면의 요소로 적어 둔 이유도 같다 — 무엇을 다시 설명할지는 근거를 보고 고르는
 *    판단이다(설계 판단 ①).
 *
 *    ❗**시작하면 확정이 잠긴다.** 상태머신에서 `RE_EXPLAIN` 이 내보내는 이벤트는
 *    `REVERIFY`(고객이 다시 답함)와 `ABORT` 뿐이라 **판정으로 갈 수 없다.** 그래서 화면은
 *    누르기 전에 그 사실을 적고, 진행 중에는 확정 버튼을 잠근다 — 설계 판단 ③과 같은
 *    논리다. 눌러서 409 를 받는 것보다 눌리지 않는 게 낫다.
 */
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiRequestError, get, post } from "../api/client";
import type {
  GatePreview, GateResult, Grade, Judgment, ReExplainRequest, ReExplanation, RiskItem, RuleRef,
  SessionResponse, Signal, SuitabilityStatus,
} from "../api/types";
import { stashReExplanation } from "../lib/reexplain";
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

/**
 * 화면이 들고 있는 게이트 값.
 *
 * **미리보기와 확정은 서로 다른 계약이다** — `/gate-preview` 는 `GatePreview`,
 * `/judge` 는 `GateResult` 를 준다. 한 타입으로 뭉치면 확정 이후에도 미리보기 전용
 * 필드를 읽는 코드가 생기는데 그건 낡은 값이다. 공통분(신호·룰)만 남기고, 미리보기
 * 전용 정보는 확정 뒤에 `null` 이 되게 한다.
 */
interface GateView {
  signal: Signal;
  ruleTrace: RuleRef[];
  /** 감사 기준점으로 기록됐는가. **응답이 알려준다**(설계 판단 ⑥). */
  settled: boolean;
  /** 미리보기일 때만 값이 있다. `/judge` 응답에는 이 필드가 없으므로 확정 뒤에는 null. */
  suitability: SuitabilityStatus | null;
}

const previewView = (g: GatePreview): GateView => ({
  signal: g.signal,
  ruleTrace: g.ruleTrace,
  settled: g.recorded,
  suitability: g.suitabilityStatus,
});

const settledView = (g: GateResult): GateView => ({
  signal: g.signal,
  ruleTrace: g.ruleTrace,
  settled: true,
  suitability: null,
});

/**
 * 재설명 요청이 거절된 자리. **항목 단위로 들고 있는다** — 화면 상단 에러로 올리면 어느
 * 항목이 거절됐는지가 사라진다.
 *
 * `exhausted` 를 따로 두는 이유: 상한 도달은 재시도해도 같으므로 버튼을 다시 열어 주면
 * 안 된다. 반면 `not_eligible` 은 다른 항목에는 해당 없고, `failed` 는 재시도가 의미 있다.
 */
interface ReExplainNote {
  itemId: string;
  kind: "exhausted" | "not_eligible" | "failed";
  text: string;
}

export default function S05Judgment() {
  const { sid = "" } = useParams();
  const navigate = useNavigate();

  const [session, setSession] = useState<SessionResponse | null>(null);
  const [judgments, setJudgments] = useState<Judgment[]>([]);
  const [items, setItems] = useState<RiskItem[]>([]);
  const [gate, setGate] = useState<GateView | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  /** 재설명을 요청 중인 항목. 버튼 하나만 도는 것이 보여야 한다. */
  const [reExplaining, setReExplaining] = useState<string | null>(null);
  const [reNotes, setReNotes] = useState<ReExplainNote[]>([]);

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

      // 확정된 세션에도 그대로 부른다 — 계약이 "이미 판정된 세션은 재계산하지 않고
      // 기록값을 돌려준다(recorded=true)" 고 명시한다. 화면이 미리보기인지 확정인지는
      // 세션 상태가 아니라 그 `recorded` 가 정한다(설계 판단 ⑥).
      const g = await get<GatePreview>(`/sessions/${sid}/gate-preview`);
      setGate(previewView(g));
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
      setGate(settledView(g));
      setConfirming(false);
      setError(null);
    } catch (e) {
      setError(describe(e));
      setConfirming(false);
    } finally {
      setBusy(false);
    }
  }

  /* ── 재설명 시작 (F-INT-004 · 설계 판단 ⑦) ───────────────────────────────
   * 문면은 이 응답에만 있고 계약에 다시 읽는 GET 이 없다. 그래서 받은 즉시 인계 자리에
   * 넣고 고객 화면으로 넘어간다 — 이동 자체가 "고객에게 태블릿을 넘기라" 는 신호다.
   * (`lib/reexplain` 의 주석이 저장소를 거치는 이유를 적어 뒀다.)                     */
  async function startReExplain(itemId: string) {
    setReExplaining(itemId);
    setReNotes((prev) => prev.filter((n) => n.itemId !== itemId));
    try {
      const body: ReExplainRequest = { itemId };
      const re = await post<ReExplanation>(`/sessions/${sid}/re-explain`, body);
      stashReExplanation(sid, re);
      navigate(`/interview/${sid}`);
    } catch (e) {
      setReNotes((prev) => [...prev, noteFor(itemId, e)]);
      // 상태가 바뀌었을 수 있다(다른 창에서 진행 등). 다시 읽어 화면을 맞춘다.
      void load();
    } finally {
      setReExplaining(null);
    }
  }

  const nameOf = (itemId: string) =>
    items.find((i) => i.itemId === itemId)?.name ?? itemId;

  const noteOf = (itemId: string) => reNotes.find((n) => n.itemId === itemId) ?? null;

  /* ── 재설명을 시작할 수 있는 상태인가 ─────────────────────────────────────
   * 상태머신이 `REQUEST_REEXPLAIN` 을 받는 상태는 `IN_PROGRESS` 와 `RE_VERIFY` 둘뿐이다.
   * `RE_EXPLAIN` 은 이미 진행 중이라 받지 않고(그래서 버튼이 아니라 배너를 그린다),
   * `JUDGED` 이후는 되돌릴 수 없다. **세션 상태를 못 읽었으면 막지 않는다** — 값이 없는
   * 것과 "안 된다" 는 다르고, 서버가 최종 판단자다(409 가 본선). */
  const reExplainOpen =
    !gate?.settled
    && (session == null || session.state === "IN_PROGRESS" || session.state === "RE_VERIFY");
  const reExplainInFlight = session?.state === "RE_EXPLAIN";

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
            {/* 신호 자체는 바꾸지 않는다. "이 신호는 모순 평가 전 값" 이라는 사실만
                덧붙인다(설계 판단 ⑥). 확정된 판정에는 붙지 않는다 — suitability 가 null 이다. */}
            {gate.suitability === "NOT_EVALUATED" && (
              <span className="s05__unevaluated">적합성 미확인</span>
            )}
            <p className="s05__signal-desc">{SIGNAL_DESC[gate.signal]}</p>
            {gate.suitability === "NOT_EVALUATED" && (
              <p className="s05__unevaluated-desc">
                적합성 모순은 아직 평가되지 않았습니다. 확정 시 신호가 바뀔 수 있습니다.
              </p>
            )}
          </div>
          <p className="s05__settled">
            {gate.settled
              ? "확정된 판정입니다."
              : "아직 확정되지 않은 미리보기입니다. 아래에서 확정합니다."}
          </p>
        </section>
      )}

      {/* ── 재설명 진행 중 ────────────────────────────────────────────────────
          `RE_EXPLAIN` 에서 나가는 길은 고객의 재답변 하나뿐이다(설계 판단 ⑦). 그러니
          여기서 할 일은 "기다린다" 가 아니라 **태블릿을 고객에게 넘기는 것**이고,
          화면은 그 다음 동작을 준다. 신호등 3색을 쓰지 않는다 — 이건 판정이 아니다. */}
      {reExplainInFlight && (
        <section className="s05__reexplain" role="status">
          <div>
            <b>재설명이 진행 중입니다.</b>
            <p>
              고객 화면에서 재설명을 읽고 다시 답하면 재검증으로 기록됩니다. 그전까지는
              판정을 확정할 수 없습니다.
            </p>
          </div>
          <button
            type="button"
            className="s05__btn s05__btn--primary"
            onClick={() => navigate(`/interview/${sid}`)}
          >
            고객 화면으로
          </button>
        </section>
      )}

      {/* ── 룰 트레이스. 감사 대상이라 접어두지 않는다(설계 판단 ④) ────────────── */}
      {gate && gate.ruleTrace.length > 0 && (
        <section className="s05__trace">
          <h2>발화한 룰</h2>
          <ul>
            {/* ID 와 문면을 같이 그린다 — ID 만 그리면 "R-00" 이 근거가 되고(이슈 #320),
                문면만 그리면 감사·심사가 근거로 삼는 룰 ID 가 화면에서 사라진다.
                ❗문면에 임계값을 덧붙이지 않는다 — 서버가 조건을 안 말하기로 한 규약이
                화면에서 깨진다(7-4 역이용 방지). */}
            {gate.ruleTrace.map((r) => (
              <li key={r.id}>
                <code>{r.id}</code> {r.label}
              </li>
            ))}
          </ul>
          <p className="s05__trace-note">
            이 신호를 만든 룰입니다. 판정 근거로 기록에 남습니다.
          </p>
        </section>
      )}

      {/* ── 항목별 판정. 신호등 색을 쓰지 않는다(설계 판단 ②) ─────────────────── */}
      <section className="s05__items">
        <h2>항목별 이해도 <span className="s05__count">{judgments.length}건</span></h2>

        {/* 누르기 전에 결과를 적는다 — 재설명은 상태를 바꾸고, 그 상태에서는 확정이
            막힌다(설계 판단 ⑦). 누른 뒤에 알게 되면 그건 화면이 숨긴 것이다. */}
        {reExplainOpen && judgments.some((j) => j.grade !== "U1") && (
          <p className="s05__items-note">
            재설명을 시작하면 고객이 다시 답할 때까지 판정을 확정할 수 없습니다.
            항목당 재검증은 상한이 있고, 상한에 닿으면 그 항목은 판정으로 넘어갑니다.
          </p>
        )}

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
                  {/* 오해 유형(misconceptionType)은 여기 안 그린다 — 이슈 #144.
                      그 값이 불공정영업 신호 그 자체라 판매자가 무엇이 탐지되는지 알면
                      문면만 바꿔 같은 영업을 한다(기획 7-4 역이용 방지). 서버가 #147 로
                      JudgmentView 에서 뺐으므로 응답에도 안 온다 — 여기서 지우는 것은
                      값이 없어서가 아니라 **다시 실리면 다시 그려지는 자리**를 없애는
                      것이다. 신호는 COMPL 로만 간다(F-GTE-003). */}
                  {/* 신뢰도를 숨기지 않는다 — 낮으면 게이트가 R-05 로 황색 강등하므로
                      판매자가 "왜 등급보다 신호가 낮은가"를 여기서 읽을 수 있어야 한다. */}
                  <span className="s05__confidence">
                    신뢰도 {(j.confidence * 100).toFixed(0)}%
                  </span>

                  {/* 재설명 버튼 — 이해(U1)에는 달지 않는다. 서버도 U1 이면
                      REEXPLAIN_NOT_ELIGIBLE 로 거절하므로, 누를 수 있게 두면 화면이
                      서버가 안 하는 일을 제안하는 셈이다. 상한 도달 항목도 같은 이유로
                      다시 열지 않는다(`exhausted`). */}
                  {j.grade !== "U1" && reExplainOpen
                    && noteOf(j.itemId)?.kind !== "exhausted" && (
                    <button
                      type="button"
                      className="s05__btn s05__btn--sm"
                      onClick={() => void startReExplain(j.itemId)}
                      disabled={reExplaining !== null}
                    >
                      {reExplaining === j.itemId ? "재설명 준비 중…" : "재설명"}
                    </button>
                  )}
                </div>

                {noteOf(j.itemId) && (
                  <p className="s05__renote" role="status">{noteOf(j.itemId)?.text}</p>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* ── 다음 행동 ─────────────────────────────────────────────────────── */}
      <footer className="s05__actions">
        {gate && !gate.settled && (
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
              // 재설명 중에는 상태머신이 판정을 안 받는다(설계 판단 ⑦). 눌러서 409 를
              // 받는 것보다 눌리지 않는 게 낫다 — 설계 판단 ③과 같은 규칙이다.
              disabled={judgments.length === 0 || reExplainInFlight}
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

        {gate?.settled && (
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

/**
 * 재설명 거절을 항목 옆 문장으로.
 *
 * ❗**400 두 개를 코드로 가른다** — 계약이 둘을 같은 상태코드에 두고 `error.code` 로 나눈
 * 이유가 화면 처리가 다르기 때문이다(결정 1.4). 문면으로 가르면 서버 문구가 바뀌는 날
 * 조용히 깨진다.
 *
 * `REVERIFY_EXHAUSTED` 는 **다음 행동이 있는 상태**다 — 그 항목은 여기서 끝이고 판정으로
 * 간다. 판매자가 그걸 알아야 같은 항목을 계속 두드리지 않는다. `REEXPLAIN_NOT_ELIGIBLE`
 * 은 대개 이미 이해(U1)라 화면이 버튼을 안 그리는 자리인데, 다른 창에서 등급이 바뀐 뒤에
 * 누르면 올 수 있다.
 */
function noteFor(itemId: string, e: unknown): ReExplainNote {
  if (e instanceof ApiRequestError) {
    if (e.code === "REVERIFY_EXHAUSTED") {
      return {
        itemId, kind: "exhausted",
        text: "재검증 상한에 도달했습니다. 이 항목은 더 재설명하지 않고 판정으로 넘어갑니다.",
      };
    }
    if (e.code === "REEXPLAIN_NOT_ELIGIBLE") {
      return {
        itemId, kind: "not_eligible",
        text: "재설명 대상이 아닙니다. 판정이 없거나 이미 이해한 항목입니다.",
      };
    }
    if (e.code === "ILLEGAL_STATE_TRANSITION") {
      return {
        itemId, kind: "failed",
        text: "지금 상태에서는 재설명을 시작할 수 없습니다. 화면을 새로고침해 주세요.",
      };
    }
    return { itemId, kind: "failed", text: `재설명을 시작하지 못했습니다 — ${e.message}` };
  }
  return { itemId, kind: "failed", text: "재설명을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요." };
}
