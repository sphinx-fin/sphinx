package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.ProductSummary;
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

    private MockData() {}
}
