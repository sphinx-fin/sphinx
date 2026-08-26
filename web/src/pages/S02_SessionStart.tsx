/**
 * S-02 세션 시작 (판매자 화면) — F-INT-001 세션 생성의 UI 몫. 소유: 오준서.
 *
 * 명세 8절 S-02 핵심 요소: 상품·채널·고객 속성 입력(비식별) · 세션 생성.
 * 기획서 7-2 메인 데모의 ①번(투자성향 설문 → 적합 판정)이 시작되는 화면이다.
 *
 * ── 설계 판단 4건 ────────────────────────────────────────────────────────────
 *
 * ① **식별정보 입력칸을 만들지 않는다 — 그리고 우회도 경고한다.**
 *    P3 대로 `CreateSessionRequest` 에는 성명·주민번호 필드가 애초에 없다. 그런데 스키마에
 *    없다고 입력이 막히는 건 아니다. 창구에서 실제로 벌어질 우회는 **계약건 참조번호 칸에
 *    "홍길동 010-1234-5678" 을 적는 것**이다. 그 값은 세션에 그대로 저장되고 리포트에 남는다.
 *    그래서 S-03 과 같은 `detectPii` 로 그 칸을 감시해 경고한다(막지는 않는다 — 막으면
 *    판매자가 다른 칸으로 옮겨 적는다. 보이게 하는 편이 낫다).
 *
 * ② **고객 속성은 자유 입력이 아니라 선택지다.**
 *    `resources/vulnerability_weights.yaml` 이 이 문자열을 키로 취약 가중을 매기고,
 *    **목록에 없는 값은 조용히 0점**이 된다. 자유 입력을 열면 "60세" · "60대 초반" 이
 *    들어와 취약 고객이 일반 고객으로 분류되고, 그 실패는 아무 데도 안 남는다.
 *    허용값은 `lib/sessionAttrs.ts` 한 곳에서 YAML 과 대조한다.
 *
 * ③ **설문 문항 세트가 곧 F-DET-002 의 입력 계약이다.**
 *    `mismatch.py` 가 "설문 스키마 확정 대기"로 멈춰 있는데, 설문을 만드는 화면이 여기뿐이라
 *    이 화면이 그 대기의 대상이다. 문항 정의는 `lib/survey.ts` 에 두고 근거를 적어 뒀다.
 *
 * ④ **세션을 만든 뒤 곧장 인터뷰로 넘기지 않는다.**
 *    S-02 는 판매자 화면이고 S-03 은 고객 화면이다. 자동 이동시키면 판매자가 그대로 앉아
 *    고객 답변을 대신 입력하는 흐름이 자연스러워진다 — F-INT-003 의 대필 방지와 정면으로
 *    어긋난다. 그래서 생성 후에는 멈춰서 세션 번호를 보여주고, "고객에게 넘겨주세요" 를
 *    명시한 뒤 시작 버튼을 누르게 한다.
 *
 * ⑤ **가입금액 구간을 화면에서 필수로 건다 — 계약은 nullable 그대로.**
 *    `amountBand` 는 계약상 선택이지만 취약 가중의 입력이다. 데모 고객(65세·5,000만 원)이
 *    `ageBand"60대"`3 + `amountBand"5천만원대"`1 = **정확히 임계값 4** 라서, 이 칸을
 *    건너뛰면 3점이 되어 **취약으로 분류되지 않는다** — 고령자 모드가 안 켜지고 재설명이
 *    일반 문면으로 나간다. 에러도 로그도 없고 시연만 밋밋해진다(decision-log 10.12).
 *    화면이 계약보다 엄격한 것은 흔하고, 여기서는 그게 맞다.
 */
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiRequestError, get, post } from "../api/client";
import type {
  Channel,
  CreateSessionRequest,
  ProductSummary,
  SessionResponse,
} from "../api/types";
import { detectPii } from "../lib/pii";
import { AGE_BANDS, AMOUNT_BANDS, CHANNELS, EXPERIENCE_LEVELS } from "../lib/sessionAttrs";
import {
  DEMO_SURVEY_ANSWERS,
  SURVEY_QUESTIONS,
  SURVEY_SCHEMA_VERSION,
  toSurveyResult,
} from "../lib/survey";
import "./S02_SessionStart.css";

