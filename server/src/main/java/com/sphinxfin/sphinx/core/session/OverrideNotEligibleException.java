package com.sphinxfin.sphinx.core.session;

/**
 * F-GTE-002 오버라이드 전제 위반 — 적색 판정이 아닌 세션에 요청했거나, 요청 없이 승인하려는 경우.
 * 상태 전제를 어긴 것이므로 409로 매핑한다(GlobalExceptionHandler). 소유: 강희진
 */
public class OverrideNotEligibleException extends RuntimeException {
    public OverrideNotEligibleException(String message) {
        super(message);
    }
}
