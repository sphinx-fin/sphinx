package com.sphinxfin.sphinx.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * 발화한 게이트 룰 하나 — ID 와 <b>사람이 읽는 문면</b>. (이슈 #320)
 *
 * @param id    {@code gate_rules.yaml} 의 룰 ID (예: {@code R-00})
 * @param label 그 룰이 무엇을 말하는지 — 판매자 화면과 교부 문서에 그대로 나간다
 *
 * <h2>왜 ID 옆에 문면을 두나</h2>
 *
 * <p>P4 가 이 제품의 논지다 — <i>"근거 없는 판정은 무효"</i>. 항목별 근거(발화 인용 ·
 * 루브릭 조항)는 화면이 그리는데 <b>세션 신호의 근거만 {@code R-00} 이라는 불투명한 ID</b>
 * 로 나갔다. 심사에서 <i>"R-00 이 뭔가요"</i> 를 화면이 못 답한다.
 *
 * <p>문면을 {@code web} 에 표로 두는 쪽은 답이 아니다 — 표가 두 벌이 되고 {@code web} 에는
 * 테스트 러너가 없어서(결정 10.59) 갈려도 아무것도 안 말한다. 실제로 {@code #316} 에서
 * 에러 코드 유니온이 그렇게 셋 갈렸다. <b>룰과 문면이 같은 파일에서 같이 바뀌어야 한다.</b>
 *
 * <p>❗<b>문면은 결과만 말하고 조건은 말하지 않는다.</b> 판매자가 읽는 글이라, 룰 조건을
 * 그대로 적으면 <b>무엇을 피해야 하는지</b>를 알려주게 된다(기획서 7-4 역이용 방지 ·
 * {@code #144} 가 {@code misconceptionType} 을 판매자 뷰에서 뺀 것과 같은 결).
 *
 * <pre>
 * 좋음   R-05  "판정 신뢰도가 낮은 항목이 있습니다"
 * 나쁨   R-05  "신뢰도 0.7 미만인 항목이 있습니다"      ← 임계값이 새면 그 위를 겨냥한다
 * </pre>
 *
 * <p>그 규약은 사람이 지키는 것이 아니라 {@code RuleLabelSafetyTest} 가 잠근다.
 *
 * <h2>옛 모양({@code "R-01"} 맨 문자열)을 받는다 — 결정 10.74 의 2번</h2>
 *
 * <p>{@code gateRuleTrace} 는 #320 전까지 {@code List&lt;String&gt;} 이라 DB 에
 * {@code ["R-01","R-05"]} 로 저장됐다. 영속 DB(#410)로 가면 그 행들이 프로세스보다 오래
 * 살므로, 객체 모양만 받으면 <b>옛 세션 읽기가 전부 500</b> 이다(append-only 라 고쳐 쓸
 * 수도 없다). {@link #fromLegacyId(String)} 가 그 모양을 받는다.
 *
 * <p>❗옛 기록의 문면은 {@code gate_rules.yaml} 에서 <b>다시 읽지 않는다</b> — 판정 뒤에
 * 파일이 바뀌었으면 기록이 그때 안 한 말을 하게 된다(감사 기준점은 기록값,
 * {@code RuleRefListConverter} javadoc). 문면이 기록되지 않았다는 사실 자체를 문면으로 남긴다.
 */
public record RuleRef(String id, String label) {

    /** 문면 도입(#320) 전에 기록된 룰의 label — 없던 것을 없었다고 말한다(재계산 금지). */
    public static final String LEGACY_LABEL = "(기록 당시 문면 없음)";

    /**
     * 옛 저장 모양 {@code "R-01"} → {@code RuleRef}. Jackson 이 문자열 토큰을 만나면 이리로,
     * 객체 토큰이면 정규 생성자로 간다(위임 생성자는 문자열에만 걸린다).
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RuleRef fromLegacyId(String id) {
        return new RuleRef(id, LEGACY_LABEL);
    }
}
