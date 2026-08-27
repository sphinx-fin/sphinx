package com.sphinxfin.sphinx.core.session;

import java.math.BigDecimal;

import com.sphinxfin.sphinx.domain.Channel;
import com.sphinxfin.sphinx.domain.Grade;
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
    private AiServiceClient aiClient;

    /** ai-service 재설명 콘텐츠 기본값 — 테스트별로 필요하면 재스텁한다. */
    private static final String AI_REEXPLAIN = "[ai-service 재설명 콘텐츠]";

    @BeforeEach
    void setUp() {
        evidence = new RecordingEvidence();
        aiClient = mock(AiServiceClient.class);
        // 재설명 배선(F-INT-004): 콘텐츠는 이제 ai-service 가 만든다. 상류 의존성이라 목으로 대신하고,
        // HTTP 계약(snake_case·파싱·실패 매핑)은 AiServiceClientTest 가 따로 검증한다.
        when(aiClient.reExplain(any(RiskItem.class), any(Judgment.class),
                nullable(String.class), nullable(String.class)))
                .thenReturn(new AiServiceClient.ReExplanation(AI_REEXPLAIN, List.of()));
        service = new SessionService(repository, new GateEngine(), new CoachingScoreService(),
                Optional.of(evidence), aiClient, 2);
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
        service.recordSuitability(s.id(), SuitabilityStatus.UNKNOWN);

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
                "원금이 줄어드는 조건을 말씀해 주시겠어요?");

        assertThat(evidence.askedQuestions)
                .as("서비스가 세션 맵을 다시 읽으면 값을 두 곳에서 구하게 되고, 폴백처럼 "
                        + "한쪽에만 있는 분기에서 채점값과 기록값이 갈린다 (#137 리뷰)")
                .containsExactly("원금이 줄어드는 조건을 말씀해 주시겠어요?");
    }

    @Test
    @DisplayName("재검증하면 각 판정에 그때의 질문이 따로 남는다 — 세션 맵은 마지막 것만 갖는다")
    void reaskedQuestionGoesWithItsOwnJudgment() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U3), null, "첫 질문");

        service.reExplain(s.id(), "A", riskItem("A"));
        service.recordJudgment(s.id(), j("A", Grade.U1), null, "다시 여쭙는 질문");

        assertThat(evidence.askedQuestions)
                .as("세션 맵은 덮어쓰지만 불변 기록에는 판정마다 그 질문이 남아야 한다 (#136)")
                .containsExactly("첫 질문", "다시 여쭙는 질문");
    }

    // ── F-DET-002 모순 배선 (이슈 #65 · 결정 10.9) ─────────────────────

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
        service.recordSuitability(unknown.id(), SuitabilityStatus.UNKNOWN);
        service.recordJudgment(unknown.id(), j("A", Grade.U1));

        Session clean = service.create(cmd(null));
        service.recordSuitability(clean.id(), SuitabilityStatus.NO_MISMATCH);
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

        Session after = service.recordSuitability(s.id(), SuitabilityStatus.MISMATCH);

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

        Session after = service.recordSuitability(s.id(), SuitabilityStatus.UNKNOWN);

        assertThat(after.coachingScore()).isEqualTo(before);
    }

    /** evidence append 지점이 실제로 호출되는지 보려는 테스트 더블(구현은 정세현 evidence/). */
    private static final class RecordingEvidence implements EvidenceRecorder {
        private final List<String> judgments = new ArrayList<>();
        private final List<Signal> gates = new ArrayList<>();
        private final List<String> askedQuestions = new ArrayList<>();

        @Override
        public void appendJudgment(String sessionId, Judgment judgment, int reverifyCount,
                                   String askedQuestion, Instant at) {
            judgments.add(judgment.itemId() + ":" + judgment.grade() + ":" + reverifyCount);
            askedQuestions.add(askedQuestion);
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
                new CoachingScoreService(), Optional.empty(), aiClient, 2);
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
