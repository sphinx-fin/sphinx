/**
 * web ↔ server 계약 타입. 기준: `contracts/openapi.yaml` (소유: 강희진).
 *
 * 주의 — 명명 규약이 경계마다 다르다:
 *   web  ↔ server      : camelCase   ← 이 파일
 *   server ↔ ai-service : snake_case  ← contracts/judgment.schema.json (프론트는 안 본다)
 * 스키마가 바뀌면 소유자 승인 후 이 파일도 같이 갱신한다.
 */

/* ── 공통 응답 봉투 ─────────────────────────────────────────────────────────── */

/**
 * `GlobalExceptionHandler` 가 내려주는 에러 코드. **`openapi.yaml` 의 `ApiError.code` enum 과
 * 같은 집합이어야 한다** — 서버가 목록에 없는 코드를 내보내면 화면이 조용히 default 분기로
 * 떨어진다(계약 주석이 직접 경고하는 지점). 서버 쪽은 `ErrorCodeContractTest` 가 핸들러와
 * enum 의 일치를 강제하지만, 이 유니온은 사람이 맞춰야 한다.
 *
 * 400 두 개를 코드로 가른 이유(결정 1.4): 화면 처리가 다르다 —
 * `REEXPLAIN_NOT_ELIGIBLE` 은 조용히 다음 항목으로, `REVERIFY_EXHAUSTED` 는 "판정으로
 * 넘어간다"를 고객에게 알려야 한다. 문면 파싱으로 가르면 서버 문구가 바뀔 때 조용히 깨진다.
 *
 * 502 세 종류도 서로 다르다: `EVIDENCE_REQUIRED` 는 상류가 근거 없는 판정을 준 계약 위반(P4)
 * 이라 재시도해도 같고, `AI_SERVICE_UNAVAILABLE` 은 일시 장애라 재시도가 의미 있다.
 */
export type ErrorCode =
  | "NOT_FOUND"                 // 404 세션 등 리소스 없음
  | "VALIDATION_ERROR"          // 400 @Valid 실패
  | "MALFORMED_REQUEST"         // 400 본문 파싱 실패(잘못된 JSON·허용되지 않은 enum)
  | "ILLEGAL_STATE_TRANSITION"  // 409 허용되지 않은 세션 상태 전이
  | "REEXPLAIN_NOT_ELIGIBLE"    // 400 재설명 대상 아님(판정 없음 또는 이미 이해 U1)
  | "REVERIFY_EXHAUSTED"        // 400 재검증 상한 도달 — 판정으로 진행
  | "EVIDENCE_REQUIRED"         // 502 P4 위반(근거 없는 판정) — 상류 ai-service 계약 위반
  | "AI_SERVICE_UNAVAILABLE"    // 502 ai-service 호출 실패(non-2xx·연결 오류·미구현)  ← PR #67
  | "OVERRIDE_NOT_ELIGIBLE"     // 409 적색 아님·승인 대기 아님 — 오버라이드 불가        ← PR #68
  | "INTERNAL_ERROR";           // 500 서버 내부 오류

export interface ApiError {
  code: ErrorCode;
  message: string;
  timestamp: string;
}

export interface ApiResponse<T> {
  success: boolean;
  data: T | null;
  error: ApiError | null;
}

/* ── 도메인 ────────────────────────────────────────────────────────────────── */

/** 이해도 4단계 (명세서 0.5): 이해·부분이해·미이해·오해. */
export type Grade = "U1" | "U2" | "U3" | "U4";

/** 신호등 3색 (세션 단위, 룰 엔진 산출). */
export type Signal = "GREEN" | "YELLOW" | "RED";

export type Channel = "FACE_TO_FACE" | "MOBILE" | "TM";

export type SessionState =
  | "CREATED"
  | "IN_PROGRESS"
  | "RE_EXPLAIN"
  | "RE_VERIFY"
  | "JUDGED"
  | "CLOSED"
  | "ABORTED";

/**
 * 세션 생성 입력. **전부 비식별** — 성명·주민번호·계좌번호 필드는 존재하지 않는다 (P3).
 * 이 인터페이스에 식별자 필드를 추가하는 변경은 명세서 F-INT-001 제약 위반이다.
 */
