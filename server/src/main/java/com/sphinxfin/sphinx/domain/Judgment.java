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
        boolean escalate,

        /**
         * 이 판정을 <b>무엇이 만들었는가</b> (이슈 #518).
         *
         * <p>{@code null} 은 {@link Source#MEASURED} 로 접힌다 — ai-service 는 이 필드를
         * 안 싣고(계약에서 optional), 이 필드가 생기기 전 레코드도 없이 저장돼 있다.
         * 그 둘은 모두 <b>모델이 잰 판정</b>이라 기본값이 사실과 같다.
         *
         * <p>❗<b>{@code promptVersion} 에 겹치지 않는 이유.</b> 저쪽은 "어느 프롬프트가
         * 냈는가" 라서 룰이 만든 판정에는 답이 <i>없다</i>. 거기에 {@code "rule:…"} 같은
         * 값을 넣으면 <b>{@code confidence} 의 정의를 가리키는 필드</b>가 두 뜻을 갖고,
         * 그건 그 필드 javadoc 이 막으려는 바로 그 모양이다(결정 10.38 · 이슈 #136).
         */
        Source source
) {
    /**
     * 이 판정의 출처. <b>등급이 아니라 등급이 나온 방식</b>이다.
     *
     * <p>문면으로는 못 가른다 — 룰이 만든 U3 도 레코드에서 측정된 U3 와 똑같이 생겼다.
     * {@code EvidenceRecorder.QuestionSource} 가 질문 문면에 하는 일을 판정에 한다
     * (#136 3항: <i>막지 않는 것과 남기지 않는 것은 다르다</i>).
     */
    public enum Source {
        /** ai-service 가 루브릭으로 잰 판정 (F-SCR-001). 정상 경로. */
        MEASURED,
        /**
         * 고객이 항목을 건너뛰어 <b>룰이 U3 로 정한</b> 판정 (E-INT-03 · 이슈 #518).
         *
         * <p>❗<b>측정이 아니다.</b> 발화가 없으면 잴 것이 없고, 명세 8절이 무응답의 등급을
         * 이미 U3 로 못 박아 뒀다 — 그건 룰의 결정이다(P1). 예전에는 화면이
         * {@code "(응답하지 않음)"} 을 보통 답변인 척 채점 경로에 태웠는데, 인용할 발화가
         * 없어 모델이 빈 {@code utterance_quote} 를 돌려주면 그대로 502 였다.
         */
        SKIPPED
    }

    /**
     * P4 강제: 근거(발화 인용 + 루브릭 조항)가 비면 판정 자체가 성립하지 않는다.
     * 목·ai-service 응답·DB 역직렬화 등 모든 경로가 이 생성자를 거치므로 여기서 한 번에 막는다.
     * (ai-service가 evidence를 빼먹은 판정이 세션·게이트·리포트로 흘러들지 않게 한다.)
     */
    public Judgment {
        // 계약에서 optional 이라 ai-service 응답과 옛 레코드에 없다 — 없으면 측정이다.
        source = source == null ? Source.MEASURED : source;
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
        this(itemId, grade, confidence, evidence, reason, misconceptionType, null, false, null);
    }

    /**
     * 상신 신호를 모르는 판정. ai-service 가 아직 {@code escalate} 를 안 싣는 동안의
     * 역직렬화·기존 호출부용이다. {@code false} 는 <b>"신호 없음"</b> 으로 읽는다 — 위 필드
     * javadoc 대로 그쪽이 안전한 방향이다.
     */
    public Judgment(String itemId, Grade grade, BigDecimal confidence, Evidence evidence,
                    String reason, String misconceptionType, String promptVersion) {
        this(itemId, grade, confidence, evidence, reason, misconceptionType, promptVersion,
                false, null);
    }

    /**
     * 출처를 안 말하는 판정 — {@link Source#MEASURED} 로 접힌다. ai-service 응답 역직렬화와
     * 기존 호출부용이다. 룰이 만드는 판정은 이 생성자로 만들지 않는다({@code SkippedItem}).
     */
    public Judgment(String itemId, Grade grade, BigDecimal confidence, Evidence evidence,
                    String reason, String misconceptionType, String promptVersion,
                    boolean escalate) {
        this(itemId, grade, confidence, evidence, reason, misconceptionType, promptVersion,
                escalate, null);
    }

    public record Evidence(String utteranceQuote, String rubricClause) {}
}
