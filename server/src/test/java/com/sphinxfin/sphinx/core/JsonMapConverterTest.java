package com.sphinxfin.sphinx.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JsonMapConverter (Map ↔ JSON 컬럼)")
class JsonMapConverterTest {

    private final JsonMapConverter converter = new JsonMapConverter();

    @Test
    @DisplayName("맵 → JSON → 맵 왕복 보존")
    void roundTrip() {
        Map<String, Object> map = Map.of("riskProfile", "안정형", "score", 3);
        String json = converter.convertToDatabaseColumn(map);
        assertThat(json).contains("riskProfile");
        assertThat(converter.convertToEntityAttribute(json))
                .containsEntry("riskProfile", "안정형")
                .containsEntry("score", 3);
    }

    @Test
    @DisplayName("null·빈 맵 → null 컬럼(불필요한 저장 안 함)")
    void emptyToNullColumn() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(Map.of())).isNull();
    }

    @Test
    @DisplayName("null·빈 컬럼 → 빈 맵(NPE 방지)")
    void nullColumnToEmptyMap() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
    }
}
