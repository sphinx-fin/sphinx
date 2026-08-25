/**
 * web ↔ server 계약 타입. 기준: `contracts/openapi.yaml` (소유: 강희진).
 *
 * 주의 — 명명 규약이 경계마다 다르다:
 *   web  ↔ server      : camelCase   ← 이 파일
 *   server ↔ ai-service : snake_case  ← contracts/judgment.schema.json (프론트는 안 본다)
 * 스키마가 바뀌면 소유자 승인 후 이 파일도 같이 갱신한다.
 */

/* ── 공통 응답 봉투 ─────────────────────────────────────────────────────────── */

/** GlobalExceptionHandler가 내려주는 에러 코드. */
export type ErrorCode =
  | "NOT_FOUND"
  | "VALIDATION_ERROR"
  | "MALFORMED_REQUEST"
  | "ILLEGAL_STATE_TRANSITION"
  | "INTERNAL_ERROR";

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
  surveyResult?: Record<string, unknown> | null;
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

export interface RiskItem {
  item_id: string;
  product_id: string;
  name: string;
  importance: "required" | "recommended";
  condition: {
    /** 원문 인용만 허용 (P6). */
    value_text: string;
    /** 페이지 **상대** 오프셋 `[start, end)` — 문서 전역 오프셋이 아니다. */
    source_span: { page: number; start: number; end: number };
  };
  /** 추출 실패는 은폐하지 않고 화면에 노출한다 (E-EXT-03). */
  status: "extracted" | "extraction_failed";
}

/**
 * F-INT-002 다음 질문.
 *
 * TODO(강희진, PR #16 리뷰 2번): 서버 응답이 현재 `{itemId, question}` 뿐이라
 * 진행 표시(명세 8절 S-03)와 인터뷰 종료 시점을 알 수 없다. `index`/`total`/`done`이
 * 확정되면 optional을 제거한다. 그때까지 화면은 risk-items 개수로 total을 보완한다.
 */
export interface NextQuestion {
  itemId: string;
  question: string;
  index?: number;
  total?: number;
  done?: boolean;
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

/** 시뮬레이터 시나리오 1건 (F-SIM-001). 금액은 원 단위 정수. */
export interface SimScenario {
  name: string;
  payout: number;
  pnl: number;
}

export interface SimulateResponse {
  scenarios: SimScenario[];
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
