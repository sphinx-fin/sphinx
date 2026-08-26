package com.sphinxfin.sphinx.security;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 귀속이 <b>실제 요청 경로에서</b> scope 판단에 쓰이는가 (F-CMN-002). 소유: 강희진
 *
 * <p>{@code AccessPolicy} 를 직접 부르는 테스트로는 부족하다 — 그건 {@code AccessGuard.targetOf}
 * 가 세션에서 귀속을 꺼내 오는 배선을 건너뛴다. 실제로 그 배선을 지우고 돌려 보면 정책 단위
 * 테스트는 전부 통과한다. 그래서 여기서는 HTTP 로 건다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sphinx.security.enforce=true")
@DisplayName("F-CMN-002 귀속 기반 차단 (enforce=true)")
class OwnershipEnforcementTest {

    @Autowired private MockMvc mvc;

    private static final String BODY = """
            {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}""";

    private String createAs() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.data.sessionId");
    }

    @Test
    @WithMockUser(username = "seller-01", roles = "SELLER")
    @DisplayName("❗SELLER 가 세션을 만들 수 있다 — own_session 인데 대상이 아직 없다")
    void sellerCanCreateSession() throws Exception {
        mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "seller-01", roles = "SELLER")
    @DisplayName("자기 세션은 읽는다")
    void ownSessionIsReadable() throws Exception {
        String sid = createAs();
        mvc.perform(get("/sessions/" + sid)).andExpect(status().isOk());
    }
}
