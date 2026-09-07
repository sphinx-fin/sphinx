package com.sphinxfin.sphinx.domain;

import java.math.BigDecimal;

/**
 * E-INT-03 — 고객이 항목을 건너뛰었을 때의 판정을 <b>룰이</b> 만든다 (이슈 #518). 소유: 강희진
 *
 * <h2>왜 채점 경로를 안 지나는가</h2>
 *
 * <p>예전에는 화면이 {@code "(응답하지 않음)"} 을 <b>보통 답변인 척</b> {@code /answers} 로
 * 보냈다. 그러면 그 문자열이 ai-service 채점 프롬프트까지 가는데, 계약이 근거 인용을
 * 강제하므로({@code evidence.utterance_quote} minLength 1) <b>인용할 발화가 없는 판정</b>을
 * 모델이 만들어야 했다. 모델은 빈 문자열을 돌려줬고, 그건 스키마 위반이라
 * {@code LlmError} → 502 → 화면에는 <i>"채점 서비스가 잠시 멈췄어요"</i> 였다.
 *
 * <p>❗<b>재판정으로 안 풀린다.</b> {@code scoring.score} 의 재시도는 근거 검증
 * ({@code verify_quote_is_verbatim})이 던지는 {@code MeasurementInvalid} 에만 걸리는데,
 * 스키마 위반은 그 검증에 닿기 전에 {@code complete_json} 안에서 난다. 알파에서 실측했다 —
 * 같은 발화 3회 모두 502, 정상 발화 3회 모두 200(2026-09-07).
 *
 * <p>그래서 <b>부르지 않는다.</b> 무응답의 등급은 명세 8절 E-INT-03 이 U3 로 이미 정해 뒀고,
 * 그건 측정이 아니라 룰의 결정이다(P1). 발화가 없으면 잴 것이 없다.
 *
 * <h2>기록에 무엇이 남는가</h2>
 *
 * <p>P4 는 그대로다 — 근거 두 칸을 채운다. 다만 <b>둘 다 룰의 값</b>이고, 그 사실은 문면이
 * 아니라 {@link Judgment.Source#SKIPPED} 가 말한다. 문면으로 가르게 두면 감사 시점에
 * 측정된 U3 와 구별할 방법이 인용문 대조뿐이다(#136 3항과 같은 판단).
 */
public final class SkippedItem {

    /**
     * 건너뛴 항목의 발화 자리에 남는 값. <b>서버가 소유한다</b> — 예전에는 화면이 이 문자열을
     * 만들어 보냈고, 그러면 버튼 라벨과 기록 값이 갈릴 수 있었다(결정 6.26: 라벨은 기록 값과
     * 같은 말이어야 한다). 이제 화면은 {@code /skips} 를 부르기만 한다.
     */
    public static final String UTTERANCE = "(응답하지 않음)";

    /**
     * 근거의 루브릭 조항 자리. <b>루브릭 조항이 아니라 명세 조항</b>이다 — 건너뛴 항목에는
     * 적용된 루브릭이 없고, 있는 척하면 감사에서 공개 루브릭과 대조되지 않는 조항이 나온다.
     */
    static final String RULE_CLAUSE = "E-INT-03 무응답 — 안내 후 항목 건너뛰기(U3 처리)";

    private SkippedItem() {
    }

    /**
     * 건너뛴 항목의 판정. 등급·신뢰도·근거가 전부 룰에서 나온다 — 입력은 항목 하나뿐이다.
     *
     * <p><b>신뢰도가 1 인 이유.</b> v2 에서 {@code confidence} 의 정의는 <b>재현 가능성</b>이다
     * (PR #114). 룰이 만든 값은 같은 입력에 항상 같으므로 1 이 그 정의에 맞다. 0 을 넣으면
     * R-05(신뢰도 &lt; 0.7 → YELLOW)가 물어 <i>"낮은 신뢰도로 측정됐다"</i> 로 읽히는데,
     * 여기서 참인 것은 <i>"측정하지 않았다"</i> 다. 등급이 U3 이므로 R-04 가 이미 황색을
     * 세우고, 게이트 결과는 어느 쪽이든 같다 — 갈리는 것은 {@code ruleTrace} 의 사유뿐이라
     * 사실과 맞는 쪽을 남긴다.
     *
     * <p>{@code promptVersion} 은 {@code null} 이다 — 이 판정을 낸 프롬프트가 없다.
     * 그 {@code null} 이 "버전 미상" 과 겹치는 것은 {@code source} 가 갈라 준다.
     */
    public static Judgment judgmentFor(String itemId) {
        return new Judgment(
                itemId,
                Grade.U3,
                BigDecimal.ONE,
                new Judgment.Evidence(UTTERANCE, RULE_CLAUSE),
                "고객이 항목을 건너뛰었다 — 발화가 없어 측정하지 않았다 (E-INT-03)",
                null,            // 오해 유형은 발화에서 나온다. 발화가 없으면 없다.
                null,            // 프롬프트가 없다 — source 가 그 사실을 말한다
                false,           // 상신 신호도 발화에서 나온다
                Judgment.Source.SKIPPED);
    }
}
