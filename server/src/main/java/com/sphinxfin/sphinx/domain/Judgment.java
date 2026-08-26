package com.sphinxfin.sphinx.domain;

import java.math.BigDecimal;

/** contracts/judgment.schema.json 과 1:1. 근거(evidence) 없는 판정은 무효 (P4) */
public record Judgment(
        String itemId,
        Grade grade,
        BigDecimal confidence,
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
            throw new EvidenceRequiredException("근거 없는 판정은 무효 (P4): evidence(발화 인용·루브릭 조항) 필수");
        }
        // confidence 는 계약(judgment.schema.json)에서 required · 0~1 이다. double 이던 시절엔
        // 빠지면 0.0 이 되어 R-05 로 황색에 떨어졌지만, BigDecimal 은 null 이 되어 게이트
        // 안쪽 compareTo 에서 NPE 로 터진다 — 상류 위반이 500(우리 잘못)으로 보인다.
        if (confidence == null) {
            throw new MeasurementInvalidException(
                    "신뢰도 없는 판정은 처리할 수 없다: confidence 는 필수다 (judgment.schema.json)");
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            // 범위를 벗어난 값은 R-05 임계값 비교를 의미 없게 만든다. 계약이 0~1 로 못 박은
            // 값이므로, 벗어났다면 상류가 다른 척도를 쓰고 있다는 뜻이다 — 조용히 통과시키면
            // 그 척도 차이가 게이트 판정에 그대로 반영된다.
            throw new MeasurementInvalidException(
                    "신뢰도가 계약 범위(0~1)를 벗어났다: " + confidence.toPlainString());
        }
    }

    public record Evidence(String utteranceQuote, String rubricClause) {}
}
