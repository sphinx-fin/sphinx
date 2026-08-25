/**
 * API 클라이언트. 기준: `contracts/openapi.yaml` (소유: 강희진).
 *
 * 봉투 해제는 **여기 한 곳**에서만 한다. 화면은 `data`만 받고, 실패는 `ApiRequestError`로
 * 던져진다 — 화면마다 `success` 분기를 복제하지 않기 위해서다.
 *
 * 과도기 주의: 서버가 아직 전 엔드포인트에 봉투를 씌우지 않았다(PR #16 리뷰 1번).
 * `/sessions`·`/answers`·`/judge`는 `ApiResponse<T>`, `/questions/next`·`/simulate`·
 * `/report`·`/products/*`·`/dashboard/*`는 raw 객체다. 그래서 `unwrap`이 봉투 **모양을
 * 보고** 벗긴다 — 엔드포인트별 분기를 화면으로 새어나가게 하지 않으려는 절충이고,
 * 서버가 통일되면 이 함수만 단순해진다.
 */
import type { ApiError, ApiResponse, ErrorCode } from "./types";

const BASE = "/api";

/** 서버가 내려준 실패 봉투 또는 HTTP 오류. 화면은 `code`로 분기한다. */
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

/** 봉투 모양인지 판별 — `success` 불리언 + `data`/`error` 키를 가진 객체. */
function isEnvelope(body: unknown): body is ApiResponse<unknown> {
  return (
    typeof body === "object" &&
    body !== null &&
    typeof (body as { success?: unknown }).success === "boolean" &&
    ("data" in body || "error" in body)
  );
}

function toApiError(body: unknown, status: number): ApiRequestError {
  if (isEnvelope(body) && body.error) {
    const err = body.error as ApiError;
    return new ApiRequestError(err.code, err.message, status);
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
  const body: unknown = raw ? JSON.parse(raw) : null;

  if (!res.ok) throw toApiError(body, res.status);

  if (isEnvelope(body)) {
    if (!body.success) throw toApiError(body, res.status);
    return body.data as T;
  }
  return body as T;   // 아직 봉투가 안 씌워진 엔드포인트
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
