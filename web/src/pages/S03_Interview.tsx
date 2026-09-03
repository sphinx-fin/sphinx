/**
 * S-03 인터뷰 (고객 화면) — F-INT-003 응답 수집. 소유: 오준서.
 *
 * 명세 8절 S-03 핵심 요소: 질문 표시 · 텍스트 응답 입력 · 진행 표시 · 고령자 모드.
 *
 * ── 설계 판단 3건 (명세 조항 대조 결과) ──────────────────────────────────────
 *
 * ① **판정 등급(U1~U4)을 고객에게 보여주지 않는다.**
 *    명세 8절에서 항목별 신호등·근거는 S-05(판매자 화면)의 요소로 지정돼 있고, S-03 의
 *    요소 목록에는 없다. 기획서 5절도 "이해→오해 오판"의 비용을 고객 마찰로 규정한다.
 *    그래서 답변 후 화면은 등급을 노출하지 않고 중립적으로 진행만 안내한다.
 *    (등급은 서버 세션에 기록되고 S-05 가 근거와 함께 렌더한다.)
 *
 * ② **답변 후 분기를 화면이 정하지 않는다.**
 *    "다음 항목 / 재설명 / 판정" 중 무엇인지는 F-INT-004 의 결정이다. 지금 응답에는
 *    그 신호가 없어서(→ PR #16 리뷰 3번으로 `nextAction` 요청) 화면이 등급으로 추론하면
 *    "항목당 재검증 최대 2회" 룰이 프론트에 복제된다 — P1(룰이 결정) 위반이다.
 *    확정 전까지는 모든 답변 후 동일한 중립 화면을 보여준다.
 *
 * ③ **무응답 60초(E-INT-03) 타이머는 고령자 모드에서 끈다.**
 *    명세 10절 접근성이 고령자 모드를 "입력 시간 제한 없음"으로 규정한다. E-INT-03 도
 *    강제 종료가 아니라 "안내 후 건너뛰기 **확인**"이므로, 타이머를 끄는 쪽이 두 조항을
 *    모두 만족한다. 일반 모드에서도 자동으로 넘기지 않고 확인을 받는다.
 *
 * ④ **재설명·재검증은 이 화면의 다른 모드다** (F-INT-004 · 명세 8절 S-03 "재설명 화면")
 *    판매자가 S-05 에서 시작하면(`session:interview` 는 SELLER 다) 문면과 재질문이
 *    `lib/reexplain` 을 거쳐 여기로 온다. 그때 화면은 **질문을 서버에 새로 묻지 않는다** —
 *    `POST /questions/next` 는 *아직 안 물은 다음 항목*을 주므로, 재검증 중에 부르면
 *    엉뚱한 항목을 묻고 그 답이 재검증으로 기록된다. 에러 없이 틀리는 종류다.
 *
 *    재검증에서는 **진행 막대를 그리지 않는다.** 서버가 주는 `index`/`total` 은 "몇 번째
 *    항목인가" 지 "재검증 몇 번째인가" 가 아니라, 그대로 그리면 진행률이 뒤로 가거나
 *    제자리인 것처럼 보인다. 대신 지금이 재검증이라는 사실만 적는다.
 *
 *    ⚠️ **남은 구멍 — 인계를 못 받은 탭에서는 이 화면이 재검증인 줄 모른다.** 문면을 다시
 *    읽는 GET 이 계약에 없어서(`ReExplanation` 주석) 다른 기기·다른 탭으로 넘어가면
 *    일반 흐름으로 떨어지고, 세션은 `RE_EXPLAIN` 인데 화면은 *다음 항목*을 묻는다 —
 *    그 답이 엉뚱한 항목의 재검증으로 기록된다. 화면 쪽에서 막으려면 세션 상태를 읽어야
 *    하는데 `session:read` 는 CUST 에게 없다(#166). 계약에 재설명 조회가 생기면 그때
 *    닫힌다. 한 태블릿에서 넘기는 데모 경로에서는 인계가 항상 있다.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiRequestError, get, post } from "../api/client";
import type { Judgment, NextQuestion, ReExplanation, RiskItem } from "../api/types";
import { useElderlyMode } from "../hooks/useElderlyMode";
import { useInputMeta } from "../hooks/useInputMeta";
import { detectPii } from "../lib/pii";
import { clearReExplanation, readReExplanation } from "../lib/reexplain";
import "./S03_Interview.css";

/** E-INT-02: 공백 제외 5자 미만이면 1회 재요청. */
const MIN_CHARS = 5;
/** E-INT-03: 무응답 안내까지의 시간(고령자 모드에서는 비활성). */
const IDLE_PROMPT_MS = 60_000;

