package com.sphinxfin.sphinx.core.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sphinxfin.sphinx.domain.RuleRef;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * 판정 시점의 발화 룰(ID + 문면)을 세션에 보관한다. 소유: 강희진 (이슈 #320)
 *
 * <h2>왜 ID 만 저장하고 문면을 다시 읽지 않나</h2>
 *
 * <p>문면은 {@code gate_rules.yaml} 에서 오고 그 파일은 바뀐다. 판정 뒤에 문면이 바뀌면
 * <b>기록된 판정이 그때 안 한 말을 하게 된다</b> — 감사 기준점은 재계산값이 아니라
 * 기록값이라는 규약(ADR-003)이 여기에도 적용된다.
 *
 * <p>{@link StringListConverter} 와 같은 방식(JSON)이다. 콤마 결합하지 않는 이유도 같다 —
 * 문면에 콤마가 들어간다.
 */
@Converter
public class RuleRefListConverter implements AttributeConverter<List<RuleRef>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<ArrayList<RuleRef>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<RuleRef> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("룰 트레이스 직렬화 실패", e);
        }
    }

    @Override
    public List<RuleRef> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("룰 트레이스 역직렬화 실패", e);
        }
    }
}
