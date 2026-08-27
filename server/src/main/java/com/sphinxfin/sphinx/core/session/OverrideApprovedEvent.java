package com.sphinxfin.sphinx.core.session;

import java.time.Instant;

/**
 * F-GTE-002 적색 오버라이드 승인 사건. 소유: 강희진
 *
 * 승인 시 발행한다 — 이것이 기획 7-2가 요구하는 "COMPL 자동 통보"의 발행 지점이다.
 * 실제 전달 채널(메일·큐 등)은 인프라 리스너가 이 이벤트를 구독해 처리한다. 발행과 전달을
 * 나눠 두면 override 흐름은 전달 방식에 묶이지 않고, 통보 누락도 이벤트 유무로 검증할 수 있다.
 */
public record OverrideApprovedEvent(String sessionId, String reason, String approver, Instant at) {
}
