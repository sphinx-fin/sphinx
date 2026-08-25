package com.sphinxfin.sphinx.api.dto;

/**
 * S-02 상품 선택 목록 항목. 소유: 강희진
 * 기존 /products/* 는 전부 id 를 이미 알고 있어야 부를 수 있어서, 고를 수 있는 목록을 주는
 * 경로가 없었다.
 */
public record ProductSummary(String productId, String name, String productType, String status) {
}
