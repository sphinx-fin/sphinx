package com.sphinxfin.sphinx.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * AuditInterceptor 등록. 소유: 강희진
 *
 * 인터셉터를 만들어만 두고 등록하지 않으면 감사 로그가 0건인데 아무도 모른다 — 그리고
 * 0건은 "접근이 없었다"로 읽힌다. 등록이 곧 F-CMN-002의 절반이다.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuditInterceptor auditInterceptor;

    public WebMvcConfig(AuditInterceptor auditInterceptor) {
        this.auditInterceptor = auditInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 전 경로에 걸고, 실제 기록 여부는 rbac_policy.yaml의 audited 목록이 정한다.
        // 경로를 여기서 추리면 정책과 두 벌이 된다.
        registry.addInterceptor(auditInterceptor).addPathPatterns("/**");
    }
}
