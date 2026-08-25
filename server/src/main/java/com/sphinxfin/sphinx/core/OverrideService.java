package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.domain.OverrideStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * F-GTE-002 적색 오버라이드 서비스. 소유: 강희진
 *
 * 적색 판정 세션을 관리자 승인으로 진행하는 예외 경로. 기획 7-2가 요구하는 견제 장치를 코드로
 * 강제한다: (1) 적색이 아니면 요청 거부, (2) 사유 필수(30자, 컨트롤러 @Size), (3) 승인 시
 * 불변 기록(evidence append, ADR-003), (4) COMPL 자동 통보 발행. 승인자 역할(MGR) 제약은
 * ADR-002이며 인가는 F-CMN-002(@PreAuthorize)가 붙인다 — 여기서는 승인자를 기록만 한다.
 *
 * 오버라이드는 게이트 신호를 바꾸지 않는다. 세션은 여전히 적색으로 남고, "적색인데 승인으로
 * 진행했다"는 사실이 별도로 기록된다 — 그래야 사후 감사에서 오버라이드 건을 가려낼 수 있다.
 */
@Slf4j
@Service
public class OverrideService {

    private final SessionRepository repository;
    private final EvidenceRecorder evidenceRecorder;
    private final ApplicationEventPublisher events;

    /**
     * evidenceRecorder는 Optional 주입 — evidence/ 구현(F-GTE-004) 등록 전에도 오버라이드 흐름이
     * 돌아야 하므로 없으면 NO_OP으로 대체한다(SessionService와 같은 처리).
     */
    public OverrideService(SessionRepository repository,
                           Optional<EvidenceRecorder> evidenceRecorder,
                           ApplicationEventPublisher events) {
        this.repository = repository;
        this.evidenceRecorder = evidenceRecorder.orElse(EvidenceRecorder.NO_OP);
        this.events = events;
    }

    /** 판매자의 적색 진행 요청. 적색 세션만 허용하고 사유를 기록, 승인 대기로 둔다. */
    @Transactional
    public Session request(String sessionId, String reason) {
        Session session = get(sessionId);
        requireRed(session);
        session.requestOverride(reason);
        return repository.save(session);
    }

    /**
     * 관리자(MGR) 승인. 요청이 선행돼야 하고, 승인 시 불변 기록 append + COMPL 통보 발행.
     * append는 recordJudgment와 같은 트랜잭션 논리다 — 기록 없이 진행 사실만 남으면 안 된다.
     */
    @Transactional
    public Session approve(String sessionId, String approver) {
        Session session = get(sessionId);
        requireRed(session);
        if (session.overrideStatus() != OverrideStatus.PENDING_APPROVAL) {
            throw new OverrideNotEligibleException(
                    "승인 대기 상태가 아니다(요청이 선행돼야 한다): " + session.overrideStatus());
        }
        Instant at = Instant.now();
        session.approveOverride(approver, at);
        repository.save(session);
        // 불변 기록(ADR-003) — 사유·승인자가 감사 증거로 남는다.
        evidenceRecorder.appendOverride(sessionId, session.overrideReason(), approver, at);
        // COMPL 자동 통보 발행(기획 7-2). 전달 채널은 인프라 리스너가 구독한다.
        events.publishEvent(new OverrideApprovedEvent(sessionId, session.overrideReason(), approver, at));
        log.warn("[COMPL 통보] 적색 오버라이드 승인 — session={} approver={}", sessionId, approver);
        return session;
    }

    private Session get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("세션을 찾을 수 없다: " + id));
    }

    /** 적색 판정이 아니면 오버라이드 자체가 성립하지 않는다 — 녹색·황색·미판정 모두 거부. */
    private void requireRed(Session session) {
        if (!session.isRedGate()) {
            throw new OverrideNotEligibleException(
                    "적색 판정 세션만 오버라이드할 수 있다(현재 신호: " + session.gateSignal() + ")");
        }
    }
}
