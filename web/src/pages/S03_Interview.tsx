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
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiRequestError, get, post } from "../api/client";
import type { Judgment, NextQuestion, RiskItem, SessionResponse } from "../api/types";
import { useElderlyMode } from "../hooks/useElderlyMode";
import { useInputMeta } from "../hooks/useInputMeta";
import { detectPii } from "../lib/pii";
import "./S03_Interview.css";

/** E-INT-02: 공백 제외 5자 미만이면 1회 재요청. */
const MIN_CHARS = 5;
/** E-INT-03: 무응답 안내까지의 시간(고령자 모드에서는 비활성). */
const IDLE_PROMPT_MS = 60_000;

type Phase = "loading" | "asking" | "submitting" | "answered" | "failed";

export default function S03Interview() {
  const { sid = "" } = useParams();
  const navigate = useNavigate();
  const { elderly, toggle } = useElderlyMode();
  const meta = useInputMeta();

  const [phase, setPhase] = useState<Phase>("loading");
  const [items, setItems] = useState<RiskItem[]>([]);
  const [question, setQuestion] = useState<NextQuestion | null>(null);
  const [text, setText] = useState("");
  const [answeredIds, setAnsweredIds] = useState<string[]>([]);
  const [shortWarned, setShortWarned] = useState(false);
  const [idlePrompt, setIdlePrompt] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const textareaRef = useRef<HTMLTextAreaElement>(null);

  /* ── 검증 대상 항목 ──────────────────────────────────────────────────────
     추출 실패 항목(E-EXT-03)은 조건값이 없어 되말하기 질문을 만들 수 없으므로 분모에서 뺀다.
     빼되 숨기지는 않는다 — 실패 항목의 가시화는 S-01 의 책임이다. */
  const targets = useMemo(() => items.filter((i) => i.status === "extracted"), [items]);
  const total = targets.length;
  const answeredCount = answeredIds.length;
  const currentItem = useMemo(
    () => targets.find((i) => i.item_id === question?.itemId) ?? null,
    [targets, question],
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

  /* ── 최초 로드: 세션 → 검증 대상 항목 → 첫 질문 ───────────────────────── */
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const session = await get<SessionResponse>(`/sessions/${sid}`);
        const res = await get<{ items: RiskItem[] }>(
          `/products/${session.productId}/risk-items`,
        );
        if (!alive) return;
        setItems(res.items ?? []);
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

  /* ── 제출 ───────────────────────────────────────────────────────────────── */
  async function submit() {
    if (!question) return;

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
      await post<Judgment>(`/sessions/${sid}/answers`, {
        itemId: question.itemId,
        text,
        inputMeta: meta.snapshot(text, elderly),
      });
      setAnsweredIds((prev) =>
        prev.includes(question.itemId) ? prev : [...prev, question.itemId],
      );
      setPhase("answered");
    } catch (e) {
      setError(describe(e));
      setPhase("asking");   // 입력은 남긴다 — 재시도 시 다시 쓰게 하지 않는다
    }
  }

  /** E-INT-03 건너뛰기 — 서버가 U3(미이해)로 처리하도록 빈 응답을 명시적으로 보낸다. */
  async function skip() {
    if (!question) return;
    setPhase("submitting");
    try {
      await post<Judgment>(`/sessions/${sid}/answers`, {
        itemId: question.itemId,
        text: "(응답하지 않음)",
        inputMeta: meta.snapshot("", elderly),
      });
      setAnsweredIds((prev) =>
        prev.includes(question.itemId) ? prev : [...prev, question.itemId],
      );
      setPhase("answered");
    } catch (e) {
      setError(describe(e));
      setPhase("asking");
    }
  }

  async function nextQuestion() {
    setPhase("loading");
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
          <div className="iv__progress">
            <span className="iv__progress-label">
              {/* TODO(강희진, PR #16 리뷰 2번): 서버가 index/total/done 을 주면 그 값을 쓴다.
                  지금은 검증 대상 항목 수로 분모를 보완하고 있다. */}
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

        {phase === "answered" ? (
          <section className="iv__card" aria-live="polite">
            <h1 className="iv__question">답변이 기록되었습니다.</h1>
            <p className="iv__alert iv__alert--info">
              {answeredCount >= total && total > 0
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
            <h1 className="iv__question">{question?.question}</h1>

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

            {idlePrompt && (
              <p className="iv__alert iv__alert--info" role="status">
                <b>천천히 하셔도 됩니다.</b>
                답변이 어려우시면 이 항목은 건너뛰고, 담당자가 다시 설명해 드립니다.
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
              {idlePrompt && (
                <button
                  type="button"
                  className="iv__btn iv__btn--ghost"
                  onClick={skip}
                  disabled={busy}
                >
                  이 항목 건너뛰기
                </button>
              )}
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
      default:
        return "잠시 후 다시 시도해 주세요.";
    }
  }
  return "알 수 없는 오류입니다. 담당자에게 알려 주세요.";
}
