package com.sphinxfin.sphinx.api.dto;

import com.sphinxfin.sphinx.core.Session;

import java.time.Instant;

/**
 * F-INT-001 세션 응답. 소유: 강희진
 *
 * 오버라이드 필드는 S-06(적색 승인 화면)이 승인 전에 반드시 봐야 하는 값이다(오준서 #68 리뷰):
 * 판매자가 적은 사유·승인 대기 여부·승인자. 이게 밖으로 안 나오면 승인자가 사유를 못 보고
 * 승인하게 되고, API에서 사유 30자를 강제한 의미(ADR-002 견제 장치)가 화면에서 사라진다.
 * 추가형이라 기존 소비자(비오버라이드 세션)는 안 깨진다 — overrideStatus는 항상 있고(NONE 기본),
 * 나머지는 승인 전이면 null.
 */
public record SessionResponse(String sessionId, String state, String productId, String contractRef,
                              String overrideStatus, String overrideReason,
                              String overrideApprover, Instant overrideDecidedAt) {
    public static SessionResponse of(Session s) {
        return new SessionResponse(s.id(), s.state().name(), s.productId(), s.contractRef(),
                s.overrideStatus().name(), s.overrideReason(),
                s.overrideApprover(), s.overrideDecidedAt());
    }
}
