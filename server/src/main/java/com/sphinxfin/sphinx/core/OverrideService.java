package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.domain.OverrideStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    /**
     * 판매자의 적색 진행 요청. 적색 세션만 허용하고 사유를 기록, 승인 대기로 둔다.
     *
     * 이미 승인된 건은 거부한다. 오버라이드가 승인돼도 세션은 여전히 적색이라 requireRed 를
     * 다시 통과하는데, 그대로 두면 재요청이 상태를 PENDING_APPROVAL 로 되돌리면서
     * overrideApprover·overrideDecidedAt 은 남아 "승인 대기인데 승인자와 승인 시각이 있는"
     * 모순 상태가 저장된다. 에러도 로그도 없이 200 으로 조용히 뒤로 간다.
     *
     * evidence 관점에서 특히 나쁘다 — append 된 승인 1건은 그대로 남으므로 불변 기록에는
     * 승인이 있고 세션에는 없다. 감사 시점에 둘이 어긋나는데 그 이유가 조작인지 중복
     * 클릭인지 기록만으로 구별할 수 없다. 오버라이드는 그 대조가 존재 이유인 기능이다.
     *
     * PENDING_APPROVAL 에서의 재요청(사유 수정)은 허용한다 — 승인 전이라 모순이 생기지 않는다.
     * 다만 이전 사유가 세션에서 덮어써지고 사라지므로, 요청도 상태 전이 단위로 append 하는
     * 것을 8/27 evidence 착수 때 함께 정한다.
     */
    @Transactional
    public Session request(String sessionId, String reason) {
        Session session = get(sessionId);
        requireRed(session);
        if (session.overrideStatus() == OverrideStatus.APPROVED) {
            throw new OverrideNotEligibleException("이미 승인된 오버라이드다: " + sessionId);
        }
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
        // ADR-008: UTC + 밀리초 3자리 고정. Instant.now() 의 정밀도는 플랫폼마다 다르고
        // (밀리초~나노초) 이 값은 세션에 저장되면서 동시에 해시 체인에도 들어간다.
        // 직렬화기가 자르므로 해시 자체는 안정적이지만, 저장값이 마이크로초로 남으면
        // audit:verify 가 세션 데이터로 기록을 재구성할 때 한 번 더 잘라야 하고 그걸
        // 잊으면 검증이 실패한다. 발생 지점에서 자르면 그 경로가 아예 없어진다.
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        session.approveOverride(approver, at);
        repository.save(session);
        // 불변 기록(ADR-003) — 사유·승인자가 감사 증거로 남는다.
        evidenceRecorder.appendOverride(sessionId, session.overrideReason(), approver, at);
        // COMPL 자동 통보 발행(기획 7-2). 전달 채널은 인프라 리스너가 구독한다.
        //
        // ❗ 구독은 반드시 @TransactionalEventListener(phase = AFTER_COMMIT) 로 한다.
        // 기본 @EventListener 는 이 트랜잭션 안에서 동기로 돌기 때문에, 리스너가 메일·큐를
        // 부르는 순간 ADR-004 가 금지한 "적재 경로의 외부 I/O" 가 된다 — 통보가 느리면
        // 승인이 그만큼 붙잡히고, 통보가 실패하면 승인이 통째로 롤백된다.
        //
        // 미해결: AFTER_COMMIT 이면 반대로 통보 실패가 조용해진다(승인은 이미 커밋됨).
        // 기획 7-2 는 "자동 통보"를 요구하므로 누락이 안 보이면 안 된다. 정석은 아웃박스 행을
        // 같은 트랜잭션에 넣고 전달만 커밋 후에 하는 것인데, MVP 범위에 넣을지는 미정이다.
        // 리스너를 붙이는 시점에 결정한다.
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
