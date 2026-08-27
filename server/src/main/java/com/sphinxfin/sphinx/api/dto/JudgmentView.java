package com.sphinxfin.sphinx.api.dto;

import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;

import java.math.BigDecimal;

/**
 * 판매자 화면이 받는 판정. 계약 {@code openapi.yaml#JudgmentView} 와 1:1. 소유: 강희진
 *
 * <h2>왜 {@link Judgment} 를 그대로 내지 않는가 (이슈 #144)</h2>
 *
 * <p>{@code misconceptionType} 이 <b>불공정영업 신호 그 자체</b>다 —
 * {@code UnfairSalesTypes.isSignal} 이 보는 것이 이 필드이고, {@code M08-TYING} 이면
 * COMPL 로 사건이 나간다(F-GTE-003). 정책은 {@code signal:unfair:read} 를 COMPL 로 좁혀
 * 뒀는데 <b>같은 값이 판정 응답으로 그냥 나가고 있었다.</b> 판매자가 무엇이 탐지되는지 알면
 * 문면만 바꿔 같은 영업을 한다(기획 7-4 · ADR-001).
 *
 * <h2>왜 애노테이션이 아니라 별도 타입인가</h2>
 *
 * <p>{@code @JsonProperty(access = WRITE_ONLY)} 를 {@code Judgment} 에 달면 한 줄로 끝나는데,
 * 그 레코드는 <b>ai-service 로도 나간다</b> — {@code AiServiceClient.reExplain} 이 요청 본문에
 * 통째로 싣고, {@code reexplain.py:174} 가 그 값으로 프롬프트를 만든다.
 *
 * <pre>
 * f"오해 유형: {judgment.misconception_type}" if … else "오해 유형: (라이브러리 미매칭)"
 * </pre>
 *
 * <p>전역으로 막으면 재설명이 <b>에러 없이 품질만 떨어진다.</b> 나가면 안 되는 곳은
 * 판매자 화면 하나뿐이므로 <b>그 경계에만</b> 둔다.
 *
 * <h2>조건부로 가리지 않는다</h2>
 *
 * <p>신호일 때만 빼면 <b>그 부재가 곧 신호</b>다 — 판매자가 "이 항목만 타입이 안 왔다" 를
 * 센다. 그래서 <b>항상 뺀다.</b> {@code #145} 가 리포트에서 같은 판단을 했다.
 *
 * <p>{@code promptVersion} 도 안 낸다. 화면이 쓰지 않고, 채점 프롬프트 버전은 감사·리포트의
 * 어휘라 창구 단말이 알 이유가 없다.
 */
public record JudgmentView(
        String itemId,
        Grade grade,
        BigDecimal confidence,
        Judgment.Evidence evidence,
        String reason) {

    /** 도메인 판정에서 화면이 볼 것만 추린다. 여기 필드를 늘리기 전에 위 javadoc 을 읽는다. */
    public static JudgmentView of(Judgment j) {
        return new JudgmentView(j.itemId(), j.grade(), j.confidence(), j.evidence(), j.reason());
    }
}
