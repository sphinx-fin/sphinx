package com.sphinxfin.sphinx.domain;

/**
 * F-GTE-002 적색 오버라이드 진행 상태.
 * NONE = 요청 없음(기본). PENDING_APPROVAL = 판매자 요청·승인 대기. APPROVED = MGR 승인.
 * NONE은 세션 내부 기본값일 뿐 오버라이드 응답으로 나가지 않는다(응답은 PENDING_APPROVAL·APPROVED).
 * 반려(REJECTED)는 현재 계약에 엔드포인트가 없어 MVP 범위 밖 — 미승인 세션은 그대로 보류(적색)다.
 */
public enum OverrideStatus { NONE, PENDING_APPROVAL, APPROVED }
