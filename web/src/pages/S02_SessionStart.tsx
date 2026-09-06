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
 *    ❗**그 두 버튼은 새 창으로 연다.** 같은 탭에서 옮기면 판매자 화면이 고객 화면으로
 *    덮이고, 창구에서 실제로 벌어지는 일 — *한 화면을 둘이 번갈아 쓰는 것* — 이 그대로
 *    재현된다. 대필 방지 안내를 적어 두고 화면은 대필하기 좋은 모양인 셈이다. 새 창이면
 *    고객 화면은 고객 앞의 창이고 판매자는 자기 창(세션 번호·판정)을 그대로 들고 있다.
 *    판정 화면은 애초에 판매자 것이라 더 그렇다 — 세션을 연 창을 잃지 않아야 한다.
 *    팝업이 막히면 같은 탭 이동으로 떨어진다(그때는 예전과 같이 동작한다).
 *
 * ⑤ **가입금액 구간을 화면에서 필수로 건다 — 계약은 nullable 그대로.**
 *    `amountBand` 는 계약상 선택이지만 취약 가중의 입력이다. 데모 고객(65세·5,000만 원)이
 *    `ageBand"60대"`3 + `amountBand"5천만원대"`1 = **정확히 임계값 4** 라서, 이 칸을
 *    건너뛰면 3점이 되어 **취약으로 분류되지 않는다** — 고령자 모드가 안 켜지고 재설명이
 *    일반 문면으로 나간다. 에러도 로그도 없고 시연만 밋밋해진다(decision-log 10.12).
 *    화면이 계약보다 엄격한 것은 흔하고, 여기서는 그게 맞다.
 *
 * ⑥ **"대상 상품 보기" 는 시스템이 읽어낸 것을 보여준다 — 문서 뷰어가 아니다.**
 *    판매자가 세션을 열기 전에 확인해야 하는 것은 *"이 상품 문서가 있느냐"* 가 아니라
 *    **"이 시스템이 그 문서에서 무엇을 조건으로 뽑았느냐"** 다. 뽑힌 항목이 곧 인터뷰
 *    질문이 되고 채점 루브릭의 대상이 되므로, 여기서 틀린 것을 못 보면 세션 하나가 통째로
 *    틀린 근거 위에서 돌아간다. 그래서 모달은 **항목별 조건 원문과 그 원문이 문서 어디에
 *    있는지(페이지·오프셋)** 를 보여준다 — 판매자가 손에 든 설명서와 눈으로 대조할 수 있는
 *    최소 단위다. 추출 실패 항목도 숨기지 않는다(E-EXT-03).
 *
 *    **PDF 원본을 이제 그린다** (#433 닫힘 — `docker-compose` 가 server 에 `data/documents`
 *    를 물렸다). alpha 에서 `GET /products/{id}/document` 가 200·`application/pdf` 로
 *    확인됐다(2026-09-06). 그 전 상태(계약은 있는데 배포가 안 닿던 것)는 #417 → #433
 *    커밋 이력에 남아 있다 — 다시 안 막히면 여기서 더 안 적는다.
 *
 *    응답이 공통 봉투가 아니라 **PDF 바이트 그대로**라(`openapi.yaml:66`), `<a href>` 가
 *    `client.ts` 를 안 타는 자리다 — S-07 미리보기와 같은 이유로 `BASE` 를 화면이 직접
 *    붙인다(`client.ts` 의 `BASE` javadoc). `inline` 응답이라 `#page=N` 앵커로 브라우저
 *    PDF 뷰어가 그 페이지로 바로 연다 — 서버가 페이지별 엔드포인트를 따로 낼 필요가 없다.
 *    그래서 항목별 "p.N" 표시를 그 항목의 `sourceSpan.page` 로 앵커를 건 링크로 바꾼다 —
 *    판매자가 조건 하나하나를 원본 그 페이지와 바로 대조하게, 문서 앞장부터 찾게 두지 않는다.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiRequestError, BASE, get, post } from "../api/client";
