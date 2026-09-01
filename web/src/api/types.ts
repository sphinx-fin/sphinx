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

/** F-GTE-002 오버라이드 진행 상태. `NONE` 이 값으로 나간다 — 부재가 아니다. */
export type OverrideStatus = "NONE" | "PENDING_APPROVAL" | "APPROVED";

export interface SessionResponse {
  sessionId: string;
  state: SessionState;
  productId: string;
  contractRef?: string | null;

  /* ── F-GTE-002 ────────────────────────────────────────────────────────────
   * 계약에 이미 있었는데(#116, `openapi.yaml` SessionResponse) 이 타입만 따라오지
   * 않았다. 서버는 네 필드를 실어 보내는데 화면 타입에 없으니 **런타임에는 있는 값을
   * 화면이 못 쓰는** 상태였다 — S-06 을 붙이면서 드러났다.
   *
   * `overrideStatus` 는 **required 다.** 계약도 required 로 잡았다(nullable 아님).
   * optional 로 두면 화면이 "오버라이드 없음"을 `!overrideStatus` 로 읽게 되고,
   * 그러면 **"없다" 와 "안 실렸다" 가 같아진다** — 필드가 빠진 응답을 "요청 없음"으로
   * 읽어 승인 대기 세션에 요청 화면을 띄운다. 값으로 비교하게 강제한다(#125 리뷰).   */
  overrideStatus: OverrideStatus;
  /** 판매자가 적은 진행 사유(30자 이상, ADR-002). 요청 전이면 null. */
  overrideReason?: string | null;
  /** 승인한 MGR. 승인 전이면 null. */
  overrideApprover?: string | null;
  /** 승인 시각(ISO-8601). 승인 전이면 null. */
  overrideDecidedAt?: string | null;
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
  /**
   * ❗`misconceptionType` 과 `escalate` 는 여기 없다 — 이슈 #144 · #160.
   * **다시 넣지 않는다.**
   *
   * 둘 다 불공정영업 신호 그 자체다. `M08-TYING` 이면 COMPL 로 사건이 나가는데
   * (F-GTE-003), 같은 값을 판매자 화면이 받으면 무엇이 탐지되는지 알게 되고 문면만
   * 바꿔 같은 영업을 한다(기획 7-4 역이용 방지). `rbac_policy.yaml` 이
   * `signal:unfair:read` 를 COMPL 로 좁혀 둔 것과 같은 이유다.
   *
   * `escalate` 는 **유형ID 를 몰라도 같은 것을 알려준다** — 서버가 발행을 판단하는 값이
   * 그대로 boolean 이라, 유형을 안 보여도 *"이번 발화가 걸렸다"* 만으로 문면을 바꿀 수
   * 있다. 그래서 가리는 근거가 `misconceptionType` 과 같고, 같이 적어 둔다.
   *
   * 서버가 `#147` 로 `JudgmentView` 에서 뺐으므로 응답 JSON 에도 오지 않는다. 선언만
   * 남겨 두면 옵셔널이라 타입은 통과하고 **다음 사람이 화면에 다시 그린다** — 실제로
   * `S05_Judgment.tsx` 가 그러고 있었다. 신호를 조건부로 가리지도 않는다: 신호일 때만
   * 빼면 그 부재가 곧 신호다.
   *
   * 내부 계약(`judgment.schema.json`)에는 그대로 있다 — 재설명 프롬프트와 불변 기록이
   * 쓴다. 없어진 것은 판매자 화면으로 가는 이 표현들이다.
   *
   * ❗**이 타입이 미러하는 것은 계약이 아니라 `JudgmentView` 다.** 계약에 필드가 늘어도
   * 여기는 안 늘어나는 것이 정상이고, 늘릴지 말지는 *"판매자가 봐도 되는가"* 한 질문으로
   * 정해진다. 서버 쪽은 `JudgmentViewFieldsTest` 가 허용목록을 **이름으로** 잠가서 새
   * 필드가 붙으면 깨지는데, **web 에는 테스트 러너가 없어**(결정 10.59) 같은 그물이
   * 없다 — 여기서는 이 문단이 그 자리다. 더하기 전에 `JudgmentView` 의 javadoc 을 읽는다.
   */
}

