package com.sphinxfin.sphinx.evidence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 접근 감사 로그. 소유: 정세현
 *
 * <p>여기서 지키는 것은 <b>거부된 접근이 남는가</b>다. 성공한 접근만 남으면 감사 로그의 값이
 * 절반이다 — 기획 7-4 2단계가 보려는 것은 <b>차단당한 시도가 반복되는 것</b>이고, 그건 성공
 * 기록에 안 나타난다.
 */
@DataJpaTest
@Import({JpaImmutableStore.class, AuditLog.class, AuditAppender.class})
// 테스트 트랜잭션을 열지 않는다. AuditLog.record 가 REQUIRES_NEW 라, 밖에서 트랜잭션을 잡고
// 있으면 같은 anchor 행을 두 트랜잭션이 건드려 락 경합으로 멈춘다(실측). 그 성질 자체가
// AuditLog 주석에 적은 "감사 적재는 업무 트랜잭션과 분리된다"의 실물이다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("AuditLog — 접근 감사")
class AuditLogTest {

    private static final Instant T0 = Instant.parse("2026-08-27T01:00:00.000Z");

    @Autowired
    private AuditLog auditLog;
    @Autowired
    private ImmutableStore store;
    @Autowired
    private EvidenceEntryRepository entries;
    @Autowired
    private EvidenceStreamAnchorRepository anchors;

    /**
     * 감사 적재는 커밋되고 <b>테스트 롤백으로 안 지워진다</b> — 업무가 롤백돼도 접근 사실은
     * 남아야 하기 때문이다(AuditLog 주석 ①). 대신 테스트끼리 간섭하므로 매번 지우고 시작한다.
     * <b>이 정리가 필요하다는 사실 자체가 그 성질의 증거다.</b>
     */
    @BeforeEach
    void clearAuditStream() {
        entries.deleteAll();
        anchors.deleteAll();
    }

