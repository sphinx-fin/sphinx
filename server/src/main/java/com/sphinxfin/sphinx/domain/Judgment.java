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
    /**
     * P4 강제: 근거(발화 인용 + 루브릭 조항)가 비면 판정 자체가 성립하지 않는다.
     * 목·ai-service 응답·DB 역직렬화 등 모든 경로가 이 생성자를 거치므로 여기서 한 번에 막는다.
     * (ai-service가 evidence를 빼먹은 판정이 세션·게이트·리포트로 흘러들지 않게 한다.)
     */
    public Judgment {
        if (evidence == null
                || evidence.utteranceQuote() == null || evidence.utteranceQuote().isBlank()
                || evidence.rubricClause() == null || evidence.rubricClause().isBlank()) {
            throw new IllegalArgumentException("근거 없는 판정은 무효 (P4): evidence(발화 인용·루브릭 조항) 필수");
        }
    }

    public record Evidence(String utteranceQuote, String rubricClause) {}
}
