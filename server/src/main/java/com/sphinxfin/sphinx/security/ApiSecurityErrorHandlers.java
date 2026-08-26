package com.sphinxfin.sphinx.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sphinxfin.sphinx.api.dto.ApiError;
import com.sphinxfin.sphinx.api.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 시큐리티 <b>필터 단계</b>에서 끊긴 요청의 응답. 소유: 강희진
 *
 * <p>{@code GlobalExceptionHandler} 는 핸들러가 잡힌 뒤(@{@code PreAuthorize} 거부 등)만
 * 탄다. 필터 체인에서 끊긴 요청은 {@code DispatcherServlet} 까지 오지 않으므로 그쪽을 안
 * 거치고, 기본값으로 두면 <b>스프링이 빈 본문이나 HTML 오류 페이지를 낸다.</b>
 * 그러면 같은 API 가 어떤 실패에서는 {@code {success,data,error}} 봉투를 주고 어떤
 * 실패에서는 안 주게 되어, 화면의 파싱이 그 경로에서만 조용히 깨진다.
 *
 * <p>봉투와 코드를 핸들러 쪽과 같은 문면으로 맞춘다.
 *
 * <p>❗<b>여기서는 감사 기록을 남기지 않는다.</b> {@code AuditInterceptor} 가 단일 통로라는
 * 규약(CLAUDE.md)을 지키기 위해서고, 필터 단계에는 핸들러가 없어 기록할 {@code action} 을
 * 정할 근거도 없다({@code AuditInterceptor} 는 {@code @PreAuthorize} 문면에서 action 을
 * 읽는다). 그래서 <b>필터에서 끊긴 요청은 감사 로그에 남지 않는다</b> — 인증된 사용자의
 * 권한 위반은 핸들러 단계라 남지만, 미인증 접근은 안 남는다. 남겨야 한다면 action 이름
 * 규약이 먼저 정해져야 하므로 별건으로 뺀다(이슈 #69).
 */
@Slf4j
@Component
public class ApiSecurityErrorHandlers {

    private final ObjectMapper mapper;

    public ApiSecurityErrorHandlers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 인증이 없다 → 401. 로그인하면 해소되는 상태다. */
    public AuthenticationEntryPoint entryPoint() {
        return (request, response, e) -> write(request, response, HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED", "인증이 필요합니다", e);
    }

    /** 인증은 됐지만 권한이 없다 → 403. 로그인해도 해소되지 않는다. */
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, e) -> write(request, response, HttpStatus.FORBIDDEN,
                "FORBIDDEN", "이 작업을 수행할 권한이 없습니다", e);
    }

    /**
     * 사유는 본문에 싣지 않는다 — 정책 사유 문면("다른 지점의 세션이다")은 리소스의 존재를
     * 알려준다. 진단은 서버 로그에만 남긴다.
     */
    private void write(HttpServletRequest request, HttpServletResponse response,
                       HttpStatus status, String code, String message, Exception cause)
            throws IOException {
        log.warn("필터 단계 차단 [{}] {} {} — {}", status.value(), request.getMethod(),
                request.getRequestURI(), cause.getMessage());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), ApiResponse.fail(ApiError.of(code, message)));
    }

    /** 이 클래스가 다루는 두 예외 타입 — 시그니처 문서화용. */
    interface Handles {
        void authentication(AuthenticationException e);
        void accessDenied(AccessDeniedException e);
    }
}
