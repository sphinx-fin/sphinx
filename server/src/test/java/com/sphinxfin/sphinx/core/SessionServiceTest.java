package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.domain.Channel;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.SessionState;
import com.sphinxfin.sphinx.domain.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    @BeforeEach
    void setUp() {
        evidence = new RecordingEvidence();
        service = new SessionService(repository, new GateEngine(), new CoachingScoreService(),
                Optional.of(evidence), 2);
    }

    /** evidence append 지점이 실제로 호출되는지 보려는 테스트 더블(구현은 정세현 evidence/). */
    private static final class RecordingEvidence implements EvidenceRecorder {
        private final List<String> judgments = new ArrayList<>();
        private final List<Signal> gates = new ArrayList<>();

        @Override
        public void appendJudgment(String sessionId, Judgment judgment, int reverifyCount, Instant at) {
            judgments.add(judgment.itemId() + ":" + judgment.grade() + ":" + reverifyCount);
        }

        @Override
        public void appendGate(String sessionId, com.sphinxfin.sphinx.domain.GateResult result, Instant at) {
            gates.add(result.signal());
        }
    }

    private CreateSessionCommand cmd(Map<String, Object> survey) {
        return new CreateSessionCommand("ELS-001", Channel.FACE_TO_FACE, "60대",
                "없음", "5천만원대", "CT-1", survey);
    }

    @Test
    @DisplayName("생성 시 UUID 발급·CREATED·감사필드 자동 채움")
    void createPersistsWithAuditing() {
        Session s = service.create(cmd(Map.of("riskProfile", "안정형")));

        assertThat(s.id()).isNotBlank();
        assertThat(s.state()).isEqualTo(SessionState.CREATED);
        assertThat(s.createdAt()).isNotNull();      // BaseEntity 감사
        assertThat(s.updatedAt()).isNotNull();
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
        Judgment u4 = new Judgment("ELS-PRINCIPAL-LOSS-WARNING", Grade.U4, 0.9,
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
    @DisplayName("판정 없이 게이트 판정 → fail-closed RED")
    void judgeWithNoJudgment_failsClosed() {
        Session s = service.create(cmd(null));
        assertThat(service.judge(s.id()).signal()).isEqualTo(Signal.RED);
    }

    // ── F-INT-004 재설명·재검증 루프 ────────────────────────────────────

    private static Judgment j(String itemId, Grade grade) {
        return new Judgment(itemId, grade, 0.9,
                new Judgment.Evidence("발화 인용", "루브릭 조항"), "사유", null);
    }

    @Test
    @DisplayName("재설명 후 재검증 통과: 미이해→재설명→이해(U1) → 상태 복귀 후 GREEN")
    void reexplainThenUnderstood_isGreen() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U3));              // IN_PROGRESS
        assertThat(service.get(s.id()).state()).isEqualTo(SessionState.IN_PROGRESS);

        service.reExplain(s.id(), "A");                                // → RE_EXPLAIN
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
        service.reExplain(s.id(), "A");                      // RE_EXPLAIN
        service.recordJudgment(s.id(), j("A", Grade.U3));   // 재검증1 실패 (RE_VERIFY)
        service.reExplain(s.id(), "A");                      // RE_EXPLAIN
        service.recordJudgment(s.id(), j("A", Grade.U3));   // 재검증2 실패 (RE_VERIFY)

        assertThat(service.get(s.id()).reverifyCount("A")).isEqualTo(2);
        // 상한 도달 → 재설명 불가. '대상 아님'과 타입이 달라야 프론트가 문면 파싱 없이 가른다.
        assertThatThrownBy(() -> service.reExplain(s.id(), "A"))
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
        assertThatThrownBy(() -> service.reExplain(s.id(), "A"))
                .isInstanceOf(ReExplainNotEligibleException.class);
    }

    @Test
    @DisplayName("판정이 아직 없는 항목도 재설명 대상이 아니다")
    void reexplainUnjudgedItem_rejected() {
        Session s = service.create(cmd(null));
        assertThatThrownBy(() -> service.reExplain(s.id(), "A"))
                .isInstanceOf(ReExplainNotEligibleException.class);
    }

    @Test
    @DisplayName("재설명 응답에 재검증용 변형 질문이 실린다 — 직전 질문 재사용 방지(F-INT-002)")
    void reexplainCarriesReverifyQuestion() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U3));

        var r = service.reExplain(s.id(), "A");
        assertThat(r.reverifyQuestion()).isNotBlank();
        assertThat(r.content()).isNotBlank();
    }

    @Test
    @DisplayName("고령자 모드에서는 재설명 문면과 변형 질문이 같은 눈높이여야 한다")
    void reverifyQuestionFollowsVulnerableMode() {
        // 60대(3)+5천만원대(1)+없음(3) = 7 ≥ 임계 4 → 취약
        Session s = service.create(cmd(Map.of("riskProfile", "안정형")));
        assertThat(s.vulnerable()).isTrue();
        service.recordJudgment(s.id(), j("ELS-PRINCIPAL-LOSS-WARNING", Grade.U3));

        var r = service.reExplain(s.id(), "ELS-PRINCIPAL-LOSS-WARNING");
        assertThat(r.vulnerable()).isTrue();
        // 쉬운 말로 설명해 놓고 곧바로 전문용어로 되물으면 한 응답 안에서 모드가 깨진다.
        assertThat(r.content()).doesNotContain("기초자산");
        assertThat(r.reverifyQuestion()).doesNotContain("기초자산");
    }

    @Test
    @DisplayName("재설명은 '해당 항목만' 설명한다 — 항목이 다르면 문면도 달라야 한다")
    void reexplainContentIsScopedToItem() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("ELS-PRINCIPAL-LOSS-WARNING", Grade.U3));
        service.recordJudgment(s.id(), j("ELS-NO-DEPOSIT-INSURANCE", Grade.U3));

        String loss = service.reExplain(s.id(), "ELS-PRINCIPAL-LOSS-WARNING").content();
        // 상태를 되돌리기 위해 재검증을 한 번 태운다(RE_EXPLAIN → RE_VERIFY → IN_PROGRESS)
        service.recordJudgment(s.id(), j("ELS-PRINCIPAL-LOSS-WARNING", Grade.U1));
        String deposit = service.reExplain(s.id(), "ELS-NO-DEPOSIT-INSURANCE").content();

        assertThat(loss).isNotEqualTo(deposit);
        assertThat(deposit).contains("예금자보호");
    }

    @Test
    @DisplayName("덮어쓰기 전 판정이 evidence로 append된다 — 재설명 이력 보존(기획서 174행)")
    void reexplainHistoryIsAppendedToEvidence() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U3));   // 최초 미이해
        service.reExplain(s.id(), "A");
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
                new CoachingScoreService(), Optional.empty(), 2);
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
}
