package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.domain.SuitabilityMismatch;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Judgment;

import java.time.Instant;
import java.util.Map;

/**
 * 이해 기록 append 지점(PR #28 리뷰 결정, 2026-08-25). 인터페이스는 core, 구현은 evidence.
 *
 * 세션은 가변 엔티티라 항목별 최신 판정만 갖는다(게이트 입력으로는 그게 맞다). 그래서
 * 재검증으로 덮어쓰기 전 값 — "처음에 황색이었다" — 이 세션에는 남지 않는다. 기획서 174행이
 * 이해 기록의 구성요소로 못박은 "재설명 이력"이 바로 그것이라, 세션을 고치는 대신 매 이벤트를
 * append-only 저장소로 흘려보낸다(ADR-003).
 *
 * 의존 방향을 뒤집으려고 인터페이스를 core에 둔다 — core는 evidence를 모른다.
 * 구현이 아직 없는 동안은 {@link #NO_OP}이 대신 들어간다(F-GTE-004 착수 전).
 */
public interface EvidenceRecorder {

    /**
     * 항목 판정 1건 append. 재검증분도 매 건 들어온다(덮어쓰기 전 값이 아니라 발생 순서 전부).
     *
     * <p>{@code askedQuestion} 은 그 판정을 만든 <b>실제 질문 문면</b>이다 — ai-service 가
     * 생성한 것이든 폴백 목 문면이든 {@code score()} 에 넘어간 값 그대로. 분기와 무관하게
     * 항상 채운다.
     *
     * <p>이유: 질문이 <b>항목의 순수 함수였을 때는</b> 기록에 없어도 {@code item_id} 로
     * 되만들 수 있었다. #133 이 ai-service 생성 질문으로 바꾸면서 그 성질이 없어졌고, 값은
     * {@code session_asked_question} — <b>재질문 시 덮어쓰는 가변 테이블</b>에만 남는다.
     * 그러면 <i>"이 판정은 어느 질문에 대한 답을 잰 것인가"</i> 에 답할 수 없다. append-only
     * 기록 옆의 결정 요인이 덮어쓰기 가능한 곳에 있으면 안 된다(이슈 #136 · ADR-004).
     *
     * <p>null 로 "폴백이었다" 를 뜻하게 하지 않는다 — 그러면 폴백 경로에 한해 위 전제를 다시
     * 들여오는 것이고, 목 문면 한 줄이 바뀌는 순간 조용히 깨진다. null 은 <b>이 필드가 생기기
     * 전 레코드</b> 하나만 가리킨다. 폴백이었다는 사실은 {@code questionSource} 가 따로 낸다.
     *
     * <p>{@code questionSource} 는 그 문면을 <b>고객이 실제로 봤는지</b>다. 문면만으로는 못
     * 가른다 — 폴백도 사람이 읽을 수 있는 질문 한 줄이라 레코드에서 Q_ai 와 똑같이 생겼다.
     * 그러면 감사 시점에 <i>"고객은 이 질문을 보고 답한 것인가"</i> 에 답할 수 없고, 경계
     * 사례의 채점이 어긋난 판정을 정상 판정과 구별할 방법이 없다(이슈 #136 3항).
     */
    void appendJudgment(String sessionId, Judgment judgment, int reverifyCount,
                        String askedQuestion, QuestionSource questionSource, Instant at);

    /**
     * 기록된 질문 문면이 어디서 왔는가.
     *
     * <p>{@code EvidenceRecorder} 안에 둔다 — 불변 기록의 어휘이고, 세션·게이트 어디에도
     * 속하지 않아 {@code core/} 하위 패키지에 새 자리를 만들 이유가 없다.
     */
    enum QuestionSource {
        /** {@code /questions/next} 로 화면에 나갔고 고객이 그것을 보고 답했다. 정상 경로. */
        DISPLAYED,
        /**
         * 표시 질문이 없어 서버가 목 문면을 지어내 채점 맥락으로 썼다 — <b>고객은 이 문면을
         * 본 적이 없다.</b> {@code /answers} 를 화면 없이 직접 부른 경우다. 채점을 막지 않는
         * 것은 답변을 버리지 않으려는 것이고(명세 10절), 여기 남기는 것은 그 판정을 나중에
         * 정상 판정과 구별하려는 것이다. <b>막지 않는 것과 남기지 않는 것은 다르다.</b>
         */
        SERVER_FALLBACK,
        /**
         * 질문이 화면에 나갔고 고객이 그것을 보고 답했다 — <b>다만 그 문면을 모델이 만들지
         * 않았다.</b> ai-service 가 정답 노출 검사를 통과하지 못해 템플릿의
         * {@code fallback_question} 으로 내려간 경우다(F-INT-002).
         *
         * <p>❗<b>{@link #SERVER_FALLBACK} 과 다르다.</b> 그쪽은 <i>"고객이 이 문면을 본 적이
         * 없다"</i> 이고 이쪽은 <i>"봤는데 고정 문장이었다"</i> 다. 고객 경험은 정상이고
         * 어긋나는 것은 <b>무엇을 측정했는가</b> 다 — 폴백 질문으로 받은 답이 섞이면
         * F-INT-002 가 사실상 안 돈 회차를 성능 수치가 정상으로 센다(이슈 #234).
         *
         * <p>ai-service 도 이 사실을 로그로 남긴다(#238). 여기 또 남기는 이유는 <b>로그는
         * 지워지고 감사·집계는 기록만 보기 때문</b>이다 — {@code SERVER_FALLBACK} 을 로그가
         * 아니라 불변 기록에 남긴 것과 같은 판단이다(#136 3항).
         */
        TEMPLATE_FALLBACK
    }

