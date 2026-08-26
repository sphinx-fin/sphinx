package com.sphinxfin.sphinx.security;

import com.sphinxfin.sphinx.evidence.AuditLog;
import com.sphinxfin.sphinx.evidence.EvidenceEntryRepository;
import com.sphinxfin.sphinx.evidence.EvidenceStreamAnchorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-CMN-002 차단이 <b>차단으로 보이는가</b>. 소유: 강희진
 *
 * <p>여기서 지키는 것은 기획 7-4 시연의 전제다 — 막힌 요청이 <b>403 으로</b> 응답되고
 * 감사 로그에 <b>403 으로</b> 남아야 한다. 이게 없으면 차단이 500(서버 오류)으로 떨어져서,
 * 화면에는 고장 난 것으로 보이고 감사에서는 차단 시도가 오류 더미에 섞인다. 둘 다
 * "막고 있다"를 증명하지 못하게 만든다.
 *
 * <p>{@code sphinx.security.enforce=true} 로 띄운다. 기본값(false)에서는 {@code AccessGuard}
 * 가 정책을 부르지 않고 통과시키므로 이 성질을 확인할 수 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sphinx.security.enforce=true")
@DisplayName("F-CMN-002 접근 차단 (enforce=true)")
class AccessEnforcementTest {

    @Autowired private MockMvc mvc;
    @Autowired private AuditLog auditLog;
    @Autowired private EvidenceEntryRepository entries;
    @Autowired private EvidenceStreamAnchorRepository anchors;

    /** 감사 스트림은 트랜잭션 밖에서 커밋되므로 테스트 롤백으로 안 지워진다. */
    @BeforeEach
    void clearAuditStream() {
        entries.deleteAll();
        anchors.deleteAll();
    }

    private Map<String, Object> lastAudit() {
        List<com.sphinxfin.sphinx.evidence.HashChain.ChainEntry> all = auditLog.replay();
        assertThat(all).as("감사 기록이 하나도 없다 — 인터셉터가 안 돌았다").isNotEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) all.get(all.size() - 1).payload();
        return payload;
    }

    @Nested
    @DisplayName("ADR-001 시연 — SELLER 는 집계에 닿지 못한다")
    class SellerCannotReachAggregate {

        @Test
        @WithMockUser(username = "seller-01", roles = "SELLER")
        @DisplayName("❗SELLER + 집계 → 403. 500 이 아니다")
        void sellerGetsForbiddenNotServerError() throws Exception {
            mvc.perform(get("/dashboard/heatmap"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }

        @Test
        @WithMockUser(username = "seller-01", roles = "SELLER")
        @DisplayName("❗차단이 감사 로그에 403 으로 남는다 — 500 이면 오류 더미에 섞인다")
        void denialIsAuditedAsForbidden() throws Exception {
            mvc.perform(get("/dashboard/heatmap")).andExpect(status().isForbidden());

            Map<String, Object> audit = lastAudit();
            assertThat(audit.get("resultCode"))
                    .as("기획 7-4 2단계가 보려는 건 차단당한 시도의 반복이다. "
                            + "500 으로 남으면 그게 서버 오류와 구별되지 않는다")
                    .isEqualTo("403");
            assertThat(audit.get("action")).isEqualTo("aggregate:heatmap:read");
            assertThat(audit.get("actorId"))
                    .as("누가 막혔는지가 감사의 핵심이다")
                    .isEqualTo("seller-01");
            assertThat(audit.get("role")).isEqualTo("ROLE_SELLER");
        }

        @Test
        @WithMockUser(username = "compl-01", roles = "COMPL")
        @DisplayName("COMPL 은 같은 집계를 본다 — 막는 것이 아니라 가르는 것이다")
        void complPasses() throws Exception {
            mvc.perform(get("/dashboard/heatmap"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    @Nested
    @DisplayName("401 과 403 을 가른다")
    class UnauthenticatedIsNotForbidden {

        @Test
        @WithAnonymousUser
        @DisplayName("인증이 없으면 401 — 로그인하면 해소되는 상태다")
        void anonymousGetsUnauthorized() throws Exception {
            mvc.perform(get("/dashboard/heatmap"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }

        @Test
        @WithMockUser(username = "seller-01", roles = "SELLER")
        @DisplayName("권한이 없으면 403 — 로그인해도 해소되지 않는다")
        void authenticatedButDeniedGetsForbidden() throws Exception {
            mvc.perform(get("/dashboard/heatmap"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("응답이 리소스 존재를 알려주지 않는다")
    class DenialLeaksNothing {

        @Test
        @WithMockUser(username = "mgr-01", roles = "MGR")
        @DisplayName("차단 사유를 본문에 싣지 않는다 — 정책 문면이 존재를 알려준다")
        void reasonIsNotInBody() throws Exception {
            String body = mvc.perform(get("/sessions/does-not-exist/report"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .as("'다른 지점의 세션이다' 같은 사유가 나가면 지점 경계 너머로 "
                            + "세션 존재 여부가 샌다")
                    .doesNotContain("지점", "세션이 아니다", "그랜트");
        }
    }
}
