package com.sphinxfin.sphinx.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * /report 응답이 계약을 만족하는지, 그리고 발행과 조회가 갈려 있는지 본다. 소유: 강희진
 *
 * 발행(POST)과 조회(GET)를 나눈 것이 이 계약의 요점이다. GET 이 발행까지 하면
 * report:read 가 audited action 이라 로그에서 "읽었다"와 "발행했다"가 구별되지 않고,
 * MGR·COMPL 이 남의 세션을 열람하는 것만으로 발행 기록이 생긴다.
 *
 * URL 두 개가 null 인 것도 계약이다. 채우면 "이 URL 로 가면 문서가 있다"를 계약이 보장하는데
 * PDF 가 없어 404 가 난다 — 스키마 검증은 통과하고 화면은 링크를 그리며, 눌러야 드러난다.
 * #77 의 value_text 와 같은 실패 양식이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("/report 응답 ↔ openapi 계약")
class ReportContractTest {

    private static final Path OPENAPI = Path.of("..", "contracts", "openapi.yaml");

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("발행·조회 응답이 계약의 필수 필드를 채운다")
    void bothSatisfyContract() throws Exception {
        String sid = createSession();
        JsonNode schemas = new ObjectMapper(new YAMLFactory()).readTree(OPENAPI.toFile())
                .path("components").path("schemas");

        for (String body : List.of(
                mvc.perform(post("/sessions/" + sid + "/report"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                mvc.perform(get("/sessions/" + sid + "/report"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())) {
            JsonNode data = new ObjectMapper().readTree(body).path("data");
            List<String> missing = new ArrayList<>();
            for (JsonNode name : schemas.path("ReportResponse").path("required")) {
                if (!data.has(name.asText())) {
                    missing.add(name.asText());
                }
            }
            assertThat(missing).as("계약 필수 필드 누락").isEmpty();
        }
    }

    @Test
    @DisplayName("❗previewUrl·downloadUrl 이 실제로 문서를 낸다 — 계약이 보장하는 것이 그것이다")
    void theUrlsActuallyResolveToTheDocument() throws Exception {
        // 예전에는 이 자리가 **null 이어야 한다**였다. PDF 생성이 없어서 URL 을 채우면
        // 계약이 "이 URL 로 가면 문서가 있다" 를 보장하는데 404 가 났기 때문이다(#233).
        //
        // PDF 가 붙었으니(PR #244) 단정이 뒤집히는 게 아니라 **한 걸음 더 간다** — 값이
        // 있는지가 아니라 **그 값이 가리키는 곳에 문서가 있는지**를 잰다. 값만 재면
        // 오타 난 경로도 초록이고, 그건 원래 막으려던 상태 그대로다.
        String sid = createSession();
        JsonNode data = new ObjectMapper().readTree(
                mvc.perform(post("/sessions/" + sid + "/report"))
                        .andReturn().getResponse().getContentAsString()).path("data");

        for (String key : new String[]{"previewUrl", "downloadUrl"}) {
            String url = data.get(key).asText(null);
            assertThat(url).as("%s 가 비었다", key).isNotBlank();

            byte[] body = mvc.perform(get(url))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                    .andReturn().getResponse().getContentAsByteArray();

            assertThat(new String(body, 0, 5, StandardCharsets.US_ASCII))
                    .as("%s 가 PDF 가 아닌 것을 낸다", key).isEqualTo("%PDF-");
        }
    }

    @Test
    @DisplayName("발행과 조회가 갈려 있다 — GET 은 계약상 상태를 바꾸지 않는다")
    void issueAndReadAreSeparatePaths() throws Exception {
        JsonNode spec = new ObjectMapper(new YAMLFactory()).readTree(OPENAPI.toFile());
        JsonNode report = spec.path("paths").path("/sessions/{id}/report");
        assertThat(report.has("post")).as("발행 경로(POST)가 계약에 있어야 한다").isTrue();
        assertThat(report.has("get")).as("조회 경로(GET)가 계약에 있어야 한다").isTrue();
    }

    @Test
    @DisplayName("없는 세션 → 404")
    void missingSessionIs404() throws Exception {
        mvc.perform(post("/sessions/does-not-exist/report")).andExpect(status().isNotFound());
        mvc.perform(get("/sessions/does-not-exist/report")).andExpect(status().isNotFound());
    }

    private String createSession() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(created).path("data").path("sessionId").asText();
    }
}
