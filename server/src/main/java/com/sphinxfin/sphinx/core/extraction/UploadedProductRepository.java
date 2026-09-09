package com.sphinxfin.sphinx.core.extraction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** F-EXT-001 업로드된 상품 문서 저장소 (이슈 #521). 소유: 강희진 */
public interface UploadedProductRepository extends JpaRepository<UploadedProduct, Long> {

    /** 상품ID 로 한 건. 비면 "업로드된 상품이 아니다"는 뜻이고 호출자가 폴백으로 간다. */
    Optional<UploadedProduct> findByProductId(String productId);

    /** {@code GET /products} 목록. 최근 올린 것이 위로 온다 — 운영자가 방금 올린 것을 찾는다. */
    List<UploadedProduct> findAllByOrderByCreatedAtDesc();
}
