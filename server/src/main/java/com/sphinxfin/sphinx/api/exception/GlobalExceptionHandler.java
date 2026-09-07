package com.sphinxfin.sphinx.api.exception;

import com.sphinxfin.sphinx.api.dto.ApiError;
import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.core.aiservice.AiServiceException;
import com.sphinxfin.sphinx.core.extraction.ProductUploads;
import com.sphinxfin.sphinx.core.session.OverrideNotEligibleException;
import com.sphinxfin.sphinx.core.session.ReExplainNotEligibleException;
import com.sphinxfin.sphinx.core.session.ReverifyExhaustedException;
import com.sphinxfin.sphinx.core.session.SessionFsm;
import com.sphinxfin.sphinx.domain.EvidenceRequiredException;
import com.sphinxfin.sphinx.domain.MeasurementInvalidException;
import com.sphinxfin.sphinx.security.AccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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

    /**
     * 계약을 벗어난 요청 값 → 400. {@code @Valid} 가 못 잡는 자리(쿼리 enum 등)다.
     *
     * <p>조용히 기본값으로 떨어뜨리지 않는 이유: {@code groupBy=brnach} 같은 오타가 기본값
     * {@code branch} 로 처리되면 화면은 <b>요청한 것과 다른 축</b>을 그리는데 아무도 모른다.
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponse<Void>> validationValue(ValidationException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.of("VALIDATION_ERROR", e.getMessage())));
    }

    /**
     * 업로드 입력이 계약을 벗어났다 → 400 {@code VALIDATION_ERROR} (이슈 #521).
     *
     * <p>{@code core} 가 {@link ValidationException}(api 층)을 알면 안 되므로 서비스는 자기
     * 예외를 던지고 여기서 같은 코드로 접는다 — 프론트가 보는 코드는 한 벌이어야 한다.
     */
    @ExceptionHandler(ProductUploads.UploadRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> onUploadRejected(ProductUploads.UploadRejectedException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.of("VALIDATION_ERROR", e.getMessage())));
    }

    /**
     * 업로드가 상한을 넘었다 → 400 {@code VALIDATION_ERROR} (이슈 #521).
     *
     * <p>❗<b>새 에러 코드를 만들지 않는다.</b> 코드 목록은 네 벌(핸들러·openapi·CLAUDE.md·
     * {@code web/src/api/types.ts})이 같아야 하고 {@code ErrorCodeContractTest} 가 대조한다 —
     * 상한 초과는 "요청 값이 계약을 벗어났다" 의 한 경우라 기존 코드로 충분하다.
     *
     * <p>이 핸들러가 없으면 {@code Exception} 갈래로 떨어져 <b>500 «서버 내부 오류»</b> 다.
     * 큰 PDF 를 올린 운영자가 서버 장애로 읽고, 파일을 줄이면 되는 것을 아무도 모른다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> onTooLarge(MaxUploadSizeExceededException e) {
        log.warn("업로드 크기 상한 초과: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.of("VALIDATION_ERROR",
                        "파일이 너무 큽니다(업로드 상한을 넘었습니다)")));
    }

    /**
     * 재설명 대상 아님(판정 없음·이미 이해 U1) → 400. 화면은 조용히 다음 항목으로 넘어간다.
     * 상한 도달과 코드를 가르는 이유: 메시지 문자열은 계약이 아니라서, 한 코드로 내보내면
     * 프론트가 서버 문면을 파싱해야 하고 문면이 바뀌는 순간 조용히 깨진다.
     */
    @ExceptionHandler(ReExplainNotEligibleException.class)
    public ResponseEntity<ApiResponse<Void>> reExplainNotEligible(ReExplainNotEligibleException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.of("REEXPLAIN_NOT_ELIGIBLE", e.getMessage())));
    }

    /** 재검증 상한 도달 → 400. 화면은 판정으로 넘어감을 고객에게 알려야 한다(F-INT-004). */
    @ExceptionHandler(ReverifyExhaustedException.class)
    public ResponseEntity<ApiResponse<Void>> reverifyExhausted(ReverifyExhaustedException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.of("REVERIFY_EXHAUSTED", e.getMessage())));
    }

    /** 오버라이드 전제 위반(적색 아님·요청 없이 승인) → 409 (F-GTE-002) */
    @ExceptionHandler(OverrideNotEligibleException.class)
    public ResponseEntity<ApiResponse<Void>> overrideNotEligible(OverrideNotEligibleException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.of("OVERRIDE_NOT_ELIGIBLE", e.getMessage())));
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
    /**
     * 모델이 낸 <b>측정값이 검증을 통과하지 못했다</b> — 근거 누락(P4)과 같은 502 지만 코드를
     * 가른다. 상류에 무엇을 고치라고 말하려면 <i>"근거가 없다"</i> 와 <i>"측정을 못 믿는다"</i>
     * 가 달라야 한다.
     *
     * <p>❗<b>{@code confidence} 얘기만이 아니다.</b> 처음엔 문면이 <i>"신뢰도가 없거나 계약
     * 범위를 벗어난"</i> 이었는데, ai-service 가 이 예외로 내는 것은 셋이고 그중 하나가
     * <b>루브릭 밖 조항 인용</b>(P4)이다 — {@code confidence} 와 무관하다. 좁은 문면을 두면
     * 이 코드가 고치려던 문제(<i>"고칠 곳이 반대편인데 반대편을 가리킨다"</i>)가 한 칸 안쪽에서
     * 다시 난다(PR #286 리뷰).
     *
     * <p>{@code EVIDENCE_REQUIRED} 와 겹쳐 보이지만 다르다 — 그쪽은 <b>근거가 비었다</b>,
     * 이쪽은 <b>근거가 있는데 우리 검증을 통과 못 했다</b> 다. 뒤엣것에 갈래가 셋 있다.
     *
     * <pre>
     *   ① 인용이 실제 발화에 없다 (모델이 지어냈다)   verify_quote_is_verbatim
     *   ② 루브릭 밖 조항을 인용했다                    verify_rubric_clause_is_published
     *   ③ 신뢰도가 없거나 0~1 을 벗어났다
     * </pre>
     *
     * <p>❗①이 <b>심사에서 제일 물어볼 것</b>이다 — <i>"AI 가 근거를 지어내면요?"</i> 의 답이
     * 이 코드다(PR #293 리뷰, 윤지석). 화면 문면은 셋을 다 덮으므로 안 가른다.
     */
    @ExceptionHandler(MeasurementInvalidException.class)
    public ResponseEntity<ApiResponse<Void>> measurementInvalid(MeasurementInvalidException e) {
        log.warn("측정값 계약 위반: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail(ApiError.of("MEASUREMENT_INVALID",
                        "측정값이 검증을 통과하지 못했습니다 — 다시 시도해 주세요")));
    }

    @ExceptionHandler(EvidenceRequiredException.class)
    public ResponseEntity<ApiResponse<Void>> evidenceRequired(EvidenceRequiredException e) {
        log.warn("P4 차단 — 근거 없는 판정 거부: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail(ApiError.of("EVIDENCE_REQUIRED", "근거 없는 판정은 처리할 수 없습니다 (P4)")));
    }

    /**
     * ai-service 호출 실패(non-2xx·연결 오류·501 미구현 등) → 502. 우리 서버 버그(500)가
     * 아니라 상류(ai-service) 의존성 문제이므로 구분한다. 프론트가 "서버가 죽었다"가 아니라
     * "채점 서비스 일시 불가"로 읽어야 재시도·안내 문면을 가를 수 있다.
     */
    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ApiResponse<Void>> aiServiceUnavailable(AiServiceException e) {
        log.warn("ai-service 호출 실패: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail(ApiError.of("AI_SERVICE_UNAVAILABLE", "채점 서비스에 연결할 수 없습니다")));
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

    /** 그 외 예기치 못한 예외 → 500. 원인은 로그에 남기고(삼키지 않음), 응답엔 노출하지 않는다. */
    /**
     * 인증되지 않은 요청 → 401.
     *
     * <p>403 과 가른다. <b>"누구인지 모른다"와 "권한이 없다"는 다른 상태다</b> — 전자는
     * 로그인하면 되고 후자는 안 된다. 화면이 같은 코드를 받으면 로그인 유도와 권한 안내를
     * 구별할 수 없고, 감사 로그에서도 미인증 접근 시도와 권한 위반이 섞인다.
     */
    @ExceptionHandler(AccessGuard.AccessDeniedNotAuthenticatedException.class)
    public ResponseEntity<ApiResponse<Void>> unauthenticated(
            AccessGuard.AccessDeniedNotAuthenticatedException e) {
        log.warn("미인증 접근: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(ApiError.of("UNAUTHORIZED", "인증이 필요합니다")));
    }

    /**
     * 권한 없는 접근 → 403 (F-CMN-002).
     *
     * <p><b>이 핸들러가 없으면 차단이 500 으로 떨어진다.</b> 아래 catch-all 이 잡아
     * {@code INTERNAL_ERROR} 를 내는데, 그러면 두 가지가 동시에 망가진다 — 화면에는 막힌 게
     * 아니라 <b>서버가 고장 난 것</b>으로 보이고, {@code AuditInterceptor} 가
     * {@code resultCode=500} 으로 기록해서 <b>차단 시도가 서버 오류 더미에 섞인다.</b>
     * 기획 7-4 2단계가 보려는 것이 차단당한 시도의 반복인데 그게 안 보이게 된다.
     * ({@code enforce=true} 로 띄워 실측한 결과다 — PR #74 리뷰)
     *
     * <p>사유는 응답에 싣지 않는다. {@code AccessPolicy.Decision.reason} 은 "다른 지점의
     * 세션이다" 처럼 <b>존재를 알려주는</b> 문면이라, 없는 세션(404)과 남의 지점 세션(403)을
     * 구별해 주면 지점 경계 너머로 세션 존재 여부가 샌다. 진단은 서버 로그에 남긴다.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> forbidden(AccessDeniedException e) {
        log.warn("접근 차단: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(ApiError.of("FORBIDDEN", "이 작업을 수행할 권한이 없습니다")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ApiError.of("INTERNAL_ERROR", "서버 내부 오류")));
    }
}
