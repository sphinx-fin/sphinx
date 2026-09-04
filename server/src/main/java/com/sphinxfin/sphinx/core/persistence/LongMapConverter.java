package com.sphinxfin.sphinx.core.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 항목별 정수 값을 한 컬럼에 담는다. 소유: 강희진 (기획 7-4 2단계 ③)
 *
 * <h2>❗{@link JsonMapConverter} 를 안 쓰는 이유 — 그건 <b>읽기 전용</b>이다</h2>
 *
 * <p>그쪽은 값이 없을 때 {@code Map.of()} 를 돌려준다. {@code surveyResult} 처럼 <b>세션
 * 생성 때 한 번 담고 그 뒤로 읽기만 하는</b> 필드에는 맞는데, <b>답변마다 넣는 필드</b>가
 * 그걸 받으면 두 번째 답변에서 {@code UnsupportedOperationException} 이 난다 —
 * 실제로 그렇게 500 이 났다.
 *
 * <p>여기서는 <b>수정 가능한 맵</b>을 돌려준다. 순서를 지키는 것({@link LinkedHashMap})은
 * 기록된 순서가 곧 답변 순서여서 사람이 읽을 때 쓸모가 있어서다.
 */
@Converter
public class LongMapConverter implements AttributeConverter<Map<String, Long>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Long>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Long> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("항목별 정수 직렬화 실패", e);
        }
    }

    @Override
    public Map<String, Long> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new LinkedHashMap<>();   // ❗수정 가능해야 한다 — 답변마다 넣는 필드다
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("항목별 정수 역직렬화 실패", e);
        }
    }
}
