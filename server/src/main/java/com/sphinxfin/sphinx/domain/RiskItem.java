package com.sphinxfin.sphinx.domain;

/**
 * contracts/risk_item.schema.json 과 1:1 (소유: 강희진).
 *
 * condition 은 **원문에서 뽑은 조건**이다. 추출에 실패하면 존재하지 않으므로 null 이고,
 * 사유 문면으로 채우지 않는다 — value_text 는 원문 인용만 허용하므로(P6) 지어낸 문장이
 * 들어가는 순간 pages[page].text[start:end] == value_text 항등식이 깨진다. 그런데 그
 * 어긋남은 스키마 검증도 통과하고 예외도 로그도 없어서, status 를 안 읽는 소비자에게는
 * 문서에 없는 문장이 원문 인용으로 보인다.
 *
 * 실패 사유는 failureReason 에 따로 담는다(E-EXT-03 — 실패를 은폐하지 않는다).
 */
public record RiskItem(
        String itemId,
        String productId,
        String name,
        String importance,          // required | recommended
        Condition condition,        // status=extracted 면 필수, extraction_failed 면 null
        String status,              // extracted | extraction_failed (E-EXT-03: 실패 은폐 금지)
        String failureReason        // extraction_failed 일 때만. nullable
) {
    /**
     * 계약의 두 상태를 생성자에서 강제한다. 스키마가 if/then 으로 막는 것과 같은 규칙이라,
     * 서버로 들어온 뒤에도 같은 불변식이 유지된다(P4 를 Judgment 에서 막는 것과 같은 자리).
     */
    public RiskItem {
        boolean extracted = "extracted".equals(status);
        if (extracted && condition == null) {
            throw new IllegalArgumentException(
                    "status=extracted 인데 condition 이 없다: " + itemId);
        }
        if (!extracted && condition != null) {
            throw new IllegalArgumentException(
                    "추출 실패 항목에 condition 을 채우면 안 된다 — 원문 인용 자리다 (P6): " + itemId);
        }
    }

    /** 추출 성공 항목. */
    public static RiskItem extracted(String itemId, String productId, String name,
                                     String importance, Condition condition) {
        return new RiskItem(itemId, productId, name, importance, condition, "extracted", null);
    }

    /** 추출 실패 항목. condition 은 비우고 사유만 남긴다. */
    public static RiskItem failed(String itemId, String productId, String name,
                                  String importance, String failureReason) {
        return new RiskItem(itemId, productId, name, importance, null,
                "extraction_failed", failureReason);
    }

    public record Condition(String valueText, SourceSpan sourceSpan) {}
    public record SourceSpan(int page, int start, int end) {}
}
