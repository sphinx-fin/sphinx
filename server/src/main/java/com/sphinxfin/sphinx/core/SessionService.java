package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Judgment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * F-INT-001 세션 서비스. 소유: 강희진
 *
 * 세션 생명주기 오케스트레이션(생성·조회·판정 기록·게이트 판정)을 담당한다. 생성 불변식은
 * 도메인 팩토리 Session.create가, 게이트 판정은 GateEngine이 소유하고, 서비스는 조율만 한다.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository repository;
    private final GateEngine gateEngine;

    public Session create(CreateSessionCommand cmd) {
        return repository.save(Session.create(cmd));
    }

    /** 세션 조회. 없으면 예외(→ GlobalExceptionHandler에서 404). */
    public Session get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("세션을 찾을 수 없다: " + id));
    }

    /** 항목별 판정(AI 측정값)을 세션에 기록한다. 첫 답변이면 인터뷰 시작으로 전이. */
    public Judgment recordJudgment(String sessionId, Judgment judgment) {
        Session session = get(sessionId);
        // 첫 답변 → CREATED에서 IN_PROGRESS로. 이미 진행 중이면 canFire=false라 건너뜀.
        if (SessionFsm.canFire(session.state(), SessionFsm.Event.START)) {
            session.fire(SessionFsm.Event.START);
        }
        session.recordJudgment(judgment);
        repository.save(session);
        return judgment;
    }

    /**
     * 게이트 판정 — 세션에 쌓인 판정 + 모순 여부 + 재검증 횟수를 GateEngine에 넘긴다.
     * LLM 원문은 관여하지 않는다(P1: AI는 측정, 룰은 결정). 판정 가능한 상태면 JUDGED로 전이.
     * (재검증 루프 RE_EXPLAIN⇄RE_VERIFY 전이는 F-INT-004에서 추가한다.)
     */
    public GateResult judge(String sessionId) {
        Session session = get(sessionId);
        GateResult result =
                gateEngine.judge(session.judgments(), session.suitabilityMismatch(), session.maxReverifyCount());
        if (SessionFsm.canFire(session.state(), SessionFsm.Event.JUDGE)) {
            session.fire(SessionFsm.Event.JUDGE);
            repository.save(session);
        }
        return result;
    }
}
