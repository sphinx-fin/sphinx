package com.sphinxfin.sphinx.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /simulate 응답이 openapi 의 필수 필드를 실제로 채우는지 본다. 소유: 강희진
 *
 * 목이 계약보다 좁으면 화면이 백지가 되는데, 서버는 200 을 내고 에러도 로그도 없다.
 * 실제로 그렇게 됐다 — severity·pathMeta·timeseriesVersion·productName 네 개가 빠져 있었고
 * S-04 가 백지가 됐다(PR #80).
 *
 * severity 누락은 특히 조용하다. 값이 없으면 화면이 카드를 받은 순서대로 세우는데, 그러면
 * 최선 → 중간 → 최악 순이 되어 기획서 4절이 요구하는 것("최선만 강조하는 관행의 정반대")과
 * 정반대가 된다. 백지가 되지도 않고 그냥 순서만 뒤집힌다.
 *
 * SimulatorService(F-SIM-001)가 붙어도 이 테스트는 그대로 유효하다 — 계약을 보는 것이지
 * 목을 보는 것이 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("/simulate 응답 ↔ openapi 계약")
class SimulateContractTest {

    private static final Path OPENAPI = Path.of("..", "contracts", "openapi.yaml");

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("응답이 계약의 필수 필드를 전부 채운다")
    void responseSatisfiesContract() throws Exception {
        String sid = createSession();
        JsonNode body = new ObjectMapper().readTree(
                mvc.perform(post("/sessions/" + sid + "/simulate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"amount":50000000}"""))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        JsonNode spec = new ObjectMapper(new YAMLFactory()).readTree(OPENAPI.toFile());
        JsonNode schemas = spec.path("components").path("schemas");
        JsonNode data = body.path("data");

        // 봉투 data 의 필수 필드
        for (JsonNode name : schemas.path("SimulateApiResponse")
                .path("properties").path("data").path("required")) {
            assertThat(data.has(name.asText()))
                    .as("계약이 요구하는 필드가 응답에 없다: data.%s", name.asText()).isTrue();
        }

        JsonNode scenarios = data.path("scenarios");
        assertThat(scenarios).as("scenarios 는 3건이어야 한다").hasSize(3);

        List<String> missing = new ArrayList<>();
        for (JsonNode scenario : scenarios) {
            for (JsonNode name : schemas.path("SimScenario").path("required")) {
                if (!scenario.has(name.asText())) {
                    missing.add("scenarios[].{" + name.asText() + "}");
                }
            }
            for (JsonNode name : schemas.path("PathMeta").path("required")) {
                if (!scenario.path("pathMeta").has(name.asText())) {
                    missing.add("pathMeta.{" + name.asText() + "}");
                }
            }
        }
        assertThat(missing).as("계약 필수 필드 누락 — 화면이 백지가 된다").isEmpty();
    }

    @Test
    @DisplayName("severity 가 worst·mid·best 각 1건 — 카드 순서가 기획 4절을 따른다")
    void severityCoversThreeSlots() throws Exception {
        String sid = createSession();
        JsonNode body = new ObjectMapper().readTree(
                mvc.perform(post("/sessions/" + sid + "/simulate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"amount":50000000}"""))
                        .andReturn().getResponse().getContentAsString());

        List<String> severities = new ArrayList<>();
        body.path("data").path("scenarios").forEach(s -> severities.add(s.path("severity").asText()));
        assertThat(severities).containsExactlyInAnyOrder("worst", "mid", "best");
    }

    @Test
    @DisplayName("가입금액을 바꾸면 금액이 따라 바뀐다 — 목이라도 슬라이더가 동작해야 한다")
    void payoutScalesWithAmount() throws Exception {
        String sid = createSession();
        long small = worstPayout(sid, 10_000_000);
        long large = worstPayout(sid, 100_000_000);
        assertThat(large).isGreaterThan(small);
    }

    private long worstPayout(String sid, long amount) throws Exception {
        JsonNode body = new ObjectMapper().readTree(
                mvc.perform(post("/sessions/" + sid + "/simulate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":" + amount + "}"))
                        .andReturn().getResponse().getContentAsString());
        for (JsonNode s : body.path("data").path("scenarios")) {
            if ("worst".equals(s.path("severity").asText())) {
                return s.path("payout").asLong();
            }
        }
        throw new AssertionError("worst 시나리오가 없다");
    }

    private String createSession() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(created).path("data").path("sessionId").asText();
    }
}
