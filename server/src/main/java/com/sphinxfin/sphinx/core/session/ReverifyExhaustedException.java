package com.sphinxfin.sphinx.core.session;

/**
 * F-INT-004 — 항목별 재검증 상한(max-reverify)에 도달해 더 이상 재설명하지 않는다.
 * 소유: 강희진
 *
 * 명세 F-INT-004 "2회 실패 시 해당 항목 최종 상태 확정 후 게이트로 이관"에 해당한다.
 * 화면은 이 경우 고객에게 판정으로 넘어감을 알려야 하므로 ReExplainNotEligibleException과
 * 별도 코드로 내보낸다.
 */
public class ReverifyExhaustedException extends RuntimeException {
    public ReverifyExhaustedException(String message) {
        super(message);
    }
}
