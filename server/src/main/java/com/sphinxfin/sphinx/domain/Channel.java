package com.sphinxfin.sphinx.domain;

/**
 * 판매 채널. 기획서 대상 채널 — 대면 영업점(1순위)·모바일 앱(2순위)·TM(확장).
 * 채널별 이해도 비교가 오해 지도의 축이 되므로 자유 문자열이 아니라 고정 enum으로 둔다.
 */
public enum Channel {
    FACE_TO_FACE,   // 대면 영업점
    MOBILE,         // 모바일 앱
    TM              // 전화 판매
}
