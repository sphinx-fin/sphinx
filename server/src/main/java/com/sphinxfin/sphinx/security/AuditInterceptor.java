package com.sphinxfin.sphinx.security;

import com.sphinxfin.sphinx.evidence.AuditLog;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * F-CMN-002 감사 기록 지점. 등록: 강희진 / 기록 내용: 정세현
 *
 * 감사 로그를 컨트롤러에서 직접 부르지 않기 위한 단일 통로. 여기 한 곳만 api/를 알고,
 * 무엇을 남기는지는 {@link AuditLog.Entry}가 정한다.
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {
    // TODO(강희진): WebMvcConfigurer에 등록 — 대상 경로는 rbac_policy.yaml의 audited 목록
    // TODO(정세현): AuditLog.Entry 채워 append
}
