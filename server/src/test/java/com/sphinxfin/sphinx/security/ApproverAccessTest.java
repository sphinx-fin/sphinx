package com.sphinxfin.sphinx.security;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 승인자가 <b>승인 대상을 읽되 세션을 몰지는 못한다</b> (이슈 #124 · PR #129). 소유: 강희진
 *
 * <p>F-GTE-002 가 사유를 응답에 실은 목적이 *"승인자가 사유를 모르고 승인"* 을 없애는
 * 것인데, 승인자가 그 응답에 닿지 못하면 목적이 권한 층에서 무너진다. 반대로
 * {@code session:interview} 를 통째로 주면 같은 action 이 덮는 {@code abort} 까지 열려
 * <b>MGR 이 지점 내 아무 세션이나 중단</b>할 수 있게 된다.
 *
 * <p>그 둘 사이가 이 테스트가 지키는 자리다. {@code enforce=true} 로 띄운다 — 기본값에서는
 * {@code AccessGuard} 가 정책을 안 부르므로 확인할 수 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sphinx.security.enforce=true")
@DisplayName("F-GTE-002 승인자 접근 (enforce=true)")
class ApproverAccessTest {

    @Autowired private MockMvc mvc;
    @Autowired private AccessPolicy policy;

    private static final String BODY = """
            {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}""";

    private static RequestPostProcessor seller(String id) { return user(id).roles("SELLER"); }
    private static RequestPostProcessor mgr(String id)    { return user(id).roles("MGR"); }

    private String sessionBySeller(String sellerId) throws Exception {
        String created = mvc.perform(post("/sessions").with(seller(sellerId))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.data.sessionId");
    }

    @Nested
    @DisplayName("읽기와 진행이 갈린다")
    class ReadIsNotDrive {

        @Test
        @DisplayName("❗MGR 읽기는 정책상 허용이다 — 지금 막히는 것은 계정에 지점이 없어서다")
        void mgrReadIsGrantedByPolicy() {
            // 정책은 session:read 에 {MGR, scope: branch} 를 준다. 그런데 CurrentActor 가
            // 아직 지점을 못 읽어서(결정 10.5, 8/29) branch 판단이 성립하지 않는다 —
            // "막고 있다" 가 아니라 "판단할 수 없다" 이고, 그래서 HTTP 로는 여전히 403 이다.
            //
            // 여기서 정책 층을 직접 보는 이유: 어노테이션이 session:interview 로 되돌아가면
            // MGR 에게 그랜트가 아예 없어지는데, HTTP 만 보면 둘 다 403 이라 구별되지 않는다.
            var target = AccessPolicy.Target.session("S-1", "seller-01", "BR-1");
            var mgrWithBranch = new AccessPolicy.Actor("mgr-01", Role.MGR, "BR-1");

            assertThat(policy.decide(mgrWithBranch, "session:read", target).allowed())
                    .as("계정에 지점이 실리면 승인자가 승인 대상을 읽는다 — #124 의 요지")
                    .isTrue();
            assertThat(policy.decide(mgrWithBranch, "session:interview", target).allowed())
                    .as("진행은 여전히 못 한다 — 읽기와 진행을 가른 이유(#129)")
                    .isFalse();
        }

        @Test
        @DisplayName("지금은 HTTP 로 403 이다 — 계정에 지점이 없어 branch 를 판단할 수 없다")
        void mgrReadIsBlockedUntilAccountsCarryBranch() throws Exception {
            String sid = sessionBySeller("seller-01");

            // 10.5 가 오면 이 단정이 깨진다. 그때 이 테스트를 200 으로 뒤집으면서
            // #124 가 실제로 닫혔는지 확인하는 자리가 된다 — 빨간 테스트는 읽힌다.
            mvc.perform(get("/sessions/{sid}", sid).with(mgr("mgr-01")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("❗MGR 이 세션을 진행하지는 못한다 — abort 가 같은 action 이었다")
        void mgrCannotDriveTheSession() throws Exception {
            String sid = sessionBySeller("seller-01");

            // session:interview 는 SELLER own_session 뿐이다. MGR 에게 그 그랜트를 주면
            // 조회뿐 아니라 이 셋이 같이 열린다 — 그게 (a) 안을 접은 이유다(#129).
            mvc.perform(post("/sessions/{sid}/abort", sid).with(mgr("mgr-01")))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/sessions/{sid}/questions/next", sid).with(mgr("mgr-01")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("진행 주체(SELLER)는 자기 세션을 계속 진행한다")
        void sellerStillDrivesOwnSession() throws Exception {
            String sid = sessionBySeller("seller-01");

            mvc.perform(get("/sessions/{sid}", sid).with(seller("seller-01")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("남의 세션은 SELLER 도 못 읽는다 — own_session 은 그대로다")
        void otherSellerStillBlocked() throws Exception {
            String sid = sessionBySeller("seller-01");

            mvc.perform(get("/sessions/{sid}", sid).with(seller("seller-02")))
                    .andExpect(status().isForbidden());
        }
    }

}
