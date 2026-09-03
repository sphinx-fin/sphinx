/**
 * 서버 에러 코드 → 사람이 읽을 문면. 소유: 오준서.
 *
 * ── 왜 이 파일이 있나 (이슈 #316) ────────────────────────────────────────────
 *
 * 네 화면(S-01·S-05·S-06·S-07)이 각자 `${e.message} (${e.code})` 를 갖고 있었다. 그래서
 * 화면에 이런 줄이 떴다.
 *
 *   적색 판정 세션만 오버라이드할 수 있다(현재 신호: null) (OVERRIDE_NOT_ELIGIBLE)
 *
 * 한 줄에 세 가지가 같이 있다 — **반말 개발 로그체**(서버 메시지는 개발자에게 쓴 문장이다),
 * **내부 상태 누출**(`현재 신호: null`), **에러 코드**(판매자·고객이 읽을 것이 아니다).
 * S-01·S-07 은 시연 경로이고 S-05 는 판매자가 상시 보는 화면이다.
 *
 * ── `Record<ErrorCode, string>` 인 이유 ──────────────────────────────────────
 *
 * 부분 맵(`Partial<…>` 이나 `Record<string, string>`)으로 두면 코드가 늘어도 **아무 일이
 * 안 난다** — 새 코드가 조용히 기본 문면으로 떨어지고, 그게 화면에서는 정상처럼 보인다.
 * 전체 맵이면 유니온에 값이 추가되는 순간 **컴파일이 깨진다.** web 에는 테스트 러너가
 * 없으므로(결정 10.59) 여기서 타입이 그 일을 대신한다.
 *
 * ❗다만 **타입이 지켜 주는 것은 이 표와 유니온의 일치까지다.** 유니온 자체가 계약보다
 * 뒤처지는 것은 못 잡는다 — 실제로 그렇게 세 개가 갈려 있었다(`types.ts` 의 `ErrorCode`
 * 주석). 계약 ↔ web 대조를 `ErrorCodeContractTest` 의 네 번째 대상으로 넣을지는 `@hd0rable`
 * 판단이 필요하고, 그때까지는 이 문단이 유일한 방어다.
 *
 * ── 서버 문면을 버리지 않는다 ────────────────────────────────────────────────
 *
 * 리허설·디버깅에는 그 문장이 필요하다. 그래서 지우지 않고 `detail` 로 돌려주고, 화면이
 * `<details>` 안에 접어 둔다. 고객 화면에서 접힌 채로 있는 것과 아예 없는 것은 다르다.
 */
import { ApiRequestError } from "../api/client";
import type { ErrorCode } from "../api/types";

/**
 * 코드별 사용자 문면.
 *
 * 규칙 둘. **내부 상태를 문면에 넣지 않는다**(신호·ID·필드명). 그리고 **다음에 무엇을
 * 하면 되는지**를 적는다 — 무엇이 잘못됐는지만 적으면 화면 앞에서 멈춘다.
 */
const ERROR_TEXT: Record<ErrorCode, string> = {
  NOT_FOUND: "찾을 수 없습니다. 주소를 다시 확인해 주세요.",
  VALIDATION_ERROR: "입력한 내용을 다시 확인해 주세요.",
  MALFORMED_REQUEST: "요청을 처리하지 못했습니다. 새로고침 후 다시 시도해 주세요.",
  ILLEGAL_STATE_TRANSITION: "지금 단계에서는 할 수 없는 동작입니다. 화면을 새로고침해 주세요.",
  UNAUTHORIZED: "로그인이 필요합니다.",
  // 차단은 결함이 아니라 시연 대상이다(기획서 7-4). "오류" 로 읽히지 않게 적는다.
  FORBIDDEN: "이 화면을 볼 권한이 없습니다. 담당자에게 문의해 주세요.",
  REEXPLAIN_NOT_ELIGIBLE: "이 항목은 재설명 대상이 아닙니다.",
  REVERIFY_EXHAUSTED: "재검증 횟수를 모두 사용했습니다. 판정으로 넘어갑니다.",
  OVERRIDE_NOT_ELIGIBLE: "판정을 확정한 뒤에 요청할 수 있습니다.",
  // 502 셋은 고칠 곳이 다르다. 문면도 다르게 적어야 판매자가 무엇을 할지 안다.
  EVIDENCE_REQUIRED: "채점 결과에 근거가 없어 받지 않았습니다. 다시 시도해 주세요.",
  MEASUREMENT_INVALID: "채점 결과를 검증하지 못했습니다. 다시 시도해 주세요.",
  AI_SERVICE_UNAVAILABLE: "채점 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.",
  INTERNAL_ERROR: "서버에서 문제가 발생했습니다. 잠시 후 다시 시도해 주세요.",
};

/** 화면이 들고 그리는 에러 한 건. */
export interface ShownError {
  /** 사람에게 보일 문면. */
  text: string;
  /** 서버 원문 — 접어서 보관한다. 없으면 null. */
  detail: string | null;
}

/**
 * 예외 하나를 화면 문면으로 바꾼다.
 *
 * `ApiRequestError` 가 아닌 것(네트워크 끊김·JSON 파싱 실패 등)은 코드가 없으므로 일반
 * 문면으로 떨어진다. 그때도 원문은 `detail` 로 남긴다.
 */
export function describeError(e: unknown): ShownError {
  if (e instanceof ApiRequestError) {
    const known = (ERROR_TEXT as Record<string, string | undefined>)[e.code];
    return {
      // 계약에 없는 코드가 오면 여기로 떨어진다. 그 경우가 정확히 계약이 갈린 순간이라
      // 원문을 같이 남기는 것이 중요하다 — 접힌 자리에 코드가 그대로 보인다.
      text: known ?? "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
      detail: `${e.code}: ${e.message}`,
    };
  }
  return {
    text: "요청을 처리하지 못했습니다. 네트워크 상태를 확인해 주세요.",
    detail: e instanceof Error ? e.message : null,
  };
}
