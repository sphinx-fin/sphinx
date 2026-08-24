package com.sphinxfin.sphinx.domain;

/**
 * P4 위반 — 근거(evidence) 없는 판정. 소유: 강희진
 * 서버 버그가 아니라 상류(ai-service) 계약 위반이므로, 500이 아니라 별도로 처리한다
 * (GlobalExceptionHandler에서 502 EVIDENCE_REQUIRED).
 */
public class EvidenceRequiredException extends RuntimeException {
    public EvidenceRequiredException(String message) {
        super(message);
    }
}
