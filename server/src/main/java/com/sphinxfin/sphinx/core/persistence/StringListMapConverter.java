package com.sphinxfin.sphinx.core.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 항목별 문자열 목록을 한 컬럼에 담는다. 소유: 강희진 (이슈 #325)
 *
 * <p>{@code @ElementCollection} 으로는 <b>항목당 목록</b>을 담을 수 없다 — 맵의 값이
 * 스칼라여야 해서다. 표를 하나 더 만드는 방법도 있지만, 이 값은 <b>질문 생성에만 쓰이고
 * 조회·집계 대상이 아니다</b>. 조인해서 읽을 일이 없는 값에 표를 만들면 스키마만 는다.
 *
 * <p>순서를 지킨다({@link LinkedHashMap}) — <b>물어본 순서가 곧 정보</b>다. 재검증 질문이
 * 직전 유형을 피하려면 언제 무엇을 썼는지 알아야 한다.
 *
 * <p>콤마 결합 대신 JSON 인 이유는 {@link StringListConverter} 와 같다.
 */
@Converter
public class StringListMapConverter implements AttributeConverter<Map<String, List<String>>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, List<String>>> TYPE =
            new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, List<String>> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("항목별 목록 직렬화 실패", e);
        }
    }

    @Override
    public Map<String, List<String>> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("항목별 목록 역직렬화 실패", e);
        }
    }
}
