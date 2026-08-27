package com.sphinxfin.sphinx.core.session;

import com.sphinxfin.sphinx.domain.SessionState;

import java.util.EnumMap;
import java.util.Map;

/**
 * F-INT-001 세션 상태머신. 소유: 강희진
 *
 * 결정론적 순수 전이표다(P2). 합법 전이만 허용하고, 정의되지 않은 전이는 예외를 던진다 —
 * 상태 전이를 컨트롤러 곳곳의 if 문으로 흩지 않고 이 한 곳에서 강제한다.
 *
 * 흐름: CREATED → IN_PROGRESS → (RE_EXPLAIN ⇄ RE_VERIFY)* → JUDGED → CLOSED.
 * 어느 진행 상태에서든 ABORT로 중단할 수 있다. 항목당 재검증 횟수 상한(sphinx.scoring.
 * max-reverify)은 상태가 아니라 항목 단위 카운트라 Session이 관리한다 — 상한에 도달하면
 * 재설명 루프 대신 JUDGE로 가고, 그러면 게이트가 R-03(reverifyFailed>=2)으로 RED를 낸다.
 */
public final class SessionFsm {

    /** 상태 전이를 일으키는 이벤트. */
    public enum Event {
        START,              // 인터뷰 시작(첫 질문)
        REQUEST_REEXPLAIN,  // 이해 부족 항목 → 재설명 필요
        REVERIFY,           // 재설명 완료 → 재질문
        RESUME,             // 재검증 통과 → 정상 흐름 복귀
        JUDGE,              // 게이트 판정
        CLOSE,              // 세션 종료(리포트 교부 등)
        ABORT               // 고객 이탈 등 중단
    }

    private static final Map<SessionState, Map<Event, SessionState>> TRANSITIONS =
            new EnumMap<>(SessionState.class);

    static {
        put(SessionState.CREATED,
                Event.START, SessionState.IN_PROGRESS,
                Event.ABORT, SessionState.ABORTED);
        put(SessionState.IN_PROGRESS,
                Event.REQUEST_REEXPLAIN, SessionState.RE_EXPLAIN,
                Event.JUDGE, SessionState.JUDGED,
                Event.ABORT, SessionState.ABORTED);
        put(SessionState.RE_EXPLAIN,
                Event.REVERIFY, SessionState.RE_VERIFY,
                Event.ABORT, SessionState.ABORTED);
        put(SessionState.RE_VERIFY,
                Event.RESUME, SessionState.IN_PROGRESS,
                Event.REQUEST_REEXPLAIN, SessionState.RE_EXPLAIN,
                Event.JUDGE, SessionState.JUDGED,
                Event.ABORT, SessionState.ABORTED);
        put(SessionState.JUDGED,
                Event.CLOSE, SessionState.CLOSED);
        // CLOSED, ABORTED는 종료 상태 — 나가는 전이 없음.
    }

    private SessionFsm() {}

    /** from에서 event로 갈 수 있는 다음 상태. 정의되지 않은 전이면 예외. */
    public static SessionState next(SessionState from, Event event) {
        SessionState to = TRANSITIONS.getOrDefault(from, Map.of()).get(event);
        if (to == null) {
            throw new IllegalStateTransitionException(from, event);
        }
        return to;
    }

    /** from에서 event가 합법인지. */
    public static boolean canFire(SessionState from, Event event) {
        return TRANSITIONS.getOrDefault(from, Map.of()).containsKey(event);
    }

    /** 종료 상태(더 이상 전이 없음) 여부. */
    public static boolean isTerminal(SessionState state) {
        return TRANSITIONS.getOrDefault(state, Map.of()).isEmpty();
    }

    private static void put(SessionState from, Object... eventStatePairs) {
        Map<Event, SessionState> row = new EnumMap<>(Event.class);
        for (int i = 0; i < eventStatePairs.length; i += 2) {
            row.put((Event) eventStatePairs[i], (SessionState) eventStatePairs[i + 1]);
        }
        TRANSITIONS.put(from, row);
    }

    /** 정의되지 않은 상태 전이 시도. */
    public static final class IllegalStateTransitionException extends IllegalStateException {
        public IllegalStateTransitionException(SessionState from, Event event) {
            super("허용되지 않은 상태 전이: " + from + " --" + event + "-->");
        }
    }
}
