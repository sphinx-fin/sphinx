package com.sphinxfin.sphinx.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * F-CMN-002 필터체인 등록. 소유: 강희진
 * 정책 자체는 정세현({@link AccessPolicy}·rbac_policy.yaml). 여기서는 붙이기만 한다 —
 * 역할별 허용 규칙을 이 파일에 하드코딩하면 경계가 무너진다.
 *
 * ── 인증과 인가를 갈라 둔 이유 ────────────────────────────────────────
 * 이 파일이 지금 닫는 것은 **인증**(누구인지 모르는 요청을 받지 않는다)까지다.
 * **인가**(어떤 역할이 무엇을 할 수 있는가)는 {@link AccessPolicy}가 서야 붙는다 —
 * 지금 그 메서드는 미구현이고, 정책을 여기에 임시로 적으면 rbac_policy.yaml 이
 * 유일한 근거라는 규약이 깨진다.
 *
 * 그래서 배포 시 노출 위험(인증 없는 API가 퍼블릭에 열림)만 먼저 막고, 역할별 차단
 * 시연은 AccessPolicy 뒤로 둔다. 둘은 별개의 마감이다.
 * ────────────────────────────────────────────────────────────────────
 */
@Configuration
public class SecurityConfig {

    /** 인증 없이 열어 두는 경로 — 컨테이너·로드밸런서 헬스체크만. */
    private static final String[] PUBLIC_PATHS = {"/actuator/health", "/actuator/health/**"};

    /**
     * 로컬 개발용 — 전면 허용. 프론트가 첫날부터 실제 엔드포인트로 개발 가능해야 한다(README).
     * 이 전제는 퍼블릭 노출과 함께 성립하지 않으므로 prod 프로파일에서는 적용되지 않는다.
     */
    @Bean
    @Profile("!prod")
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            // H2 콘솔은 iframe 기반 — 기본 X-Frame-Options: DENY면 화면이 렌더링되지 않는다.
            // 개발용 콘솔에 한해 sameOrigin 허용(prod 프로파일은 H2 콘솔 자체를 끈다).
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * 배포용 — 헬스체크 외 전부 인증 요구.
     *
     * 역할 구분은 아직 없다. 여기서 세우는 것은 "인증되지 않은 요청을 받지 않는다" 하나이며,
     * 이게 서 있어야 compose network 설정 실수 한 번에 전체가 열리는 구조를 피한다
     * (네트워크 격리가 1차 방어, 이건 2차).
     */
    @Bean
    @Profile("prod")
    public SecurityFilterChain prodFilterChain(HttpSecurity http) throws Exception {
        http
            // 세션 쿠키를 쓰지 않는 REST API 라 CSRF 토큰이 성립하지 않는다.
            // 쿠키 인증을 도입하면 이 줄부터 다시 봐야 한다.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // H2 콘솔이 꺼져 있으므로 iframe 예외가 필요 없다 — 기본값(DENY)으로 되돌린다.
            .headers(headers -> headers.frameOptions(frame -> frame.deny()))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(PUBLIC_PATHS).permitAll()
                    .anyRequest().authenticated())
            .httpBasic(basic -> {});
        return http.build();
    }

    /**
     * 배포용 자격증명. 환경변수(SPHINX_API_USER·SPHINX_API_PASSWORD)에서만 읽는다.
     *
     * 미설정이면 기동을 거부한다. 이 빈이 없으면 Spring Boot 가 임의 비밀번호를 만들어
     * 기동 로그에 찍는데, 그 로그가 남는 곳이 곧 자격증명이 노출되는 곳이 된다.
     * 조용히 뜨는 것보다 안 뜨는 편이 낫다.
     */
    @Bean
    @Profile("prod")
    public UserDetailsService prodUsers(@Value("${sphinx.api.auth.username:}") String username,
                                        @Value("${sphinx.api.auth.password:}") String password,
                                        PasswordEncoder encoder) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "prod 프로파일에는 SPHINX_API_USER·SPHINX_API_PASSWORD 환경변수가 필요하다. "
                    + "설정하지 않으면 Spring 이 임의 비밀번호를 생성해 기동 로그에 남긴다.");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password(encoder.encode(password)).roles("API").build());
    }

    @Bean
    @Profile("prod")
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
