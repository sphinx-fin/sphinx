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

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @DisplayName("로컬 개발(기본 프로파일)")
    class Dev {
        @Autowired
        MockMvc mvc;

        @Test
        @DisplayName("전면 허용 — 프론트가 인증 없이 개발할 수 있다")
        void permitsEverything() throws Exception {
            mvc.perform(get("/products/mock-els-001/risk-items")).andExpect(status().isOk());
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
            "sphinx.api.auth.password=test-only-not-a-real-credential"
    })
    @DisplayName("배포(prod 프로파일)")
    class Prod {
        @Autowired
        MockMvc mvc;

        @Test
        @DisplayName("인증 없는 API 요청 → 401")
        void rejectsAnonymous() throws Exception {
            mvc.perform(get("/products/mock-els-001/risk-items")).andExpect(status().isUnauthorized());
            mvc.perform(get("/sessions/any")).andExpect(status().isUnauthorized());
            mvc.perform(get("/dashboard/heatmap")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("자격증명이 맞으면 통과")
        void allowsAuthenticated() throws Exception {
            mvc.perform(get("/products/mock-els-001/risk-items")
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
            "sphinx.api.auth.password=test-only-not-a-real-credential"
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
            mvc.perform(get("/products/mock-els-001/risk-items").with(httpBasic("nobody", PW)))
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
