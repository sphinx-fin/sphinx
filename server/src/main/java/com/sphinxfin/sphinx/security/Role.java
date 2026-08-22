package com.sphinxfin.sphinx.security;

/**
 * F-CMN-002 역할 정의. 소유: 정세현
 * 이름을 바꾸면 rbac_policy.yaml과 컨트롤러 어노테이션이 동시에 깨진다 — 변경 시 강희진 멘션.
 */
public enum Role {
    /** 창구 판매직원 — 세션 생성·인터뷰 진행 */
    FC,
    /** 관리자·책임자 — 적색 오버라이드 승인 (F-GTE-002) */
    MGR,
    /** 준법감시 — 오버라이드 자동 통보 수신, 감사 로그 조회 */
    COMPL,
    /** 감사 — 읽기 전용. 해시 체인 검증 권한 포함 */
    AUDITOR
}
