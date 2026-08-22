package com.sphinxfin.sphinx.domain;

/** contracts/risk_item.schema.json 과 1:1 (소유: 강희진). value_text는 원문 인용만 (P6) */
public record RiskItem(
        String itemId,
        String productId,
        String name,
        String importance,          // required | recommended
        Condition condition,
        String status               // extracted | extraction_failed (E-EXT-03: 실패 은폐 금지)
) {
    public record Condition(String valueText, SourceSpan sourceSpan) {}
    public record SourceSpan(int page, int start, int end) {}
}
