package com.sphinxfin.sphinx.api.dto;

import jakarta.validation.constraints.NotBlank;

/** F-INT-004 재설명 요청 — 다시 설명할 항목. 소유: 강희진 */
public record ReExplainRequest(@NotBlank String itemId) {
}
