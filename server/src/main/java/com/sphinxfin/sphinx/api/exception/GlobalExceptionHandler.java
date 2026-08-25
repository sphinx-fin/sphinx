package com.sphinxfin.sphinx.api.exception;

import com.sphinxfin.sphinx.api.dto.ApiError;
import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.core.SessionFsm;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * 전역 예외 핸들러. 소유: 강희진
 * 컨트롤러마다 예외 처리를 흩지 않고 여기서 일관된 ApiResponse.fail(...) 로 변환한다.
 * (@RestControllerAdvice는 패키지와 무관하게 모든 컨트롤러에 적용된다.)
 */
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

    /** 요청 본문 파싱 실패(잘못된 JSON·허용되지 않은 enum 값 등) → 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.of("MALFORMED_REQUEST", "요청 본문을 읽을 수 없다(형식·허용값 확인)")));
    }

    /**
     * 매핑되지 않은 경로 → 404. 포괄 Exception 핸들러가 이걸 삼키면 오타 난 URL 이
     * 500 INTERNAL_ERROR 로 나가고, 프론트는 "서버가 죽었다"로, 모니터링은 장애로 읽는다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> noResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ApiError.of("NOT_FOUND", "경로를 찾을 수 없다: " + e.getResourcePath())));
    }

    /** 그 외 예기치 못한 예외 → 500 (원문 메시지는 노출하지 않음) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ApiError.of("INTERNAL_ERROR", "서버 내부 오류")));
    }
}
