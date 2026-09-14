package com.sphinxfin.sphinx.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배포 프로파일이 실제로 닫는지 확인한다. 소유: 강희진
 *
 * 이 테스트가 지키는 것은 "인증 없는 요청을 받지 않는다"까지다. 역할별 차단(인가)은
 * AccessPolicy 가 서야 붙으며 여기 범위가 아니다 — 그 구분이 흐려지면 "보안이 됐다"고
 * 착각하게 된다.
 */
@DisplayName("F-CMN-002 SecurityConfig 프로파일 분리")
class SecurityConfigTest {

    // ── prod 프로파일을 띄우되 DB 는 H2 로 되돌린다 ────────────────────────
    //
    // `application-prod.yml` 이 MySQL 을 가리키고 자격증명을 **기본값 없이** 요구한다.
    // 그건 배포에서 조용히 빈 값으로 뜨는 것을 막으려는 것이라 옳은데(#126), 그대로면
    // 여기서 컨텍스트가 안 뜬다 — 이 테스트가 재는 것은 보안 설정이지 DB 가 아니다.
    //
    // ❗**H2 로 되돌리는 것이 검사를 무르게 하지 않는다.** 이 클래스가 잠그는 것은
    // `SecurityConfig` 의 prod 체인(인증 요구 · H2 콘솔 차단 · 역할 분리)이고, 그건
    // 데이터소스와 무관하다. 반대로 여기서 MySQL 을 띄우려 들면 테스트가 도커에
    // 의존하게 되고 CI 가 그걸 못 준다.
    //
    // flyway 를 끄고 `create-drop` 을 쓰는 이유: 마이그레이션 SQL 은 MySQL 문법이라
    // H2 에서 파싱부터 실패한다. 스키마는 엔티티에서 만든다(기본 프로파일과 같은 방식).
    /**
     * 두 프로파일이 <b>같은 자리에서 다른 답</b>을 내는지 보는 경로들.
     *
     * <p>한 벌로 두는 이유는 대조가 짝으로만 성립하기 때문이다 — prod 에서 401 인 것과
     * dev 에서 401 이 아닌 것이 <b>같은 경로</b>여야 「프로파일이 가른다」가 증명된다.
     * 따로 적으면 한쪽만 늘어난 것을 아무도 모른다(이슈 #611).
     */
    private static final java.util.List<String> GUARDED_PATHS = java.util.List.of(
            "/products/doc-els-kiwoom-4181/risk-items",
            "/sessions/any",
            "/dashboard/heatmap");

    private static final String DB_URL =
            "spring.datasource.url=jdbc:h2:mem:sectest;DB_CLOSE_DELAY=-1";
    private static final String DB_DRIVER = "spring.datasource.driver-class-name=org.h2.Driver";
    private static final String DB_USER = "spring.datasource.username=sa";
    private static final String DB_PASSWORD = "spring.datasource.password=";
    private static final String DB_NO_FLYWAY = "spring.flyway.enabled=false";
    private static final String DB_DDL = "spring.jpa.hibernate.ddl-auto=create-drop";

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @DisplayName("로컬 개발(기본 프로파일)")
    class Dev {
        @Autowired
        MockMvc mvc;

