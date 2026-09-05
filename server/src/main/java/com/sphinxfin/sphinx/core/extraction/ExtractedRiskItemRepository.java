package com.sphinxfin.sphinx.core.extraction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** F-EXT-002 추출 스냅샷 저장소. 소유: 강희진 */
public interface ExtractedRiskItemRepository extends JpaRepository<ExtractedRiskItem, Long> {

    /** 상품의 추출 항목을 추출 응답의 순서대로. 비면 "저장된 추출이 없다"는 뜻이다. */
    List<ExtractedRiskItem> findByProductIdOrderByItemIndexAsc(String productId);

    /** 재추출 시 기존 스냅샷 폐기. 호출자는 트랜잭션 안에서 saveAll 과 묶는다. */
    void deleteByProductId(String productId);
}
