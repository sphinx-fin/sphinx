package com.sphinxfin.sphinx.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

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
@lombok.extern.slf4j.Slf4j
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
    public SecurityFilterChain prodFilterChain(HttpSecurity http,
                                               ApiSecurityErrorHandlers errors) throws Exception {
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
            // 필터 단계에서 끊긴 요청도 같은 봉투로 답한다. 기본값이면 빈 본문·HTML 이 나가
            // 화면 파싱이 그 경로에서만 깨진다.
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(errors.entryPoint())
                    .accessDeniedHandler(errors.accessDeniedHandler()))
            .httpBasic(basic -> {});
        return http.build();
    }

    /**
     * 배포용 계정 — <b>명부에서 만든다</b>({@code demo_accounts.yaml}, 결정 10.5 · 이슈 #41).
     *
     * <h2>왜 단일 계정으로는 안 되나</h2>
     *
     * <p>전에는 {@code roles("API")} 짜리 한 명이었다. 그러면 <b>인증은 서는데 인가가 아무것도
     * 안 가른다</b> — 실측했다.
     *
     * <pre>
     * GET /dashboard/heatmap  무인증        → 401
     * GET /dashboard/heatmap  demo(API)     → 200   ← SELLER 였다면 403 이어야 한다
     * </pre>
     *
     * <p>ADR-001 시연(<i>"SELLER 는 집계에 닿지 못한다"</i>)이 그 상태로는 성립하지 않는다.
     * {@code AccessGuard} 가 {@code Role.valueOf(권한이름)} 으로 역할을 읽으므로, 권한이
     * {@code ROLE_API} 면 정책에 대응하는 역할이 없다.
     *
     * <h2>자격증명은 명부에 없다</h2>
     *
     * <p>{@code demo_accounts.yaml} 은 <i>"누가 있고 무엇인가"</i> 까지만 말한다(#163). 비밀번호를
     * 거기 적으면 그 값이 곧 배포 자격증명이 되고 파일을 지워도 git 이력에 남는다. 그래서
     * <b>계정 목록은 명부에서, 비밀번호는 환경변수에서</b> 온다.
     *
     * <p>❗<b>일곱 계정이 같은 비밀번호다.</b> 하나를 알면 어느 역할로도 로그인할 수 있다 —
     * 그리고 <b>데모에서는 그게 필요하다.</b> 심사에서 역할을 바꿔 가며 차단을 보여줘야 하는데
     * 계정마다 다른 값을 두면 환경변수가 일곱 개가 된다. 실제 운영 계정 체계가 아니라
     * <b>역할별 차단을 시연하기 위한 구성</b>이고, 그 사실이 여기 적혀 있어야 다음 사람이
     * 이걸 운영용으로 오해하지 않는다.
     *
     * <p>미설정이면 기동을 거부한다. 이 빈이 없으면 Spring Boot 가 임의 비밀번호를 만들어
     * 기동 로그에 찍는데, <b>그 로그가 남는 곳이 곧 자격증명이 노출되는 곳</b>이 된다.
     * 조용히 뜨는 것보다 안 뜨는 편이 낫다.
     *
     * <p>{@code SPHINX_API_USER} 는 그대로 받는다 — nginx {@code auth_basic}(#162)이 그 값으로
     * htpasswd 를 만들고 브라우저가 실어 보낸 {@code Authorization} 이 여기까지 온다. 그 계정도
     * 명부에 있어야 통과하므로, <b>둘이 어긋나면 화면이 열리는데 API 가 401</b> 이 된다.
     */
    @Bean
    @Profile("prod")
    public UserDetailsService prodUsers(@Value("${sphinx.api.auth.username:}") String username,
                                        @Value("${sphinx.api.auth.password:}") String password,
                                        DemoAccountsFile roster,
                                        PasswordEncoder encoder) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "prod 프로파일에는 SPHINX_API_USER·SPHINX_API_PASSWORD 환경변수가 필요하다. "
                    + "설정하지 않으면 Spring 이 임의 비밀번호를 생성해 기동 로그에 남긴다.");
        }
        if (roster.byId(username).isEmpty()) {
            throw new IllegalStateException(
                    "SPHINX_API_USER 가 명부에 없다: " + username + ". nginx 가 이 계정으로 "
                    + "htpasswd 를 만들므로(#162) 화면은 열리는데 API 가 전부 401 이 된다. "
                    + "demo_accounts.yaml 의 id 중 하나여야 한다 (결정 10.5)");
        }
        String hashed = encoder.encode(password);
        List<UserDetails> users = roster.accounts().stream()
                .map(a -> (UserDetails) User.withUsername(a.actorId())
                        .password(hashed)
                        .roles(a.role().name())     // AccessGuard 가 ROLE_ 접두어를 떼고 Role.valueOf 한다
                        .build())
                .toList();
        log.info("prod 계정 {}건 등록 — {} (비밀번호 {}자, 전 계정 공통)",
                users.size(),
                roster.accounts().stream().map(a -> a.actorId() + ":" + a.role()).toList(),
                password.length());
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    @Profile("prod")
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
