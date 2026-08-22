package com.sphinxfin.sphinx.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * F-CMN-002 필터체인 등록. 소유: 강희진
 * 정책 자체는 정세현({@link AccessPolicy}·rbac_policy.yaml). 여기서는 붙이기만 한다 —
 * 역할별 허용 규칙을 이 파일에 하드코딩하면 경계가 무너진다.
 */
@Configuration
public class SecurityConfig {

    /**
     * MVP 초기값은 전면 허용 — 프론트가 첫날부터 실제 엔드포인트로 개발 가능해야 한다(README).
     * TODO(강희진): AccessPolicy 기반 인증 붙이고 permitAll 제거
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