/**
 * 게이트 **확정** 판정 (`POST /sessions/{id}/judge`).
 * `ruleTrace`는 발화한 룰 ID(예: R-01) — 감사 대상이므로 화면에도 노출한다.
 */
export interface GateResult {
  signal: Signal;
  ruleTrace: string[];
}

/**
 * F-GTE-004 이해 기록 리포트 (판매자용 전문).
 *
 * `GET /sessions/{id}/report` · `POST /sessions/{id}/report` 가 같은 모양을 돌려준다.
 * **고객 교부용 요약(`/report/summary`)은 다른 문서다** — 계약이 스키마를 나눈 근거가
 * 둘이 다르다는 것이므로 이 타입을 그쪽에 재사용하지 않는다.
 */
export interface ReportResponse {
  reportId: string;
  sessionId: string;
  generatedAt: string;
  /**
   * 리포트 **내용**의 sha256 (CanonicalJson). 체인 항목 해시가 아니다 — 항목 해시는 앞
   * 항목과 순번에 의존해서 문서 한 장을 든 고객이 재계산할 수 없다.
   *
   * **자르거나 숨기지 않는다.** 고객이 받은 문서를 나중에 대조하는 값이라, 앞 8자만
   * 보이면 대조가 성립하지 않는다.
   */
  contentHash: string;
  /**
   * ❗**PDF 생성이 붙기 전까지 `null` 이다.** 계약이 그렇게 정한 이유가 여기 있다 —
   * 값을 채우면 "이 URL 로 가면 문서가 있다" 를 계약이 보장하는데 404 가 난다.
   * 스키마 검증은 통과하고 화면은 링크를 그리며, **눌러야 드러난다.**
   *
   * 그래서 화면은 `null` 일 때 링크를 그리지 않는다. 빈 링크보다 "아직 없다" 가 낫다.
   */
  previewUrl?: string | null;
  /** `previewUrl` 과 같은 이유로 PDF 생성 전까지 null. */
  downloadUrl?: string | null;
}

/**
 * 적합성 모순 판정 상태 (F-DET-002).
 * 미리보기는 모순을 평가하지 않으므로 보통 `NOT_EVALUATED` 다.
 */
export type SuitabilityStatus = "NOT_EVALUATED" | "NO_MISMATCH" | "MISMATCH" | "UNKNOWN";

/**
 * 게이트 **미리보기** (`GET /sessions/{id}/gate-preview`).
 *
 * **`GateResult` 와 다른 타입이다.** `/judge` 는 `signal`·`ruleTrace` 둘뿐이고, 이쪽은
 * 미리보기를 안전하게 만드는 두 필드를 더 싣는다. 미리보기를 `GateResult` 로 받으면
 * 남는 필드를 TS 가 잡아주지 않아 **타입은 통과하는데 화면만 조용히 덜 그린다** —
 * `RiskItem` 의 snake_case 사고(decision-log 10.18)와 같은 종류다.
 */
export interface GatePreview {
  signal: Signal;
  ruleTrace: string[];
  /**
   * 감사 기준점으로 기록된 값인가. false 면 아직 확정이 아니다.
   *
   * **확정 여부의 근거는 이 필드다.** 세션 상태(`state === "JUDGED"`)로 유추하면 지금은
   * 우연히 일치하지만 상태 전이가 하나 늘 때 조용히 어긋난다.
   */
  recorded: boolean;
  /** 기준점 확정 시각. null 이면 `/judge` 가 아직 호출되지 않은 세션이다. */
  judgedAt?: string | null;
  /**
   * ❗`NOT_EVALUATED` 인 GREEN 을 최종 통과로 그리면 안 된다.
   *
   * 미리보기는 모순을 평가하지 않으므로 그 GREEN 은 **모순 평가 전** 값이고, `/judge` 에서
   * YELLOW(UNKNOWN)·RED(MISMATCH) 로 갈릴 수 있다. 방향이 나쁜 쪽이다 — 판매자가 GREEN 을
   * 보고 재설명 루프를 건너뛰고 확정으로 갔다가 거기서 막힌다. 그래서 신호 옆에
   * "적합성 미확인" 을 함께 낸다(신호 자체는 바꾸지 않는다).
   */
  suitabilityStatus: SuitabilityStatus;
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
  /** 오해율 0~1. `masked` 면 null. */
  misrate: number | null;
  /** 표본 수. 마스킹돼도 내려준다. */
  n: number;
  /**
   * 소표본(n<30) 마스킹 여부. **셀을 제거하지 않는다** — 제거하면 화면이 "데이터 없음"과
   * "가려짐"을 구분할 수 없고, 감사·심사 관점에서는 가려졌다는 사실 자체가 마스킹이
   * 동작한 증거다(계약 · `rbac_policy.yaml` 집계 절).
   *
   * 계약에 required 로 있었는데 이 타입에만 없었다. `misrate === null` 하나로 두 상태를
   * 읽으면 소표본 셀이 "데이터 없음"으로 그려지고, **마스킹이 동작한 증거가 화면에서
   * 사라진다** — 그게 데모에서 보여야 하는 것인데.
   */
  masked: boolean;
}

