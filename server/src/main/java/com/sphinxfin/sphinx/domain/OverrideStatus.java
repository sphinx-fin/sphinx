package com.sphinxfin.sphinx.domain;

/**
 * F-GTE-002 적색 오버라이드 진행 상태.
 * NONE = 요청 없음(기본). PENDING_APPROVAL = 판매자 요청·승인 대기. APPROVED = MGR 승인.
 * NONE은 세션 내부 기본값일 뿐 오버라이드 응답(OverrideResponse)으로는 나가지 않는다(응답은 PENDING_APPROVAL·APPROVED).
 * 반려(REJECTED)는 현재 계약에 엔드포인트가 없어 MVP 범위 밖 — 미승인 세션은 그대로 보류(적색)다.
  *
 * <p>단 {@code SessionResponse} 로는 NONE 이 나간다(#116) — 화면이 "오버라이드 없음" 을
 * 필드 부재가 아니라 값으로 읽어야 한다. 부재로 두면 "없다" 와 "안 실렸다" 가 같아진다.
 */
public enum OverrideStatus { NONE, PENDING_APPROVAL, APPROVED }