    /**
     * 적합성 모순 판정 1건 append. {@code judge()} 직전 <b>세션당 한 번</b> 들어온다 (이슈 #169).
     *
     * <p>게이트와 따로 뗀 이유가 둘이다. <b>발생 시점이 다르다</b> — 재검증마다 게이트는 다시
     * 도는데 모순은 한 번만 돈다. 그리고 <b>판정의 종류가 다르다</b> — 게이트는 룰이 만든
     * 결정이고 이것은 LLM 이 만든 측정이다(P1). {@code OverrideApprovedEvent} 를 별도 타입으로
     * 둔 것과 같은 결이다.
     *
     * <p>❗<b>근거와 입력을 함께 남긴다.</b> 이 판정이 게이트를 움직이는데
     * ({@code suitabilityMismatch} → R-02, {@code suitabilityUnknown} → R-02b) 지금까지 기록에
     * 남은 것은 {@code GateResult(signal, ruleTrace)} 뿐이었다. 감사 시점에 보이는 것이
     * <i>"R-02 로 YELLOW 였다"</i> 하나라서 <b>왜 모순이라고 판단했는지에 답할 수 없었다.</b>
     *
     * <p>{@code surveyResult} 는 원문으로 받는다. PII 가 아니고(구간 값·선택지),
     * <b>해시만 실으면 "왜 모순인가" 를 못 읽는다</b> — 그게 이 기록의 목적이라 해시로는
     * 목적을 못 채운다. 이것을 리포트에 낼지는 별개 판단이다(화이트리스트).
     *
     * <p>{@code surveySchemaVersion} 은 선택지 문면의 정의다. 같은 세트라고 적힌 두 기록이
     * 서로 다른 문면을 담지 않게 한다 — {@code promptVersion} 이 {@code confidence} 의 정의인
     * 것과 같은 자리다(결정 5.18).
     */
    void appendMismatch(String sessionId, SuitabilityMismatch mismatch,
                        String surveySchemaVersion, Map<String, Object> surveyResult, Instant at);

    /** 게이트 판정 1건 append. judge() 호출마다 들어온다(최종 신호가 아니라 신호의 변천). */
    void appendGate(String sessionId, GateResult result, Instant at);

    /**
     * F-GTE-002 적색 오버라이드 승인 1건 append. 사유·승인자가 불변 기록으로 남아야 한다
     * (ADR-002 견제 장치·기획 7-2). 게이트를 뚫고 진행한 사실 자체가 내부통제 증거다.
     */
    void appendOverride(String sessionId, String reason, String approver, Instant at);

    /** 구현(evidence/) 등록 전까지의 기본값. 삼키는 것을 드러내려고 무명 클래스가 아니라 상수로 둔다. */
    EvidenceRecorder NO_OP = new EvidenceRecorder() {
        @Override
        public void appendJudgment(String sessionId, Judgment judgment, int reverifyCount,
                                   String askedQuestion, QuestionSource questionSource, Instant at) {
            // F-GTE-004 미착수 — 구현 등록 시 자동으로 대체된다.
        }

        @Override
        public void appendMismatch(String sessionId, SuitabilityMismatch mismatch,
                                   String surveySchemaVersion, Map<String, Object> surveyResult,
                                   Instant at) {
        }

        @Override
        public void appendGate(String sessionId, GateResult result, Instant at) {
            // 위와 같다.
        }

        @Override
        public void appendOverride(String sessionId, String reason, String approver, Instant at) {
            // 위와 같다.
        }
    };
}
