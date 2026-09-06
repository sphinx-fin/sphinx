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
 *
 * ⑧ **판매자 창은 고객이 답하는 것을 볼 수 없다 — 그래서 스스로 되읽는다**
 *    S-02 가 고객 화면과 이 화면을 **각각 새 창**으로 연다(그쪽 설계 판단 ④). 그래서
 *    재설명을 시작한 뒤 고객이 다른 창에서 다시 답해도 이 화면에서는 아무 일도 일어나지
 *    않았다 — 판매자가 「새로고침」을 눌러야 재검증이 보였고, 안 누르면 **낡은 판정을 보며
 *    확정을 판단**한다. 그건 이 화면이 존재하는 이유와 정면으로 어긋난다.
 *
 *    되읽기는 **기다리는 상태에서만** 돈다(`RE_EXPLAIN`). 그 상태에서 나가는 길이 고객의
 *    재답변 하나뿐이라(설계 판단 ⑦), 상태가 거기서 벗어나는 순간이 곧 *"재검증이
 *    기록됐다"* 다. 그때 한 번만 전체를 다시 읽는다 — `/gate-preview` 는 부를 때마다
 *    계산이라 주기적으로 두드릴 자리가 아니다.
 *
 * ⑨ **등급은 색이 아니라 무게로 갈린다** (피드백 9번)
 *    네 등급 배지가 전부 같은 회색이라 「이해」와 「오해」가 한눈에 안 갈렸다. 그렇다고
 *    신호등 3색을 쓸 수는 없다(설계 판단 ② · tokens.css 규칙 1). 그래서 **먹 한 색의 채움
 *    단계**로 무게를 준다 — 오해가 가장 진하고 이해가 가장 조용하다. 등급기호(U4)를
 *    라벨과 같이 내서 명도를 못 읽는 조건에서도 갈리게 한다.
 */
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiRequestError, get, post } from "../api/client";
import type {
  GatePreview, GateResult, Grade, Judgment, ReExplainRequest, ReExplanation, RiskItem, RuleRef,
  SessionResponse, Signal, SuitabilityStatus,
} from "../api/types";
import { reverifyPath, stashReExplanation } from "../lib/reexplain";
import ErrorNote from "../components/ErrorNote";
import { describeError, type ShownError } from "../lib/errorText";
import "./S05_Judgment.css";

/**
 * 재검증을 기다리는 동안의 되읽기 간격(설계 판단 ⑧). 사람이 답을 쓰는 시간 척도라 초
 * 단위면 충분하다 — 더 짧게 잡아도 빨라지는 것은 없고 세션 조회만 늘어난다.
 */
const WATCH_MS = 4000;

/** 등급 라벨. 명세서 0.5 의 4단계 — 색이 아니라 말로 읽힌다. */
const GRADE_LABEL: Record<Grade, string> = {
  U1: "이해",
  U2: "부분 이해",
  U3: "미이해",
  U4: "오해",
};

/**
 * 요약 줄에서의 순서 — **무거운 것부터**. 판매자가 이 화면에서 하는 일이 «무엇을 다시
 * 설명할지 고르는 것» 이라 오해·미이해가 먼저 읽혀야 한다.
 *
 * ❗카드 목록의 순서는 **안 건드린다.** 그쪽은 서버가 itemId 순으로 고정해 둔 것이고
 * (`JudgmentsResponse`), 화면이 다시 정렬하면 되읽기마다 카드가 자리를 바꿀 수 있다.
 */
const GRADE_ORDER: readonly Grade[] = ["U4", "U3", "U2", "U1"];

/** 신호등 라벨. 색과 **반드시** 함께 나간다(tokens.css 규칙 3). */
const SIGNAL_LABEL: Record<Signal, string> = {
  GREEN: "통과",
  YELLOW: "보완 필요",
  RED: "보류",
};

