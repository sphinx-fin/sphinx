package com.sphinxfin.sphinx.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * F-DET-002 적합성 모순 판정 — <b>상태와 그 근거</b>. 계약 {@code suitability_mismatch.schema.json}
 * 과 1:1. 소유: 강희진
 *
 * <h2>왜 근거를 함께 드는가 (이슈 #169)</h2>
 *
 * <p>전에는 {@link SuitabilityStatus} 하나만 흘렀고 {@code reason}·{@code contradictions}·
 * {@code confidence} 를 <b>경계에서 버렸다.</b> HTTP 응답 레코드는 계약대로 전부 받고 있었으니
 * 계약 위반이 아니라 <b>조용한 손실</b>이었다.
 *
 * <p>이 판정은 게이트를 움직인다 — {@code mismatch} → R-02, {@code unknown} → R-02b. 즉
 * YELLOW·RED 를 만드는 입력인데, 불변 기록에 남는 것은 {@code GateResult(signal, ruleTrace)}
 * 뿐이라 <i>"왜 모순이라고 판단했는가"</i> 에 답할 것이 없었다.
 *
 * <p>{@link Judgment} 는 {@code Evidence} 가 비면 생성자가 막는다(P4). <b>둘은 같은 종류의
 * 판정이다</b> — 하나는 항목 이해도를, 하나는 설문↔발화 어긋남을 재는데 둘 다 LLM 측정이고
 * 둘 다 게이트 입력이다. 한쪽만 근거를 요구하는 것이 일관되지 않다.
 *
 * <h2>왜 {@code domain/} 인가</h2>
 *
 * <p>처음에 {@code AiServiceClient} 안의 중첩 레코드로 뒀는데, 그러면 <b>적재 층의 시그니처가
 * HTTP 클라이언트 타입으로 지어진다</b> — {@code appendJudgment(… domain.Judgment …)} ·
 * {@code appendGate(… domain.GateResult …)} 옆에 {@code appendMismatch(… SuitabilityMismatch …)}
 * 가 붙는 모양이다. {@code evidence/} 가 HTTP 클라이언트를 알 이유가 없다(#186 리뷰).
 *
 * <p>생성자로 근거를 강제하지는 않는다 — ai-service 호출이 실패하면 {@code UNKNOWN} 으로
 * 진행시키는 것이 정해진 규약이고(그 실패에 게이트를 막는 것은 비례하지 않는다) 그 경로에는
 * 근거가 없다. 대신 {@link #unknown(String)} 이 <b>왜 근거가 없는지</b>를 사유로 적는다 —
 * 빈 것과 못 받은 것을 가른다(E-EXT-03 과 같은 결).
 *
 * @param status        세 상태. {@code insufficient_input} 은 NO_MISMATCH 가 아니라 UNKNOWN 이다
 * @param reason        판정 사유 1문장. 호출 실패 시에는 그 사실을 적는다
 * @param confidence    nullable — 판정을 못 했으면 없다
 * @param contradictions 어긋난 축들. {@code reason} 은 요약이고 대조 대상은 이쪽이다
 */
public record SuitabilityMismatch(SuitabilityStatus status, String reason,
                                  BigDecimal confidence,
                                  List<Map<String, Object>> contradictions) {

    public SuitabilityMismatch {
        contradictions = contradictions == null ? List.of() : List.copyOf(contradictions);
    }

    /** ai-service 를 못 불렀을 때. 근거가 없는 이유를 사유로 남긴다. */
    public static SuitabilityMismatch unknown(String why) {
        return new SuitabilityMismatch(SuitabilityStatus.UNKNOWN, why, null, List.of());
    }
}