import type {
  Channel,
  CreateSessionRequest,
  ProductSummary,
  RiskItem,
  SessionResponse,
} from "../api/types";
import { detectPii } from "../lib/pii";
import { AGE_BANDS, AMOUNT_BANDS, CHANNELS, EXPERIENCE_LEVELS } from "../lib/sessionAttrs";
import { SURVEY_QUESTIONS, SURVEY_SCHEMA_VERSION, toSurveyResult } from "../lib/survey";
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

  /* ── 대상 상품 보기 (설계 판단 ⑥) ─────────────────────────────────────── */
  const [docOpen, setDocOpen] = useState(false);
  const [docItems, setDocItems] = useState<RiskItem[] | null>(null);
  const [docLoading, setDocLoading] = useState(false);
  const [docError, setDocError] = useState<string | null>(null);
  /** 닫을 때 포커스를 되돌릴 곳. 안 되돌리면 키보드 사용자가 문서 맨 위로 튕긴다. */
  const docTriggerRef = useRef<HTMLButtonElement>(null);

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

  /**
   * 새 창으로 연다. 막히면 같은 탭으로 간다.
   *
   * `noopener` 를 붙이는 이유는 보안 관례이기도 하지만, 붙이면 브라우저가 `null` 을
   * 돌려주는 경우가 있어 **성공/차단을 반환값으로 못 가른다.** 그래서 팝업 차단 판정은
   * `noreferrer` 없이 열어 본 뒤 `null` 인지로만 본다 — 열렸으면 그 핸들의 `opener` 를
   * 직접 끊는다.
   */
  const openAside = useCallback((path: string) => {
    const win = window.open(path, "_blank");
    if (!win) {
      navigate(path);   // 팝업 차단 — 아무 데도 못 가는 것보다 같은 탭이 낫다
      return;
    }
    win.opener = null;
  }, [navigate]);

  const closeDoc = useCallback(() => {
    setDocOpen(false);
    docTriggerRef.current?.focus();
  }, []);

  /* 상품을 바꾸면 이전 상품의 항목이 남아 있으면 안 된다 — 그대로 두면 A 상품을 고르고
     B 상품의 조건을 확인한 채로 세션을 연다. */
  useEffect(() => {
    setDocItems(null);
    setDocError(null);
  }, [productId]);

  useEffect(() => {
    if (!docOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") closeDoc();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [docOpen, closeDoc]);

  async function openDoc() {
    setDocOpen(true);
    if (docItems || !productId) return;      // 이미 받아 뒀으면 다시 부르지 않는다
    setDocLoading(true);
    setDocError(null);
    try {
      const res = await get<{ items: RiskItem[] }>(`/products/${productId}/risk-items`);
      setDocItems(res.items ?? []);
    } catch (e) {
      setDocError(describe(e));
    } finally {
      setDocLoading(false);
    }
  }

  const refPii = useMemo(() => detectPii(contractRef), [contractRef]);
  const unanswered = SURVEY_QUESTIONS.filter((q) => !answers[q.id]).length;
  const canSubmit =
    productId !== "" &&
    ageBand !== "" &&
    amountBand !== "" &&   // 설계 판단 ⑤ — 계약은 nullable 이지만 화면에서 필수로 건다
    unanswered === 0 &&
    phase === "editing";

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
            <h1 className="ss__done-title">세션을 만들었어요</h1>
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
                onClick={() => openAside(`/interview/${session.sessionId}`)}
              >
                고객 인터뷰 시작
              </button>
              <button
                type="button"
                className="ss__btn ss__btn--ghost"
                onClick={() => openAside(`/judgment/${session.sessionId}`)}
              >
                판정 화면으로
              </button>
            </div>
            <p className="ss__hint">
              두 화면 모두 <b>새 창</b>으로 열립니다. 이 창은 세션 번호를 그대로 들고
              있으니 닫지 마세요.
            </p>
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
          <h1 className="ss__title">고객검사 시작</h1>
          {/* 심사용 목차(S-00)로 가는 **유일한 링크**다. 목차에 있는 화면 5개가 세션ID 를
              경로에 받아 주소만으로는 못 닿으므로, 심사자가 목차를 못 찾으면 절반이 안
              열린다. 이 자리인 이유는 두 가지다 — ① 브랜드 바에 달 수 없다. 그건 전
              화면에 뜨고 그중 S-03 은 고객이 답변 도중이라, 습관적으로 눌렀다가 세션 밖으로
              나가는 사고가 정확히 BrandBar 가 링크를 안 거는 이유다. ② S-02 는 태블릿을
              넘기기 **전**의 판매자 화면이라 그 사고가 없다. 그래서 목차 진입은 여기 하나다. */}
          <Link className="ss__navlink" to="/admin">
            심사용 화면 목록
          </Link>
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
            <button
              type="button"
              ref={docTriggerRef}
              className="ss__linkbtn"
              disabled={!productId}
              onClick={openDoc}
            >
              대상 상품 보기
            </button>

            {/* 파싱 실패는 은폐하지 않는다 (E-EXT-03) — 조건을 못 읽은 상품은 검증도 못 한다. */}
            {product?.status === "parse_failed" && (
              <p className="ss__hint" role="alert">
                <b>아직 문서를 읽어내지 못한 상품이에요.</b> 조건이 불완전해 이해도 검증
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
            </div>
          </div>

          {/* 설계 판단 ① — 스키마에 필드가 없다고 우회까지 막히는 건 아니다 */}
          {refPii.length > 0 && (
            <p className="ss__alert ss__alert--warn" role="status">
              <b>참조번호 칸에 {refPii.join("·")}로 보이는 내용이 있습니다.</b>
              계약 번호만 적어 주세요. 리포트에 그대로 남아요.
            </p>
          )}
        </section>

        <section className="ss__card">
          <h2 className="ss__section">적합성 설문</h2>
          <p className="ss__hint ss__hint--block">
            고객이 기재한 답변 그대로 입력합니다. 이 답변은 되말하기 발화와 대조되어
            <b> 설문과 실제 이해가 어긋나는지</b>를 판정하는 데 써요.
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
            <b>세션을 만들지 못했어요</b>
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

      {docOpen && (
        <ProductDocumentModal
          product={product}
          items={docItems}
          loading={docLoading}
          error={docError}
          onClose={closeDoc}
        />
      )}
    </main>
  );
}

/**
 * 대상 상품 모달 — 설계 판단 ⑥.
 *
 * 판매자가 손에 든 설명서와 **눈으로 대조**할 수 있게, 항목별로 조건 원문과 그 원문의
 * 위치(페이지·오프셋)를 나란히 둔다. 원문을 요약하지 않는다 — 요약하면 대조가 안 된다(P6).
 */
function ProductDocumentModal({
  product,
  items,
  loading,
  error,
  onClose,
}: {
  product: ProductSummary | undefined;
  items: RiskItem[] | null;
  loading: boolean;
  error: string | null;
  onClose: () => void;
}) {
  const closeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => { closeRef.current?.focus(); }, []);

  const extracted = items?.filter((i) => i.status === "extracted") ?? [];
  const failed = items?.filter((i) => i.status !== "extracted") ?? [];

  return (
    <div className="ss__overlay" onClick={onClose}>
      {/* 바깥 클릭으로 닫되, 안쪽 클릭이 새어 올라가면 내용을 누를 때마다 닫힌다. */}
      <div
        className="ss__modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="doc-title"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="ss__modal-head">
          <div>
            <h2 className="ss__modal-title" id="doc-title">
              {product?.name ?? "대상 상품"}
            </h2>
            <p className="ss__modal-meta">
              <code>{product?.productId}</code>
              {product?.productType && <> · {product.productType}</>}
              {items && <> · 이해항목 {items.length}개</>}
            </p>
          </div>
          <button type="button" ref={closeRef} className="ss__modal-x" onClick={onClose}
                  aria-label="닫기">
            닫기
          </button>
        </header>

        <div className="ss__modal-body">
          <p className="ss__hint ss__hint--block">
            이 시스템이 상품 문서에서 <b>조건으로 읽어낸 것</b>입니다. 인터뷰 질문과 채점
            기준이 이 원문에서 나오므로, 설명서와 다르면 세션을 열기 전에 잡아야 합니다.
          </p>

          {/* 원본 전체 보기. 항목별 링크(아래)가 각 조건의 페이지로 바로 열어 주지만,
              항목이 전부 추출 실패이거나 그냥 문서 전체를 훑고 싶을 때를 위해 따로 둔다. */}
          {product && (
            <a
              className="ss__modal-doclink"
              href={`${BASE}/products/${product.productId}/document`}
              target="_blank"
              rel="noreferrer"
            >
              원본 PDF 열기
            </a>
          )}

          {loading && <p className="ss__hint">불러오는 중…</p>}
          {error && <p className="ss__alert ss__alert--error" role="alert">{error}</p>}

          {items && items.length === 0 && !loading && (
            <p className="ss__alert ss__alert--warn" role="status">
              <b>읽어낸 조건이 없어요.</b>
              지금 세션을 열면 물어볼 항목이 없어요.
            </p>
          )}

          {extracted.map((it) => (
            <article key={it.itemId} className="ss__doc-item">
              <div className="ss__doc-item-head">
                <h3 className="ss__doc-item-name">{it.name}</h3>
                <span className={`ss__badge ${it.importance === "required" ? "ss__badge--req" : ""}`}>
                  {it.importance === "required" ? "필수" : "권장"}
                </span>
              </div>
              <blockquote className="ss__doc-quote">{it.condition.valueText}</blockquote>
              <p className="ss__doc-span">
                {it.itemId} ·{" "}
                {/* #page=N 앵커 — inline PDF 라 브라우저 뷰어가 그 페이지로 바로 연다
                    (서버가 페이지별 엔드포인트를 따로 낼 필요가 없다, openapi.yaml:66). */}
                {product ? (
                  <a
                    className="ss__doc-link"
                    href={`${BASE}/products/${product.productId}/document#page=${it.condition.sourceSpan.page}`}
                    target="_blank"
                    rel="noreferrer"
                  >
                    p.{it.condition.sourceSpan.page}
                  </a>
                ) : (
                  `p.${it.condition.sourceSpan.page}`
                )}
                {" "}· 위치 {it.condition.sourceSpan.start}–{it.condition.sourceSpan.end}
              </p>
            </article>
          ))}

          {/* 추출 실패를 숨기지 않는다 (E-EXT-03) — 못 읽은 조건은 검증도 안 된다. */}
          {failed.map((it) => (
            <article key={it.itemId} className="ss__doc-item ss__doc-item--failed">
              <div className="ss__doc-item-head">
                <h3 className="ss__doc-item-name">{it.name}</h3>
                <span className="ss__badge ss__badge--fail">추출 실패</span>
              </div>
              <p className="ss__doc-span">
                {it.itemId} — 조건 원문을 못 읽었습니다. 이 항목은 검증되지 않습니다.
              </p>
            </article>
          ))}
        </div>
      </div>
    </div>
  );
}

/** ApiRequestError 를 판매자가 읽을 수 있는 문장으로. */
function describe(e: unknown): string {
  if (e instanceof ApiRequestError) {
    switch (e.code) {
      case "NOT_FOUND":
        return "상품을 찾을 수 없어요. 문서 추출이 끝났는지 확인해 주세요.";
      case "VALIDATION_ERROR":
      case "MALFORMED_REQUEST":
        return "입력값을 확인해 주세요.";
      case "ILLEGAL_STATE_TRANSITION":
        return "이미 진행 중인 세션이에요.";
      default:
        return "잠시 후 다시 시도해 주세요.";
    }
  }
  return "알 수 없는 오류예요.";
}
