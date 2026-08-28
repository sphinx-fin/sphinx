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

import static org.assertj.core.api.Assertions.assertThat;
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
    /** 고객 역할. 대면 데모에서는 안 쓰이지만 정책에는 그랜트가 있다(#166 · 결정 7.24). */
    private static RequestPostProcessor cust(String id) {
        return user(id).roles("CUST");
    }

    @Test
    @DisplayName("❗귀속은 언제나 판매자다 — CUST 는 이름이 안 맞아 막히고, 맞으면 통과한다")
    void ownershipComesFromTheSellerNotTheRole() throws Exception {
        // 결정 7.24·7.25 가 세운 사실을 **배선 층에서** 고정한다. AccessPolicyTest 는
        // AS_WIRED 라는 상수로 그 모양을 손으로 적는데, AccessGuard.targetOf 가 바뀌면
        // 그 상수만 낡고 테스트는 초록이다 — 이 클래스 docstring 이 경고하는 그 함정이다.
        String created = mvc.perform(post("/sessions").with(seller("seller-01"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sid = JsonPath.read(created, "$.data.sessionId");

        String answer = "{\"itemId\":\"ELS-PRINCIPAL-LOSS-WARNING\",\"text\":\"낙인 하회하면 손실 납니다\"}";

        // session:answer 는 { roles: [CUST, SELLER], scope: own_session } 이다.
        // 역할은 있는데 이름이 sellerId 와 달라 막힌다.
        mvc.perform(post("/sessions/" + sid + "/answers").with(cust("cust-01"))
                        .contentType(MediaType.APPLICATION_JSON).content(answer))
                .andExpect(status().isForbidden());

        // ❗같은 CUST 역할이라도 이름이 sellerId 와 같으면 **판매자와 똑같이** 통과한다.
        //   막는 것이 역할이 아니라 ownerId 대조라는 것이 여기서 드러난다.
        //
        //   isNotEqualTo(403) 으로 두지 않는다 — 그건 401 에도 만족된다. currentActor() 가
        //   던지는 경로가 열리면(#105 가 401·403 을 가른 자리다) 이 단정은 초록인데 귀속에
        //   대해 아무것도 증명하지 않는다. 정당한 판매자와 같은 코드인지를 보면 그 구멍이
        //   닫히고, 하류 결과에도 안 묶인다 — 오늘은 둘 다 502(ai-service 없음)이고
        //   나중에 목이 붙으면 둘 다 200 이 된다 (#174 리뷰).
        int asSeller = answerAs(sid, seller("seller-01"), answer);
        int asCustNamedLikeSeller = answerAs(sid, cust("seller-01"), answer);

        assertThat(asCustNamedLikeSeller)
                .as("역할이 CUST 여도 이름이 sellerId 와 같으면 판매자와 똑같이 통과한다 — "
                        + "귀속이 역할이 아니라 이름 대조라는 뜻이다(결정 7.24). 여기가 "
                        + "갈리면 귀속 모델이 바뀐 것이므로 rbac_policy.yaml 의 CUST 도달 "
                        + "불가 주석과 7.24·7.25 를 같이 고쳐야 한다")
                .isEqualTo(asSeller);
    }

    /** 답변을 한 번 넣고 상태코드만 돌려준다. 하류 결과가 아니라 인가 결과를 보려는 것이다. */
    private int answerAs(String sid, RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(post("/sessions/" + sid + "/answers").with(who)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }
}
