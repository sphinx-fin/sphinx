package com.sphinxfin.sphinx.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /sessions/{sid}/judge} 가 내보내는 {@code GateResult} ≡
 * {@code contracts/openapi.yaml} 의 같은 이름 스키마. 소유: 강희진
 *
 * <h2>왜 필요한가 — 이슈 {@code #294}</h2>
 *
 * <p>{@code GateResult} 는 도메인 레코드이면서 <b>동시에 응답 본문</b>이다. 필드를 더하면
 * 계약이 조용히 낡는데, 낡은 것을 알아챌 자리가 없다 — 프론트는 계약을 유니온 타입으로
 * 들고 분기하므로 <b>계약에 없는 필드는 없는 것으로 취급</b>하고, 서버 테스트는 자기가
 * 생각한 키만 {@code jsonPath} 로 집는다. {@code #165} 가 {@code risk_item} 에서
 * 똑같이 났다.
 *
 * <p>그래서 여기서는 <b>키 집합 전체</b>를 맞춘다. 양방향이다 — 계약에 없는 필드를
 * 내보내면 아무도 안 읽고, 계약의 {@code required} 를 안 내보내면 프론트가 없는 값을 믿는다.
 */
@DisplayName("나가는 GateResult ≡ contracts/openapi.yaml GateResult (이슈 #294)")
class GateResultWireContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    @DisplayName("❗필드 집합이 계약과 같다 — 필드를 더하고 계약을 안 고치면 여기서 깨진다")
    void theWireShapeMatchesTheContract() throws Exception {
        JsonNode schema = contractSchema();

        Set<String> contract = new TreeSet<>();
        schema.path("properties").fieldNames().forEachRemaining(contract::add);

        Set<String> wire = new TreeSet<>();
        JSON.valueToTree(sample()).fieldNames().forEachRemaining(wire::add);

        assertThat(wire)
                .as("서버가 보내는 키와 계약의 properties 가 같아야 한다")
                .isEqualTo(contract);
    }

    @Test
    @DisplayName("❗계약이 required 로 선언한 것을 실제로 다 보낸다")
    void everyRequiredFieldIsActuallySent() throws Exception {
        JsonNode sent = JSON.valueToTree(sample());

        for (JsonNode name : contractSchema().path("required")) {
            assertThat(sent.has(name.asText()))
                    .as("계약이 required 로 선언한 '%s' 가 응답에 없다 — 프론트는 있다고 믿는다",
                            name.asText())
                    .isTrue();
        }
    }

    /** 판정 하나. 값이 아니라 <b>키</b>를 재므로 내용은 아무거나 된다. */
    private static GateResult sample() {
        return new GateResult(Signal.RED, List.of("R-00"), 1, 3);
    }

    private static JsonNode contractSchema() throws Exception {
        JsonNode root = YAML.readTree(Files.readString(Path.of("../contracts/openapi.yaml")));
        JsonNode schema = root.path("components").path("schemas").path("GateResult");
        assertThat(schema.isObject())
                .as("contracts/openapi.yaml 에 GateResult 스키마가 없다")
                .isTrue();
        return schema;
    }
}