/** `reexplain` = 재설명 문면을 읽는 중. 읽고 나서 `asking`(재질문)으로 간다(설계 판단 ④). */
type Phase = "loading" | "reexplain" | "asking" | "submitting" | "answered" | "failed";

export default function S03Interview() {
  const { sid = "" } = useParams();
  const navigate = useNavigate();
  const { elderly, toggle, enable } = useElderlyMode();
  const meta = useInputMeta();

  const [phase, setPhase] = useState<Phase>("loading");
  const [items, setItems] = useState<RiskItem[]>([]);
  const [question, setQuestion] = useState<NextQuestion | null>(null);
  /** 진행 중인 재설명. null 이면 일반 인터뷰 흐름이다(설계 판단 ④). */
  const [reExplain, setReExplain] = useState<ReExplanation | null>(null);
  const [text, setText] = useState("");
  const [shortWarned, setShortWarned] = useState(false);
  const [idlePrompt, setIdlePrompt] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const textareaRef = useRef<HTMLTextAreaElement>(null);

  /* ── 진행 상태는 서버가 준다 (계약 `NextQuestion.index·total·done`) ──────
     예전에는 risk-items 개수로 분모를 보완했는데, **서버가 물어볼 항목 수와 추출 항목 수는
     다를 수 있다**(계약 주석의 경고 그대로). 어긋나면 에러 없이 진행률만 틀리고, 고객은
     "몇 개 남았는지"를 잘못 안 채로 인터뷰를 한다. 그래서 분모·분자를 모두 서버 값으로 둔다.
     세션을 이어서 열었을 때도 맞는다는 이점이 따라온다 — 로컬 카운트는 0 부터 다시 셌다. */
  const total = question?.total ?? 0;
  const answeredCount = (() => {
    if (!question) return 0;
    if (question.done) return question.total;
    // index 는 "지금 묻고 있는 항목의 1-based 번호"다. 아직 답하기 전이면 그 앞까지가 완료분이고,
    // 답을 기록한 직후(phase="answered")에는 이 항목까지 완료다. 다음 질문을 받을 때까지
    // 기다리면 "답변이 기록되었습니다" 옆에서 카운터가 0 으로 남아 화면이 자기 말을 뒤집는다.
    return phase === "answered" ? question.index : question.index - 1;
  })();

  /* ── 지금 묻고 있는 것 ───────────────────────────────────────────────────
     재검증이면 출처가 재설명 응답이고, 아니면 `/questions/next` 다. **문면을 고르는 곳을
     한 군데로 둔다** — 렌더에서 매번 삼항으로 가르면 한 자리를 빠뜨렸을 때 화면에 보인
     질문과 제출한 itemId 가 갈린다.                                                   */
  const reverifying = reExplain !== null;
  const askedItemId = reverifying ? reExplain.itemId : question?.itemId ?? null;
  const askedText = reverifying ? reExplain.reverifyQuestion : question?.question ?? null;

  /* 항목명 표시용. 추출 실패 항목(E-EXT-03)은 서버가 애초에 묻지 않지만, 숨기지는 않는다 —
     실패 항목의 가시화는 S-01 의 책임이다. */
  const currentItem = useMemo(
    () => items.find((i) => i.itemId === askedItemId) ?? null,
    [items, askedItemId],
  );

  const charCount = text.replace(/\s/g, "").length;
  const piiKinds = useMemo(() => detectPii(text), [text]);

  /* ── 질문 요청 ─────────────────────────────────────────────────────────── */
  const loadQuestion = useCallback(async () => {
    const next = await post<NextQuestion>(`/sessions/${sid}/questions/next`);
    setQuestion(next);
    setText("");
    setShortWarned(false);
    setIdlePrompt(false);
    meta.reset();
    setPhase("asking");
  }, [sid, meta]);

  /* ── 최초 로드: 검증 대상 항목 → 첫 질문 ────────────────────────────────
     항목을 **세션 경유**로 받는다(#164, 이슈 #158 1항). 예전에는 `GET /sessions/{sid}` 로
     productId 를 알아낸 뒤 `GET /products/{productId}/risk-items` 를 불렀는데, 그쪽은
     `product:read`(scope org)라 **고객에게 열어 주면 자기 계약 건과 무관한 상품까지 전
     카탈로그가 열린다.** 세션 경유는 대상이 세션이라 범위가 자연히 own_session 이다.
     세션 조회는 통째로 지웠다 — 이 화면이 응답에서 쓰던 것이 productId 하나뿐이었다.
     그래서 최초 로드가 3회 → 2회(항목 → 첫 질문)가 된다. */
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        // 재설명 인계가 있으면 **질문을 새로 묻지 않는다**(설계 판단 ④). 항목 목록은
        // 두 흐름 모두 항목명을 그리는 데 쓰므로 먼저 받는다.
        const handoff = readReExplanation(sid);
        const res = await get<{ items: RiskItem[] }>(`/sessions/${sid}/risk-items`);
        if (!alive) return;
        setItems(res.items ?? []);
        if (handoff) {
          setReExplain(handoff);
          // `vulnerable` 은 렌더링 힌트다 — 모드만 켜고 이유를 화면에 적지 않는다
          // (계약 주석 · 기획서 7-4: 취약 분류를 본인에게 보이지 않는다).
          if (handoff.vulnerable) enable();
          setPhase("reexplain");
          return;
        }
        await loadQuestion();
      } catch (e) {
        if (!alive) return;
        setError(describe(e));
        setPhase("failed");
      }
    })();
    return () => {
      alive = false;
    };
    // loadQuestion 은 sid 에만 의존하므로 최초 1회로 충분하다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sid]);

  /* ── E-INT-03 무응답 안내 ───────────────────────────────────────────────
     고령자 모드에서는 타이머 자체를 걸지 않는다(명세 10절: 입력 시간 제한 없음). */
  useEffect(() => {
    if (elderly || phase !== "asking" || text.length > 0) return;
    const t = setTimeout(() => setIdlePrompt(true), IDLE_PROMPT_MS);
    return () => clearTimeout(t);
  }, [elderly, phase, text]);

  useEffect(() => {
    if (phase === "asking") textareaRef.current?.focus();
  }, [phase, question]);

  /* ── 재설명을 읽고 나서 재질문으로 ────────────────────────────────────────
     여기서 `meta.reset()` 을 부른다 — 입력 메타의 기준점은 "질문이 화면에 뜬 순간"이고,
     재검증에서 그건 문면을 읽고 이 버튼을 누른 때다. 마운트 시점으로 잡으면 문면을
     읽은 시간이 통째로 '첫 키 입력 지연'에 들어가 코칭 정황 신호가 오염된다.          */
  function beginReverify() {
    setText("");
    setShortWarned(false);
    setIdlePrompt(false);
    meta.reset();
    setPhase("asking");
  }

  /* ── 제출 ───────────────────────────────────────────────────────────────── */
  async function submit() {
    if (!askedItemId) return;   // done=true 면 itemId 가 null 이다

    // E-INT-02: 극단적 단답은 1회만 되묻는다. 두 번째는 그대로 받는다 —
    // 계속 막으면 "모르겠다"는 응답 자체가 기록되지 못하고 세션이 멎는다(U3 도 판정이다).
    if (charCount < MIN_CHARS && !shortWarned) {
      setShortWarned(true);
      textareaRef.current?.focus();
      return;
    }

    setPhase("submitting");
    setError(null);
    try {
      // 판정은 받지만 화면에 등급을 그리지 않는다(설계 판단 ①). 서버 세션에 기록된다.
      // 재검증도 같은 엔드포인트다 — 세션이 `RE_EXPLAIN` 이면 서버가 이 답변을 재검증으로
      // 세고 상태를 옮긴다(`recordJudgment`). 화면이 "재검증 제출" 을 따로 말하지 않는 이유다.
      await post<Judgment>(`/sessions/${sid}/answers`, {
        itemId: askedItemId,
        text,
        inputMeta: meta.snapshot(text, elderly),
      });
      // 인계는 여기서 지운다 — 성공한 뒤라야 한다(`lib/reexplain` 주석). 화면 상태는
      // 남겨 둔다: 다음 화면이 "재검증이 기록됐다"를 말해야 하고, 그건 이 값으로만 안다.
      if (reverifying) clearReExplanation(sid);
      setPhase("answered");
    } catch (e) {
      setError(describe(e));
      setPhase("asking");   // 입력은 남긴다 — 재시도 시 다시 쓰게 하지 않는다
    }
  }

  /** E-INT-03 건너뛰기 — 서버가 U3(미이해)로 처리하도록 빈 응답을 명시적으로 보낸다. */
  async function skip() {
    if (!askedItemId) return;
    setPhase("submitting");
    try {
      await post<Judgment>(`/sessions/${sid}/answers`, {
        itemId: askedItemId,
        text: "(응답하지 않음)",
        inputMeta: meta.snapshot("", elderly),
      });
      if (reverifying) clearReExplanation(sid);
      setPhase("answered");
    } catch (e) {
      setError(describe(e));
      setPhase("asking");
    }
  }

  async function nextQuestion() {
    setPhase("loading");
    // 재검증이 끝났으니 일반 흐름으로 돌아간다. 저장소는 제출 때 이미 비웠다 —
    // 여기서 지우는 것은 화면 상태뿐이다.
    setReExplain(null);
    try {
      await loadQuestion();
    } catch (e) {
      setError(describe(e));
      setPhase("failed");
    }
  }

  /* ── 렌더 ───────────────────────────────────────────────────────────────── */
  if (phase === "loading" && !question) {
    return <main className="iv"><p className="iv__state">불러오는 중…</p></main>;
  }
  if (phase === "failed") {
    return (
      <main className="iv">
        <div className="iv__shell">
          <div className="iv__alert iv__alert--error" role="alert">
            <b>인터뷰를 시작할 수 없습니다</b>
            {error}
          </div>
        </div>
      </main>
    );
  }

  const busy = phase === "submitting" || phase === "loading";
  const pct = total > 0 ? Math.round((answeredCount / total) * 100) : 0;

  return (
    <main className="iv">
      <div className="iv__shell">
        <div className="iv__top">
          {/* 재검증에서는 진행 막대를 그리지 않는다(설계 판단 ④) — 서버의 index/total 은
              "몇 번째 항목인가" 라 재검증 중에 그리면 진행률이 제자리이거나 뒤로 간다. */}
          {reverifying ? (
            <p className="iv__reverify">다시 한 번 확인하는 항목입니다.</p>
          ) : (
            <div className="iv__progress">
              <span className="iv__progress-label">
                검증 항목 <b>{total}</b>개 중 <b>{answeredCount}</b>개 응답 완료
              </span>
              <div
                className="iv__bar"
                role="progressbar"
                aria-valuemin={0}
                aria-valuemax={total}
                aria-valuenow={answeredCount}
                aria-label="인터뷰 진행률"
              >
                <div className="iv__bar-fill" style={{ width: `${pct}%` }} />
              </div>
            </div>
          )}

          <button
            type="button"
            className="iv__mode"
            aria-pressed={elderly}
            onClick={toggle}
          >
            {elderly ? "큰 글씨 켜짐" : "큰 글씨로 보기"}
          </button>
        </div>

        {/* 대필 방지 안내 (F-INT-003 메모) — 창구에서 판매자가 대신 입력하는 것을 막는다 */}
        <p className="iv__notice">
          <span aria-hidden="true">✋</span>
          <span>
            <b>고객님이 직접 입력해 주세요.</b> 정답을 맞히는 시험이 아닙니다. 아는 만큼,
            평소 쓰시는 말로 적어 주시면 됩니다. 잘 모르는 항목은 다시 설명해 드립니다.
          </span>
        </p>

        {phase === "reexplain" && reExplain ? (
          /* ── 재설명 문면 (F-INT-004) ────────────────────────────────────────
             ❗**등급도 "왜 다시 설명하는지"도 적지 않는다.** 고객에게 판정을 보이지
             않는 것이 설계 판단 ①이고, 재설명 자리에서 "미이해로 판정되어" 를 적으면
             그 원칙이 여기서만 깨진다. 화면은 다시 설명한다는 사실만 말한다.
             문면은 서버가 만든 그대로 낸다 — 요약하거나 자르지 않는다.               */
          <section className="iv__card" aria-live="polite">
            {currentItem && <span className="iv__item-name">{currentItem.name}</span>}
            <h1 className="iv__question">다시 한 번 설명드릴게요.</h1>
            <p className="iv__reexplain-body">{reExplain.content}</p>
            <div className="iv__actions">
              <button
                type="button"
                className="iv__btn iv__btn--primary"
                onClick={beginReverify}
              >
                읽었습니다, 답변하기
              </button>
            </div>
          </section>
        ) : phase === "answered" ? (
          <section className="iv__card" aria-live="polite">
            <h1 className="iv__question">답변이 기록되었습니다.</h1>
            <p className="iv__alert iv__alert--info">
              {reverifying
                ? "다시 답해 주셔서 감사합니다. 담당자가 결과를 확인합니다."
                : question?.done
                  ? "모든 항목에 응답하셨습니다. 담당자가 결과를 확인합니다."
                  : "다음 항목으로 넘어가시겠어요?"}
            </p>
            <div className="iv__actions">
              <button
                type="button"
                className="iv__btn iv__btn--primary"
                onClick={nextQuestion}
                disabled={busy}
              >
                다음 질문
              </button>
              <button
                type="button"
                className="iv__btn iv__btn--ghost"
                onClick={() => navigate(`/simulator/${sid}`)}
              >
                손실 시뮬레이터로 확인하기
              </button>
            </div>
          </section>
        ) : (
          <section className="iv__card">
            {currentItem && (
              <span className="iv__item-name">{currentItem.name}</span>
            )}
            {/* 재검증이면 재설명 응답의 변형 질문이다 — 직전 질문을 다시 띄우지 않는다
                (계약 `ReExplanation.reverifyQuestion`). 고르는 자리는 `askedText` 한 곳. */}
            <h1 className="iv__question">{askedText}</h1>

            <div className="iv__field">
              <label htmlFor="answer" className="sr-only">
                답변 입력
              </label>
              <textarea
                id="answer"
                ref={textareaRef}
                className="iv__textarea"
                value={text}
                disabled={busy}
                placeholder="본인 말씀으로 적어 주세요"
                onChange={(e) => {
                  setText(e.target.value);
                  setIdlePrompt(false);
                }}
                onKeyDown={meta.onKeyDown}
                onPaste={meta.onPaste}
              />
              <div className="iv__meta-row">
                <span>{elderly ? "시간 제한 없이 천천히 적으셔도 됩니다." : " "}</span>
                <span className="iv__count">{charCount}자</span>
              </div>
            </div>

            {piiKinds.length > 0 && (
              <p className="iv__alert iv__alert--warn" role="status">
                <b>{piiKinds.join("·")}로 보이는 내용이 있습니다.</b>
                이 답변에는 개인정보가 필요하지 않습니다. 빼고 적어 주세요.
              </p>
            )}

            {shortWarned && charCount < MIN_CHARS && (
              <p className="iv__alert iv__alert--warn" role="status">
                <b>조금 더 자세히 적어 주세요.</b>
                한두 문장이면 충분합니다. 그대로 제출하셔도 됩니다.
              </p>
            )}

            {/* 시간 기반 안내만 여기 남는다. 건너뛰기 **버튼**은 아래에서 상시 노출이라
                이 문면은 "이제야 건너뛸 수 있다" 가 아니라 재촉하지 않는다는 말이다. */}
            {idlePrompt && (
              <p className="iv__alert iv__alert--info" role="status">
                <b>천천히 하셔도 됩니다.</b>
                답변이 어려우시면 아래 <b>이 항목 건너뛰기</b>를 눌러 주세요. 담당자가 다시
                설명해 드립니다.
              </p>
            )}

            {error && (
              <p className="iv__alert iv__alert--error" role="alert">
                <b>제출하지 못했습니다</b>
                {error} 입력하신 내용은 그대로 남아 있습니다.
              </p>
            )}

            <div className="iv__actions">
              <button
                type="button"
                className="iv__btn iv__btn--primary"
                onClick={submit}
                disabled={busy || text.trim().length === 0}
              >
                {phase === "submitting" ? "제출 중…" : "답변 제출"}
              </button>
              {/* ❗**항상 보인다.** 예전에는 `idlePrompt` 에 달려 있었는데, 고령자 모드는
                  타이머를 아예 안 걸므로(명세 10절 "입력 시간 제한 없음") 그 플래그가
                  영원히 false 였다 — **도움이 가장 필요한 사람에게만 도움 경로가 없었다**
                  (이슈 #315). 타이머를 끈 것 자체는 옳고, 문제는 시간 기반 안내와
                  건너뛰기 가능 여부라는 **두 관심사가 한 플래그에 묶여** 있던 것이다.
                  남는 선택지가 빈칸 제출(버튼이 잠긴다)과 아무 말이나 적기뿐이었는데,
                  후자는 U3(미이해)로 갈 것이 U4(오해)로 채점될 수 있다.
                  크기는 따로 안 만진다 — 고령자 모드는 `<html data-elderly>` 의 토큰
                  배율이라 이 버튼도 같이 커진다(useElderlyMode 주석). */}
              <button
                type="button"
                className="iv__btn iv__btn--ghost"
                onClick={skip}
                disabled={busy}
              >
                이 항목 건너뛰기
              </button>
            </div>
          </section>
        )}
      </div>
    </main>
  );
}

