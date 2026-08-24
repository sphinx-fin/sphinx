package com.sphinxfin.sphinx.api.dto;

import com.sphinxfin.sphinx.core.Session;

/** F-INT-001 세션 응답. 소유: 강희진 */
public record SessionResponse(String sessionId, String state, String productId, String contractRef) {
    public static SessionResponse of(Session s) {
        return new SessionResponse(s.id(), s.state().name(), s.productId(), s.contractRef());
    }
}