export interface CreateSessionRequest {
  productId: string;
  channel: Channel;
  /** 10세 구간 (예: "60대"). 생년월일·나이 원값 금지. */
  ageBand: string;
  experienceLevel?: string | null;
  /** 가입금액 구간. 원 단위 금액 금지. */
  amountBand?: string | null;
  /** 금융사 측 계약건 참조번호 — 고객 식별자가 아니다. */
  contractRef?: string | null;
  /**
   * 적합성 설문 문항 세트 버전(`lib/survey.ts` 의 `SURVEY_SCHEMA_VERSION`).
   *
   * 이전에는 자리가 없어 `surveyResult` 맵에 `_surveySchemaVersion` 으로 얹어 우회했다.
   * #61 로 서버에 typed 필드가 생겨(`CreateSessionRequest.surveySchemaVersion` · `Session`)
   * 우회를 걷었다 — 이제 `surveyResult` 에는 `SUIT-` 문항만 들어간다 (decision-log 10.8).
   */
  surveySchemaVersion?: string | null;
  /** 적합성 설문 결과. **`SUIT-` 로 시작하는 문항 키만** 들어간다(메타데이터 금지). */
  surveyResult?: Record<string, string> | null;
}

export interface SessionResponse {
  sessionId: string;
  state: SessionState;
  productId: string;
  contractRef?: string | null;
}

/**
 * P4 — 근거 없는 판정은 무효. `evidence`는 서버 `domain/Judgment` 컴팩트 생성자가
 * 강제하므로 optional이 아니다. 화면은 근거 없이 판정을 그리지 않는다.
 */
export interface Judgment {
  itemId: string;
  grade: Grade;
  /** 0~1. 낮으면 게이트가 R-05로 황색 강등한다 (판정 강등은 서버 몫). */
  confidence: number;
  evidence: {
    /** 고객 발화 인용. */
    utteranceQuote: string;
    /** 매칭된 루브릭 조항. */
    rubricClause: string;
  };
  /** 판정 사유 1문장. */
  reason: string;
  /** F-DET-001 매칭 시 오해 유형ID (예: M01-PRINCIPAL-GUARANTEE). */
  misconceptionType?: string | null;
}

/** 게이트 판정. `ruleTrace`는 발화한 룰 ID(예: R-01) — 감사 대상이므로 화면에도 노출한다. */
export interface GateResult {
  signal: Signal;
  ruleTrace: string[];
}

/**
 * F-EXT-002 필수 이해항목.
 *
 * **필드명은 camelCase 다.** `contracts/risk_item.schema.json` 은 snake_case 지만 그건
 * server ↔ ai-service 계약이고, 웹으로 나오는 것은 Java `domain/RiskItem`(camelCase)을 전역
 * Jackson 이 직렬화한 값이다. `openapi.yaml` 의 `RiskItemsResponse` 도 "snake_case 계약의
 * camelCase 웹 표현"이라고 명시한다.
 *
 * 이전 정의는 snake_case 였다. **타입은 통과하는데 런타임 값만 undefined** 가 되는 종류라
 * `strict` 로도 안 잡히고 화면이 조용히 비었다 (decision-log 10.18).
 */
export interface RiskItem {
  itemId: string;
  productId: string;
  name: string;
  importance: "required" | "recommended";
  condition: {
    /** 원문 인용만 허용 (P6). */
    valueText: string;
    /** 페이지 **상대** 오프셋 `[start, end)` — 문서 전역 오프셋이 아니다. */
    sourceSpan: { page: number; start: number; end: number };
  };
  /** 추출 실패는 은폐하지 않고 화면에 노출한다 (E-EXT-03). */
  status: "extracted" | "extraction_failed";
}

/**
 * F-INT-002 다음 질문.
 *
 * `index`/`total`/`done` 은 **서버가 준다**(#49 로 확정). 화면이 risk-items 개수로 분모를
 * 보완하던 코드는 지웠다 — 서버가 물어볼 항목 수와 추출 항목 수는 다를 수 있고, 어긋나면
 * 조용히 틀린 진행률이 나온다(계약 주석의 경고 그대로).
 *
 * `done === true` 면 `itemId`·`question` 은 **null** 이다. 인터뷰 종료 판단의 유일한 근거는
 * `done` 이며, `answeredCount >= total` 로 추정하지 않는다.
 */
export interface NextQuestion {
  /** `done=true` 면 null. */
  itemId: string | null;
  /** `done=true` 면 null. */
  question: string | null;
  /** 1-based. `done=true` 면 `total` 과 같다. */
  index: number;
  /** 서버가 물어볼 항목 수. 추출 항목 수와 다를 수 있다. */
  total: number;
  /** 더 물을 항목이 없다. */
  done: boolean;
}

/**
 * F-INT-003 응답 제출.
 *
 * `inputMeta`는 F-DET-002 코칭 정황 스코어의 입력이다. 명세 F-DET-002 단서:
 * 이 값은 **판정에 직접 반영되지 않고** 세션 메타데이터로만 기록되어 지점 단위
 * 통계 이상치 탐지에 쓰인다. 화면이 이걸로 고객을 막거나 표시하면 안 된다.
 */
