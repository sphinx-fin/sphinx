package com.sphinxfin.sphinx.core.extraction;

import com.sphinxfin.sphinx.domain.RiskItem;

import java.util.List;
import java.util.Optional;

/**
 * 저장된 추출이 없을 때의 항목·상품유형 출처. 소유: 강희진
 *
 * <p>지금 구현은 {@code api/MockData} 다(키 없는 환경에서 데모가 계속 돌게 하는 폴백).
 * core 가 MockData 를 직접 알면 안 되므로 — reExplain 배선이 상품유형을 컨트롤러에서
 * 넘기는 것과 같은 이유 — {@code EvidenceRecorder} 처럼 core 쪽에 경계 인터페이스만 두고
 * 구현은 {@code api/} 에 산다. MockData 를 걷는 날 구현체만 지우면 된다.
 */
public interface FallbackCatalog {

    /**
     * 저장된 추출이 없는 상품의 이해항목. <b>모르는 상품이면 empty</b> — 아무 목록이나
     * 지어내지 않는다({@link #productType} 과 같은 규약, 결정 10.81). 없는 상품ID 에도
     * 같은 목록을 내주면 카탈로그가 둘 이상이 되는 순간 조용히 틀린 목록이 된다.
     */
    Optional<List<RiskItem>> riskItems(String productId);

    /** 카탈로그가 아는 상품유형. 모르는 상품이면 empty — 기본값을 지어내지 않는다. */
    Optional<String> productType(String productId);
}
