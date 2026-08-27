package com.sphinxfin.sphinx.core.aiservice;

/**
 * ai-service(FastAPI) 호출 실패. 소유: 강희진
 *
 * 우리 서버 버그(500)가 아니라 상류 의존성 문제이므로 구분한다 —
 * GlobalExceptionHandler에서 502 AI_SERVICE_UNAVAILABLE로 매핑한다.
 * (근거 없는 판정 P4는 EvidenceRequiredException으로 따로 502가 나간다.)
 */
public class AiServiceException extends RuntimeException {
    public AiServiceException(String message) {
        super(message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
