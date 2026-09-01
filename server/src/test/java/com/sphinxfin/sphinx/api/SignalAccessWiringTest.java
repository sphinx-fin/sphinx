package com.sphinxfin.sphinx.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 불공정영업 신호 조회 접근통제 (이슈 #63). 소유: 정세현
 *
 * <p>{@code enforce=true} 로 돌린다 — 기본값에서는 {@code AccessGuard} 가 정책을 아예 안
 * 부르므로 이 파일이 재려는 것을 못 잰다({@code ProductAccessWiringTest} 와 같은 이유).
 *
 * <p>❗<b>무서운 쪽은 덜 허용하는 변이가 아니라 더 허용하는 변이다.</b> 이 신호가 판매자에게
 * 열리면 기획 7-4 가 막으려는 역이용이 그대로 성립한다 — 무엇이 탐지되는지 알면 문면만
 * 바꿔 같은 영업을 한다. 그래서 거부 쪽 단정을 먼저 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sphinx.security.enforce=true")
@DisplayName("불공정영업 신호 조회 (이슈 #63)")
class SignalAccessWiringTest {

    @Autowired private MockMvc mvc;

    private static RequestPostProcessor as(String id, String role) {
        return user(id).roles(role);
    }

    @Test
    @DisplayName("❗판매자는 못 본다 — 무엇이 탐지되는지 알면 문면만 바꿔 같은 영업을 한다")
    void theSellerCannotSeeTheSignals() throws Exception {
        mvc.perform(get("/signals/unfair").with(as("seller-01", "SELLER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("❗지점장도 못 본다 — signal:unfair:read 에 MGR 이 **부재**다 (ADR-001 과 같은 결)")
    void theBranchManagerCannotSeeThemEither() throws Exception {
        // 감독 역할이라고 다 열리지 않는다. MGR 은 판매 라인에 가깝고, 지점 성과와
        // 붙어 있는 자리라 이 신호가 그쪽으로 흐르면 역이용 경로가 다시 생긴다.
        mvc.perform(get("/signals/unfair").with(as("mgr-01", "MGR")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("❗관리자도 못 본다 — ADMIN 은 계정·설정이지 데이터가 아니다")
    void theAdminCannotSeeThemEither() throws Exception {
        mvc.perform(get("/signals/unfair").with(as("admin-01", "ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("준법감시는 본다 — 막기만 하는 구현도 위 단정들을 통과한다")
    void complianceCanRead() throws Exception {
        mvc.perform(get("/signals/unfair").with(as("compl-01", "COMPL")))
                .andExpect(status().isOk());
    }
}
