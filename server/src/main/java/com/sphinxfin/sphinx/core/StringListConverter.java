package com.sphinxfin.sphinx.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * List&lt;String&gt; ↔ DB 컬럼(JSON 배열). 소유: 강희진
 *
 * 콤마 결합으로 평탄화하지 않는 이유: evidence가 이 값을 해싱 대상으로 받으면 CanonicalJson이
 * 배열이 아니라 문자열로 정규화하고, 되돌리려면 split 규약이 계약이 돼야 한다. 룰 ID에 콤마가
 * 들어가는 순간 조용히 깨진다(현재 R-01 형태라 사고가 안 나는 건 우연이지 보장이 아니다).
 */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<ArrayList<String>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("문자열 목록 직렬화 실패", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;   // 판정 전 세션은 트레이스가 없다(빈 목록과 구분)
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("문자열 목록 역직렬화 실패", e);
        }
    }
}
