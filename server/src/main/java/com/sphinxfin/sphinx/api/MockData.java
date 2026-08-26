package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.ProductSummary;
import com.sphinxfin.sphinx.domain.RiskItem;
import java.util.List;

/** 초기 목 데이터 — 각 모듈 구현이 붙으면 삭제한다 */
public final class MockData {

    /**
     * S-02 상품 선택 목록. 데모 대상 2종.
     *
     * **표시명은 가명이다.** 기획서: "데모와 제출물에서는 상품명과 발행사를 가명 처리하고
     * 조건만 인용한다." 공시 문서라 열람은 자유롭지만 제출물에 실명을 싣는 건 다른 문제다.
     * 화면(web/src/lib/sessionAttrs.ts DEMO_PRODUCTS)과 같은 문면을 쓴다 — 서버가 실명을
     * 내보내면 화면이 이 목록으로 갈아타는 순간 실명이 데모에 되돌아온다.
     *
     * productId 는 파싱 산출물의 document_id 와 맞춰야 하므로 그대로 둔다(가명 대상 아님).
     */
    public static final List<ProductSummary> PRODUCTS = List.of(
            new ProductSummary("doc-els-kiwoom-4181",
                    "A증권 제4181회 ELS (원금비보장형)", "ELS", "parsed"),
            new ProductSummary("doc-var-samsung-b2601",
                    "B생명 변액연금보험 (최저연금보증형)", "VARIABLE_INSURANCE", "parsed"));

    public static final List<RiskItem> RISK_ITEMS = List.of(
            RiskItem.extracted("ELS-PRINCIPAL-LOSS-WARNING", "mock-els-001", "원금손실 조건", "required",
                    new RiskItem.Condition(
                            "만기평가일에 기초자산 중 하나라도 최초기준가격의 50% 미만인 경우 …(원문 인용)",
                            new RiskItem.SourceSpan(3, 120, 210))),
            RiskItem.extracted("ELS-NO-DEPOSIT-INSURANCE", "mock-els-001", "예금자보호 비대상", "required",
                    new RiskItem.Condition(
                            "이 금융투자상품은 예금자보호법에 따라 보호되지 않습니다 …(원문 인용)",
                            new RiskItem.SourceSpan(1, 40, 88))));

    private MockData() {}
}
