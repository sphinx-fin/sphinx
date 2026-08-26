package com.sphinxfin.sphinx.evidence;

import com.sphinxfin.sphinx.core.CreateSessionCommand;
import com.sphinxfin.sphinx.core.Session;
import com.sphinxfin.sphinx.core.SessionRepository;
import com.sphinxfin.sphinx.domain.Channel;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원문 응답 보존기간. 소유: 정세현
 *
 * <p>지키는 것은 <b>무엇을 지우고 무엇을 남기는가</b>다. 기획서 437행이 *"원문 발화는 보관기간을
 * 한정하고, 이후에는 판정 결과만 남긴다"* 인데, 판정의 <b>근거 인용</b>도 고객의 말이라 경계가
 * 모호해 보인다. 인용은 판정의 일부이고(P4) append-only 사슬 안이라 지울 수 없으므로,
 * <b>발화 전문만</b> 지운다 — 그 구분이 지켜지는지를 본다.
 *
 * <p>{@code enforce=true} 로 켜고 돌린다. 기본이 꺼져 있는 것 자체도 케이스로 둔다.
 */
@SpringBootTest
@TestPropertySource(properties = {"sphinx.retention.enforce=true", "sphinx.retention.raw-answer-months=6"})
@DisplayName("RetentionService — 발화 전문만 지운다")
class RetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-27T01:00:00.000Z");

    @Autowired
    private RetentionService retention;
    @Autowired
    private SessionRepository sessions;
    @Autowired
    private EntityManager em;
    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void clear() {
        tx.executeWithoutResult(status -> sessions.deleteAll());
    }

    /** createdAt 은 감사 필드라 직접 못 넣는다 — 저장 후 네이티브로 되감는다. */
    private String sessionAged(int daysAgo, String utterance) {
        return tx.execute(status -> {
            Session session = Session.create(new CreateSessionCommand(
                    "ELS-001", Channel.FACE_TO_FACE, "60대", "없음", "5천만원대",
                    "CT-1", "SUIT-v1", Map.of()));
            session.recordUtterance("ELS-A", utterance);
            sessions.saveAndFlush(session);
            em.createNativeQuery("update sessions set created_at = ? where id = ?")
                    .setParameter(1, NOW.minus(daysAgo, ChronoUnit.DAYS))
                    .setParameter(2, session.id())
                    .executeUpdate();
            em.clear();
            return session.id();
        });
    }

    private int utteranceRows(String sessionId) {
        return tx.execute(status -> ((Number) em.createNativeQuery(
                        "select count(*) from session_utterance where session_id = ?")
                .setParameter(1, sessionId).getSingleResult()).intValue());
    }

    @Nested
    @DisplayName("경계")
    class Boundary {

        @Test
        @DisplayName("보존기간이 지난 세션의 발화 전문을 지운다")
        void purgesExpired() {
            String old = sessionAged(200, "원금은 지켜지죠");

            assertThat(retention.purgeExpiredUtterances(NOW)).isEqualTo(1);
            assertThat(utteranceRows(old)).isZero();
        }

        @Test
        @DisplayName("기간 안의 세션은 건드리지 않는다")
        void keepsFresh() {
            String fresh = sessionAged(10, "원금은 지켜지죠");

            assertThat(retention.purgeExpiredUtterances(NOW)).isZero();
            assertThat(utteranceRows(fresh)).isEqualTo(1);
        }

        @Test
        @DisplayName("이미 지운 세션을 다시 세지 않는다 — 감사에 '지웠다' 가 두 번 남으면 안 된다")
        void isIdempotent() {
            sessionAged(200, "원금은 지켜지죠");

            assertThat(retention.purgeExpiredUtterances(NOW)).isEqualTo(1);
            assertThat(retention.purgeExpiredUtterances(NOW))
                    .as("두 번째 호출이 또 1 을 돌려주면 언제 지웠는지가 흐려진다")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("판정은 남는다 — 지우는 것과 남기는 것의 구분")
    class JudgmentSurvives {

        @Test
        @DisplayName("발화 전문은 지워도 세션 자체와 판정은 남는다")
        void keepsSessionAndJudgment() {
            String old = sessionAged(200, "원금은 지켜지죠");

            retention.purgeExpiredUtterances(NOW);

            assertThat(sessions.findById(old))
                    .as("발화를 지우는 것이지 세션을 지우는 것이 아니다")
                    .isPresent();
        }
    }

    @Nested
    @DisplayName("삭제도 기록한다")
    class PurgeIsAudited {

        @Autowired
        private AuditLog auditLog;

        @Test
        @DisplayName("지운 사실이 감사 스트림에 남는다 — '없다' 와 '지웠다' 는 다른 답이다")
        void recordsPurge() {
            String old = sessionAged(200, "원금은 지켜지죠");
            int before = auditLog.replay().size();

            retention.purgeExpiredUtterances(NOW);

            assertThat(auditLog.replay().size() - before).isEqualTo(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload =
                    (Map<String, Object>) auditLog.replay().get(auditLog.replay().size() - 1).payload();
            assertThat(payload.get("action")).isEqualTo(RetentionService.PURGE_ACTION);
            assertThat(payload.get("resource")).isEqualTo("session:" + old);
            assertThat(payload.get("resultCode")).isEqualTo("PURGED");
            assertThat(payload.get("actorId"))
                    .as("시스템 행위라 행위자가 없다 — 키는 남기고 값이 null 이다")
                    .isNull();
        }
    }
}