type Phase = "editing" | "creating" | "created";

export default function S02SessionStart() {
  const navigate = useNavigate();

  const [products, setProducts] = useState<ProductSummary[]>([]);
  const [productId, setProductId] = useState<string>("");
  const [channel, setChannel] = useState<Channel>("FACE_TO_FACE");
  const [ageBand, setAgeBand] = useState("");
  const [experienceLevel, setExperienceLevel] = useState("");
  const [amountBand, setAmountBand] = useState("");
  const [contractRef, setContractRef] = useState("");
  const [answers, setAnswers] = useState<Record<string, string>>({});

  const [phase, setPhase] = useState<Phase>("editing");
  const [session, setSession] = useState<SessionResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  /* ── 상품 목록 (`GET /products`) ─────────────────────────────────────────
     상수로 들고 있으면 가명 표기가 서버와 두 벌이 된다(lib/sessionAttrs.ts 주석). */
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const list = await get<ProductSummary[]>("/products");
        if (!alive) return;
        setProducts(list ?? []);
        setProductId((prev) => prev || list?.[0]?.productId || "");
      } catch (e) {
        if (alive) setError(describe(e));
      }
    })();
    return () => { alive = false; };
  }, []);

  const refPii = useMemo(() => detectPii(contractRef), [contractRef]);
  const unanswered = SURVEY_QUESTIONS.filter((q) => !answers[q.id]).length;
  const canSubmit =
    productId !== "" &&
    ageBand !== "" &&
    amountBand !== "" &&   // 설계 판단 ⑤ — 계약은 nullable 이지만 화면에서 필수로 건다
    unanswered === 0 &&
    phase === "editing";

  function applyDemoPreset() {
    setAgeBand("60대");            // 기획서 7-2 ③ "65세 고객"
    setAmountBand("5천만원대");     // 7-2 표 기준금액 5,000만 원
    setExperienceLevel("없음");
    setChannel("FACE_TO_FACE");
    setAnswers({ ...DEMO_SURVEY_ANSWERS });
    setError(null);
  }

  async function createSession() {
    setPhase("creating");
    setError(null);
    const body: CreateSessionRequest = {
      productId,
      channel,
      ageBand,
      experienceLevel: experienceLevel || null,
      amountBand: amountBand || null,
      contractRef: contractRef.trim() || null,
      // 세트 버전은 typed 필드로 나간다 — surveyResult 맵에 얹던 우회를 걷었다(10.8, #61).
      surveySchemaVersion: SURVEY_SCHEMA_VERSION,
      surveyResult: toSurveyResult(answers),
    };
    try {
      const created = await post<SessionResponse>("/sessions", body);
      setSession(created);
      setPhase("created");
    } catch (e) {
      setError(describe(e));
      setPhase("editing");
    }
  }

  /* ── 생성 완료 — 판매자가 여기서 태블릿을 넘긴다 (설계 판단 ④) ──────────── */
  if (phase === "created" && session) {
    return (
      <main className="ss">
        <div className="ss__shell">
          <section className="ss__done" aria-live="polite">
            <h1 className="ss__done-title">세션이 생성되었습니다</h1>
            <p className="ss__done-id">
              세션 번호 <b>{session.sessionId}</b>
            </p>
            <p className="ss__alert ss__alert--info">
              <b>여기서 고객님께 화면을 넘겨 주세요.</b>
              되말하기 인터뷰는 고객이 직접 입력하셔야 합니다. 판매자가 대신 입력한 응답은
              이해도 근거로 쓸 수 없습니다.
            </p>
            <div className="ss__actions">
              <button
                type="button"
                className="ss__btn ss__btn--primary"
                onClick={() => navigate(`/interview/${session.sessionId}`)}
              >
                고객 인터뷰 시작
              </button>
              <button
                type="button"
                className="ss__btn ss__btn--ghost"
                onClick={() => navigate(`/judgment/${session.sessionId}`)}
              >
                판정 화면으로
              </button>
            </div>
          </section>
        </div>
      </main>
    );
  }

  /* ── 입력 ───────────────────────────────────────────────────────────────── */
  const busy = phase === "creating";
  const product = products.find((p) => p.productId === productId);

  return (
    <main className="ss">
      <div className="ss__shell">
        <header className="ss__head">
          <h1 className="ss__title">세션 시작</h1>
          <p className="ss__sub">
            계약 직전 이해도 검증 세션을 만듭니다. 고객 속성은 <b>구간 값</b>만 받습니다 —
            성명·주민등록번호·계좌번호는 이 시스템에 저장되지 않습니다.
          </p>
          <button type="button" className="ss__preset" onClick={applyDemoPreset} disabled={busy}>
            데모 입력값 채우기
          </button>
        </header>

        <section className="ss__card">
          <h2 className="ss__section">상품</h2>
          <div className="ss__field">
            <label className="ss__label" htmlFor="product">
              대상 상품
            </label>
            <select
              id="product"
              className="ss__control"
              value={productId}
              disabled={busy || products.length === 0}
              onChange={(e) => setProductId(e.target.value)}
            >
              {products.length === 0 && <option value="">상품 목록을 불러오는 중…</option>}
              {products.map((p) => (
                <option key={p.productId} value={p.productId}>
                  {p.name}
                </option>
              ))}
            </select>
            {/* 파싱 실패는 은폐하지 않는다 (E-EXT-03) — 조건을 못 읽은 상품은 검증도 못 한다. */}
            {product?.status === "parse_failed" && (
              <p className="ss__hint" role="alert">
                <b>이 상품은 문서를 아직 읽어내지 못했습니다.</b> 조건이 불완전해 이해도 검증
                결과를 신뢰할 수 없습니다.
              </p>
            )}
          </div>

          <div className="ss__field">
            <span className="ss__label" id="channel-label">
              판매 채널
            </span>
            <div className="ss__choices" role="radiogroup" aria-labelledby="channel-label">
              {CHANNELS.map((c) => (
                <button
                  key={c.value}
                  type="button"
                  role="radio"
                  aria-checked={channel === c.value}
                  className={`ss__choice ${channel === c.value ? "ss__choice--on" : ""}`}
                  disabled={busy}
                  onClick={() => setChannel(c.value)}
                >
                  {c.label}
                </button>
              ))}
            </div>
          </div>
        </section>

        <section className="ss__card">
          <h2 className="ss__section">고객 속성 (비식별)</h2>

          <div className="ss__grid">
            <div className="ss__field">
              <label className="ss__label" htmlFor="age">
                연령대 <span className="ss__req">필수</span>
              </label>
              <select
                id="age"
                className="ss__control"
                value={ageBand}
                disabled={busy}
                onChange={(e) => setAgeBand(e.target.value)}
              >
                <option value="">선택</option>
                {AGE_BANDS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
              <p className="ss__hint">생년월일·나이는 받지 않습니다.</p>
            </div>

            <div className="ss__field">
              <label className="ss__label" htmlFor="amount">
                가입금액 구간 <span className="ss__req">필수</span>
              </label>
              <select
                id="amount"
                className="ss__control"
                value={amountBand}
                disabled={busy}
                onChange={(e) => setAmountBand(e.target.value)}
              >
                <option value="">선택</option>
                {AMOUNT_BANDS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
              <p className="ss__hint">정확한 금액은 받지 않습니다.</p>
            </div>

            <div className="ss__field">
              <label className="ss__label" htmlFor="experience">
                투자 경험
              </label>
              <select
                id="experience"
                className="ss__control"
                value={experienceLevel}
                disabled={busy}
                onChange={(e) => setExperienceLevel(e.target.value)}
              >
                <option value="">선택</option>
                {EXPERIENCE_LEVELS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>

            <div className="ss__field">
              <label className="ss__label" htmlFor="contract-ref">
                계약건 참조번호
              </label>
              <input
                id="contract-ref"
                className="ss__control"
                value={contractRef}
                disabled={busy}
                placeholder="예: 2026-0825-0031"
                onChange={(e) => setContractRef(e.target.value)}
              />
              <p className="ss__hint">금융사 내부 계약 번호입니다. 고객 식별정보가 아닙니다.</p>
            </div>
          </div>

          {/* 설계 판단 ① — 스키마에 필드가 없다고 우회까지 막히는 건 아니다 */}
          {refPii.length > 0 && (
            <p className="ss__alert ss__alert--warn" role="status">
              <b>참조번호 칸에 {refPii.join("·")}로 보이는 내용이 있습니다.</b>
              이 값은 세션과 이해 기록 리포트에 그대로 남습니다. 계약 번호만 적어 주세요.
            </p>
          )}
        </section>

        <section className="ss__card">
          <h2 className="ss__section">적합성 설문</h2>
          <p className="ss__hint ss__hint--block">
            고객이 기재한 답변 그대로 입력합니다. 이 답변은 되말하기 발화와 대조되어
            <b> 설문과 실제 이해가 어긋나는지</b>를 판정하는 데 쓰입니다(F-DET-002).
          </p>

          {SURVEY_QUESTIONS.map((q, qi) => (
            <fieldset key={q.id} className="ss__q">
              <legend className="ss__q-text">
                <span className="ss__q-no">{qi + 1}</span>
                {q.text}
              </legend>
              <div className="ss__choices">
                {q.options.map((opt) => (
                  <button
                    key={opt}
                    type="button"
                    role="radio"
                    aria-checked={answers[q.id] === opt}
                    className={`ss__choice ${answers[q.id] === opt ? "ss__choice--on" : ""}`}
                    disabled={busy}
                    onClick={() => setAnswers((prev) => ({ ...prev, [q.id]: opt }))}
                  >
                    {opt}
                  </button>
                ))}
              </div>
            </fieldset>
          ))}
        </section>

        {error && (
          <p className="ss__alert ss__alert--error" role="alert">
            <b>세션을 만들지 못했습니다</b>
            {error} 입력하신 내용은 그대로 남아 있습니다.
          </p>
        )}

        <div className="ss__foot">
          {!canSubmit && phase === "editing" && (
            <p className="ss__hint">
              {productId === "" && "상품을 선택해 주세요. "}
              {ageBand === "" && "연령대를 선택해 주세요. "}
              {amountBand === "" && "가입금액 구간을 선택해 주세요. "}
              {unanswered > 0 && `설문 ${unanswered}개 문항이 남았습니다.`}
            </p>
          )}
          <button
            type="button"
            className="ss__btn ss__btn--primary"
            onClick={createSession}
            disabled={!canSubmit}
          >
            {busy ? "생성 중…" : "세션 생성"}
          </button>
        </div>
      </div>
    </main>
  );
}

/** ApiRequestError 를 판매자가 읽을 수 있는 문장으로. */
function describe(e: unknown): string {
  if (e instanceof ApiRequestError) {
    switch (e.code) {
      case "NOT_FOUND":
        return "선택한 상품을 찾을 수 없습니다. 문서 추출이 끝났는지 확인해 주세요.";
      case "VALIDATION_ERROR":
      case "MALFORMED_REQUEST":
        return "입력값을 다시 확인해 주세요.";
      case "ILLEGAL_STATE_TRANSITION":
        return "이미 진행 중인 세션입니다.";
      default:
        return "잠시 후 다시 시도해 주세요.";
    }
  }
  return "알 수 없는 오류입니다.";
}
