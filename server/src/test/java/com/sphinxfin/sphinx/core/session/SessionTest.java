package com.sphinxfin.sphinx.core.session;

import com.sphinxfin.sphinx.core.EvidenceRecorder;
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
    @DisplayName("❗쓴 질문 유형은 항목별로 쌓이고 순서를 지킨다 — 물어본 순서가 정보다")
    void askedTypesAccumulatePerItem() {
        Session s = newSession();
        assertThat(s.askedTypes("A")).isEmpty();

        s.recordAskedQuestion("A", "1회차", "situation", EvidenceRecorder.QuestionSource.DISPLAYED);
        s.recordAskedQuestion("A", "2회차", "amount", EvidenceRecorder.QuestionSource.REVERIFY);
        s.recordAskedQuestion("B", "다른 항목", "condition",
                EvidenceRecorder.QuestionSource.DISPLAYED);

        assertThat(s.askedTypes("A")).containsExactly("situation", "amount");
        assertThat(s.askedTypes("B"))
                .as("유형은 항목의 성격을 따라간다 — 세션 단위로 배제하면 뒤 항목이 자기에게 "
                        + "맞는 유형을 못 쓴다")
                .containsExactly("condition");
    }

    @Test
    @DisplayName("같은 유형을 두 번 적지 않는다 — 목록은 '무엇을 썼나' 이지 몇 번이 아니다")
    void theSameTypeIsNotRecordedTwice() {
        Session s = newSession();
        s.recordAskedQuestion("A", "1회차", "situation", EvidenceRecorder.QuestionSource.DISPLAYED);
        s.recordAskedQuestion("A", "2회차", "situation", EvidenceRecorder.QuestionSource.REVERIFY);

        assertThat(s.askedTypes("A")).containsExactly("situation");
    }

    @Test
    @DisplayName("유형을 모르면 안 적는다 — 빈 값을 넣으면 생성기가 없는 유형을 배제한다")
    void anUnknownTypeIsNotRecorded() {
        Session s = newSession();
        s.recordAskedQuestion("A", "문면", null, EvidenceRecorder.QuestionSource.DISPLAYED);
        s.recordAskedQuestion("A", "문면", "  ", EvidenceRecorder.QuestionSource.DISPLAYED);

        assertThat(s.askedTypes("A")).isEmpty();
        assertThat(s.askedQuestion("A"))
                .as("유형이 없다고 문면까지 잃으면 채점이 다른 질문으로 채점한다")
                .isEqualTo("문면");
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