    private static AuditLog.Entry entry(String actor, String role, String action,
                                        String resultCode, Instant at) {
        return new AuditLog.Entry(actor, role, action, "/sessions/S-1/report", resultCode, at);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadAt(int index) {
        return (Map<String, Object>) auditLog.replay().get(index).payload();
    }

    @Nested
    @DisplayName("무엇이 남는가")
    class WhatIsRecorded {

        @Test
        @DisplayName("거부된 접근도 남는다 — 차단이 반복되는 것 자체가 신호다")
        void recordsDeniedAccess() {
            auditLog.record(entry("seller-01", "ROLE_SELLER", "aggregate:heatmap:read", "403", T0));
            auditLog.record(entry("compl-01", "ROLE_COMPL", "aggregate:heatmap:read", "200", T0.plusSeconds(1)));

            assertThat(auditLog.replay()).hasSize(2);
            assertThat(payloadAt(0).get("resultCode"))
                    .as("성공만 남으면 ADR-001 범위 분리가 동작했다는 증거가 사라진다")
                    .isEqualTo("403");
        }

        @Test
        @DisplayName("occurredAt 은 ADR-008 형식으로 적힌다 — 밀리초 3자리 고정")
        void formatsTimestampPerAdr008() {
            auditLog.record(entry("seller-01", "ROLE_SELLER", "report:read", "200", T0));

            assertThat(payloadAt(0).get("occurredAt"))
                    .as("Instant.toString() 이면 밀리초 0 일 때 '.000' 이 사라진다 — 타입으로 넘겨 "
                            + "CanonicalJson 이 한 곳에서 포맷하게 한다")
                    .isEqualTo("2026-08-27T01:00:00.000Z");
        }

        @Test
        @DisplayName("actorId 가 null 이어도 기록한다 — 미인증 접근이 사라지면 안 된다")
        void recordsUnauthenticatedAccess() {
            auditLog.record(entry(null, null, "report:read", "200", T0));

            Map<String, Object> payload = payloadAt(0);
            assertThat(payload.keySet())
                    .as("생략하면 '미인증' 과 '필드 없음' 이 같은 바이트가 된다")
                    .contains("actorId", "role");
            assertThat(payload.get("actorId")).isNull();
        }
    }

    @Nested
    @DisplayName("사슬 — 리포트와 같은 규칙, 다른 스트림")
    class Chain {

        @Test
        @DisplayName("감사 스트림도 검증된다 — 꼬리 절단까지")
        void chainIsVerifiable() {
            auditLog.record(entry("seller-01", "ROLE_SELLER", "report:read", "200", T0));
            auditLog.record(entry("mgr-01", "ROLE_MGR", "override:approve", "200", T0.plusSeconds(5)));

            HashChain.Verification result = auditLog.verify();
            assertThat(result.ok()).isTrue();
            assertThat(result.checked()).isEqualTo(2);
        }

        @Test
        @DisplayName("세션 스트림과 섞이지 않는다 — 감사는 세션을 넘어 훑은 것도 봐야 한다")
        void isSeparateFromSessionStreams() {
            auditLog.record(entry("mgr-01", "ROLE_MGR", "report:read", "200", T0));

            assertThat(auditLog.replay()).hasSize(1);
            assertThat(auditLog.verify().ok()).isTrue();
        }

        @Test
        @DisplayName("같은 접근을 두 번 해도 두 건이다 — 흡수하지 않는다 (ADR-004)")
        void doesNotDeduplicate() {
            auditLog.record(entry("seller-01", "ROLE_SELLER", "report:read", "200", T0));
            auditLog.record(entry("seller-01", "ROLE_SELLER", "report:read", "200", T0));

            assertThat(auditLog.replay())
                    .as("같은 사람이 같은 것을 두 번 열어본 사실이 감사 정보다")
                    .hasSize(2);
        }
    }

    /**
     * 집계 조회(이슈 #326 파트2). 여기서 지키는 것 둘이다 — <b>개인이 안 실려 나가는 것</b>과
     * <b>못 센 것을 0 으로 뭉개지 않는 것</b>.
     */
    @Nested
    @DisplayName("집계 — 심사에서 물어볼 숫자")
    class Summary {

        @Test
        @DisplayName("차단을 역할별로 센다 — 기획서 7-4 의 실물 숫자가 이것이다")
        void countsDenialsByRole() {
            auditLog.record(entry("seller-01", "ROLE_SELLER", "aggregate:heatmap:read", "403", T0));
            auditLog.record(entry("seller-02", "ROLE_SELLER", "aggregate:heatmap:read", "403", T0.plusSeconds(1)));
            auditLog.record(entry(null, null, "report:read", "401", T0.plusSeconds(2)));
            auditLog.record(entry("compl-01", "ROLE_COMPL", "aggregate:heatmap:read", "200", T0.plusSeconds(3)));

            AuditLog.AccessSummary summary = auditLog.summary(null, null);

            assertThat(summary.total()).isEqualTo(4);
            assertThat(summary.deniedByRole())
                    .as("401 과 403 을 같이 센다 — 둘 다 막힌 시도다")
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("ROLE_SELLER", 2L, AuditLog.UNKNOWN, 1L));
            assertThat(summary.byResultCode()).containsEntry("200", 1L).containsEntry("403", 2L);
            assertThat(summary.byAction()).containsEntry("aggregate:heatmap:read", 3L);
        }

        @Test
        @DisplayName("★ 개인 식별자가 집계에 안 실린다 — 열지 않기로 한 것이 그것이다")
        void carriesNoIdentifiers() {
            auditLog.record(entry("seller-02", "ROLE_SELLER", "report:read", "403", T0));

            AuditLog.AccessSummary summary = auditLog.summary(null, null);

            assertThat(summary.toString())
                    .as("alpha 가 개방 모드라 계약에 나가는 값에 actorId·resource 가 있으면 "
                            + "인증 없이 '누가 무엇을 했는가' 가 읽힌다")
                    .doesNotContain("seller-02")
                    .doesNotContain("/sessions/S-1/report");
        }

        @Test
        @DisplayName("기간은 반열림이다 — 이어지는 두 기간을 합쳐도 겹치는 건이 없다")
        void windowIsHalfOpen() {
            auditLog.record(entry("mgr-01", "ROLE_MGR", "report:read", "200", T0));
            auditLog.record(entry("mgr-01", "ROLE_MGR", "report:read", "200", T0.plusSeconds(10)));
            auditLog.record(entry("mgr-01", "ROLE_MGR", "report:read", "200", T0.plusSeconds(20)));

            long first = auditLog.summary(T0, T0.plusSeconds(10)).total();
            long second = auditLog.summary(T0.plusSeconds(10), T0.plusSeconds(20)).total();
            long whole = auditLog.summary(T0, T0.plusSeconds(20)).total();

            assertThat(first).isEqualTo(1);
            assertThat(second).isEqualTo(1);
            assertThat(first + second)
                    .as("경계가 닫혀 있으면 경계에 걸린 건이 두 기간에 다 들어간다")
                    .isEqualTo(whole);
        }

        @Test
        @DisplayName("❗못 읽은 건을 따로 센다 — 0 으로 뭉개면 '접근이 없었다' 로 읽힌다")
        void countsUnreadableSeparately() {
            auditLog.record(entry("mgr-01", "ROLE_MGR", "report:read", "200", T0));
            // 감사 스트림에 감사 항목이 아닌 payload 가 섞인 경우. 사슬은 무엇이든 담을 수
            // 있으므로 집계가 이걸 만난다고 죽으면 안 되고, 조용히 버려도 안 된다.
            store.append("audit", Map.of("무엇인가", "occurredAt 이 없다"));

            AuditLog.AccessSummary summary = auditLog.summary(null, null);

            assertThat(summary.total()).as("셀 수 있었던 것만 total 이다").isEqualTo(1);
            assertThat(summary.unreadable())
                    .as("여기가 0 이면 '10건 중 1건' 과 '1건만 읽고 1건' 이 같아 보인다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("기록이 없으면 0 이다 — 빈 집계도 답이어야 한다")
        void emptyStreamIsAnAnswer() {
            AuditLog.AccessSummary summary = auditLog.summary(null, null);

            assertThat(summary.total()).isZero();
            assertThat(summary.unreadable()).isZero();
            assertThat(summary.deniedByRole()).isEmpty();
        }
    }
}
