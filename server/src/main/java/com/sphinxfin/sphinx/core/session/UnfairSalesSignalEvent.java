package com.sphinxfin.sphinx.core.session;

import java.time.Instant;

/**
 * F-GTE-003 불공정영업 신호 사건. 소유: 강희진
 *
 * <p>기획 [기능2]가 요구하는 것이다 — 고객이 <i>"대출받으려면 이것도 들어야 한다고 해서요"</i>
 * 라고 말하면 꺾기(유형⑧)로 탐지돼 컴플라이언스에 알림이 간다.
 *
 * <p>{@link OverrideApprovedEvent} 와 같은 구조다: 발행과 전달을 나눈다. 실제 전달 채널은
 * 인프라 리스너가 구독해 처리하고, 여기서는 <b>사건이 있었다는 사실</b>만 낸다. 그래야
 * 채점 흐름이 전달 방식에 묶이지 않고, 통보 누락도 이벤트 유무로 검증할 수 있다.
 *
 * <h2>❗판매자에게 보이면 안 된다</h2>
 *
 * <p>이 신호가 판매자 화면에 뜨면 <b>역이용된다</b> — 무엇이 탐지되는지 알면 그 문면을 피해
 * 같은 영업을 한다(기획 7-4). 그래서 {@code rbac_policy.yaml} 의 {@code signal:unfair:read}
 * 가 COMPL 전용이고 SELLER·MGR 이 <b>부재</b>다. 권한을 안 주는 것과 줄 수 있는 대상이
 * 없는 것은 다르다(ADR-001 과 같은 결).
 *
 * <p>{@code utteranceQuote} 를 싣는 이유: 컴플라이언스가 판단하려면 <b>고객이 실제로 한 말</b>
 * 이 필요하다. 이미 마스킹된 값이다 — 채점 경로에서 {@code PiiGateway.mask()} 를 거친 발화를
 * ai-service 가 인용 대조로 검증한 것이라 구성상 마스킹 상태다(P3, #119 리뷰).
 */
public record UnfairSalesSignalEvent(
        String sessionId,
        String itemId,
        String misconceptionType,
        String utteranceQuote,
        Instant at
) {
}
