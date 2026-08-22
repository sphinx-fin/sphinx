package com.sphinxfin.sphinx.domain;

/** contracts/judgment.schema.json 과 1:1. 근거(evidence) 없는 판정은 무효 (P4) */
public record Judgment(
        String itemId,
        Grade grade,
        double confidence,
        Evidence evidence,
        String reason,
        String misconceptionType    // nullable, 예: M08-TYING
) {
    public record Evidence(String utteranceQuote, String rubricClause) {}
}
