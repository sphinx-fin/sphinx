package com.sphinxfin.sphinx.evidence;

import com.sphinxfin.sphinx.core.session.UnfairSalesSignalEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-GTE-003 불공정영업 신호 큐 (이슈 #63). 소유: 정세현
 *
 * <p>{@link UnfairSalesSignalEvent} 를 받아 <b>append-only 스트림에 남긴다.</b> 발행까지는
 * 되어 있었는데 <b>구독자가 없어서 이벤트가 허공으로 갔다</b> — 큐가 비어 있던 게 아니라
 * 큐가 없었다(이슈 #63 코멘트, 실측: {@code @EventListener} 0건).
 *
 * <h2>왜 별도 저장소가 아니라 {@link ImmutableStore} 인가</h2>
 *
 * <p>ADR-003 이 리포트와 감사 로그의 기반을 한 벌로 묶은 것과 같은 이유다. 이 큐가 답해야
 * 하는 질문이 <i>"언제 무엇이 걸렸고, 그 근거가 무엇인가"</i> 라서 <b>사후에 고쳐 쓸 수 없어야</b>
 * 한다. 별도 테이블을 두면 정규화·해시가 두 벌이 되고, 그러면 큐의 항목과 판정 기록을
 * 교차 검증할 수단이 없어진다.
 *
 * <p>스트림이 세션별이 아니라 <b>하나</b>인 이유는 읽는 사람이 세션을 모르기 때문이다 —
 * COMPL 은 <i>"어느 세션에 신호가 있었나"</i> 를 물으러 오지, 세션을 지정해서 오지 않는다.
 * {@code audit} 스트림이 하나인 것과 같다.
 *
 * <h2>❗같은 트랜잭션이다 — {@code AFTER_COMMIT} 이 아니다</h2>
 *
 * <p>{@code SessionService.publishIfUnfairSales} 가 판정 적재 <b>뒤</b>, 같은 트랜잭션
 * <b>안</b>에서 낸다. 여기서 {@code @TransactionalEventListener(AFTER_COMMIT)} 을 쓰면 큐
 * 적재만 별도 트랜잭션으로 빠져서, <b>판정은 기록됐는데 큐에는 없는 구간</b>이 생긴다.
 * 그 구간은 조용하고 — 실패해도 요청은 200 이다 — 컴플라이언스가 알림을 못 받은 사실이
 * 어디에도 안 남는다. ADR-004 의 <i>"기록 없는 판정은 무효"</i> 와 같은 논리로 같이 롤백시킨다.
 *
 * <p>{@link AuditLog} 가 반대로 예외를 삼키는 것과 갈리는데, 그쪽은 <b>접근 로그</b>라
 * 적재 실패가 요청 자체를 죽이면 안 된다. 이쪽은 판정 경로의 일부다.
 */
@Slf4j
@Service
public class UnfairSignalLog {

    /** 세션별로 가르지 않는다 — 위 javadoc 참조. {@code audit} 과 같은 단일 스트림이다. */
    static final String STREAM = "signal:unfair";

    private final ImmutableStore store;

    public UnfairSignalLog(ImmutableStore store) {
        this.store = store;
    }

    /** 큐에 쌓인 신호 하나. 저장 형태가 곧 이 모양이라 읽기와 쓰기가 갈리지 않는다. */
    public record Signal(String sessionId, String itemId, String misconceptionType,
                         String utteranceQuote, Instant at) {}

    @EventListener
    public void on(UnfairSalesSignalEvent event) {
        store.openStream(STREAM);
        store.append(STREAM, payload(event));
        log.info("불공정영업 신호 적재: session={} item={} type={}",
                event.sessionId(), event.itemId(), event.misconceptionType());
    }

    /**
     * 펴서 담는다 — {@code StoredEvidenceRecorder.judgmentPayload} 와 같은 이유로
     * <b>무엇을 담는지가 이 파일에 보여야</b> 한다.
     *
     * <p>{@code utteranceQuote} 를 담는 것이 이 큐의 값이다. COMPL 이 판단하려면 고객이
     * 실제로 한 말이 있어야 하고, 그 값은 이미 {@code PiiGateway.mask()} 를 거친 것이다(P3).
     */
    private static Map<String, Object> payload(UnfairSalesSignalEvent event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", event.sessionId());
        out.put("itemId", event.itemId());
        out.put("misconceptionType", event.misconceptionType());
        out.put("utteranceQuote", event.utteranceQuote());
        out.put("at", event.at());
        return out;
    }

    /**
     * 쌓인 순서 그대로 낸다. <b>정렬하지 않는다</b> — 스트림이 append-only 라 순서가 곧
     * 일어난 순서이고, 다시 정렬하면 같은 밀리초에 들어온 둘의 순서가 기록과 달라진다.
     */
    public List<Signal> all() {
        List<Signal> out = new ArrayList<>();
        for (HashChain.ChainEntry entry : store.replay(STREAM)) {
            Map<?, ?> p = (Map<?, ?>) entry.payload();
            out.add(new Signal(
                    str(p.get("sessionId")), str(p.get("itemId")), str(p.get("misconceptionType")),
                    str(p.get("utteranceQuote")), Instant.parse(str(p.get("at")))));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