export interface HeatmapResponse {
  /** 합성 세션 기반이면 true — 화면에 워터마크를 상시 노출해야 한다 (F-DSH-001, 연출 금지). */
  synthetic: boolean;
  /**
   * **데이터 범위**. `rbac_policy.yaml` 의 `own_session`/`branch`/`org` 와 같은 어휘이고
   * 요청자 역할이 결정한다(MGR=branch · COMPL=org). 집계 축(`groupBy`)과 다른 개념이라
   * 화면에 표시해 무엇을 보고 있는지 드러낸다. 계약 required — 이 타입에만 없었다.
   */
  scope: "branch" | "org";
  cells: HeatmapCell[];
}

/**
 * F-DSH-002 선행지표 뷰 (`GET /dashboard/leading-indicators`).
 *
 * **집계 축(`groupBy`)과 데이터 범위(`scope`)는 다른 개념이다** — 계약이 이름을 갈라 둔
 * 것도 그래서다. 축은 요청이 고르고(지점·판매자·항목), 범위는 요청자 역할이 정한다
 * (MGR=branch · COMPL=org). 화면은 둘을 같이 보여야 무엇을 보고 있는지가 드러난다.
 */
export type IndicatorAxis = "branch" | "seller" | "item";

/**
 * 한 구간(주)의 값.
 *
 * ❗**값이 없는 주도 자리를 남긴다**(`n=0` · `masked=true`). 서버가 빈 주를 빼지 않는
 * 이유를 화면도 지켜야 한다 — 빼면 추이의 끊김이 안 보이고, 8주가 계열마다 다른 길이가
 * 된다. 그래서 화면은 빈 칸을 **높이 0 인 막대로 그리지 않는다**: 0% 와 "그 주에 판정이
 * 없었다" 는 다른 사실이다.
 */
export interface IndicatorPoint {
  /** ISO 주(예: `2026-W32`). 목록의 **끝이 최신**이다. */
  period: string;
  /** 오해율 0~1. `masked` 면 null. */
  misrate: number | null;
  n: number;
  /** 히트맵과 같은 규칙(n<30). */
  masked: boolean;
}

export interface IndicatorSeries {
  groupBy: IndicatorAxis;
  /** 지점·항목 식별자, 또는 **판매자 비식별 대체키**(로그인 ID 가 아니다). */
  key: string;
  points: IndicatorPoint[];
}

/**
 * 이상치 1건. 서버가 *직전 구간 평균 대비 상승폭*으로 판단한다 — 화면이 다시 계산하지
 * 않는다(P1 과 같은 결: 판단은 서버, 화면은 표시).
 */
export interface IndicatorOutlier {
  groupBy: IndicatorAxis;
  key: string;
  /** 사람이 읽는 사유(예: `직전 4구간 평균 대비 +18.0%p`). 화면은 이 문장을 그대로 낸다. */
  reason: string;
  /** 기계 판독용 변화량. 없을 수 있다. */
  delta?: number | null;
}

export interface LeadingIndicatorResponse {
  /** 히트맵과 동일 — 두 뷰 모두 워터마크를 상시 노출한다(F-DSH-003 연출 금지). */
  synthetic: boolean;
  /** 데이터 범위. 히트맵의 같은 이름 필드와 같은 뜻이고, 집계 축은 `groupBy` 다. */
  scope: "branch" | "org";
  series: IndicatorSeries[];
  outliers: IndicatorOutlier[];
}
