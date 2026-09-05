package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.ProductSummary;
import com.sphinxfin.sphinx.core.extraction.FallbackCatalog;
import com.sphinxfin.sphinx.domain.RiskItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@link FallbackCatalog} 의 MockData 구현 — 키 없는 환경 폴백. 소유: 강희진
 *
 * <p>MockData 를 아는 쪽이 api 라서 구현이 여기 산다(core 는 인터페이스만 안다 —
 * {@code EvidenceRecorder} 와 같은 모양). MockData 를 걷는 후속에서 이 클래스가 같이 죽는다.
 */
@Component
class MockDataFallbackCatalog implements FallbackCatalog {

    /** 폴백이 대표하는 상품유형 — {@code MockData.RISK_ITEMS} 는 ELS 항목 한 벌이다. */
    private static final String FALLBACK_PRODUCT_TYPE = "ELS";

    @Override
    public Optional<List<RiskItem>> riskItems(String productId) {
        // ❗상품유형이 맞을 때만 목 목록을 낸다(결정 10.81 · 이슈 #427). RISK_ITEMS 는 ELS
        // 한 벌뿐이라, 변액 상품에 이걸 내주면 변액 세션에 ELS 질문이 조용히 나온다(유형은
        // productType 이 맞게 내는데 항목은 틀리는, 같은 폴백 안의 두 기준 어긋남). productType
        // 이 이미 상품을 보니 대조할 값이 있다 — 유형이 다르거나 없는 상품이면 empty 라,
        // riskItemsOf 가 404 로 실패시킨다(조용한 오답보다 낫다). 실추출이 상품별로 채우면
        // 저장 경로가 이 폴백을 덮으므로, 이건 키 없는 데모용 임시 가드다.
        return productType(productId)
                .filter(FALLBACK_PRODUCT_TYPE::equals)
                .map(t -> MockData.RISK_ITEMS);
    }

    @Override
    public Optional<String> productType(String productId) {
        return MockData.PRODUCTS.stream()
                .filter(p -> p.productId().equals(productId))
                .findFirst()
                .map(ProductSummary::productType);
    }
}
