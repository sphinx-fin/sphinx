package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.ProductSummary;
import com.sphinxfin.sphinx.domain.RiskItem;
import java.util.List;

/** 초기 목 데이터 — 각 모듈 구현이 붙으면 삭제한다 */
public final class MockData {

    /** S-02 상품 선택 목록. 데모 대상 2종(data/documents/ 의 실문서와 같은 ID). */
    public static final List<ProductSummary> PRODUCTS = List.of(
            new ProductSummary("doc-els-kiwoom-4181", "키움 ELS 제4181회", "ELS", "parsed"),
            new ProductSummary("doc-var-samsung-b2601", "삼성생명 변액유니버셜 B2601",
                    "VARIABLE_INSURANCE", "parsed"));

    public static final List<RiskItem> RISK_ITEMS = List.of(
            new RiskItem("ELS-PRINCIPAL-LOSS-WARNING", "mock-els-001", "원금손실 조건", "required",
                    new RiskItem.Condition(
                            "만기평가일에 기초자산 중 하나라도 최초기준가격의 50% 미만인 경우 …(원문 인용)",
                            new RiskItem.SourceSpan(3, 120, 210)),
                    "extracted"),
            new RiskItem("ELS-NO-DEPOSIT-INSURANCE", "mock-els-001", "예금자보호 비대상", "required",
                    new RiskItem.Condition(
                            "이 금융투자상품은 예금자보호법에 따라 보호되지 않습니다 …(원문 인용)",
                            new RiskItem.SourceSpan(1, 40, 88)),
                    "extracted"));

    private MockData() {}
}
