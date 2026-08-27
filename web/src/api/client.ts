/**
 * API 클라이언트. 기준: `contracts/openapi.yaml` (소유: 강희진).
 *
 * 봉투 해제는 **여기 한 곳**에서만 한다. 화면은 `data` 만 받고, 실패는 `ApiRequestError` 로
 * 던져진다 — 화면마다 `success` 분기를 복제하면 규약이 깨지는 게 아니라 **조용히 다르게
 * 해석**된다 (decision-log 1.14).
 *
 * ── 모양 추측을 걷어낸 이유 (decision-log 1.1 · 10.18) ──────────────────────
 *
 * 예전에는 서버가 일부 엔드포인트에만 봉투를 씌워서(`/simulate`·`/report`·`/products/*` 등이
 * raw) `unwrap` 이 **응답 모양을 보고** 봉투인지 판별했다. #49 로 raw 반환 10개가 전부
 * `ApiResponse<T>` 로 통일됐고 `EnvelopeContractTest` 가 새 엔드포인트의 누락을 막는다.
 *
 * 모양 추측을 남겨두면 안 되는 이유는 그것이 **틀리는 방식**에 있다. raw 응답이 우연히
 * `success` 불리언 필드를 갖는 순간 그 응답은 봉투로 오인돼 `data`(=undefined)가 화면으로
 * 흘러간다. 에러도 로그도 없이 빈 화면이 된다. 지금은 봉투가 아니면 **던진다** — 계약 위반이
 * 조용한 빈 화면이 아니라 눈에 보이는 실패가 되게.
 */
import type { ApiError, ApiResponse, ErrorCode } from "./types";

const BASE = "/api";

/** 서버가 내려준 실패 봉투 또는 HTTP 오류. 화면은 `code` 로 분기한다. */
export class ApiRequestError extends Error {
  readonly code: ErrorCode;
  readonly status: number;

  constructor(code: ErrorCode, message: string, status: number) {
    super(message);
    this.name = "ApiRequestError";
    this.code = code;
    this.status = status;
  }
}

/** 실패 봉투에서 코드를 꺼낸다. 봉투가 아니면(프록시 오류 페이지 등) 내부 오류로 본다. */
function toApiError(body: unknown, status: number): ApiRequestError {
  const error = (body as ApiResponse<unknown> | null)?.error as ApiError | undefined;
  if (error?.code) {
    return new ApiRequestError(error.code, error.message, status);
  }
  return new ApiRequestError("INTERNAL_ERROR", `요청 실패 (HTTP ${status})`, status);
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${BASE}${path}`, init);
  } catch {
    // 네트워크 단절 — 세션 데이터를 잃지 않도록 화면이 재시도를 안내해야 한다(명세 10절 가용성).
    throw new ApiRequestError("INTERNAL_ERROR", "서버에 연결할 수 없습니다", 0);
  }

  const raw = await res.text();
  let body: unknown = null;
  try {
    body = raw ? JSON.parse(raw) : null;
  } catch {
    throw new ApiRequestError("INTERNAL_ERROR", `응답을 해석할 수 없습니다 (HTTP ${res.status})`, res.status);
  }

  if (!res.ok) throw toApiError(body, res.status);

  // 성공 응답은 예외 없이 봉투다(결정 1.1). 아니면 계약 위반이므로 조용히 넘기지 않는다.
  const envelope = body as ApiResponse<T> | null;
  if (typeof envelope?.success !== "boolean") {
    throw new ApiRequestError(
      "INTERNAL_ERROR",
      `봉투가 아닌 응답입니다 (${path}) — contracts/openapi.yaml 위반`,
      res.status,
    );
  }
  if (!envelope.success) throw toApiError(body, res.status);
  return envelope.data as T;
}

export function get<T>(path: string): Promise<T> {
  return request<T>(path);
}

export function post<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

/**
 * 멀티파트 전송. `POST /products/documents`(F-EXT-001 문서 업로드)가 유일한 사용처다.
 *
 * `post` 를 쓰면 안 된다 — 그쪽은 본문을 항상 `JSON.stringify` 하므로 `FormData` 가
 * **`{}` 로 직렬화된다.** 파일이 사라진 채 요청이 나가고 서버는 "file 파라미터 없음"으로
 * 400 을 준다. 타입은 `unknown` 이라 통과하고 런타임에만 틀리는 종류다.
 *
 * `Content-Type` 을 **일부러 안 넣는다.** 멀티파트는 헤더에 boundary 문자열이 들어가야
 * 하는데 그 값은 브라우저가 `FormData` 를 직렬화하면서 정한다. 직접 지정하면 boundary 가
 * 빠져 서버가 본문을 못 가른다.
 */
export function postForm<T>(path: string, form: FormData): Promise<T> {
  return request<T>(path, { method: "POST", body: form });
}
