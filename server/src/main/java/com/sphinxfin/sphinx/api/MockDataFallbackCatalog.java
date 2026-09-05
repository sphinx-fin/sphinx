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
    public Optional<List<RiskItem>> riskItems(String productId) {
        // ❗알려진 상품일 때만 목 목록을 낸다(결정 10.81). 예전엔 어떤 productId 든 같은
        // 목록을 냈는데, 없는 상품에도 200 이 나가 productType(없으면 404)과 답이 갈렸다.
        // 목 RISK_ITEMS 가 한 벌뿐이라 알려진 상품끼리는 아직 같은 목록을 공유하지만, 그
        // 한계는 실추출이 상품별로 채우면 저장 경로가 덮는다 — 이 폴백이 고칠 몫이 아니다.
        return known(productId) ? Optional.of(MockData.RISK_ITEMS) : Optional.empty();
    }

    @Override
    public Optional<String> productType(String productId) {
        return MockData.PRODUCTS.stream()
                .filter(p -> p.productId().equals(productId))
                .findFirst()
                .map(ProductSummary::productType);
    }

    private static boolean known(String productId) {
        return MockData.PRODUCTS.stream().anyMatch(p -> p.productId().equals(productId));
    }
}
