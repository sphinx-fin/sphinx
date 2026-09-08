package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-OPS-001 운영 상태 실측 (이슈 #522). 소유: 강희진
 *
 * <p>고치기 전에 이 스택은 <b>세 가지로 실패하면서 겉모습이 하나</b>였다 — 판정이 502 로
 * 떨어지는 것. ai-service 가 안 떠 있는 것 · 떴는데 LLM 키가 없는 것 · 공유 시크릿이
 * 어긋난 것이 같은 {@code AI_SERVICE_UNAVAILABLE} 이었다.
 *
 * <p>그래서 재는 것이 <b>「셋이 서로 다른 값으로 나오는가」</b> 다. 200 이 나오는지가 아니다.
 *
 * <ol>
 *   <li>키 없이 뜬 ai-service → <b>{@code DEGRADED}</b> (예전엔 이게 정상으로 보였다:
 *       {@code /healthz} 는 200 이고 컨테이너도 healthy 다)</li>
 *   <li>인증이 한쪽만 켜짐 → <b>{@code DEGRADED}</b>, 어느 쪽인지가 {@code note} 에</li>
 *   <li>안 닿음 → <b>{@code DOWN}</b>, {@code latencyMs} 는 <b>null</b>(0 이 아니다)</li>
 *   <li>상류가 죽어도 이 경로는 <b>200</b> — 상태를 그리는 경로가 상태 때문에 죽으면
 *       화면이 아무것도 못 그린다</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-OPS-001 운영 상태 (이슈 #522)")
class OpsStatusEndpointTest {

    @Autowired private MockMvc mvc;
    @MockBean private AiServiceClient aiServiceClient;

    /** 정상 healthz. 필드 이름은 ai-service {@code /healthz} 와 1:1 이다. */
    private AiServiceClient.AiHealth healthy(boolean keyConfigured, String internalAuth) {
        return new AiServiceClient.AiHealth("ok", "gpt-5-mini", "https://api.example",
                keyConfigured, List.of(".env"), "INFO", "INFO",
                internalAuth, true, "/data", "SPHINX_DATA_DIR", 6,
                Map.of("F-SCR-001", "F-SCR-001_v3"), null);
    }

    /* ── ① 키가 없다 — 떠 있는데 못 한다 ────────────────────────────────────── */

    @Test
    @DisplayName("★ 키 없이 뜬 ai-service 는 DEGRADED 다 — /healthz 200 만 보면 정상으로 보인다")
    void aiServiceWithoutAKeyIsDegradedNotUp() throws Exception {
        when(aiServiceClient.health())
                .thenReturn(new AiServiceClient.HealthProbe(healthy(false, "disabled"), 41, null));
        when(aiServiceClient.hasInternalToken()).thenReturn(false);

        mvc.perform(get("/ops/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.components[?(@.id == 'ai-service')].health")
                        .value("DEGRADED"))
                .andExpect(jsonPath("$.data.components[?(@.id == 'ai-service')].note")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("LLM 키가 없다"))))
                .andExpect(jsonPath("$.data.components[?(@.id == 'ai-service')].latencyMs")
                        .value(41));
    }

    /* ── ② 인증이 한쪽만 켜졌다 ─────────────────────────────────────────────── */

    @Test
    @DisplayName("★ ai-service 만 인증을 켰으면 DEGRADED 다 — /internal/* 이 전부 401 인 상태다")
    void internalAuthAsymmetryIsDegraded() throws Exception {
        when(aiServiceClient.health())
                .thenReturn(new AiServiceClient.HealthProbe(healthy(true, "enabled"), 12, null));
        when(aiServiceClient.hasInternalToken()).thenReturn(false);   // 서버엔 토큰이 없다

        mvc.perform(get("/ops/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.components[?(@.id == 'ai-service')].health")
                        .value("DEGRADED"))
                .andExpect(jsonPath("$.data.components[?(@.id == 'ai-service')].note")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("서버에 토큰이 없다"))));
    }

    @Test
    @DisplayName("★ 반대 방향(서버만 토큰)도 DEGRADED 다 — 마지막 방어선이 선언만 된 상태다")
    void theOtherDirectionIsAlsoDegraded() throws Exception {
        when(aiServiceClient.health())
                .thenReturn(new AiServiceClient.HealthProbe(healthy(true, "disabled"), 12, null));
        when(aiServiceClient.hasInternalToken()).thenReturn(true);

        mvc.perform(get("/ops/status"))
                .andExpect(jsonPath("$.data.components[?(@.id == 'ai-service')].health")
                        .value("DEGRADED"))
                .andExpect(jsonPath("$.data.components[?(@.id == 'ai-service')].note")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("ai-service 가 인증을 끄고"))));
    }

    /* ── ③ 안 닿는다 ────────────────────────────────────────────────────────── */

    @Test
    @DisplayName("★ 안 닿으면 DOWN 이고 latencyMs 가 null 이다 — 0 으로 채우면 「즉시 응답」과 같아진다")
    void unreachableAiServiceIsDownWithNullLatency() throws Exception {
        when(aiServiceClient.health()).thenReturn(new AiServiceClient.HealthProbe(
                null, null, "Connection refused"));
        when(aiServiceClient.hasInternalToken()).thenReturn(true);

        mvc.perform(get("/ops/status"))
                // ❗상류가 죽어도 이 경로는 200 이다. 502 로 내면 화면이 아무것도 못 그린다.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.components[?(@.id == 'ai-service')].health")
                        .value("DOWN"))
                .andExpect(jsonPath("$.data.components[?(@.id == 'ai-service')].latencyMs")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.nullValue())));
    }

    /* ── ④ 정상 · 봉투 · 구성요소 넷 ─────────────────────────────────────────── */

    @Test
    @DisplayName("★ 키·인증이 다 맞으면 UP 이고 note 가 null 이다")
    void everythingConfiguredIsUp() throws Exception {
        when(aiServiceClient.health())
                .thenReturn(new AiServiceClient.HealthProbe(healthy(true, "enabled"), 9, null));
        when(aiServiceClient.hasInternalToken()).thenReturn(true);

        mvc.perform(get("/ops/status"))
                .andExpect(jsonPath("$.data.components[?(@.id == 'ai-service')].health").value("UP"))
                .andExpect(jsonPath("$.data.components[?(@.id == 'ai-service')].note")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.nullValue())));
    }

    @Test
    @DisplayName("★ 구성요소가 넷이고 순서가 고정이다 — 화면이 이 순서로 카드를 놓는다")
    void fourComponentsInAFixedOrder() throws Exception {
        when(aiServiceClient.health())
                .thenReturn(new AiServiceClient.HealthProbe(healthy(true, "enabled"), 9, null));
        when(aiServiceClient.hasInternalToken()).thenReturn(true);

        mvc.perform(get("/ops/status"))
                .andExpect(jsonPath("$.data.components.length()").value(4))
                .andExpect(jsonPath("$.data.components[0].id").value("server"))
                .andExpect(jsonPath("$.data.components[1].id").value("database"))
                .andExpect(jsonPath("$.data.components[2].id").value("ai-service"))
                .andExpect(jsonPath("$.data.components[3].id").value("data-volumes"))
                // server 는 항상 UP 이다 — 이 코드가 돌고 있다는 것이 증거다.
                .andExpect(jsonPath("$.data.components[0].health").value("UP"))
                // facts 는 **배열**이다. 객체면 순서가 구현에 달린다(#522 요청).
                .andExpect(jsonPath("$.data.components[0].facts[0].label").exists())
                .andExpect(jsonPath("$.data.components[0].facts[0].value").exists());
    }

    @Test
    @DisplayName("★ 배포 정보가 실린다 — 로컬은 stack 이 빈 문자열이고 그게 정상이다")
    void deploymentFactsAreThere() throws Exception {
        when(aiServiceClient.health())
                .thenReturn(new AiServiceClient.HealthProbe(healthy(true, "enabled"), 9, null));
        when(aiServiceClient.hasInternalToken()).thenReturn(true);

        mvc.perform(get("/ops/status"))
                .andExpect(jsonPath("$.data.deployment.profile").exists())
                // 색이 없는 것이 로컬의 정상이다 — 「모른다」로 그리면 상시 경고가 된다.
                .andExpect(jsonPath("$.data.deployment.stack").value(""))
                .andExpect(jsonPath("$.data.deployment.startedAt").exists())
                .andExpect(jsonPath("$.data.deployment.uptimeSec").exists())
                .andExpect(jsonPath("$.data.checkedAt").exists());
    }

    @Test
    @DisplayName("❗DB 카드에 접속 문자열의 쿼리 파라미터가 안 실린다 — 자격증명이 붙는 날을 대비한다")
    void theJdbcUrlIsTruncatedAtTheQueryString() throws Exception {
        when(aiServiceClient.health())
                .thenReturn(new AiServiceClient.HealthProbe(healthy(true, "enabled"), 9, null));
        when(aiServiceClient.hasInternalToken()).thenReturn(true);

        mvc.perform(get("/ops/status"))
                .andExpect(jsonPath("$.data.components[?(@.id == 'database')].health").value("UP"))
                .andExpect(jsonPath("$.data.components[1].facts[?(@.label == '접속')].value")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.not(
                                        org.hamcrest.Matchers.containsString("?")))));
    }

    @Test
    @DisplayName("❗비밀 값이 응답 어디에도 없다 — 「설정됐는가」까지만 낸다")
    void noSecretValuesAnywhereInTheResponse() throws Exception {
        // healthz 는 애초에 키 값을 안 내지만, 서버가 자기 설정에서 무언가를 실을 수도 있다.
        // 응답 전문을 훑어 자격증명 문면이 없는지 본다 — 이 화면은 ADMIN 이 상시 띄워 둔다.
        when(aiServiceClient.health())
                .thenReturn(new AiServiceClient.HealthProbe(healthy(true, "enabled"), 9, null));
        when(aiServiceClient.hasInternalToken()).thenReturn(true);

        String body = mvc.perform(get("/ops/status"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(body)
                .as("운영 상태 응답에 비밀이 실렸다 — 「설정됐는가」까지만 낸다"
                        + "(ai-service /healthz 가 이미 그 규칙이다)")
                .doesNotContain("password")
                .doesNotContain("x-sphinx-internal-token")
                .doesNotContain("LLM_API_KEY");
    }
}
