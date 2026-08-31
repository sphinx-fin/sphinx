package com.sphinxfin.sphinx.domain;

import java.math.BigDecimal;

/** contracts/judgment.schema.json 과 1:1. 근거(evidence) 없는 판정은 무효 (P4) */
public record Judgment(
        String itemId,
        Grade grade,
        BigDecimal confidence,
        Evidence evidence,
        String reason,
        String misconceptionType,   // nullable, 예: M08-TYING

        /**
         * 이 판정을 낸 채점 프롬프트 버전(ai-service scoring.PROMPT_VERSION). nullable —
         * 이 필드가 생기기 전 레코드와, 아직 안 싣는 ai-service 응답이 null 이다.
         *
         * <p>❗{@code confidence} 의 정의가 버전마다 다르다 — v1 은 등급 확신도, v2 는
         * 재현 가능성이다(PR #114). evidence 가 append-only 라 두 정의가 같은 컬럼에 섞이면
         * 감사 시점에 0.65 가 두 가지 뜻일 수 있게 되고, <b>그때는 이미 못 고친다</b>
         * (결정 10.38 · 이슈 #136).
         */
        String promptVersion,

        /**
         * 이 판정이 컴플라이언스로 올라갈 신호인가 (F-GTE-003 · 이슈 #160).
         * 오해 라이브러리의 {@code escalate: compliance} 에서 온다.
         *
         * <p>❗<b>{@code Boolean} 이 아니라 {@code boolean} 이다.</b> 값이 안 실린 응답은
         * {@code false} 가 된다 — <i>"신호 없음"</i> 쪽으로 떨어지는 것이 안전한 방향이다.
         * 없는 값을 <i>"신호 있음"</i> 으로 읽으면 판매를 막지 않아도 될 세션이 COMPL 로 간다.
         * <i>"측정했는데 아니다"</i> 와 <i>"아직 안 싣는다"</i> 를 갈라야 할 때는
         * {@code promptVersion} 이 그 정보를 이미 들고 있다.
         *
         * <p>❗<b>판매자 응답에 싣지 않는다.</b> 어떤 발화가 탐지되는지 알면 문면만 바꿔 같은
         * 영업을 한다(기획 7-4 역이용 방지). {@code JudgmentView} 가 필드를 골라 담고,
         * {@code JudgmentViewFieldsTest}·{@code UnfairSignalNotExposedTest} 가 그 상태를 잠근다.
         */
        boolean escalate
) {
    /**
     * P4 강제: 근거(발화 인용 + 루브릭 조항)가 비면 판정 자체가 성립하지 않는다.
     * 목·ai-service 응답·DB 역직렬화 등 모든 경로가 이 생성자를 거치므로 여기서 한 번에 막는다.
     * (ai-service가 evidence를 빼먹은 판정이 세션·게이트·리포트로 흘러들지 않게 한다.)
     */
    public Judgment {
        if (evidence == null
                || evidence.utteranceQuote() == null || evidence.utteranceQuote().isBlank()
                || evidence.rubricClause() == null || evidence.rubricClause().isBlank()) {
            throw new EvidenceRequiredException("근거 없는 판정은 무효 (P4): evidence(발화 인용·루브릭 조항) 필수");
        }
        // confidence 는 계약(judgment.schema.json)에서 required · 0~1 이다. double 이던 시절엔
        // 빠지면 0.0 이 되어 R-05 로 황색에 떨어졌지만, BigDecimal 은 null 이 되어 게이트
        // 안쪽 compareTo 에서 NPE 로 터진다 — 상류 위반이 500(우리 잘못)으로 보인다.
        if (confidence == null) {
            throw new MeasurementInvalidException(
                    "신뢰도 없는 판정은 처리할 수 없다: confidence 는 필수다 (judgment.schema.json)");
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            // 범위를 벗어난 값은 R-05 임계값 비교를 의미 없게 만든다. 계약이 0~1 로 못 박은
            // 값이므로, 벗어났다면 상류가 다른 척도를 쓰고 있다는 뜻이다 — 조용히 통과시키면
            // 그 척도 차이가 게이트 판정에 그대로 반영된다.
            throw new MeasurementInvalidException(
                    "신뢰도가 계약 범위(0~1)를 벗어났다: " + confidence.toPlainString());
        }
    }

    /**
     * 프롬프트 버전을 모르는 판정. <b>운영 경로에서 쓰지 않는다</b> — 테스트 픽스처와,
     * ai-service 가 아직 {@code prompt_version} 을 안 싣는 동안의 역직렬화용이다.
     *
     * <p>{@code null} 은 "버전 미상" 하나만 뜻해야 한다. 여기에 다른 뜻(예: 폴백 경로)을
     * 겹치면 감사 시점에 두 상태가 같아진다 — 이슈 #136 이 없애려는 모양이 그것이다.
     */
    public Judgment(String itemId, Grade grade, BigDecimal confidence, Evidence evidence,
                    String reason, String misconceptionType) {
        this(itemId, grade, confidence, evidence, reason, misconceptionType, null, false);
    }

    /**
     * 상신 신호를 모르는 판정. ai-service 가 아직 {@code escalate} 를 안 싣는 동안의
     * 역직렬화·기존 호출부용이다. {@code false} 는 <b>"신호 없음"</b> 으로 읽는다 — 위 필드
     * javadoc 대로 그쪽이 안전한 방향이다.
     */
    public Judgment(String itemId, Grade grade, BigDecimal confidence, Evidence evidence,
                    String reason, String misconceptionType, String promptVersion) {
        this(itemId, grade, confidence, evidence, reason, misconceptionType, promptVersion, false);
    }

    public record Evidence(String utteranceQuote, String rubricClause) {}
}
