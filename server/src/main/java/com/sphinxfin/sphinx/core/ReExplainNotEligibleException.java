package com.sphinxfin.sphinx.core;

/**
 * F-INT-004 — 재설명 대상이 아닌 항목에 재설명을 요청했다(판정 없음 또는 이미 이해 U1).
 * 소유: 강희진
 *
 * 상한 도달(ReverifyExhaustedException)과 타입을 가르는 이유: 둘 다 400이지만 화면 처리가
 * 다르다. 이쪽은 고객에게 아무것도 띄우지 않고 다음 항목으로 넘어가면 된다. 메시지 문자열은
 * 계약이 아니므로 프론트가 문면을 파싱해 구분하게 두지 않는다.
 */
public class ReExplainNotEligibleException extends RuntimeException {
    public ReExplainNotEligibleException(String message) {
        super(message);
    }
}