export interface AnswerRequest {
  itemId: string;
  text: string;
  inputMeta: InputMeta;
}

export interface InputMeta {
  /** 질문 표시 → 첫 키 입력까지(ms). 즉답 극단값이 코칭 정황 신호. */
  firstKeystrokeDelayMs: number;
  /** 첫 키 입력 → 제출까지(ms). */
  totalInputMs: number;
  /** 붙여넣기 발생 여부 — 대필·메모 제시 정황. */
  pasteDetected: boolean;
  backspaceCount: number;
  /** 공백 제외 글자 수. E-INT-02(5자 미만 재요청) 판단 근거를 서버와 공유한다. */
  charCount: number;
  /** 고령자 모드로 응답했는지 — 재설명 생성(F-INT-004) 입력. */
  elderlyMode: boolean;
}

/**
 * 어느 실제 지수 구간을 썼는지 (F-SIM-001). 명세 8절 S-04 의 "역사 구간 라벨"이자
 * P2 재현성의 실물 근거다 — 화면에 박아 두면 "이 수치가 어디서 나왔는지"가 보인다.
 */
export interface PathMeta {
  /** 계약일. */
  startDate: string;
  /** 상환일(조기상환 또는 만기). */
  endDate: string;
  /** 기초자산 키(sp500·nikkei225·eurostoxx50). */
  underlyings: string[];
  /** 만기평가일 최저 종목 — 손실 판정의 기준. */
  worstUnderlying: string;
  /** 그 종목의 최초기준가격 대비 비율(예: 0.507). */
  worstFinal: number;
  /** 낙인 배리어를 하회한 적이 있는가. */
  knockedIn: boolean;
}

/** 시뮬레이터 시나리오 1건 (F-SIM-001). 금액은 원 단위 정수. */
export interface SimScenario {
  /**
   * 화면 배치·정렬 키. **표시 라벨이 아니다** — 사람에게 보일 문면은 `name` 을 쓴다.
   * 계약이 이 키를 준 이유가 정렬을 문자열 매칭으로 하지 말라는 것이다.
   */
  severity: "worst" | "mid" | "best";
  /** 정확한 상환 유형. `early_1`..`early_5` | `maturity` | `loss`. */
  result: string;
  /** 표시 문면(예: 낙인 45% 하회 후 만기 손실). */
  name: string;
  /** 상환금액(원). 원 단위 절사. */
  payout: number;
  /** 손익(원). 음수는 손실. */
  pnl: number;
  /** 역사 전 구간에서 이 전개가 발생한 비중 0~1. */
  share?: number | null;
  pathMeta: PathMeta;
}

/**
 * F-SIM-001 시뮬레이션 요청.
 *
 * 금액은 **body 로만** 보낸다. 서버에 기본값이 없으므로(결정 1.19) 금액이 빠지면 400 이고,
 * 그게 의도다 — 기본값이 있으면 프론트 버그가 조용히 그럴듯한 숫자로 덮인다.
 */
export interface SimulateRequest {
  /** 가입금액(원, 양수). */
  amount: number;
}

export interface SimulateResponse {
  /** `severity` 별로 정확히 1건씩, 총 3건. */
  scenarios: SimScenario[];
  /** `data/timeseries/VERSION` 의 snapshot(예: 2026-08-24). **화면에 표시한다**(P2). */
  timeseriesVersion: string;
  /** 상품 조건의 출처. */
  productName: string;
}

/**
 * S-02 상품 선택 목록 항목 (`GET /products`).
 *
 * 표시명은 **가명**이다 — 기획서가 "데모와 제출물에서는 상품명과 발행사를 가명 처리하고
 * 조건만 인용한다"로 못박았고, 서버 `MockData.PRODUCTS` 도 같은 문면을 쓴다.
 */
export interface ProductSummary {
  productId: string;
  name: string;
  productType: "ELS" | "VARIABLE_INSURANCE";
  status: "parsed" | "parse_failed";
}

export interface HeatmapCell {
  product: string;
  item: string;
  /** 오해율 0~1. 표본 부족(n<30) 셀은 서버가 마스킹해 null로 내려준다. */
  misrate: number | null;
  n: number;
}

export interface HeatmapResponse {
  /** 합성 세션 기반이면 true — 화면에 워터마크를 상시 노출해야 한다 (F-DSH-001). */
  synthetic: boolean;
  cells: HeatmapCell[];
}
