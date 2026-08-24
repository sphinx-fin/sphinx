package com.sphinxfin.sphinx.api.exception;

import com.sphinxfin.sphinx.api.dto.ApiError;
import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.core.SessionFsm;
import com.sphinxfin.sphinx.domain.EvidenceRequiredException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * 전역 예외 핸들러. 소유: 강희진
 * 컨트롤러마다 예외 처리를 흩지 않고 여기서 일관된 ApiResponse.fail(...) 로 변환한다.
 * (@RestControllerAdvice는 패키지와 무관하게 모든 컨트롤러에 적용된다.)
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 존재하지 않는 리소스 조회 → 404 */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ApiError.of("NOT_FOUND", e.getMessage())));
    }

    /** 허용되지 않은 상태 전이 → 409 (F-INT-001) */
    @ExceptionHandler(SessionFsm.IllegalStateTransitionException.class)
    public ResponseEntity<ApiResponse<Void>> illegalTransition(SessionFsm.IllegalStateTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.of("ILLEGAL_STATE_TRANSITION", e.getMessage())));
    }

    /** 요청 본문 유효성 실패(@Valid) → 400 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.of("VALIDATION_ERROR", detail)));
    }

    /** 잘못된 요청(재설명 대상 아님·상한 도달 등) → 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> illegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.of("INVALID_REQUEST", e.getMessage())));
    }

    /** 요청 본문 파싱 실패(잘못된 JSON·허용되지 않은 enum 값 등) → 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.of("MALFORMED_REQUEST", "요청 본문을 읽을 수 없다(형식·허용값 확인)")));
    }

    /**
     * P4 위반 — 근거 없는 판정 → 502. 우리 서버 버그(500)가 아니라 상류(ai-service) 계약
     * 위반이므로 구분한다. 차단 사실을 로그로 남긴다(감사 로그 F-CMN-002 붙기 전까지의 최소 기록).
     */
    @ExceptionHandler(EvidenceRequiredException.class)
    public ResponseEntity<ApiResponse<Void>> evidenceRequired(EvidenceRequiredException e) {
        log.warn("P4 차단 — 근거 없는 판정 거부: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail(ApiError.of("EVIDENCE_REQUIRED", "근거 없는 판정은 처리할 수 없습니다 (P4)")));
    }

    /** 그 외 예기치 못한 예외 → 500. 원인은 로그에 남기고(삼키지 않음), 응답엔 노출하지 않는다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ApiError.of("INTERNAL_ERROR", "서버 내부 오류")));
    }
}
