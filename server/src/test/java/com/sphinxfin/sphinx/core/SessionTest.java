package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.domain.Channel;
import com.sphinxfin.sphinx.domain.SessionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("F-INT-001 세션 집합체")
class SessionTest {

    private static Session newSession() {
        return Session.builder()
                .id("s1").productId("ELS-001").channel(Channel.FACE_TO_FACE).ageBand("60대")
                .experienceLevel("없음").amountBand("5천만원대").contractRef("CONTRACT-2026-0001")
                .surveyResult(Map.of("riskProfile", "안정형"))
                .build();
    }

    @Test
    @DisplayName("생성 직후 상태는 CREATED")
    void startsInCreated() {
        assertThat(newSession().state()).isEqualTo(SessionState.CREATED);
    }

    @Test
    @DisplayName("fire는 FSM을 따라 상태를 전이시킨다")
    void fireAdvancesState() {
        Session s = newSession();
        s.fire(SessionFsm.Event.START);
        assertThat(s.state()).isEqualTo(SessionState.IN_PROGRESS);
    }

    @Test
    @DisplayName("불법 전이를 fire하면 예외, 상태는 유지")
    void illegalFireThrowsAndKeepsState() {
        Session s = newSession();
        assertThatThrownBy(() -> s.fire(SessionFsm.Event.JUDGE))
                .isInstanceOf(SessionFsm.IllegalStateTransitionException.class);
        assertThat(s.state()).isEqualTo(SessionState.CREATED);
    }

    @Test
    @DisplayName("항목별 재검증 카운트는 독립적으로 누적된다")
    void reverifyCountPerItem() {
        Session s = newSession();
        assertThat(s.reverifyCount("A")).isZero();
        assertThat(s.recordReverify("A")).isEqualTo(1);
        assertThat(s.recordReverify("A")).isEqualTo(2);
        assertThat(s.recordReverify("B")).isEqualTo(1);
        assertThat(s.reverifyCount("A")).isEqualTo(2);
        assertThat(s.reverifyCount("B")).isEqualTo(1);
    }

    @Test
    @DisplayName("상한(2회) 도달 시 reverifyExhausted true")
    void reverifyExhaustedAtMax() {
        Session s = newSession();
        s.recordReverify("A");
        assertThat(s.reverifyExhausted("A", 2)).isFalse();
        s.recordReverify("A");
        assertThat(s.reverifyExhausted("A", 2)).isTrue();
    }

    @Test
    @DisplayName("설문 결과 미지정이면 빈 맵으로 안전 처리")
    void noSurveyBecomesEmptyMap() {
        Session s = Session.builder()
                .id("s2").productId("ELS-001").channel(Channel.MOBILE).ageBand("30대")
                .build();
        assertThat(s.surveyResult()).isEmpty();
    }
}