        /**
         * ❗<b>데이터에 기대는 경로를 쓰지 않는다</b> (이슈 #611). 예전에는
         * {@code /products/{id}/risk-items} 를 불렀는데, 그 경로는 <b>저장된 추출이 DB 에
         * 있어야</b> 200 이다({@code #478} 이 MockData 폴백을 걷은 뒤로).
         *
         * <p>그래서 전체 실행에서는 <b>앞선 테스트가 H2 에 남긴 것</b> 덕에 초록이고,
         * {@code --tests '…SecurityConfigTest'} 로 <b>혼자 돌리면 404</b> 였다. 정책 파일을
         * 만지는 사람이 가장 먼저 치는 것이 그 명령이고({@code CLAUDE.md} 가 그 형태를
         * 적어 뒀다), 그러면 <b>자기 변경과 무관한 빨강</b>을 본다.
         *
         * <p>❗<b>바로 옆 {@code Prod.allowsAuthenticated} 가 같은 이유로 이미 고쳐져
         * 있었다</b>({@code #478}) — <i>"인증을 재는 테스트가 카탈로그 상태 때문에 빨개지면
         * 재는 것과 깨지는 이유가 달라진다"</i>. 형제 쪽만 안 따라왔다.
         *
         * <p>지금은 <b>{@code Prod.rejectsAnonymous} 와 같은 세 경로</b>를 본다. 거기서
         * 401 인 자리가 여기서는 401 이 아니어야 하고, 그 대조가 이 클래스가 재려는 것
         * 자체다. 상태를 200 으로 못 박지 않는 이유는 <b>404 든 200 이든 인증 때문이
         * 아니면 통과</b>가 이 테스트의 뜻이기 때문이다.
         */
        @Test
        @DisplayName("전면 허용 — 프론트가 인증 없이 개발할 수 있다")
        void permitsEverything() throws Exception {
            // 체인이 아예 안 서는 것(전부 500)과 구별한다 — 목록은 비어도 200 이다.
            mvc.perform(get("/products")).andExpect(status().isOk());

            for (String path : GUARDED_PATHS) {
                mvc.perform(get(path))
                        .andExpect(status().is(org.hamcrest.Matchers.not(401)));
            }
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("prod")
    @TestPropertySource(properties = {
            // ❗명부(demo_accounts.yaml)에 있는 id 여야 한다 — 없으면 SecurityConfig 가
            // 기동을 거부한다(#41). nginx htpasswd 도 이 값으로 만들어지므로 어긋나면
            // 화면은 열리는데 API 가 전부 401 이 된다.
            "sphinx.api.auth.username=seller-01",
            "sphinx.api.auth.password=test-only-not-a-real-credential",
            DB_URL, DB_DRIVER, DB_USER, DB_PASSWORD, DB_NO_FLYWAY, DB_DDL
    })
    @DisplayName("배포(prod 프로파일)")
    class Prod {
        @Autowired
        MockMvc mvc;

        @Test
        @DisplayName("인증 없는 API 요청 → 401")
        void rejectsAnonymous() throws Exception {
            // ❗Dev.permitsEverything 이 **같은 목록**을 본다 — 한쪽만 늘리면 두 프로파일의
            //   대조가 그만큼 비는데, 그 사실이 어느 쪽에서도 안 보인다(이슈 #611).
            for (String path : GUARDED_PATHS) {
                mvc.perform(get(path)).andExpect(status().isUnauthorized());
            }
        }

        @Test
        @DisplayName("자격증명이 맞으면 통과")
        void allowsAuthenticated() throws Exception {
            // ❗**데이터에 기대지 않는 경로를 쓴다**(이슈 #478). 예전에는 risk-items 를 불렀고
            //   MockData 폴백이 항목을 내줘서 200 이었다. 폴백을 걷으면 추출 없는 상품이
            //   404 라, **인증을 재는 테스트가 카탈로그 상태 때문에 빨개진다** — 재는 것과
            //   깨지는 이유가 달라지면 다음 사람이 원인을 여기서 못 찾는다.
            //   `GET /products` 는 목록이라 비어도 200 이다.
            mvc.perform(get("/products")
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors
                                    .httpBasic("seller-01", "test-only-not-a-real-credential")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("헬스체크는 인증 없이 열린다 — 컨테이너·LB 가 부른다")
        void healthIsPublic() throws Exception {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("H2 콘솔이 꺼져 있다 — 브라우저로 판정 기록을 고칠 수 없다")
        void h2ConsoleIsOff() throws Exception {
            // 콘솔 서블릿이 등록되지 않으므로 401(인증 필터) 또는 404 이며, 200 이면 안 된다.
            mvc.perform(get("/h2-console"))
                    .andExpect(result -> {
                        int s = result.getResponse().getStatus();
                        if (s == 200) {
                            throw new AssertionError("prod 에서 H2 콘솔이 열려 있다: " + s);
                        }
                    });
        }
    }
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("prod")
    @TestPropertySource(properties = {
            "sphinx.api.auth.username=seller-01",
            "sphinx.api.auth.password=test-only-not-a-real-credential",
            DB_URL, DB_DRIVER, DB_USER, DB_PASSWORD, DB_NO_FLYWAY, DB_DDL
    })
    @DisplayName("배포에서 역할이 실제로 가른다 (이슈 #41 ①)")
    class ProdRoles {

        private static final String PW = "test-only-not-a-real-credential";

        @Autowired
        MockMvc mvc;

        /**
         * ❗이 Nested 가 없으면 <b>인증만 서고 인가는 아무것도 안 가른다.</b>
         *
         * <p>전에는 prod 계정이 {@code roles("API")} 하나였고 {@code sphinx.security.enforce} 가
         * 코드 기본값 {@code false} 였다. 그 상태를 실측하면 이렇다.
         *
         * <pre>
         * GET /dashboard/heatmap  무인증        → 401
         * GET /dashboard/heatmap  demo(API)     → 200   ← SELLER 였다면 403 이어야 한다
         * </pre>
         *
         * <p>ADR-001 시연이 그 상태로는 성립하지 않는다 — 심사에서 <i>"SELLER 로 집계를 열어
         * 보세요"</i> 가 나오면 열린다.
         */
        @Test
        @DisplayName("❗SELLER 는 집계에 닿지 못한다 — ADR-001 시연의 실물")
        void sellerCannotReachAggregate() throws Exception {
            mvc.perform(get("/dashboard/heatmap").with(httpBasic("seller-01", PW)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/dashboard/leading-indicators").with(httpBasic("seller-01", PW)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("❗COMPL 은 집계를 org 범위로 본다 — 막는 것과 못 여는 것은 다르다")
        void complSeesOrgWideAggregate() throws Exception {
            mvc.perform(get("/dashboard/heatmap").with(httpBasic("compl-01", PW)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scope").value("org"));
        }

        @Test
        @DisplayName("❗MGR 은 branch 로 좁혀진다 — 계정에 지점이 실려야 성립한다")
        void mgrIsNarrowedToHisBranch() throws Exception {
            mvc.perform(get("/dashboard/heatmap").with(httpBasic("mgr-01", PW)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scope").value("branch"));
        }

        @Test
        @DisplayName("❗명부에 없는 계정은 인증부터 막힌다")
        void unknownAccountIsRejected() throws Exception {
            mvc.perform(get("/products/doc-els-kiwoom-4181/risk-items").with(httpBasic("nobody", PW)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("역할이 다르면 결과가 다르다 — 같은 요청, 다른 답")
        void sameRequestDiffersByRole() throws Exception {
            int seller = mvc.perform(get("/dashboard/heatmap").with(httpBasic("seller-01", PW)))
                    .andReturn().getResponse().getStatus();
            int compl = mvc.perform(get("/dashboard/heatmap").with(httpBasic("compl-01", PW)))
                    .andReturn().getResponse().getStatus();

            org.assertj.core.api.Assertions.assertThat(seller)
                    .as("둘이 같으면 역할이 아무것도 안 가르고 있다 — 계정을 늘려도 "
                            + "enforce 가 꺼져 있으면 그렇게 된다(이슈 #41 ①)")
                    .isNotEqualTo(compl);
        }
    }

    @Nested
    @DisplayName("SPHINX_API_USER 가 명부에 없으면 기동을 거부한다 (이슈 #41)")
    class RosterGuard {

        /**
         * ❗<b>이 단정이 없으면 가드를 지워도 아무도 모른다.</b> 역검증에서 확인했다 —
         * {@code if (roster.byId(username).isEmpty())} 를 빼도 나머지 테스트가 전부 초록이다.
         * 그 계정으로 로그인하지 않으면 드러나지 않기 때문이다.
         *
         * <p>드러나는 시점은 <b>배포한 뒤</b>다. nginx 가 {@code SPHINX_API_USER} 로 htpasswd 를
         * 만들므로(#162) 화면은 그 계정으로 열리는데, 그 계정이 명부에 없으면 <b>브라우저가
         * 실어 보낸 Authorization 이 server 에서 401</b> 이 된다 — 화면은 뜨는데 API 가 전부
         * 죽는 상태이고, 원인이 "SSM 비밀번호가 틀렸나" 로 읽힌다.
         */
        @Test
        @DisplayName("❗명부에 없는 id 면 컨텍스트가 안 뜬다 — 화면만 열리고 API 가 죽는 것을 막는다")
        void unknownUsernameFailsStartup() {
            DemoAccountsFile roster = new DemoAccountsFile();
            SecurityConfig config = new SecurityConfig();

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            config.prodUsers("nobody", "test-only-not-a-real-credential",
                                    roster, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()))
                    .as("명부에 없는 계정으로 뜨면 nginx 와 어긋나 화면만 열린다(#162)")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("명부에 없다")
                    .hasMessageContaining("nobody");
        }

        @Test
        @DisplayName("명부에 있는 id 면 계정이 만들어진다 — 거부만 재면 늘 거부하는 구현도 통과한다")
        void knownUsernameBuildsAccounts() {
            DemoAccountsFile roster = new DemoAccountsFile();
            org.springframework.security.core.userdetails.UserDetailsService svc =
                    new SecurityConfig().prodUsers("seller-01", "test-only-not-a-real-credential",
                            roster, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder());

            org.assertj.core.api.Assertions.assertThat(
                            svc.loadUserByUsername("compl-01").getAuthorities())
                    .as("명부의 다른 계정도 같이 등록돼야 역할 전환 시연이 된다")
                    .extracting(Object::toString)
                    .containsExactly("ROLE_COMPL");
        }

    }

}
