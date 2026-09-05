package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대시보드 두 뷰의 배선 (F-DSH-001·002, 이슈 #178 · #54 ⑤). 소유: 강희진
 *
 * <h2>여기서 고정하는 것</h2>
 *
 * <p>집계 계산은 {@code AggregateServiceTest} 가 본다. 이 파일은 <b>컨트롤러가 서비스에
 * 무엇을 넘기는가</b>만 본다 — 특히 <b>범위(scope)를 어디서 얻는가</b>다.
 *
 * <p>❗{@code scope} 를 쿼리로 받으면 MGR 이 {@code scope=org} 를 적어 보내는 것으로 정책을
 * 우회한다. 그래서 <b>받는 자리가 아예 없어야 한다</b>(결정 5.10) — 값이 맞는지가 아니라
 * <b>경로가 없는지</b>를 재는 것이 요지다.
 *
 * <p>{@code enforce=true} 로 돌린다. 기본값에서는 {@code grantedScope} 가 늘 ORG 를 돌려줘서
 * 범위 판단이 아예 안 돈다 — 그러면 이 파일이 재려는 것을 못 잰다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sphinx.security.enforce=true")
@DisplayName("F-DSH 대시보드 배선 (enforce=true)")
class DashboardEndpointWiringTest {

    @Autowired private MockMvc mvc;

    @Test
    @DisplayName("❗선행지표 뷰가 존재한다 — 계약과 집계는 있는데 엔드포인트만 없었다 (#178)")
    void leadingIndicatorsIsReachable() throws Exception {
        mvc.perform(get("/dashboard/leading-indicators").with(user("compl-01").roles("COMPL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.scope").exists())
                .andExpect(jsonPath("$.data.series").exists())
                .andExpect(jsonPath("$.data.outliers").exists());
    }

    @Test
    @DisplayName("❗접근 감사 집계는 COMPL 만, 개인 식별자 없이 집계만 낸다 (#326 파트2)")
    void auditSummaryIsComplOnlyAndAggregateOnly() throws Exception {
        mvc.perform(get("/dashboard/audit-summary").with(user("compl-01").roles("COMPL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").exists())
                .andExpect(jsonPath("$.data.byAction").exists())
                .andExpect(jsonPath("$.data.byResultCode").exists())
                .andExpect(jsonPath("$.data.deniedByRole").exists())
                // ❗개인 식별자는 나가면 안 된다 — 개방 모드에서 "누가 무엇을 했는가" 누설(집계만)
                .andExpect(jsonPath("$.data.actorId").doesNotExist())
                .andExpect(jsonPath("$.data.resource").doesNotExist());

        // SELLER 는 집계 접근 불가 — audit:read 는 COMPL 전용(ADR-001 · 기획서 7-4)
        mvc.perform(get("/dashboard/audit-summary").with(user("seller-01").roles("SELLER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("❗from/to 가 ISO_INSTANT 로 바인딩된다 — @DateTimeFormat 없이 InstantFormatter (#468)")
    void auditSummaryBindsFromToAsInstant() throws Exception {
        // 애노테이션 없이 Instant 쿼리 파라미터가 실제로 바인딩되는지 잠근다 — 정세현이 수기로
        // 확인한 그 회차다. 응답 from 이 그대로 echo 되면 서버가 파싱해 받은 것이다.
        mvc.perform(get("/dashboard/audit-summary")
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-07T00:00:00Z")
                        .with(user("compl-01").roles("COMPL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from").value("2026-09-01T00:00:00Z"));
    }

    @Test
    @DisplayName("❗감사 체인 검증은 COMPL 만 — 무결성 결과만 낸다 (#326 파트2)")
    void auditVerifyIsComplOnly() throws Exception {
        mvc.perform(get("/dashboard/audit-verify").with(user("compl-01").roles("COMPL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ok").exists())
                .andExpect(jsonPath("$.data.checked").exists())
                // 개인 식별자 없음
                .andExpect(jsonPath("$.data.actorId").doesNotExist());

        // audit:verify 도 COMPL 전용 — 조회(audit:read)와 다른 action 이지만 범위는 같다
        mvc.perform(get("/dashboard/audit-verify").with(user("seller-01").roles("SELLER")))
                .andExpect(status().isForbidden());
    }

    // 계약이 소문자 enum [branch, org] 이고 서비스의 label() 이 그대로 낸다.
    // 대문자로 적으면 이 테스트가 계약이 아니라 자바 enum 이름을 재게 된다.
    @Test
    @DisplayName("❗범위는 정책이 정한다 — MGR 은 branch, COMPL 은 org")
    void scopeComesFromThePolicyNotTheRequest() throws Exception {
        assertThat(scopeOf(get("/dashboard/leading-indicators")
                .with(user("compl-01").roles("COMPL"))))
                .isEqualTo("org");
        assertThat(scopeOf(get("/dashboard/leading-indicators")
                .with(user("mgr-01").roles("MGR"))))
                .as("MGR 에게 org 를 주면 정책이 통과시킨 의미가 없다 (결정 5.10)")
                .isEqualTo("branch");
    }

    @Test
    @DisplayName("❗scope 를 쿼리로 넓힐 수 없다 — 받는 자리가 없다")
    void scopeCannotBeWidenedByTheRequest() throws Exception {
        String widened = scopeOf(get("/dashboard/leading-indicators")
                .param("scope", "org")
                .with(user("mgr-01").roles("MGR")));

        assertThat(widened)
                .as("쿼리로 범위를 받으면 MGR 이 scope=org 를 적어 보내는 것으로 정책을 "
                        + "우회한다. 무시되는 것이 아니라 **받는 자리가 없어야** 한다")
                .isEqualTo("branch");
    }

    @Test
    @DisplayName("❗SELLER 는 두 뷰 다 못 본다 — ADR-001 이 지키는 자리다")
    void sellerCannotReachEitherView() throws Exception {
        mvc.perform(get("/dashboard/leading-indicators").with(user("seller-01").roles("SELLER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/dashboard/heatmap").with(user("seller-01").roles("SELLER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("❗히트맵이 목을 안 낸다 — 목은 n=100 이라 소표본 마스킹이 한 번도 안 걸렸다 (#54 ⑤)")
    void heatmapNoLongerReturnsTheMock() throws Exception {
        String body = mvc.perform(get("/dashboard/heatmap").with(user("compl-01").roles("COMPL")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .as("목이 늘 돌려주던 셀이다. 남아 있으면 화면이 '가려짐' 상태를 영영 못 만난다")
                .doesNotContain("mock-els-001");
        // 세션이 없으니 셀도 없다 — 그게 목과 다른 점이다.
        assertThat(JsonPath.read(body, "$.data.scope").toString()).isEqualTo("org");
    }

    @Test
    @DisplayName("계약 밖 groupBy 는 400 이다 — 조용히 기본값으로 떨어지면 다른 축을 그린다")
    void unknownGroupByIsRejected() throws Exception {
        mvc.perform(get("/dashboard/leading-indicators")
                        .param("groupBy", "brnach")
                        .with(user("compl-01").roles("COMPL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    private String scopeOf(org.springframework.test.web.servlet.RequestBuilder req) throws Exception {
        String body = mvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.scope").toString();
    }
}
