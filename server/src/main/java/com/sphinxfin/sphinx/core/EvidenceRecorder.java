package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Judgment;

import java.time.Instant;

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
        SERVER_FALLBACK
    }

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
        public void appendGate(String sessionId, GateResult result, Instant at) {
            // 위와 같다.
        }

        @Override
        public void appendOverride(String sessionId, String reason, String approver, Instant at) {
            // 위와 같다.
        }
    };
}
