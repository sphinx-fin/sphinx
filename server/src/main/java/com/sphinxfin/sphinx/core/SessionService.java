package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.SessionState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.NoSuchElementException;

/**
 * F-INT-001/004 세션 서비스. 소유: 강희진
 *
 * 세션 생명주기 오케스트레이션: 생성·조회, 답변 기록(+상태전이), 재설명·재검증 루프,
 * 게이트 판정, 중단. 생성 불변식은 Session.create, 판정은 GateEngine, 취약 가중은
 * CoachingScoreService가 소유하고 서비스는 조율만 한다.
 */
@Service
public class SessionService {

    private final SessionRepository repository;
    private final GateEngine gateEngine;
    private final CoachingScoreService coachingScoreService;
    private final int maxReverify;   // 항목당 재검증 상한(application.yml)

    public SessionService(SessionRepository repository,
                          GateEngine gateEngine,
                          CoachingScoreService coachingScoreService,
                          @Value("${sphinx.scoring.max-reverify:2}") int maxReverify) {
        this.repository = repository;
        this.gateEngine = gateEngine;
        this.coachingScoreService = coachingScoreService;
        this.maxReverify = maxReverify;
    }

    public Session create(CreateSessionCommand cmd) {
        Session session = Session.create(cmd);
        // 생성 시점 취약 가중 산출(모순은 아직 없음).
        var coaching = coachingScoreService.score(session, false);
        session.applyCoaching(coaching.score(), coaching.vulnerable());
        return repository.save(session);
    }

    /** 세션 조회. 없으면 예외(→ 404). */
    public Session get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("세션을 찾을 수 없다: " + id));
    }

    /**
     * 항목별 판정(AI 측정값)을 세션에 기록한다. 상태에 따라:
     * - CREATED: 첫 답변이므로 인터뷰 시작(→IN_PROGRESS)
     * - RE_EXPLAIN: 재설명 후 재답변이므로 재검증(재검증 횟수 +1, →RE_VERIFY).
     *   이번에 이해(U1)했으면 정상 흐름으로 복귀(→IN_PROGRESS).
     */
    public Judgment recordJudgment(String sessionId, Judgment judgment) {
        Session session = get(sessionId);
        if (SessionFsm.canFire(session.state(), SessionFsm.Event.START)) {
            session.fire(SessionFsm.Event.START);
        }
        boolean reverifying = session.state() == SessionState.RE_EXPLAIN;
        if (reverifying) {
            session.recordReverify(judgment.itemId());
            session.fire(SessionFsm.Event.REVERIFY);
        }
        session.recordJudgment(judgment);
        if (reverifying && judgment.grade() == Grade.U1) {
            session.fire(SessionFsm.Event.RESUME);
        }
        repository.save(session);
        return judgment;
    }

    /**
     * F-INT-004 재설명 — 이해 부족 항목을 다시 설명한다. 상태를 RE_EXPLAIN으로 두고,
     * 이후 같은 항목 재답변(recordJudgment)이 재검증이 된다. 재검증 상한에 도달한 항목은
     * 재설명하지 않고 판정으로 보낸다(게이트 R-03이 RED). 문면은 취약 여부로 맞춘다.
     */
    public ReExplanation reExplain(String sessionId, String itemId) {
        Session session = get(sessionId);
        Judgment judgment = session.judgmentFor(itemId);
        if (judgment == null || judgment.grade() == Grade.U1) {
            throw new IllegalArgumentException("재설명 대상이 아니다(판정 없음 또는 이미 이해): " + itemId);
        }
        if (session.reverifyExhausted(itemId, maxReverify)) {
            throw new IllegalArgumentException(
                    "재검증 상한(" + maxReverify + "회) 도달 — 재설명 불가, 판정으로 진행: " + itemId);
        }
        session.fire(SessionFsm.Event.REQUEST_REEXPLAIN);
        repository.save(session);
        return new ReExplanation(itemId, reExplainContent(session.vulnerable()), session.vulnerable());
    }

    /** TODO: ai-service /internal/reexplain 연결(F-INT-004, 윤지석). 지금은 취약 여부로 가른 목. */
    private String reExplainContent(boolean vulnerable) {
        return vulnerable
                ? "쉽게 다시 설명드릴게요. 이 상품은 은행 예금과 달라서 맡긴 돈(원금)이 줄어들 수 있어요. "
                  + "'예금처럼 안전하다'가 아니라는 점만 꼭 기억해 주세요."
                : "다시 설명드리면, 기초자산이 정해진 수준 아래로 내려가면 원금 손실이 발생합니다. "
                  + "예금자보호 대상도 아닙니다.";
    }

    /**
     * 게이트 판정 — 세션에 쌓인 판정 + 모순 + '재검증 실패' 횟수를 GateEngine에 넘긴다(P1).
     * 판정 가능한 상태면 JUDGED로 전이.
     */
    public GateResult judge(String sessionId) {
        Session session = get(sessionId);
        GateResult result = gateEngine.judge(
                session.judgments(), session.suitabilityMismatch(), session.failedReverifyCount());
        session.recordGate(result, Instant.now());   // 감사 기준점 기록(F-GTE-004)
        if (SessionFsm.canFire(session.state(), SessionFsm.Event.JUDGE)) {
            session.fire(SessionFsm.Event.JUDGE);
        }
        repository.save(session);
        return result;
    }

    /** 세션 중단(고객 이탈 등) → ABORTED. */
    public Session abort(String sessionId) {
        Session session = get(sessionId);
        if (SessionFsm.canFire(session.state(), SessionFsm.Event.ABORT)) {
            session.fire(SessionFsm.Event.ABORT);
            repository.save(session);
        }
        return session;
    }

    /** 재설명 결과(문면 + 취약 모드 여부). */
    public record ReExplanation(String itemId, String content, boolean vulnerable) {}
}
