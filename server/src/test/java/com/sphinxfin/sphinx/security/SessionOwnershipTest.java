package com.sphinxfin.sphinx.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.sphinxfin.sphinx.core.session.Session;
import com.sphinxfin.sphinx.core.session.SessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 세션 귀속(F-CMN-002). 소유: 강희진
 *
 * <p>{@code rbac_policy.yaml} 의 {@code own_session}·{@code branch} 는 세션에 <b>누구 것인지</b>
 * 가 적혀 있어야 평가된다. 그 값이 어디서 오는가가 이 테스트의 전부다 —
 * <b>인증 주체에서만 오고 요청 본문에서는 오지 않는다.</b>
 *
 * <p>본문으로 받으면 판매자가 자기가 아닌 사람을 소유자로 적을 수 있고, 그러면
 * {@code own_session} 이 견제가 아니라 자기 신고가 된다. 오버라이드 승인자를 본문에서 뺀 것과
 * 같은 이유다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-CMN-002 세션 귀속")
class SessionOwnershipTest {

    @Autowired private MockMvc mvc;
    @Autowired private SessionRepository sessions;
    @Autowired private ObjectMapper mapper;

    private Session createdBy(String body) throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sid = JsonPath.read(created, "$.data.sessionId");
        return sessions.findById(sid).orElseThrow();
    }

    private static final String PLAIN = """
            {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}""";

    @Nested
    @DisplayName("귀속은 인증 주체에서만 온다")
    class OwnershipComesFromPrincipal {

        @Test
        @WithMockUser(username = "seller-01", roles = "SELLER")
        @DisplayName("세션을 만든 사람이 진행 주체로 적힌다")
        void sellerIdComesFromAuthentication() throws Exception {
            assertThat(createdBy(PLAIN).sellerId()).isEqualTo("seller-01");
        }

        @Test
        @WithMockUser(username = "seller-01", roles = "SELLER")
        @DisplayName("❗본문으로 남을 소유자로 적을 수 없다 — own_session 이 자기 신고가 되면 안 된다")
        void bodyCannotClaimOwnership() throws Exception {
            // 계약에 없는 필드지만, 누가 보내더라도 무시되는지를 고정한다.
            String spoofed = """
                    {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대",
                     "sellerId":"seller-99","branchId":"BR-9"}""";

            Session session = createdBy(spoofed);

            assertThat(session.sellerId())
                    .as("본문 값이 먹히면 판매자가 남의 세션을 자기 것으로, 자기 세션을 남의 "
                            + "것으로 만들 수 있다")
                    .isEqualTo("seller-01");
            assertThat(session.branchId()).isNotEqualTo("BR-9");
        }

        @Test
        @WithAnonymousUser
        @DisplayName("익명이면 주인이 없다 — 만들기를 막지는 않는다(dev 프로파일)")
        void anonymousLeavesNoOwner() throws Exception {
            assertThat(createdBy(PLAIN).sellerId())
                    .as("인증이 없다고 세션 생성을 막으면 permitAll 인 dev 에서 화면이 전부 죽는다")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("적힌 귀속으로 scope 가 평가된다")
    class ScopeIsEvaluable {

        @Autowired private AccessPolicy policy;

        @Test
        @WithMockUser(username = "seller-01", roles = "SELLER")
        @DisplayName("자기 세션은 own_session 으로 통과하고 남의 세션은 막힌다")
        void ownSessionNowEvaluates() throws Exception {
            Session mine = createdBy(PLAIN);

            var owner = new AccessPolicy.Actor("seller-01", Role.SELLER, null);
            var other = new AccessPolicy.Actor("seller-02", Role.SELLER, null);
            var target = AccessPolicy.Target.session(mine.id(), mine.sellerId(), mine.branchId());

            assertThat(policy.permits(owner, "report:read", target))
                    .as("귀속이 없던 동안에는 자기 세션도 '판단 불가 → 거부' 였다")
                    .isTrue();
            assertThat(policy.permits(other, "report:read", target)).isFalse();
        }

        @Test
        @WithAnonymousUser
        @DisplayName("주인 없는 세션은 아무도 own_session 으로 못 읽는다 — 통과가 아니라 거부다")
        void ownerlessSessionIsDeniedNotAllowed() throws Exception {
            Session ownerless = createdBy(PLAIN);

            var anyone = new AccessPolicy.Actor("seller-01", Role.SELLER, null);
            var target = AccessPolicy.Target.session(
                    ownerless.id(), ownerless.sellerId(), ownerless.branchId());

            assertThat(policy.permits(anyone, "report:read", target))
                    .as("주인을 모르는 세션을 누군가의 것으로 쳐 주면 own_session 이 견제가 아니다")
                    .isFalse();
        }
    }
}
