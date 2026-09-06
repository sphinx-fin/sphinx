package com.sphinxfin.sphinx.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ★ <b>시연 주장을 그대로 잰다</b> — <i>"명부의 SELLER 계정으로 집계를 부르면 막힌다"</i>
 * (ADR-001 · 기획 7-4 · 이슈 #464). 소유: 정세현
 *
 * <h2>왜 «또» 만드나 — 조각은 다 있는데 «조립» 이 안 잠겨 있었다</h2>
 *
 * <p>이 주장을 떠받치는 네 조각이 각각 CI 에 있다. 그런데 <b>넷을 이어 붙인 것</b>을 재는
 * 곳이 없었다.
 *
 * <pre>
 * DemoAccountsTest            명부의 seller-01 이 aggregate:heatmap:read 에 «정책상» 막힌다
 * DashboardEndpointWiringTest ROLE_SELLER 가 그 엔드포인트에서 403 (모의 사용자 · enforce=true)
 * SecurityConfigTest          prod 프로파일이 인증을 요구한다 (401 · 자격증명 통과)
 * DemoModeAccountMapTest      개방 모드가 그 경로에 compl-01 을 «주입» 한다
 * ❗없던 것                    명부 계정으로 «HTTP 를 실제로 태워» 403 이 나는가
 * </pre>
 *
 * <p>가운데 둘이 특히 어긋날 수 있다 — {@code DashboardEndpointWiringTest} 는
 * {@code .with(user("seller-01").roles("SELLER"))} 로 <b>모의 권한을 손으로 심는다.</b>
 * 실제 배포에서 그 권한을 만드는 것은 {@code SecurityConfig.prodUsers} 가 명부에서 읽는
 * {@code roles(a.role().name())} 이고, <b>둘이 갈리면 이 테스트들은 전부 초록인 채로 시연이
 * 깨진다.</b> 실제로 그 갈림이 한 번 있었다 — 계정이 {@code roles("API")} 하나였을 때
 * <i>"인증은 서는데 인가가 아무것도 안 가르는"</i> 상태였고(#41), 그때도 정책 테스트는
 * 통과하고 있었다.
 *
 * <h2>왜 «지금» 필요한가</h2>
 *
 * <p>{@code #464} 가 prod 를 못 세우면서 데모를 alpha 로 가기로 했는데(ⓒ), alpha 는 개방
 * 모드라 nginx 가 {@code /api/dashboard/} 에 {@code compl-01} 을 주입해 <b>브라우저로는 차단이
 * 안 보인다</b>({@code docs/deployment.md} §9.5 — <i>"개방 모드와 역할 차단 시연은 서로
 * 배타적"</i>). 그래서 시연은 nginx 를 안 지나는 경로로 한다.
 *
 * <pre>
 * docker compose exec -T server curl -u seller-01:$PW http://localhost:8000/dashboard/heatmap
 * </pre>
 *
 * <p>❗<b>그 명령이 무엇을 낼지가 데모 대본에 걸려 있는데, 그것을 재는 곳이 없었다.</b> 이
 * 파일이 그 자리다 — 같은 프로파일({@code prod})·같은 명부·같은 체인으로 <b>같은 것을</b>
 * 잰다. 호스트에서 실제로 돌리는 것을 대신하지는 않지만, <b>돌리기 전에 답을 알 수 있다.</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        // 명부에 있는 id 여야 기동한다(#41). 비밀번호는 전 계정 공통이다 — 그 사실이
        // SecurityConfig.prodUsers 주석에 있고, «역할을 바꿔 가며 시연» 하려는 의도다.
        "sphinx.api.auth.username=compl-01",
        "sphinx.api.auth.password=test-only-not-a-real-credential",
        "spring.datasource.url=jdbc:h2:mem:roleblock;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
})
@DisplayName("시연: 명부 계정으로 역할 차단이 실제로 난다 (이슈 #464 · ADR-001)")
class DemoRoleBlockIsRealTest {

    /** 전 계정 공통이다 — {@code SecurityConfig.prodUsers} 가 그렇게 만든다. */
    private static final String PW = "test-only-not-a-real-credential";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("★❗SELLER 는 집계에 닿지 못한다 — 시연 1번 (ADR-001 · 7-4)")
    void sellerFromTheRosterIsBlockedFromAggregates() throws Exception {
        mvc.perform(get("/dashboard/heatmap").with(httpBasic("seller-01", PW)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("❗양성 대조 — COMPL 은 같은 경로로 통과한다")
    void complPassesTheSamePath() throws Exception {
        // ❗이게 없으면 위 403 이 «권한» 때문인지 «경로 오타·설정 사고» 때문인지 못 가른다.
        //   실제로 nginx 가 접두어를 벗겨 넘기므로(web/app.conf:42) 시연 명령에서 `/api` 를
        //   붙이면 404 가 나는데, 그것을 "막혔다" 로 읽으면 결론이 뒤집힌다.
        mvc.perform(get("/dashboard/heatmap").with(httpBasic("compl-01", PW)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("❗인증이 없으면 401 이다 — 403 과 다르다")
    void anonymousIsUnauthorizedNotForbidden() throws Exception {
        // 401(누구인지 모른다)과 403(누구인지 알고 막는다)이 시연에서 다른 문장이다.
        // 403 만 재면 «인증이 통째로 꺼진» 회귀를 못 잡는다.
        mvc.perform(get("/dashboard/heatmap")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("★❗MGR 은 «자기 지점만» — 역할만으로는 부족하다 (rbac_policy 범위 분리)")
    void managerReachesAggregatesButScopedToBranch() throws Exception {
        // 시연 2번이다. `rbac_policy.yaml` 이 *"역할만으로는 부족하다"* 로 세운 두 번째 층 —
        // MGR 은 닿기는 하는데 범위가 branch 다. 여기서는 «닿는다» 까지만 잰다(범위 계산은
        // AggregateServiceTest 가 본다) — 그 둘이 다른 층이라 한 곳에서 재면 흐려진다.
        mvc.perform(get("/dashboard/heatmap").with(httpBasic("mgr-01", PW)))
                .andExpect(status().isOk());
    }
}
