package com.sphinxfin.sphinx.api.dto;

import com.sphinxfin.sphinx.domain.RiskItem;

import java.util.List;

/** F-EXT-002 이해항목 목록. 소유: 강희진 */
public record RiskItemsResponse(List<RiskItem> items) {
}
