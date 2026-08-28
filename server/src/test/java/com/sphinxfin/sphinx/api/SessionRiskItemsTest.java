package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 고객 화면이 <b>세션을 통해</b> 이해항목을 받는다 (이슈 #158). 소유: 강희진
 *
 * <p>S-03·S-04 가 지금은 {@code GET /products/{productId}/risk-items} 를 쓴다. 그 경로는
 * {@code product:read}(scope org)라 <b>고객에게 열어 주면 전 카탈로그가 열린다</b> — 자기
 * 계약 건과 무관한 상품의 위험항목까지다. 세션 경유 라우트는 대상이 세션이라 범위가 자연히
 * {@code own_session} 이 된다.
 *
 * <p>지금 {@code SecurityConfig} 가 {@code permitAll()} 이라 권한 자체는 여기서 못 잰다
 * ({@code AccessEnforcementTest} 가 그쪽을 본다). 여기서 고정하는 것은 <b>라우트가 세션을
 * 실제로 조회한다</b>는 것 — 목을 그냥 돌려주면 {@code @PreAuthorize} 만 남고 없는 세션이
 * 200 으로 나간다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-INT-001 세션 경유 이해항목 조회 (이슈 #158)")
class SessionRiskItemsTest {

    @Autowired private MockMvc mvc;

    @Test
    @DisplayName("세션의 이해항목을 낸다 — 카탈로그 경로와 같은 목록이다")
    void returnsTheItemsForTheSession() throws Exception {
        String sid = createSession();

        String viaSession = mvc.perform(get("/sessions/{sid}/risk-items", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<Map<String, Object>> items = JsonPath.read(viaSession, "$.data.items");
        assertThat(items)
                .as("항목이 비면 S-03 이 물어볼 것을 못 받는다")
                .isNotEmpty();
        assertThat(items).extracting(i -> i.get("itemId"))
                .as("카탈로그 경로와 같은 목록이어야 화면이 옮겨갈 수 있다")
                .containsExactlyElementsOf(catalogItemIds());
    }

    @Test
    @DisplayName("❗없는 세션이면 404 다 — 목을 그냥 돌려주면 이게 200 이 된다")
    void unknownSessionIs404() throws Exception {
        mvc.perform(get("/sessions/{sid}/risk-items", "S-NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("❗조회가 세션을 바꾸지 않는다 — 읽기가 상태 전이를 일으키면 안 된다")
    void readingDoesNotChangeTheSession() throws Exception {
        String sid = createSession();
        String before = state(sid);

        mvc.perform(get("/sessions/{sid}/risk-items", sid)).andExpect(status().isOk());

        assertThat(state(sid))
                .as("항목을 보는 것만으로 면담이 시작되면 안 된다 — START 는 첫 답변이 낸다")
                .isEqualTo(before);
    }

    private List<String> catalogItemIds() throws Exception {
        String body = mvc.perform(get("/products/{id}/risk-items", "doc-els-kiwoom-4181"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.items[*].itemId");
    }

    private String state(String sid) throws Exception {
        String body = mvc.perform(get("/sessions/{sid}", sid))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.state");
    }

    private String createSession() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(created, "$.data.sessionId");
    }
}
