package com.sphinxfin.sphinx.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contracts/suitability_mismatch.schema.json 과 MismatchResponse 레코드가 어긋나지 않는지 본다.
 *
 * 이 클라이언트는 전용 ObjectMapper 를 쓰는데 FAIL_ON_UNKNOWN_PROPERTIES 가 기본값(on)이라,
 * 계약에 필드가 하나 늘고 레코드가 그대로면 /internal/mismatch 응답 역직렬화가 **통째로**
 * 실패한다. 컴파일도 되고 단위 테스트도 통과하므로 붙여 보기 전엔 드러나지 않는다.
 */
class MismatchContractTest {

    private static final Path SCHEMA = Path.of("../contracts/suitability_mismatch.schema.json");

    @Test
    @DisplayName("❗계약의 응답 필드는 MismatchResponse 가 전부 받는다 — 하나라도 빠지면 역직렬화가 깨진다")
    void responseCoversEveryContractField() throws Exception {
        JsonNode props = new ObjectMapper().readTree(Files.readString(SCHEMA)).get("properties");
        assertThat(props).as("계약 파일에 properties 가 없다").isNotNull();

        Set<String> declared = List.of(AiServiceClient.MismatchResponse.class.getRecordComponents())
                .stream()
                .map(c -> toSnake(c.getName()))
                .collect(Collectors.toSet());

        List<String> missing = new ArrayList<>();
        props.fieldNames().forEachRemaining(f -> {
            if (!declared.contains(f)) {
                missing.add(f);
            }
        });
        assertThat(missing)
                .as("계약에만 있고 레코드에 없는 필드 — 응답이 오면 역직렬화가 실패한다")
                .isEmpty();
    }

    @Test
    @DisplayName("계약의 status 값은 evaluated / insufficient_input 둘뿐이다 — 세 상태 매핑의 전제")
    void statusEnumIsTwoValues() throws Exception {
        JsonNode status = new ObjectMapper().readTree(Files.readString(SCHEMA))
                .path("properties").path("status").path("enum");
        List<String> values = new ArrayList<>();
        status.forEach(v -> values.add(v.asText()));
        assertThat(values)
                .as("값이 늘면 toStatus() 의 else 분기가 조용히 그 값을 NO_MISMATCH 로 삼킨다")
                .containsExactlyInAnyOrder("evaluated", "insufficient_input");
    }

    private static String toSnake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
