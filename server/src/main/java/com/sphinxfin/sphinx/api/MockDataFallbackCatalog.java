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

    @Override
    public List<RiskItem> riskItems(String productId) {
        // 목 시절 카탈로그 라우트가 어떤 상품이든 같은 목록을 냈다 — 그 동작을 그대로
        // 보존한다(폴백은 새 동작을 만들지 않는다).
        return MockData.RISK_ITEMS;
    }

    @Override
    public Optional<String> productType(String productId) {
        return MockData.PRODUCTS.stream()
                .filter(p -> p.productId().equals(productId))
                .findFirst()
                .map(ProductSummary::productType);
    }
}
