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

import java.util.Map;
import java.util.NoSuchElementException;

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

    @BeforeEach
    void setUp() {
        service = new SessionService(repository, new GateEngine(), new CoachingScoreService(), 2);
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
        assertThat(judged.gateRuleTrace()).isEqualTo("R-01");
        assertThat(judged.judgedAt()).isNotNull();
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
        // 상한 도달 → 재설명 불가
        assertThatThrownBy(() -> service.reExplain(s.id(), "A"))
                .isInstanceOf(IllegalArgumentException.class);

        var r = service.judge(s.id());
        assertThat(r.signal()).isEqualTo(Signal.RED);
        assertThat(r.ruleTrace()).containsExactly("R-03");
    }

    @Test
    @DisplayName("이해(U1) 항목은 재설명 대상이 아니다 → 예외")
    void reexplainUnderstoodItem_rejected() {
        Session s = service.create(cmd(null));
        service.recordJudgment(s.id(), j("A", Grade.U1));
        assertThatThrownBy(() -> service.reExplain(s.id(), "A"))
                .isInstanceOf(IllegalArgumentException.class);
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
