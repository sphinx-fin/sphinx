package com.sphinxfin.sphinx.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * F-INT-003 텍스트 응답 요청. 소유: 강희진
 * text는 서버에서 PiiGateway.mask() 후 ai-service로 나간다. inputMeta는 붙여넣기·지연·수정빈도 등.
 */
public record AnswerRequest(
        @NotBlank String itemId,
        @NotBlank String text,
        Map<String, Object> inputMeta) {
}