const SIGNAL_DESC: Record<Signal, string> = {
  GREEN: "계약을 진행할 수 있어요.",
  YELLOW: "재설명이 필요한 항목이 있어요.",
  RED: "이 상태로는 계약을 진행할 수 없어요.",
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
  const [error, setError] = useState<ShownError | null>(null);
  /** 재설명을 요청 중인 항목. 버튼 하나만 도는 것이 보여야 한다. */
  const [reExplaining, setReExplaining] = useState<string | null>(null);
  const [reNotes, setReNotes] = useState<ReExplainNote[]>([]);

  /* ── 적재 ────────────────────────────────────────────────────────────────
   * 세션·판정·항목명을 함께 받는다. 항목명이 필요한 이유는 판정이 `itemId` 만 들고
   * 오기 때문이다 — 화면에 `ELS-KNOCK-IN` 을 그대로 보이면 판매자가 못 읽는다.       */
  const load = useCallback(async (silent = false) => {
    // ❗**조용한 되읽기가 따로 필요하다.** 아래 렌더는 `loading` 이면 화면을 통째로
    // «판정을 불러오고 있어요» 한 줄로 바꾼다. 자동 갱신(설계 판단 ⑧)이 그 길로 가면
    // 판매자가 보고 있던 판정이 몇 초마다 사라졌다 돌아온다 — 손으로 누른 새로고침과
    // 스스로 도는 되읽기는 **같은 데이터를 받아도 덮개를 씌우는지가 다르다.**
    if (!silent) setLoading(true);
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
      // ❗**조용한 되읽기는 에러도 조용해야 한다.** 여기를 안 가르면 폴링·복귀 되읽기가
      // 실패할 때 판매자가 **아무것도 안 누른 자리에서** 에러 배너를 본다 — 그건 고칠 것을
      // 알려주는 게 아니라 화면의 잡음이고, 사람이 지금 하던 판단(무엇을 다시 설명할지)을
      // 끊는다. 실패해도 화면은 직전 값을 그대로 들고 있고, 다음 주기가 회복하며, 끝내
      // 안 되면 「새로고침」이 그대로 있다 — 그때는 사람이 누른 것이라 에러가 나가야 한다.
      if (!silent) setError(describeError(e));
    } finally {
      if (!silent) setLoading(false);
    }
  }, [sid]);

  useEffect(() => { void load(); }, [load]);

  /* ── 재검증을 기다리는 동안 스스로 되읽는다 (설계 판단 ⑧) ─────────────────
   * 보는 것은 **세션 상태 하나뿐**이다. 판정 목록·게이트까지 주기적으로 부르면 화면이
   * 하는 일이 «표시» 에서 «상시 재계산» 으로 바뀐다.                                */
  const awaitingReverify = session?.state === "RE_EXPLAIN";
  useEffect(() => {
    if (!awaitingReverify) return;
    let alive = true;
    const timer = window.setInterval(() => {
      void (async () => {
        try {
          const s = await get<SessionResponse>(`/sessions/${sid}`);
          // 아직 고객이 답하지 않았다. 아무것도 안 하는 것이 맞다.
          if (!alive || s.state === "RE_EXPLAIN") return;
          await load(true);
        } catch {
          /* 폴링 실패는 화면에 올리지 않는다 — 판매자가 아무것도 안 한 자리에서 뜨는
             에러는 잡음이고, 다음 주기가 다시 시도한다. 끝내 안 되면 「새로고침」이
             그대로 있다. */
        }
      })();
    }, WATCH_MS);
    return () => { alive = false; window.clearInterval(timer); };
  }, [awaitingReverify, sid, load]);

  /* ── 창이 다시 앞에 오면 한 번 되읽는다 ──────────────────────────────────
   * 팝업이 막혀 **한 탭을 번갈아 쓰는** 경로에서는 위 폴링이 돌 새가 없다 — 이 화면이
   * 아예 뒤에 있다. 확정 전에만, 조용히 한 번. 확정된 판정은 더 변하지 않는다.       */
  const settled = gate?.settled ?? false;
  useEffect(() => {
    if (settled) return;
    const onVisible = () => {
      if (document.visibilityState === "visible") void load(true);
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [settled, load]);

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
      setError(describeError(e));
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
      // 경로에 «재검증» 표시를 실어 보낸다(이슈 #492). 인계는 `sessionStorage` 라 탭을
      // 못 따라가는데 표시는 URL 이라 따라간다 — 표시만 오고 인계가 없으면 S-03 이
      // 조용히 다음 항목을 묻는 대신 «못 받았다» 를 말한다.
      navigate(reverifyPath(sid));
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
    return <main className="s05"><p className="s05__loading">판정을 불러오고 있어요…</p></main>;
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

      {error && <ErrorNote error={error} className="s05__error" />}

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
                적합성 모순은 아직 확인 전이에요. 확정하면 신호가 바뀔 수 있어요.
              </p>
            )}
          </div>
          <p className="s05__settled">
            {gate.settled
              ? "확정된 판정이에요."
              : "아직 확정 전이에요. 아래에서 확정해요."}
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
            <b>재설명이 진행 중이에요.</b>
            <p>
              고객 화면에서 재설명을 읽고 다시 답하면 재검증으로 기록됩니다. 그전까지는
              판정을 확정할 수 없습니다.
            </p>
          </div>
          {/* 여기도 표시를 싣는다(#492). 이 버튼이 눌리는 상태(`RE_EXPLAIN`)가 곧
              «인계가 있어야 하는 상태» 다 — 새로고침·다른 탭으로 인계가 날아간 채
              눌리는 것이 정확히 이 자리이고, 그때 표시가 없으면 못 알아챈다. */}
          <button
            type="button"
            className="s05__btn s05__btn--primary"
            onClick={() => navigate(reverifyPath(sid))}
          >
            고객 화면으로
          </button>
        </section>
      )}

      {/* ── 재설명 문면을 만드는 중 (피드백 4번) ──────────────────────────────
          `POST /re-explain` 은 ai-service 를 거치는 LLM 왕복이라 몇 초 걸린다. 그동안
          화면에서 움직이는 것이 **버튼 라벨 한 줄**뿐이었고, 응답이 오면 화면이 통째로
          고객 화면으로 바뀌었다 — 판매자는 자기가 무엇을 눌렀는지 확인할 새가 없다.
          그래서 «무엇을 만들고 있는지» 를 항목 이름과 함께 적고, 올 문면의 자리를 미리
          비워 둔다. 스켈레톤은 장식이 아니라 **다음에 올 것의 모양**이다.

          ❗등급도 재설명 사유도 여기 안 적는다 — 이 자리는 곧 고객에게 넘길 화면이고,
          고객에게 판정을 보이지 않는 것이 S-03 설계 판단 ①이다. */}
      {reExplaining && (
        <section className="s05__pending" role="status" aria-live="polite">
          <p className="s05__pending-title">
            <b>{nameOf(reExplaining)}</b> 항목을 다시 설명할 문면을 만들고 있어요.
          </p>
          <div className="s05__skeleton" aria-hidden="true">
            <span className="s05__skeleton-line" />
            <span className="s05__skeleton-line" />
            <span className="s05__skeleton-line s05__skeleton-line--short" />
          </div>
          <p className="s05__pending-note">
            준비되면 고객 화면으로 넘어가요. 그때 화면을 고객님께 넘겨 주세요.
          </p>
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
            이 신호를 만든 룰이에요. 판정 근거로 남아요.
          </p>
        </section>
      )}

      {/* ── 항목별 판정. 신호등 색을 쓰지 않는다(설계 판단 ②) ─────────────────── */}
      <section className="s05__items">
        <h2>항목별 이해도 <span className="s05__count">{judgments.length}건</span></h2>

        {/* ── 등급 요약 (설계 판단 ⑨) ───────────────────────────────────────
            이 화면에서 판매자가 하는 일은 «무엇을 다시 설명할지 고르는 것» 이다. 분포를
            보려고 카드를 매번 다 훑게 두지 않는다. 무거운 등급부터 적고, **0 건인 등급은
            아예 안 적는다** — 「오해 0」 은 정보가 아니라 잡음이다.
            ❗이건 측정값의 집계지 판정이 아니다. 신호등 3색을 쓰지 않는다(설계 판단 ②). */}
        {judgments.length > 0 && (
          <ul className="s05__tally">
            {GRADE_ORDER.map((g) => {
              const n = judgments.filter((j) => j.grade === g).length;
              if (n === 0) return null;
              return (
                <li key={g} className={`s05__tally-item s05__tally-item--${g.toLowerCase()}`}>
                  <span className="s05__tally-label">{GRADE_LABEL[g]}</span>
                  <b className="s05__tally-count">{n}</b>
                </li>
              );
            })}
          </ul>
        )}

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
            아직 판정된 항목이 없어요. 고객이 답하면 여기에 쌓여요.
          </p>
        ) : (
          <ul className="s05__list">
            {judgments.map((j) => (
              <li key={j.itemId} className={`s05__item s05__item--${j.grade.toLowerCase()}`}>
                <div className="s05__item-head">
                  <h3>{nameOf(j.itemId)}</h3>
                  {/* 등급 배지 — 판정 3색을 못 쓰므로(설계 판단 ②) **먹 한 색의 채움
                      단계**로 무게를 준다: 오해가 가장 진하고 이해가 가장 조용하다.
                      등급기호를 라벨 옆에 같이 낸다 — 명도를 못 읽는 조건에서도 갈리고,
                      명세서 0.5 의 4단계와 화면이 같은 어휘를 쓰게 된다(설계 판단 ⑨). */}
                  <span className={`s05__grade s05__grade--${j.grade.toLowerCase()}`}>
                    {GRADE_LABEL[j.grade]}
                    <span className="s05__grade-code">{j.grade}</span>
                  </span>
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

      {/* ── 다음 행동 ───────────────────────────────────────────────────────
          ❗**버튼 줄과 설명 줄을 세로로 가른다.** 예전에는 한 flex 줄에 버튼과 `<p>` 안내가
          섞여 있어서, 안내가 뜨는 순간(적색·미확정 — 데모에서 제일 자주 보는 상태다) 문단이
          버튼 사이를 밀고 들어와 **버튼이 두 덩이로 쪼개지고 baseline 도 안 맞았다.**
          확인 패널(`width:100%`)까지 같은 줄에 있어서 확정을 누르면 배치가 한 번 더 바뀐다.
          버튼은 **항상 맨 위 한 줄**에 있고, 이유·경고는 그 아래에 붙는다. */}
      <footer className="s05__actions">
        <div className="s05__cta">
          {gate && !gate.settled && (
            <button
              type="button"
              className="s05__btn s05__btn--primary"
              onClick={() => setConfirming(true)}
              // 재설명 중에는 상태머신이 판정을 안 받는다(설계 판단 ⑦). 눌러서 409 를
              // 받는 것보다 눌리지 않는 게 낫다 — 설계 판단 ③과 같은 규칙이다.
              // 확인 패널이 열려 있는 동안에도 잠근다 — 두 번 눌러도 하는 일이 없다.
              disabled={judgments.length === 0 || reExplainInFlight || confirming}
            >
              판정 확정
            </button>
          )}

          {/* 적색이면 오버라이드 요청 경로를 연다. 승인은 MGR 이 S-06 에서 한다.

              ❗`settled` 를 같이 본다. 미리보기 적색은 **아직 판정이 아니다** — 응답 0건
              세션도 `R-00`(unmeasured > 0)으로 RED 가 서므로, 이 조건이 신호만 보면
              인터뷰를 시작도 안 한 세션에서 버튼이 열리고 서버가 409 를 낸다(이슈 #311).
              바로 위 `판정 확정` 이 이미 같은 규칙을 지킨다 — 눌러서 409 를 받는 것보다
              눌리지 않는 게 낫다(설계 판단 ③). `recorded` 를 둔 목적이 미리보기를 확정으로
              오인하지 않게 하는 것이고(결정 2.6), 여기가 정확히 그 자리다. */}
          {gate?.signal === "RED" && gate.settled && (
            <button type="button" className="s05__btn" onClick={() => navigate(`/override/${sid}`)}>
              예외 승인 요청
            </button>
          )}

          {gate?.settled && (
            <button type="button" className="s05__btn" onClick={() => navigate(`/report/${sid}`)}>
              이해 기록 리포트
            </button>
          )}

          {/* 오해 지도 대시보드로 가는 길 (피드백 5번). 이 화면에는 없었다 — 닿는 길이
              S-02 → 심사용 목차 하나뿐이었다. **확정한 뒤에만** 그린다: 그 전의 다음
              행동은 재설명이거나 확정이지 집계가 아니고, 확정 전에 집계로 새면 이 화면이
              «판정을 마치라» 고 말하는 힘이 약해진다.
              ❗여는 것은 화면이지 권한이 아니다 — 집계 API 는 `rbac_policy.yaml` 에서
              COMPL·MGR 로 좁혀져 있고 SELLER 계정이 열면 서버가 막는다(기획 7-4). */}
          {gate?.settled && (
            <button type="button" className="s05__btn" onClick={() => navigate("/dashboard")}>
              오해 지도 대시보드
            </button>
          )}

          {/* 오른쪽 끝에 떨어뜨린다 — 이 줄에서 유일하게 세션을 앞으로 못 밀고,
              나머지와 섞여 있으면 다음 행동을 고르는 눈이 한 번 더 멈춘다. */}
          <button type="button" className="s05__btn s05__btn--quiet s05__btn--end"
                  onClick={() => void load()}>
            새로고침
          </button>
        </div>

        {gate && !gate.settled && confirming && (
          <div className="s05__confirm" role="alertdialog" aria-label="판정 확정 확인">
            <p>
              <strong>확정하면 되돌릴 수 없어요.</strong> 확정 이후에는 재설명·재검증으로
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
        )}

        {/* 잠긴 이유를 적는다. 안 적으면 판매자가 "적색인데 버튼이 없다"에서 멈춘다.

            ❗**두 갈래로 가른다.** 위 `판정 확정` 이 `judgments.length === 0` 이면 같이
            잠기므로, 한 문면으로만 두면 안내가 **눌리지 않는 버튼을 가리킨다** — 멈추는
            자리가 없어지는 게 아니라 *"확정하라는데 확정이 안 눌린다"* 로 한 칸 옮겨진다.
            그리고 `#311` 이 재현 조건으로 적은 것이 정확히 응답 0건 세션이라, 그 경우가
            이 안내의 첫 독자다(#328 리뷰). */}
        {gate?.signal === "RED" && !gate.settled && (
          <p className="s05__action-note">
            {judgments.length === 0 ? (
              <>아직 채점된 응답이 없어요. <b>인터뷰를 진행한 뒤</b> 판정을 확정하면 예외 승인
                요청을 할 수 있습니다.</>
            ) : (
              <>예외 승인 요청은 <b>판정을 확정한 뒤</b>에 할 수 있어요.</>
            )}
          </p>
        )}
      </footer>
    </main>
  );
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
        text: "재설명 횟수를 다 썼어요. 이 항목은 판정으로 넘어가요.",
      };
    }
    if (e.code === "REEXPLAIN_NOT_ELIGIBLE") {
      return {
        itemId, kind: "not_eligible",
        text: "재설명할 항목이 아니에요. 판정이 없거나 이미 이해했어요.",
      };
    }
    if (e.code === "ILLEGAL_STATE_TRANSITION") {
      return {
        itemId, kind: "failed",
        text: "지금은 재설명을 시작할 수 없어요. 새로고침해 주세요.",
      };
    }
    // 서버 원문을 그대로 붙이지 않는다 — 이 자리도 판매자가 보는 화면이다(#316).
    return { itemId, kind: "failed", text: `재설명을 시작하지 못했습니다. ${describeError(e).text}` };
  }
  return { itemId, kind: "failed", text: "재설명을 시작하지 못했어요. 잠시 후 다시 시도해 주세요." };
}
