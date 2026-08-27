package com.sphinxfin.sphinx.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * vulnerability_weights.yaml의 취약 가중 키가 계약(openapi CreateSessionRequest enum) 안에
 * 있는지 강제한다(#43①). enum·YAML·프론트 세 곳을 사람이 맞춰둔 상태라, YAML에 계약에 없는
 * 키가 생기면 그 값은 화면이 절대 못 보내므로 죽은 가중치가 된다 — 그걸 테스트로 잡는다.
 *
 * channel(자바 enum)은 VulnerabilityWeightsContractTest가 담당하고, 여기선 문자열 enum인
 * ageBand·amountBand·experienceLevel을 openapi 계약과 대조한다.
 */
@DisplayName("취약 가중 키 ↔ openapi enum 계약")
class SessionAttrEnumSyncTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    @DisplayName("ageBand·amountBand·experienceLevel의 YAML 가중 키는 전부 계약 enum에 있다")
    void weightKeysAreDeclaredInContract() throws Exception {
        Map<String, Map<String, Integer>> factors = loadFactors();
        Map<String, List<String>> contract = loadContractEnums();

        for (String field : List.of("ageBand", "amountBand", "experienceLevel")) {
            Set<String> yamlKeys = factors.get(field).keySet();
            List<String> enumValues = contract.get(field);
            assertThat(enumValues)
                    .as("openapi CreateSessionRequest.%s.enum 이 vulnerability_weights.yaml 키를 모두 포함해야 함", field)
                    .containsAll(yamlKeys);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Integer>> loadFactors() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/vulnerability_weights.yaml")) {
            Map<String, Object> yaml = YAML.readValue(in, Map.class);
            return (Map<String, Map<String, Integer>>) yaml.get("factors");
        }
    }

    /** 계약(openapi.yaml)은 리포지토리 루트의 contracts/에 있다 — 서버 작업 디렉토리 기준 ../ */
    @SuppressWarnings("unchecked")
    private Map<String, List<String>> loadContractEnums() throws Exception {
        Path openapi = Path.of("..", "contracts", "openapi.yaml");
        assumeTrue(Files.exists(openapi), "openapi.yaml 없음(작업 디렉토리 상이) — 스킵");
        Map<String, Object> doc = YAML.readValue(openapi.toFile(), Map.class);
        Map<String, Object> props = (Map<String, Object>)
                ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) doc.get("components"))
                        .get("schemas")).get("CreateSessionRequest")).get("properties");
        return Map.of(
                "ageBand", (List<String>) ((Map<String, Object>) props.get("ageBand")).get("enum"),
                "amountBand", (List<String>) ((Map<String, Object>) props.get("amountBand")).get("enum"),
                "experienceLevel", (List<String>) ((Map<String, Object>) props.get("experienceLevel")).get("enum"));
    }
}
