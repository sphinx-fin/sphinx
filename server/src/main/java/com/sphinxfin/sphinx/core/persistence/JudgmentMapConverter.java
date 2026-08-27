package com.sphinxfin.sphinx.core.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sphinxfin.sphinx.domain.Judgment;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.HashMap;
import java.util.Map;

/**
 * 항목별 Judgment 맵 ↔ DB 컬럼(JSON). 소유: 강희진
 * 세션이 항목별 최신 판정을 보관하기 위한 저장 변환. 읽을 때는 가변 맵으로 돌려준다
 * (recordJudgment가 put 할 수 있게).
 */
@Converter
public class JudgmentMapConverter implements AttributeConverter<Map<String, Judgment>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<HashMap<String, Judgment>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Judgment> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Judgment 맵 직렬화 실패", e);
        }
    }

    @Override
    public Map<String, Judgment> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Judgment 맵 역직렬화 실패", e);
        }
    }
}
