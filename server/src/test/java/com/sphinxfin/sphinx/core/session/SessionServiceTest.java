package com.sphinxfin.sphinx.core.session;

import java.math.BigDecimal;

import com.sphinxfin.sphinx.domain.Channel;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.api.dto.JudgmentView;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
import com.sphinxfin.sphinx.domain.SessionState;
import com.sphinxfin.sphinx.domain.SuitabilityStatus;
import com.sphinxfin.sphinx.domain.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.sphinxfin.sphinx.core.EvidenceRecorder;
import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.core.gate.GateEngine;
import com.sphinxfin.sphinx.core.persistence.BaseEntity;
import com.sphinxfin.sphinx.core.persistence.JpaAuditingConfig;

/**
 * F-INT-001 세션 영속 통합. 실제 H2에 저장·재조회하며 감사필드·컨버터·재검증 카운트를 검증한다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@DisplayName("F-INT-001 SessionService (영속)")
class SessionServiceTest {

    @Autowired
    private SessionRepository repository;
    @Autowired
    private TestEntityManager em;

    private SessionService service;
    private RecordingEvidence evidence;
    /** 발행된 사건을 모은다 — F-GTE-003 신호가 실제로 나가는지 본다. */
    private final List<Object> published = new ArrayList<>();
    private final org.springframework.context.ApplicationEventPublisher events = published::add;
    private AiServiceClient aiClient;

    /** ai-service 재설명 콘텐츠 기본값 — 테스트별로 필요하면 재스텁한다. */
    private static final String AI_REEXPLAIN = "[ai-service 재설명 콘텐츠]";

    @BeforeEach
    void setUp() {
        evidence = new RecordingEvidence();
        published.clear();
        aiClient = mock(AiServiceClient.class);
        // 재설명 배선(F-INT-004): 콘텐츠는 이제 ai-service 가 만든다. 상류 의존성이라 목으로 대신하고,
        // HTTP 계약(snake_case·파싱·실패 매핑)은 AiServiceClientTest 가 따로 검증한다.
        when(aiClient.reExplain(any(RiskItem.class), any(Judgment.class),
                nullable(String.class), nullable(String.class)))
                .thenReturn(new AiServiceClient.ReExplanation(AI_REEXPLAIN, List.of()));
        service = new SessionService(repository, new GateEngine(), new CoachingScoreService(),
                Optional.of(evidence), aiClient, events);
    }

    /** 서비스의 3-arg reExplain 을 감싼다 — 항목 id 로 목 risk_item 을 만들어 넘긴다. */
    private SessionService.ReExplanation reExplain(String sid, String itemId) {
        return service.reExplain(sid, itemId, riskItem(itemId));
    }

    /** itemId 로 만든 목 risk_item. 배선은 이 항목을 그대로 ai-service 로 넘긴다. */
    private static RiskItem riskItem(String itemId) {
        return RiskItem.extracted(itemId, "mock-doc", itemId, "required",
                new RiskItem.Condition("…(원문 인용)", new RiskItem.SourceSpan(1, 0, 10)));
    }

    @Test
    @DisplayName("❗미리보기 GREEN 은 모순 평가 전 값이다 — 상태를 실어 화면이 구별하게 한다")
    void previewCarriesSuitabilityStatus() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U1));

        var preview = service.previewGate(s.id());

        // 신호 자체는 GREEN 이다 — 미리보기는 모순을 평가하지 않으므로 바꾸지 않는다.
        assertThat(preview.signal()).isEqualTo(Signal.GREEN);
        assertThat(preview.suitabilityStatus())
                .as("이 값이 없으면 화면은 signal=GREEN 만 보고 최종 통과로 그린다 — "
                        + "그런데 /judge 는 모순을 평가하므로 YELLOW·RED 로 갈릴 수 있다")
                .isEqualTo(SuitabilityStatus.NOT_EVALUATED);
    }

    @Test
    @DisplayName("모순을 평가한 뒤에는 미리보기와 판정이 같은 신호를 낸다")
    void previewMatchesJudgeAfterEvaluation() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U1));
        service.recordSuitability(s.id(), mismatchOf(SuitabilityStatus.UNKNOWN));

        var preview = service.previewGate(s.id());
        assertThat(preview.signal()).isEqualTo(Signal.YELLOW);
        assertThat(preview.suitabilityStatus()).isEqualTo(SuitabilityStatus.UNKNOWN);
        assertThat(service.judge(s.id()).signal()).isEqualTo(preview.signal());
    }

    @Test
    @DisplayName("❗호출자가 넘긴 질문이 불변 기록으로 그대로 간다 — 서비스가 다시 구하지 않는다")
    void askedQuestionReachesTheEvidenceRecord() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U1), null,
                "원금이 줄어드는 조건을 말씀해 주시겠어요?",
                EvidenceRecorder.QuestionSource.DISPLAYED);

        assertThat(evidence.askedQuestions)
                .as("서비스가 세션 맵을 다시 읽으면 값을 두 곳에서 구하게 되고, 폴백처럼 "
                        + "한쪽에만 있는 분기에서 채점값과 기록값이 갈린다 (#137 리뷰)")
                .containsExactly("원금이 줄어드는 조건을 말씀해 주시겠어요?");
    }

    @Test
    @DisplayName("재검증하면 각 판정에 그때의 질문이 따로 남는다 — 세션 맵은 마지막 것만 갖는다")
    void reaskedQuestionGoesWithItsOwnJudgment() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U3), null, "첫 질문",
                EvidenceRecorder.QuestionSource.DISPLAYED);

        service.reExplain(s.id(), "A", riskItem("A"));
        service.recordJudgment(s.id(), j("A", Grade.U1), null, "다시 여쭙는 질문",
                EvidenceRecorder.QuestionSource.DISPLAYED);

        assertThat(evidence.askedQuestions)
                .as("세션 맵은 덮어쓰지만 불변 기록에는 판정마다 그 질문이 남아야 한다 (#136)")
                .containsExactly("첫 질문", "다시 여쭙는 질문");
    }

    // ── F-GTE-003 불공정영업 신호 (이슈 #63) ──────────────────────────

    /** 꺾기 판정 — misconceptionType 이 M08-TYING 이다. */
    /**
     * 꺾기로 채점된 판정 — <b>ai-service 가 실제로 보내는 모양</b>이다.
     *
     * <p>❗{@code misconceptionType} 이 {@code null} 인 것이 요지다. 예전 픽스처는 여기에
     * {@code "M08-TYING"} 을 넣었는데 <b>실물에서는 그 값이 안 온다</b> — 등급 상향은 루브릭의
     * {@code related_misconceptions} 로 거르고, 17개 루브릭 어디에도 M08 이 안 걸려 있다.
     * 그래서 유형이 실릴 경로가 없다(이슈 #160).
     *
     * <p>그 픽스처가 <b>일어나지 않는 상태를 모델링</b>하고 있었고, 그래서 발행 게이트가
     * 유형ID 를 보는 동안에도 이 테스트는 초록이었다. 탐지는 만점인데 COMPL 발행이 0회인
     * 상태를 테스트가 못 잡은 이유다.
     *
     * <p>신호는 {@code escalate} 로 온다 — ai-service 가 루브릭 필터 <b>밖에서</b> 계산한다.
     */
    private static Judgment tying(String itemId) {
        return new Judgment(itemId, Grade.U4, new BigDecimal("0.9"),
                new Judgment.Evidence("대출받으려면 이것도 들어야 한다고 해서요", "구속행위 금지"),
                "꺾기 정황", null, null, true);
    }

    @Test
    @DisplayName("❗꺾기 판정이면 COMPL 로 사건이 나간다 — 기획 [기능2]")
    void tyingPublishesUnfairSignal() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), tying("A"));

        assertThat(published).filteredOn(e -> e instanceof UnfairSalesSignalEvent).hasSize(1);
        UnfairSalesSignalEvent e = (UnfairSalesSignalEvent) published.stream()
                .filter(x -> x instanceof UnfairSalesSignalEvent).findFirst().orElseThrow();
        assertThat(e.sessionId()).isEqualTo(s.id());
        assertThat(e.utteranceQuote())
                .as("컴플라이언스가 판단하려면 고객이 실제로 한 말이 필요하다")
                .isEqualTo("대출받으려면 이것도 들어야 한다고 해서요");

        // ❗**유형ID 는 비어 있다 — 그리고 그게 실물이다.** 루브릭 필터가 M08 을 안 실으므로
        // (이슈 #160) 사건이 들고 가는 근거는 발화 인용이다. 예전 단정은 여기서 "M08-TYING"
        // 을 기대했는데, 그건 픽스처가 만든 값이지 ai-service 가 보내는 값이 아니었다.
        //
        // 필드를 지우지는 않는다. 승급 유형이 어느 루브릭의 related_misconceptions 에 걸리는
        // 날에는 값이 실리고, 그때 COMPL 이 유형까지 보는 것이 낫다.
        assertThat(e.misconceptionType())
                .as("유형이 실리는 경로가 없다 — 근거는 인용이다")
                .isNull();
    }

    @Test
    @DisplayName("다른 오해는 사건이 안 나간다 — 모든 U4 가 불공정영업은 아니다")
    void otherMisconceptionsDoNotSignal() {
        Session s = service.create(cmd(null));
        // M01 은 루브릭에 걸려 있어 유형이 실린다 — 그런데 escalate 는 false 다.
        // **유형이 있는 것과 상신 대상인 것은 다르다**는 것을 이 줄이 고정한다.
        Judgment other = new Judgment("A", Grade.U4, new BigDecimal("0.9"),
                new Judgment.Evidence("원금은 지켜지죠", "원금손실 조건"),
                "원금 보장 오해", "M01-PRINCIPAL-GUARANTEE", null, false);
        service.recordJudgment(s.id(), other);

        assertThat(published).filteredOn(e -> e instanceof UnfairSalesSignalEvent).isEmpty();
    }

    @Test
    @DisplayName("❗판정 응답에는 신호가 안 실린다 — 판매자가 알면 문면만 바꿔 우회한다")
    void signalIsNotExposedInJudgmentResponse() {
        Session s = service.create(cmd(null));
        Judgment returned = service.recordJudgment(s.id(), tying("A"));

        // 서비스가 돌려주는 것은 ai-service 가 준 판정 그대로다 — 발행 사실을 얹지 않는다.
        // 발행됐다는 것을 여기서 알려주면 판매자가 어느 답변이 걸렸는지 알게 된다.
        assertThat(returned).isEqualTo(tying("A"));

        // ❗**도메인이 아니라 뷰에서 막는다.** 예전에는 이 자리에서 Judgment 레코드에
        // unfair·escalate·signal 이름의 필드가 아예 없는 것을 단정했다. 그 근거는
        // *"판정에 필드가 생기면 판매자 화면까지 흘러간다"* 였는데, `#147` 이후로 참이
        // 아니다 — 두 경로(단건 submitAnswer · 목록 judgments) 모두 JudgmentView 로 내려가고
        // 그 레코드가 담을 필드를 명시적으로 고른다.
        //
        // 그리고 그 단정은 **계약이 escalate 를 실을 수 없게 만들었다**(이슈 #160). 신호를
        // 서버가 들고 있으면서 판매자에게만 안 보내는 것이 목적인데, 도메인에 두지 못하게
        // 하면 들고 있을 자리가 없다.
        //
        // 잠금은 JudgmentViewFieldsTest 로 옮겼고 거기서 더 강해졌다 — 이름 세 개를 막는
        // 금지 목록이 아니라 **허용 목록**이라, 앞으로 붙는 어떤 필드든 판단을 강제한다.
        assertThat(JudgmentView.of(returned))
                .as("판매자에게 나가는 것은 뷰다 — 필드 잠금은 JudgmentViewFieldsTest")
                .isEqualTo(JudgmentView.of(tying("A")));
    }

    @Test
    @DisplayName("불변 기록 append 뒤에 낸다 — 근거 없는 알림이 먼저 가면 안 된다")
    void signalComesAfterEvidenceAppend() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), tying("A"));

        assertThat(evidence.judgments)
                .as("append 가 실패하면 같은 트랜잭션이라 여기까지 안 온다")
                .isNotEmpty();
        assertThat(published).filteredOn(e -> e instanceof UnfairSalesSignalEvent).hasSize(1);
    }

    // ── F-DET-002 모순 배선 (이슈 #65 · 결정 10.9) ─────────────────────
    @Test
    @DisplayName("❗모순 판정이 근거와 함께 불변 기록으로 간다 — 세션 필드에만 두면 덮인다 (#169)")
    void mismatchReachesTheImmutableRecordWithItsBasis() {
        Session s = service.create(cmd(null));
        var detected = new com.sphinxfin.sphinx.domain.SuitabilityMismatch(
                SuitabilityStatus.MISMATCH,
                "설문은 손실 감수 가능인데 발화는 원금 보장을 전제한다",
                new java.math.BigDecimal("0.82"),
                List.of(java.util.Map.of("axis", "risk_tolerance")));

        service.recordSuitability(s.id(), detected);

        assertThat(evidence.mismatches)
                .as("이 판정이 R-02 로 게이트를 움직이는데, append 가 없으면 감사 기록에 "
                        + "남는 것이 GateResult(signal, ruleTrace) 뿐이다 — 왜 모순인지에 "
                        + "답할 것이 하나도 없다")
                .hasSize(1);
        assertThat(evidence.mismatches.get(0).reason())
                .as("상태만 넘기면 ai-service 가 이미 만든 근거를 경계에서 버리게 된다")
                .isEqualTo("설문은 손실 감수 가능인데 발화는 원금 보장을 전제한다");
    }

    @Test
    @DisplayName("❗기록은 세션 저장 뒤에 간다 — 기록 없는 판정도 무효다")
    void theRecordFollowsTheSessionSave() {
        Session s = service.create(cmd(null));
        service.recordSuitability(s.id(), mismatchOf(SuitabilityStatus.MISMATCH));

        assertThat(service.get(s.id()).suitabilityMismatch())
                .as("세션에도 반영되고")
                .isTrue();
        assertThat(evidence.mismatches)
                .as("같은 트랜잭션 안이라 append 가 실패하면 세션 저장도 함께 롤백된다")
                .hasSize(1);
    }


    @Test
    @DisplayName("모순 판정 전에는 NOT_EVALUATED — 모순도 미확인도 아니다")
    void beforeDetectionIsNotEvaluated() {
        Session s = service.create(cmd(null));
        assertThat(s.suitabilityMismatch()).isFalse();
        assertThat(s.suitabilityUnknown()).isFalse();
    }

    @Test
    @DisplayName("❗판정 못 함(UNKNOWN)은 모순 없음과 다르다 — 게이트가 YELLOW 로 받는다")
    void unknownIsNotSameAsNoMismatch() {
        Session unknown = service.create(cmd(null));
        service.recordSuitability(unknown.id(), mismatchOf(SuitabilityStatus.UNKNOWN));
        service.recordJudgment(unknown.id(), j("A", Grade.U1));

        Session clean = service.create(cmd(null));
        service.recordSuitability(clean.id(), mismatchOf(SuitabilityStatus.NO_MISMATCH));
        service.recordJudgment(clean.id(), j("A", Grade.U1));

        // 같은 U1 인데 신호가 갈린다 — 그게 두 상태를 나눈 이유다
        assertThat(service.judge(unknown.id()).signal()).isEqualTo(Signal.YELLOW);
        assertThat(service.judge(clean.id()).signal()).isEqualTo(Signal.GREEN);
    }

    @Test
    @DisplayName("모순 확인 시 코칭 스코어를 다시 계산한다 — 생성 시점엔 모순을 몰랐다")
    void mismatchRecalculatesCoachingScore() {
        // 40대·소액·경험있음 → 가중 0점. mismatch-bonus 가 붙어야 점수가 오른다.
        Session s = service.create(new CreateSessionCommand("ELS-001", Channel.FACE_TO_FACE,
                "40대", "3년이상", "1천만원대", "CT-1", null, null));
        int before = s.coachingScore();

        Session after = service.recordSuitability(s.id(), mismatchOf(SuitabilityStatus.MISMATCH));

        assertThat(after.coachingScore())
                .as("모순이 확인됐는데 가산이 없으면 취약 임계값을 넘겨야 할 고객이 안 넘는다")
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("판정 못 함(UNKNOWN)은 코칭 가산 대상이 아니다 — 모순이 확인된 게 아니다")
    void unknownDoesNotAddCoachingBonus() {
        Session s = service.create(new CreateSessionCommand("ELS-001", Channel.FACE_TO_FACE,
                "40대", "3년이상", "1천만원대", "CT-1", null, null));
        int before = s.coachingScore();

        Session after = service.recordSuitability(s.id(), mismatchOf(SuitabilityStatus.UNKNOWN));

        assertThat(after.coachingScore()).isEqualTo(before);
    }

    /** 근거를 들고 오는 모순 판정. 상태만 보던 테스트를 최소로 옮긴다(#169). */
    private static com.sphinxfin.sphinx.domain.SuitabilityMismatch mismatchOf(
            SuitabilityStatus status) {
        return new com.sphinxfin.sphinx.domain.SuitabilityMismatch(
                status, "테스트 사유", null, List.of());
    }

    /** evidence append 지점이 실제로 호출되는지 보려는 테스트 더블(구현은 정세현 evidence/). */
    private static final class RecordingEvidence implements EvidenceRecorder {
        private final List<String> judgments = new ArrayList<>();
        private final List<Signal> gates = new ArrayList<>();
        private final List<String> askedQuestions = new ArrayList<>();
        private final List<com.sphinxfin.sphinx.domain.SuitabilityMismatch> mismatches
                = new ArrayList<>();
        private final List<EvidenceRecorder.QuestionSource> questionSources = new ArrayList<>();

        @Override
        public void appendJudgment(String sessionId, Judgment judgment, int reverifyCount,
                                   String askedQuestion, QuestionSource questionSource,
                                   Instant at) {
            judgments.add(judgment.itemId() + ":" + judgment.grade() + ":" + reverifyCount);
            askedQuestions.add(askedQuestion);
            questionSources.add(questionSource);
        }

        @Override
        public void appendMismatch(String sessionId,
                com.sphinxfin.sphinx.domain.SuitabilityMismatch mismatch,
                String surveySchemaVersion, java.util.Map<String, Object> surveyResult,
                Instant at) {
            mismatches.add(mismatch);
        }

        @Override
        public void appendGate(String sessionId, com.sphinxfin.sphinx.domain.GateResult result, Instant at) {
            gates.add(result.signal());
        }

        @Override
        public void appendOverride(String sessionId, String reason, String approver, Instant at) {
            // 이 테스트는 세션 루프만 다룬다 — 오버라이드 append는 OverrideServiceTest에서 검증.
        }
    }

    private CreateSessionCommand cmd(Map<String, Object> survey) {
        return new CreateSessionCommand("ELS-001", Channel.FACE_TO_FACE, "60대",
                "없음", "5천만원대", "CT-1", "SUIT-v1", survey);
    }

    @Test
    @DisplayName("생성 시 UUID 발급·CREATED·감사필드 자동 채움")
    void createPersistsWithAuditing() {
        Session s = service.create(cmd(Map.of("riskProfile", "안정형")));

        assertThat(s.id()).isNotBlank();
        assertThat(s.state()).isEqualTo(SessionState.CREATED);
        assertThat(s.createdAt()).isNotNull();      // BaseEntity 감사
        assertThat(s.updatedAt()).isNotNull();
        assertThat(s.surveySchemaVersion()).isEqualTo("SUIT-v1");   // #43③ 설문 버전 보존
        assertThat(repository.findById(s.id())).isPresent();
        // F-DET-002 코칭: 60대(3)+5천만원대(1)+없음(3)+FACE_TO_FACE(0) = 7 → 취약
        assertThat(s.coachingScore()).isEqualTo(7);
        assertThat(s.vulnerable()).isTrue();
    }

    @Test
    @DisplayName("설문·재검증 카운트가 DB 왕복 후에도 보존")
    void surveyAndReverifyRoundTrip() {
        Session s = service.create(cmd(Map.of("riskProfile", "안정형")));
        s.recordReverify("원금손실");
        s.recordReverify("원금손실");
        repository.save(s);

        em.flush();
        em.clear();   // 1차 캐시 비워 실제 DB 재조회 강제

        Session reloaded = repository.findById(s.id()).orElseThrow();
        assertThat(reloaded.surveyResult()).containsEntry("riskProfile", "안정형");
        assertThat(reloaded.reverifyCount("원금손실")).isEqualTo(2);
        assertThat(reloaded.reverifyExhausted("원금손실", 2)).isTrue();
    }

    @Test
    @DisplayName("없는 세션 조회 → NoSuchElementException")
    void getMissingThrows() {
        assertThatThrownBy(() -> service.get("nope"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("판정 기록 후 게이트 판정: U4 오해 → RED (R-01)")
    void recordJudgmentThenJudgeRed() {
        Session s = service.create(cmd(null));
        Judgment u4 = new Judgment("ELS-PRINCIPAL-LOSS-WARNING", Grade.U4, conf("0.9"),
                new Judgment.Evidence("은행이니까 원금 보장되죠", "원금손실 조건 인지 필요"),
                "원금 보장 오해", "M01-PRINCIPAL-GUARANTEE");
        service.recordJudgment(s.id(), u4);
        // 첫 답변 → 인터뷰 시작
        assertThat(service.get(s.id()).state()).isEqualTo(SessionState.IN_PROGRESS);

        var result = service.judge(s.id());
        assertThat(result.signal()).isEqualTo(Signal.RED);
        assertThat(result.ruleTrace()).containsExactly("R-01");
        // 판정 후 → JUDGED + 게이트 결과 기록(F-GTE-004 감사 기준점)
        Session judged = service.get(s.id());
        assertThat(judged.state()).isEqualTo(SessionState.JUDGED);
        assertThat(judged.gateSignal()).isEqualTo(Signal.RED);
        assertThat(judged.judgedAt()).isNotNull();

        em.flush();
        em.clear();   // 컨버터를 실제로 태운다(1차 캐시로 통과하지 않도록)

        // 저장 형태가 콤마 문자열이 아니라 JSON 배열이어야 한다 — evidence가 CanonicalJson으로
        // 해싱할 때 배열로 정규화돼야 하고, 룰 ID에 콤마가 들어가도 깨지지 않아야 한다.
        Object raw = em.getEntityManager()
                .createNativeQuery("select gate_rule_trace from sessions where id = ?1")
                .setParameter(1, s.id())
                .getSingleResult();
        assertThat(raw.toString()).isEqualTo("[\"R-01\"]");
        assertThat(repository.findById(s.id()).orElseThrow().gateRuleTrace())
                .containsExactly("R-01");
    }

    @Test
    @DisplayName("판정 없는 세션의 미리보기 → fail-closed RED (엔진 동작은 그대로)")
    void previewWithNoJudgment_failsClosed() {
        // 게이트가 보수적으로 RED 를 내는 것(P5)은 그대로다. 바뀐 건 그 RED 로 감사
        // 기준점을 찍을 수 있느냐이고, 그건 judge 쪽에서 막는다.
        Session s = service.create(cmd(null));
        assertThat(service.previewGate(s.id()).signal()).isEqualTo(Signal.RED);
        assertThat(service.previewGate(s.id()).recorded()).isFalse();
    }

    // ── F-INT-004 재설명·재검증 루프 ────────────────────────────────────

    /** 문자열에서 만든다 — new BigDecimal(double) 은 0.9 를 0.9000000000000000222… 로 만든다. */
    private static BigDecimal conf(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("❗물어봤는데 판정이 없으면 RED — 차집합이 실제로 도는 경로다 (이슈 #280 ②)")
    void anAskedButUnjudgedItemBlocksTheVerdict() {
        // ❗GateEngineTest 는 미측정 수를 **손으로 넣는다**(engine.judge(…, 1)). 그래서 룰은
        // 잠기는데 **그 룰에 들어가는 숫자는 아무도 안 지나간다** — unmeasuredItemCount() 가
        // return 0 이어도 전건 초록이었다(#291 리뷰, 윤지석 실측).
        //
        // 여기서는 askedQuestionsByItem 과 judgments 를 실제로 갈라 놓고 judge() 를 부른다.
        Session s = service.create(cmd(null));
        service.recordAskedQuestion(s.id(), "A", "A 질문", EvidenceRecorder.QuestionSource.DISPLAYED);
        service.recordAskedQuestion(s.id(), "B", "B 질문", EvidenceRecorder.QuestionSource.DISPLAYED);
        service.recordJudgment(s.id(), j("A", Grade.U1));   // B 는 채점이 실패했다고 본다

        GateResult r = service.judge(s.id());

        assertThat(r.signal())
                .as("A 가 U1 이라 R-06 이 GREEN 을 냈다 — 물어본 B 를 못 쟀는데도. "
                        + "이 경로를 안 지나가면 계산이 0 을 돌려줘도 아무도 모른다")
                .isEqualTo(Signal.RED);
        assertThat(r.ruleTrace()).contains("R-00");
    }

    @Test
    @DisplayName("❗미리보기도 같은 답을 낸다 — 미리보기가 더 낙관적이면 재설명 루프를 건너뛴다")
    void thePreviewAgreesWithTheVerdict() {
        Session s = service.create(cmd(null));
        service.recordAskedQuestion(s.id(), "A", "A 질문", EvidenceRecorder.QuestionSource.DISPLAYED);
        service.recordAskedQuestion(s.id(), "B", "B 질문", EvidenceRecorder.QuestionSource.DISPLAYED);
        service.recordJudgment(s.id(), j("A", Grade.U1));

        assertThat(service.previewGate(s.id()).signal())
                .as("previewGate 가 미측정 수를 안 넘기면 여기가 GREEN 이고, 판매자는 그걸 보고 "
                        + "재설명을 건너뛴다 — /judge 는 RED 를 낸다")
                .isEqualTo(Signal.RED);
    }

    private static Judgment j(String itemId, Grade grade) {
        return new Judgment(itemId, grade, conf("0.9"),
                new Judgment.Evidence("발화 인용", "루브릭 조항"), "사유", null);
    }

    @Test
    @DisplayName("재설명 후 재검증 통과: 미이해→재설명→이해(U1) → 상태 복귀 후 GREEN")
    void reexplainThenUnderstood_isGreen() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U3));              // IN_PROGRESS
        assertThat(service.get(s.id()).state()).isEqualTo(SessionState.IN_PROGRESS);

        reExplain(s.id(), "A");                                // → RE_EXPLAIN
        assertThat(service.get(s.id()).state()).isEqualTo(SessionState.RE_EXPLAIN);

        service.recordJudgment(s.id(), j("A", Grade.U1));             // 재검증 통과 → IN_PROGRESS 복귀
        assertThat(service.get(s.id()).state()).isEqualTo(SessionState.IN_PROGRESS);
        assertThat(service.get(s.id()).reverifyCount("A")).isEqualTo(1);

        var r = service.judge(s.id());
        assertThat(r.signal()).isEqualTo(Signal.GREEN);
    }

    @Test
    @DisplayName("재검증 2회 실패: 계속 미이해 → 상한 도달·재설명 불가 → RED (R-03)")
    void reexplainTwiceFailed_isRed() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U3));   // IN_PROGRESS
        reExplain(s.id(), "A");                      // RE_EXPLAIN
        service.recordJudgment(s.id(), j("A", Grade.U3));   // 재검증1 실패 (RE_VERIFY)
        reExplain(s.id(), "A");                      // RE_EXPLAIN
        service.recordJudgment(s.id(), j("A", Grade.U3));   // 재검증2 실패 (RE_VERIFY)

        assertThat(service.get(s.id()).reverifyCount("A")).isEqualTo(2);
        // 상한 도달 → 재설명 불가. '대상 아님'과 타입이 달라야 프론트가 문면 파싱 없이 가른다.
        assertThatThrownBy(() -> reExplain(s.id(), "A"))
                .isInstanceOf(ReverifyExhaustedException.class);

        var r = service.judge(s.id());
        assertThat(r.signal()).isEqualTo(Signal.RED);
        assertThat(r.ruleTrace()).containsExactly("R-03");
    }

    @Test
    @DisplayName("이해(U1) 항목은 재설명 대상이 아니다 → REEXPLAIN_NOT_ELIGIBLE 계열 예외")
    void reexplainUnderstoodItem_rejected() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U1));
        assertThatThrownBy(() -> reExplain(s.id(), "A"))
                .isInstanceOf(ReExplainNotEligibleException.class);
    }

    @Test
    @DisplayName("판정이 아직 없는 항목도 재설명 대상이 아니다")
    void reexplainUnjudgedItem_rejected() {
        Session s = service.create(cmd(null));
        assertThatThrownBy(() -> reExplain(s.id(), "A"))
                .isInstanceOf(ReExplainNotEligibleException.class);
    }

    @Test
    @DisplayName("재설명 응답에 재검증용 변형 질문이 실린다 — 직전 질문 재사용 방지(F-INT-002)")
    void reexplainCarriesReverifyQuestion() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U3));

        var r = reExplain(s.id(), "A");
        assertThat(r.reverifyQuestion()).isNotBlank();
        assertThat(r.content()).isNotBlank();
    }

    @Test
    @DisplayName("재설명 콘텐츠는 ai-service 응답에서 온다 (F-INT-004 배선)")
    void reexplainContentComesFromAiService() {
        when(aiClient.reExplain(any(RiskItem.class), any(Judgment.class),
                nullable(String.class), nullable(String.class)))
                .thenReturn(new AiServiceClient.ReExplanation("ai-service 가 만든 재설명", List.of()));
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U3));

        var r = reExplain(s.id(), "A");

        // 문면은 더 이상 서비스 목이 아니라 ai-service 가 만든 것이다(목 문자열이 지워졌다).
        assertThat(r.content()).isEqualTo("ai-service 가 만든 재설명");
    }

    @Test
    @DisplayName("고령자 모드에서는 재설명 콘텐츠·변형 질문이 같은 눈높이여야 한다")
    void reverifyQuestionFollowsVulnerableMode() {
        // 60대(3)+5천만원대(1)+없음(3) = 7 ≥ 임계 4 → 취약
        Session s = service.create(cmd(Map.of("riskProfile", "안정형")));
        assertThat(s.vulnerable()).isTrue();
        service.recordJudgment(s.id(), j("ELS-PRINCIPAL-LOSS-WARNING", Grade.U3));

        var r = reExplain(s.id(), "ELS-PRINCIPAL-LOSS-WARNING");
        assertThat(r.vulnerable()).isTrue();
        // 콘텐츠의 눈높이는 이제 ai-service 가 연령대·경험수준으로 맞춘다 — 세션 값이 실제로
        // 넘어가야 ai-service 가 고령자 모드로 생성할 수 있다.
        verify(aiClient).reExplain(any(RiskItem.class), any(Judgment.class), eq("60대"), eq("없음"));
        // 변형 질문은 여전히 서비스 목이고 취약 모드를 따른다 — 쉬운 말 설명에 전문용어로 되묻지 않는다.
        assertThat(r.reverifyQuestion()).doesNotContain("기초자산");
    }

    @Test
    @DisplayName("재설명은 '해당 항목만' 다룬다 — 항목이 갈리면 ai-service 로 넘어가는 risk_item 도 갈린다")
    void reexplainForwardsScopedItemToAiService() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("ELS-PRINCIPAL-LOSS-WARNING", Grade.U3));
        service.recordJudgment(s.id(), j("ELS-NO-DEPOSIT-INSURANCE", Grade.U3));

        reExplain(s.id(), "ELS-PRINCIPAL-LOSS-WARNING");
        // 상태를 되돌리기 위해 재검증을 한 번 태운다(RE_EXPLAIN → RE_VERIFY → IN_PROGRESS)
        service.recordJudgment(s.id(), j("ELS-PRINCIPAL-LOSS-WARNING", Grade.U1));
        reExplain(s.id(), "ELS-NO-DEPOSIT-INSURANCE");

        ArgumentCaptor<RiskItem> itemCaptor = ArgumentCaptor.forClass(RiskItem.class);
        verify(aiClient, times(2)).reExplain(itemCaptor.capture(), any(Judgment.class),
                nullable(String.class), nullable(String.class));
        // 항목이 다르면 넘어가는 risk_item 도 달라야 한다 — 예금자보호를 물었는데 원금손실
        // 항목을 넘기면 재설명이 아니라 딴소리가 된다(스코핑은 이제 배선이 담보한다).
        assertThat(itemCaptor.getAllValues()).extracting(RiskItem::itemId)
                .containsExactly("ELS-PRINCIPAL-LOSS-WARNING", "ELS-NO-DEPOSIT-INSURANCE");
    }

    @Test
    @DisplayName("덮어쓰기 전 판정이 evidence로 append된다 — 재설명 이력 보존(기획서 174행)")
    void reexplainHistoryIsAppendedToEvidence() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U3));   // 최초 미이해
        reExplain(s.id(), "A");
        service.recordJudgment(s.id(), j("A", Grade.U1));   // 재검증 통과 — 세션에서 U3는 소실
        var result = service.judge(s.id());

        // 세션에는 최신값만 남는다 — 게이트 입력으로는 이게 맞다
        assertThat(service.get(s.id()).judgmentFor("A").grade()).isEqualTo(Grade.U1);
        assertThat(result.signal()).isEqualTo(Signal.GREEN);
        // 이력은 evidence 쪽에 전부 남는다 — "처음에 미이해였다"가 복원 가능해야 한다
        assertThat(evidence.judgments).containsExactly("A:U3:0", "A:U1:1");
        assertThat(evidence.gates).containsExactly(Signal.GREEN);
    }

    @Test
    @DisplayName("evidence 구현이 없어도(NO_OP) 세션 루프는 그대로 돈다")
    void worksWithoutEvidenceRecorder() {
        SessionService bare = new SessionService(repository, new GateEngine(),
                new CoachingScoreService(), Optional.empty(), aiClient, events);
        Session s = bare.create(cmd(null));
        bare.recordJudgment(s.id(), j("A", Grade.U1));
        assertThat(bare.judge(s.id()).signal()).isEqualTo(Signal.GREEN);
    }

    @Test
    @DisplayName("중단 → ABORTED")
    void abortEndsSession() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U3));
        Session aborted = service.abort(s.id());
        assertThat(aborted.state()).isEqualTo(SessionState.ABORTED);
    }

    // ── 2026-08-25 결정 반영 ────────────────────────────────────────────

    @Test
    @DisplayName("판정 0건 세션은 판정할 수 없다 — JUDGED 는 되돌릴 수 없으므로")
    void judgeWithNoJudgment_isRejected() {
        Session s = service.create(cmd(null));
        assertThatThrownBy(() -> service.judge(s.id()))
                .isInstanceOf(SessionFsm.IllegalStateTransitionException.class);
        // 거절됐으니 상태도 기준점도 안 남는다
        assertThat(service.get(s.id()).state()).isEqualTo(SessionState.CREATED);
        assertThat(service.get(s.id()).judgedAt()).isNull();
        assertThat(evidence.gates).isEmpty();
    }

    @Test
    @DisplayName("judge 는 멱등하다 — 재호출해도 재계산하지 않고 기록값을 돌려준다")
    void judgeIsIdempotent() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U3));

        var first = service.judge(s.id());
        Instant firstAt = service.get(s.id()).judgedAt();
        var second = service.judge(s.id());
        var third = service.judge(s.id());

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        // 기준점이 갱신되지 않는다 — 감사 기준점은 재계산값이 아니라 기록값이다
        assertThat(service.get(s.id()).judgedAt()).isEqualTo(firstAt);
        // append 도 1건뿐이다 (evidence 는 흡수하지 않으므로 서버가 막는다)
        assertThat(evidence.gates).hasSize(1);
    }

    @Test
    @DisplayName("미리보기는 아무것도 기록하지 않는다 — 7-2 [기능 1] 재설명 흐름이 성립한다")
    void previewGateHasNoSideEffect() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U3));   // 황색 재료

        var preview = service.previewGate(s.id());
        assertThat(preview.signal()).isEqualTo(Signal.YELLOW);
        assertThat(preview.recorded()).isFalse();
        assertThat(preview.judgedAt()).isNull();

        // 상태도 기준점도 안 움직인다 → 재설명 루프로 들어갈 수 있다
        assertThat(service.get(s.id()).state()).isEqualTo(SessionState.IN_PROGRESS);
        assertThat(service.get(s.id()).judgedAt()).isNull();
        assertThat(evidence.gates).isEmpty();

        // 황색을 보고 재설명 → 재검증 → 녹색. 미리보기가 없으면 이 흐름이 막힌다.
        reExplain(s.id(), "A");
        service.recordJudgment(s.id(), j("A", Grade.U1));
        assertThat(service.previewGate(s.id()).signal()).isEqualTo(Signal.GREEN);
        assertThat(service.judge(s.id()).signal()).isEqualTo(Signal.GREEN);
    }

    @Test
    @DisplayName("판정 후 미리보기는 재계산하지 않고 기록값을 준다(recorded=true)")
    void previewAfterJudgeReturnsRecorded() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U1));
        var judged = service.judge(s.id());

        var preview = service.previewGate(s.id());
        assertThat(preview.recorded()).isTrue();
        assertThat(preview.judgedAt()).isNotNull();
        assertThat(preview.signal()).isEqualTo(judged.signal());
        assertThat(preview.ruleTrace()).isEqualTo(judged.ruleTrace());
    }
}
