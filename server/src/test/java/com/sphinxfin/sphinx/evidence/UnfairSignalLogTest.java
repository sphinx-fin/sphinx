package com.sphinxfin.sphinx.evidence;

import com.sphinxfin.sphinx.core.session.UnfairSalesSignalEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 불공정영업 신호 큐 (F-GTE-003 · 이슈 #63). 소유: 정세현
 *
 * <p>❗<b>이벤트를 실제로 발행해서 잰다.</b> {@code log.on(event)} 를 직접 부르면
 * <b>구독이 걸려 있는지를 안 재게 된다</b> — 이 기능이 없던 이유가 정확히 그것이었다.
 * 발행은 되는데 {@code @EventListener} 가 0건이라 이벤트가 허공으로 갔다.
 */
@DataJpaTest
@Import({JpaImmutableStore.class, UnfairSignalLog.class})
@DisplayName("UnfairSignalLog — 불공정영업 신호 큐")
class UnfairSignalLogTest {

    private static final Instant T0 = Instant.parse("2026-09-03T10:00:00.000Z");

    @Autowired private UnfairSignalLog signals;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private JpaImmutableStore store;
    @Autowired private TestEntityManager em;

    private static UnfairSalesSignalEvent event(String sid, Instant at) {
        return new UnfairSalesSignalEvent(sid, "ELS-PRINCIPAL-LOSS-WARNING", "M08-TYING",
                "대출받으려면 이것도 들어야 한다고 해서요", at);
    }

    @Test
    @DisplayName("★ 발행된 신호가 큐에 남는다 — 구독자가 없으면 허공으로 간다")
    void thePublishedSignalIsRecorded() {
        events.publishEvent(event("S-1", T0));
        em.flush();

        List<UnfairSignalLog.Signal> all = signals.all();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).sessionId()).isEqualTo("S-1");
        assertThat(all.get(0).misconceptionType()).isEqualTo("M08-TYING");
        assertThat(all.get(0).at()).isEqualTo(T0);
    }

    @Test
    @DisplayName("❗발화 인용이 실린다 — 없으면 COMPL 이 판단할 근거가 없다")
    void theUtteranceIsCarried() {
        events.publishEvent(event("S-1", T0));
        em.flush();

        assertThat(signals.all().get(0).utteranceQuote())
                .as("이 값이 이 큐의 실질이다. 유형ID 만으로는 무엇이 있었는지 알 수 없다")
                .isEqualTo("대출받으려면 이것도 들어야 한다고 해서요");
    }

    @Test
    @DisplayName("쌓인 순서 그대로 낸다 — 정렬하면 같은 밀리초의 둘이 기록과 달라진다")
    void theOrderIsTheRecordedOrder() {
        events.publishEvent(event("S-2", T0.plusSeconds(10)));
        events.publishEvent(event("S-1", T0));          // 시각은 앞이지만 나중에 들어왔다
        em.flush();

        assertThat(signals.all()).extracting(UnfairSignalLog.Signal::sessionId)
                .containsExactly("S-2", "S-1");
    }

    @Test
    @DisplayName("❗체인이 검증된다 — 사후에 고쳐 쓸 수 없어야 큐가 근거가 된다")
    void theQueueIsAVerifiableChain() {
        events.publishEvent(event("S-1", T0));
        events.publishEvent(event("S-2", T0.plusSeconds(5)));
        em.flush();

        assertThat(store.verify(UnfairSignalLog.STREAM).ok()).isTrue();
    }

    @Test
    @DisplayName("신호가 없으면 빈 목록이다 — 스트림이 없는 것과 구별되지 않아야 한다")
    void anEmptyQueueIsEmptyNotAnError() {
        assertThat(signals.all()).isEmpty();
    }
}
