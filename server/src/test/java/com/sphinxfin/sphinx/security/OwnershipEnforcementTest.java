package com.sphinxfin.sphinx.security;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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

    /** @WithMockUser 는 메서드 단위라 못 갈아탄다. 한 테스트에서 두 사람을 쓰려면 이쪽이다. */
    private static RequestPostProcessor seller(String id) {
        return user(id).roles("SELLER");
    }

    @Test
    @DisplayName("❗남의 세션은 못 읽는다 — own_session 이 실제로 막는지는 이것만 본다")
    void otherSellersSessionIsForbidden() throws Exception {
        // 허용만 거는 테스트로는 **과허용 변이**가 안 잡힌다. targetOf 가 요청자를 소유자로
        // 쳐 주도록 바꿔도(= own_session 무력화) 다른 테스트는 전부 통과한다 — 접근 통제에서
        // 무서운 쪽은 덜 허용하는 변이가 아니라 더 허용하는 변이다.
        String created = mvc.perform(post("/sessions").with(seller("seller-01"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sid = JsonPath.read(created, "$.data.sessionId");

        // 소유자 쪽은 **200 이어야** 한다. 404(미발행)로는 "막히지 않았다"밖에 못 보고, 이
        // 테스트가 잡으려는 것은 과허용 변이라 허용 쪽이 끝까지 가는 것을 봐야 한다.
        // 그래서 먼저 발행한다 — report:issue 도 SELLER own_session 이므로 같은 사람이 한다.
        mvc.perform(post("/sessions/" + sid + "/report").with(seller("seller-01")))
                .andExpect(status().isOk());

        mvc.perform(get("/sessions/" + sid + "/report").with(seller("seller-01")))
                .andExpect(status().isOk());
        mvc.perform(get("/sessions/" + sid + "/report").with(seller("seller-02")))
                .andExpect(status().isForbidden());
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
