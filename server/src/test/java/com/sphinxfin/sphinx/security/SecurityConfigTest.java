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
            "sphinx.api.auth.username=demo",
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
                                    .httpBasic("demo", "test-only-not-a-real-credential")))
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
}
