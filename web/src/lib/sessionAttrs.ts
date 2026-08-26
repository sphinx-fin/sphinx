/**
 * S-02 세션 시작이 보내는 **비식별 속성**의 허용값. 소유: 오준서.
 *
 * ── 이 파일이 따로 있는 이유 ────────────────────────────────────────────────
 *
 * `ageBand`·`amountBand`·`experienceLevel` 은 계약상 자유 문자열(`string`)이지만,
 * 서버 `resources/vulnerability_weights.yaml`(F-DET-002, 소유: 강희진)이 **문자열을 키로**
 * 취약 가중치를 매긴다. 그리고 그 파일의 규칙은 이렇다:
 *
 *   > 목록에 없는 값은 0점(오탐 없음).
 *
 * 즉 화면이 "60세~69세" 처럼 보내면 **에러 없이 0점**이 되고, 취약 고객이 일반 고객으로
 * 조용히 분류된다. 실패가 화면에도 로그에도 안 남는다 — 리포트를 열어봐야 안다.
 * 그래서 문자열을 화면 곳곳에 흩지 않고 여기 한 곳에 모아 YAML 과 대조해 둔다.
 *
 * **대조 기준**: `server/src/main/resources/vulnerability_weights.yaml` version 1
 *   ageBand         50대:1 · 60대:3 · 70대:4 · 80대:5
 *   amountBand      5천만원대:1 · 1억원대:2 · 3억원대이상:3
 *   experienceLevel 없음:3 · 1년미만:2 · 3년미만:1
 *   channel         MOBILE:1
 *   vulnerable-threshold: 4
 *
 * 가중 대상이 아닌 값(20~40대, 소액, 3년이상)은 YAML 에 없는 게 정상이다 — 0점이 맞는
 * 값과, 오타라서 0점이 된 값을 구분하려고 `weighted` 로 표시해 둔다.
 *
 * ⚠️ 요청했던 enum 승격은 **#43 으로 들어왔다** — `openapi.yaml` 의 `CreateSessionRequest`
 *    가 `ageBand`·`experienceLevel`·`amountBand` 를 enum 으로 고정한다. 그래서 오타는 이제
 *    서버에서 400 으로 걸린다(조용한 0점이 아니라).
 *
 *    남은 구멍은 **enum ↔ YAML** 이다. 그 둘이 어긋나면 값은 유효한데 가중치만 사라져서
 *    여전히 조용히 0점이 된다. `VulnerabilityWeightsContractTest`(#59)가 `channel` 은 막지만
 *    나머지 셋은 아직 사람이 맞추고 있다 — PR #59 리뷰에서 확대를 요청해 뒀다.
 */

/** 선택지 하나. `weighted`=true 면 취약 가중 대상(YAML 에 키가 있는 값). */
export interface AttrOption {
  /** 서버로 나가는 값 — YAML 키와 **바이트 단위로 같아야** 한다. */
  value: string;
  /** 화면 표기. value 와 같아도 분리해 둔다(표기만 바뀌는 변경이 값을 건드리지 않도록). */
  label: string;
  weighted: boolean;
}

/** 연령대 — 10세 구간. 생년월일·나이 원값은 받지 않는다 (P3). */
export const AGE_BANDS: readonly AttrOption[] = [
  { value: "20대", label: "20대", weighted: false },
  { value: "30대", label: "30대", weighted: false },
  { value: "40대", label: "40대", weighted: false },
  { value: "50대", label: "50대", weighted: true },
  { value: "60대", label: "60대", weighted: true },
  { value: "70대", label: "70대", weighted: true },
  { value: "80대", label: "80대 이상", weighted: true },
];

/**
 * 가입금액 구간 — 원 단위 금액은 받지 않는다 (P3).
 * YAML 주석이 이 값을 "가입 비중 프록시" 라고 적어 뒀다. 총자산 대비 비중을 직접 물으면
 * 재산 정보가 되므로, 금액대로 대신 재는 구조다.
 */
export const AMOUNT_BANDS: readonly AttrOption[] = [
  { value: "1천만원미만", label: "1천만 원 미만", weighted: false },
  { value: "1천만원대", label: "1천만 원대", weighted: false },
  { value: "3천만원대", label: "3천만 원대", weighted: false },
  { value: "5천만원대", label: "5천만 원대", weighted: true },
  { value: "1억원대", label: "1억 원대", weighted: true },
  { value: "3억원대이상", label: "3억 원대 이상", weighted: true },
];

/** 투자 경험 기간. */
export const EXPERIENCE_LEVELS: readonly AttrOption[] = [
  { value: "없음", label: "없음", weighted: true },
  { value: "1년미만", label: "1년 미만", weighted: true },
  { value: "3년미만", label: "3년 미만", weighted: true },
  { value: "3년이상", label: "3년 이상", weighted: false },
];

/**
 * 판매 채널. 서버 `domain/Channel` enum 과 1:1.
 *
 * TM 은 enum 에 있지만 **MVP 범위 밖**이다 — `docs/README.md` 가 "통화 기반 채널(TM·GA)은
 * MVP 범위 밖이며 구현·데모 대상이 아니다" 로 못박았다(음성 입력 제외, 기능 명세 v1.1).
 * 되말하기가 텍스트 전용이라 TM 세션은 애초에 진행할 수 없다. 그래서 선택지에서 뺀다.
 */
export const CHANNELS = [
  { value: "FACE_TO_FACE", label: "대면 영업점" },
  { value: "MOBILE", label: "모바일 앱" },
] as const;

/**
 * 상품 목록은 **`GET /products` 로 받는다** — 상수를 두지 않는다.
 *
 * 예전에는 계약에 목록 조회 경로가 없어(`POST /products/documents` · `POST /products/{id}/extract`
 * · `GET /products/{id}/risk-items` 뿐이라 전부 id 를 이미 알아야 부를 수 있었다) 데모 2종을
 * 여기 상수로 고정했다. #49 로 `GET /products` 가 생겨 상수를 지웠다 (decision-log 10.18).
 *
 * 지우는 게 중요한 이유는 **가명 표기** 때문이다. 기획서가 "데모와 제출물에서는 상품명과
 * 발행사를 가명 처리한다"로 못박았고 서버 `MockData.PRODUCTS` 도 같은 문면을 쓰는데, 같은
 * 문자열이 두 곳에 있으면 한쪽만 고쳐진다. 목록을 서버에서 받으면 문면의 출처가 하나가 된다.
 *
 * 타입은 `api/types.ts` 의 `ProductSummary`.
 */
