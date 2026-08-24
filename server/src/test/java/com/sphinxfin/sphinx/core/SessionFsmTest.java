package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.core.SessionFsm.Event;
import com.sphinxfin.sphinx.domain.SessionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("F-INT-001 세션 상태머신")
class SessionFsmTest {

    @Test
    @DisplayName("정상 경로: CREATED→IN_PROGRESS→JUDGED→CLOSED")
    void happyPath() {
        SessionState s = SessionFsm.next(SessionState.CREATED, Event.START);
        assertThat(s).isEqualTo(SessionState.IN_PROGRESS);
        s = SessionFsm.next(s, Event.JUDGE);
        assertThat(s).isEqualTo(SessionState.JUDGED);
        s = SessionFsm.next(s, Event.CLOSE);
        assertThat(s).isEqualTo(SessionState.CLOSED);
    }

    @Test
    @DisplayName("재설명 루프: IN_PROGRESS→RE_EXPLAIN→RE_VERIFY→(RESUME)→IN_PROGRESS")
    void reexplainLoopThenResume() {
        SessionState s = SessionFsm.next(SessionState.IN_PROGRESS, Event.REQUEST_REEXPLAIN);
        assertThat(s).isEqualTo(SessionState.RE_EXPLAIN);
        s = SessionFsm.next(s, Event.REVERIFY);
        assertThat(s).isEqualTo(SessionState.RE_VERIFY);
        s = SessionFsm.next(s, Event.RESUME);
        assertThat(s).isEqualTo(SessionState.IN_PROGRESS);
    }

    @Test
    @DisplayName("재검증 후 또 부족하면 RE_VERIFY→RE_EXPLAIN으로 다시 루프")
    void reverifyCanLoopAgain() {
        SessionState s = SessionFsm.next(SessionState.RE_VERIFY, Event.REQUEST_REEXPLAIN);
        assertThat(s).isEqualTo(SessionState.RE_EXPLAIN);
    }

    @Test
    @DisplayName("RE_VERIFY에서 바로 판정으로 갈 수 있다(상한 도달 시)")
    void reverifyCanJudge() {
        assertThat(SessionFsm.next(SessionState.RE_VERIFY, Event.JUDGE)).isEqualTo(SessionState.JUDGED);
    }

    @Test
    @DisplayName("진행 상태에서 ABORT하면 ABORTED")
    void abortFromProgress() {
        assertThat(SessionFsm.next(SessionState.CREATED, Event.ABORT)).isEqualTo(SessionState.ABORTED);
        assertThat(SessionFsm.next(SessionState.IN_PROGRESS, Event.ABORT)).isEqualTo(SessionState.ABORTED);
        assertThat(SessionFsm.next(SessionState.RE_VERIFY, Event.ABORT)).isEqualTo(SessionState.ABORTED);
    }

    @Test
    @DisplayName("정의되지 않은 전이는 예외 (CREATED에서 바로 JUDGE 불가)")
    void illegalTransitionThrows() {
        assertThatThrownBy(() -> SessionFsm.next(SessionState.CREATED, Event.JUDGE))
                .isInstanceOf(SessionFsm.IllegalStateTransitionException.class);
    }

    @Test
    @DisplayName("종료 상태(CLOSED·ABORTED)에서는 어떤 전이도 불가")
    void terminalStatesHaveNoTransition() {
        assertThat(SessionFsm.isTerminal(SessionState.CLOSED)).isTrue();
        assertThat(SessionFsm.isTerminal(SessionState.ABORTED)).isTrue();
        assertThat(SessionFsm.isTerminal(SessionState.IN_PROGRESS)).isFalse();
        assertThatThrownBy(() -> SessionFsm.next(SessionState.CLOSED, Event.START))
                .isInstanceOf(SessionFsm.IllegalStateTransitionException.class);
    }

    @Test
    @DisplayName("canFire: 합법 전이는 true, 불법은 false")
    void canFire() {
        assertThat(SessionFsm.canFire(SessionState.CREATED, Event.START)).isTrue();
        assertThat(SessionFsm.canFire(SessionState.CREATED, Event.CLOSE)).isFalse();
    }
}