/** ApiRequestError 를 고객이 읽을 수 있는 문장으로. 코드별 후속 안내가 다르다. */
function describe(e: unknown): string {
  if (e instanceof ApiRequestError) {
    switch (e.code) {
      case "NOT_FOUND":
        return "세션을 찾을 수 없습니다. 담당자에게 알려 주세요.";
      case "ILLEGAL_STATE_TRANSITION":
        return "이미 종료된 세션입니다. 담당자에게 알려 주세요.";
      case "VALIDATION_ERROR":
      case "MALFORMED_REQUEST":
        return "입력을 다시 확인해 주세요.";
      // 아래 둘은 채점 경로(server → ai-service)의 상류 실패다. 답변은 화면에 그대로 남으므로
      // 재시도가 가능하고, 재시도가 의미 있는지가 서로 다르다.
      case "AI_SERVICE_UNAVAILABLE":
        return "채점 서비스가 잠시 응답하지 않습니다. 다시 제출해 주세요.";
      case "EVIDENCE_REQUIRED":
        return "채점 결과를 기록할 수 없었습니다. 담당자에게 알려 주세요.";
      default:
        return "잠시 후 다시 시도해 주세요.";
    }
  }
  return "알 수 없는 오류입니다. 담당자에게 알려 주세요.";
}
