package com.sphinxfin.sphinx.core.session;

import com.sphinxfin.sphinx.domain.RuleRef;
import com.sphinxfin.sphinx.domain.Channel;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.OverrideStatus;
import com.sphinxfin.sphinx.domain.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sphinxfin.sphinx.core.EvidenceRecorder;
import com.sphinxfin.sphinx.core.persistence.JpaAuditingConfig;

/**
 * F-GTE-002 적색 오버라이드 서비스. 실제 H2에 저장·재조회하며 적색 가드·승인·불변기록·통보를 검증한다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@DisplayName("F-GTE-002 OverrideService")
class OverrideServiceTest {

    private static final String REASON =
            "고객이 이미 유사 상품 3년 경험이 있고 손실 위험을 서면으로 재확인하여 진행합니다.";  // 30자 이상

    @Autowired
    private SessionRepository repository;

    private OverrideService service;
    private RecordingEvidence evidence;
    private final List<Object> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        evidence = new RecordingEvidence();
        ApplicationEventPublisher publisher = events::add;
        service = new OverrideService(repository, Optional.of(evidence), publisher);
    }

    @Test
    @DisplayName("적색 세션 요청 → PENDING_APPROVAL, 사유 기록")
    void requestOnRed() {
        String id = save(Signal.RED).id();

        Session s = service.request(id, REASON);

        assertThat(s.overrideStatus()).isEqualTo(OverrideStatus.PENDING_APPROVAL);
        assertThat(s.overrideReason()).isEqualTo(REASON);
        assertThat(repository.findById(id).orElseThrow().overrideStatus())
                .isEqualTo(OverrideStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("녹색 세션 요청 → OVERRIDE_NOT_ELIGIBLE(적색만 가능)")
    void requestOnGreenRejected() {
        String id = save(Signal.GREEN).id();

        assertThatThrownBy(() -> service.request(id, REASON))
                .isInstanceOf(OverrideNotEligibleException.class);
    }

    @Test
    @DisplayName("미판정 세션 요청 → OVERRIDE_NOT_ELIGIBLE(gateSignal null)")
    void requestOnUnjudgedRejected() {
        Session unjudged = repository.save(Session.create(cmd()));   // recordGate 안 함 → gateSignal null

        assertThatThrownBy(() -> service.request(unjudged.id(), REASON))
                .isInstanceOf(OverrideNotEligibleException.class);
    }

    @Test
    @DisplayName("요청 후 승인 → APPROVED, 승인자·시각 기록 + 불변기록 append + COMPL 통보 발행")
    void approveAfterRequest() {
        String id = save(Signal.RED).id();
        service.request(id, REASON);

        Session s = service.approve(id, "mgr-01");

        assertThat(s.overrideStatus()).isEqualTo(OverrideStatus.APPROVED);
        assertThat(s.overrideApprover()).isEqualTo("mgr-01");
        assertThat(s.overrideDecidedAt()).isNotNull();
        // 불변 기록(ADR-003) — 사유·승인자가 evidence로 흘러갔다.
        assertThat(evidence.overrides).containsExactly(id + "|" + REASON + "|mgr-01");
        // COMPL 자동 통보(기획 7-2) — 이벤트가 발행됐다.
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(OverrideApprovedEvent.class);
        OverrideApprovedEvent e = (OverrideApprovedEvent) events.get(0);
        assertThat(e.sessionId()).isEqualTo(id);
        assertThat(e.approver()).isEqualTo("mgr-01");
        assertThat(e.reason()).isEqualTo(REASON);
    }

    @Test
    @DisplayName("요청 없이 승인 → OVERRIDE_NOT_ELIGIBLE, 통보 발행 안 됨")
    void approveWithoutRequestRejected() {
        String id = save(Signal.RED).id();   // 요청 생략

        assertThatThrownBy(() -> service.approve(id, "mgr-01"))
                .isInstanceOf(OverrideNotEligibleException.class);
        assertThat(events).isEmpty();
        assertThat(evidence.overrides).isEmpty();
    }

    @Test
    @DisplayName("녹색 세션 승인 → OVERRIDE_NOT_ELIGIBLE(적색 가드 우선)")
    void approveOnGreenRejected() {
        String id = save(Signal.GREEN).id();

        assertThatThrownBy(() -> service.approve(id, "mgr-01"))
                .isInstanceOf(OverrideNotEligibleException.class);
    }

    @Test
    @DisplayName("승인 후 재요청 → OVERRIDE_NOT_ELIGIBLE, 승인 상태가 되돌려지지 않는다")
    void requestAfterApproveRejected() {
        String id = save(Signal.RED).id();
        service.request(id, REASON);
        service.approve(id, "mgr-01");

        // 오버라이드가 승인돼도 세션은 여전히 적색이라 requireRed 만으로는 막히지 않는다.
        // 중복 클릭이든 사유 수정이든, 재요청이 통과하면 상태가 PENDING_APPROVAL 로 돌아가고
        // 승인자·승인 시각은 그대로 남아 "승인 대기인데 승인자가 있는" 모순이 저장된다.
        assertThatThrownBy(() -> service.request(id, "사유를 고쳐서 다시 올립니다. 고객이 충분히 이해했다고 판단합니다."))
                .isInstanceOf(OverrideNotEligibleException.class);

        Session after = repository.findById(id).orElseThrow();
        assertThat(after.overrideStatus()).isEqualTo(OverrideStatus.APPROVED);
        assertThat(after.overrideApprover()).isEqualTo("mgr-01");
        assertThat(after.overrideReason()).isEqualTo(REASON);
        // 불변 기록은 승인 1건 그대로 — 세션과 evidence 가 어긋나지 않는다.
        assertThat(evidence.overrides).hasSize(1);
    }

    @Test
    @DisplayName("승인 전 재요청은 허용 — 사유 수정이고 모순이 생기지 않는다")
    void requestAgainBeforeApproveAllowed() {
        String id = save(Signal.RED).id();
        service.request(id, REASON);
        String fixed = "사유를 고쳐서 다시 올립니다. 고객이 손실 조건을 이해했음을 확인했습니다.";

        Session s = service.request(id, fixed);

        assertThat(s.overrideStatus()).isEqualTo(OverrideStatus.PENDING_APPROVAL);
        assertThat(s.overrideReason()).isEqualTo(fixed);
        assertThat(s.overrideApprover()).isNull();
    }

    /** 지정 신호로 판정 기록된 세션을 저장한다. */
    private Session save(Signal signal) {
        Session s = Session.create(cmd());
        s.recordGate(new GateResult(signal, List.of(new RuleRef("R-01", "테스트 문면")), 0, 3), Instant.now());
        return repository.save(s);
    }

    private CreateSessionCommand cmd() {
        return new CreateSessionCommand("ELS-001", Channel.FACE_TO_FACE, "60대",
                "없음", "5천만원대", "CT-1", "SUIT-v1", Map.of());
    }

    /** evidence append 지점 호출을 잡는 테스트 더블(구현은 정세현 evidence/). */
    private static final class RecordingEvidence implements EvidenceRecorder {
        private final List<String> overrides = new ArrayList<>();

        @Override
        public void appendJudgment(String sessionId, com.sphinxfin.sphinx.domain.Judgment judgment,
                                   int reverifyCount,
                                   String askedQuestion, QuestionSource questionSource,
                                   Instant at) { }

        @Override
        public void appendMismatch(String sessionId,
                com.sphinxfin.sphinx.domain.SuitabilityMismatch mismatch,
                String surveySchemaVersion, java.util.Map<String, Object> surveyResult,
                Instant at) {
        }

        @Override
        public void appendGate(String sessionId, GateResult result, Instant at) { }

        @Override
        public void appendOverride(String sessionId, String reason, String approver, Instant at) {
            overrides.add(sessionId + "|" + reason + "|" + approver);
        }
    }
}
