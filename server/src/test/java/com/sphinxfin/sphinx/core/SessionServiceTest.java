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
        service = new SessionService(repository, new GateEngine(), new CoachingScoreService());
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
        // 판정 후 → JUDGED
        assertThat(service.get(s.id()).state()).isEqualTo(SessionState.JUDGED);
    }

    @Test
    @DisplayName("판정 없이 게이트 판정 → fail-closed RED")
    void judgeWithNoJudgment_failsClosed() {
        Session s = service.create(cmd(null));
        assertThat(service.judge(s.id()).signal()).isEqualTo(Signal.RED);
    }
}
