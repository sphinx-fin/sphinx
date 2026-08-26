package com.sphinxfin.sphinx.evidence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * F-CMN-002 접근 감사 로그. 소유: 정세현
 *
 * F-CMN-002는 두 덩어리다 — 접근 통제(RBAC)와 감사 로그. 감사 로그가 이쪽,
 * RBAC는 {@code security/}. 경계는 security/AccessPolicy 주석 참고.
 *
 * 기록 주체는 컨트롤러가 아니라 security/AuditInterceptor다. 컨트롤러마다 record()를
 * 부르면 감사 관심사가 api/(강희진)에 흩어져 소유권이 다시 겹친다.
 *
 * ReportService와 동일한 해시 체인·정규화를 쓴다. 스트림 이름: "audit"
 *
 * <h2>트랜잭션이 세션과 다르다 — 여기가 유일한 예외다</h2>
 *
 * <p>판정 적재는 세션 저장과 <b>같은 트랜잭션</b>이다(ADR-004: 기록 없는 판정도 무효).
 * 감사 로그는 <b>반대로 새 트랜잭션을 연다</b>({@code REQUIRES_NEW}). 이유가 둘이다.
 *
 * <p>① <b>실패한 요청도 남아야 한다.</b> 차단당한 시도가 반복되는 것 자체가 신호다
 * (기획 7-4 2단계). 업무 트랜잭션에 묶으면 롤백과 함께 <b>기록이 사라져서</b>, 감사에서
 * "거부가 없었다"와 "거부를 기록하지 못했다"가 구별되지 않는다.
 *
 * <p>② <b>기록되는 시점이 응답 이후다.</b> {@code AuditInterceptor}는
 * {@code afterCompletion}에서 부르는데 그때는 컨트롤러 트랜잭션이 이미 커밋됐다. 참여할
 * 트랜잭션이 없으므로 어차피 새로 열린다 — 명시하는 것이 낫다.
 *
 * <p><b>대가가 둘 있다. 정직하게 적어둔다.</b>
 *
 * <p>하나 — 스트림이 하나라 {@code evidence_stream_anchors} 의 {@code audit} 행이 <b>모든 감사
 * 적재의 직렬화 지점</b>이다. 사슬은 순서를 요구하므로 직렬화 자체는 맞는데, 동시 요청이 많으면
 * 여기서 줄을 선다. 그리고 밖에서 그 행을 잡고 있으면 {@code REQUIRES_NEW} 가 자기 자신과
 * 락 경합을 한다(테스트에서 실측했다 — {@code AuditLogTest} 주석 참고). 업무 트랜잭션은 세션
 * 스트림 anchor 만 건드리므로 실사용 경로에는 겹치는 지점이 없다.
 *
 * <p>둘 — 이 append가 실패하면 <b>요청은 이미 성공했고 기록만
 * 없다.</b> 판정 쪽은 append 실패가 요청 실패로 이어지지만 여기는 그럴 수 없다 — 감사 실패로
 * 창구 업무를 막을 수는 없다. 그래서 실패를 <b>삼키되 시끄럽게</b> 남긴다. 3주 범위 밖이지만
 * 제대로 하려면 실패한 감사 기록을 재시도 큐에 넣어야 한다.
 */
@Slf4j
@Service
public class AuditLog {

    /** 감사 로그 스트림. 세션별로 나누지 않는다 — "누가 여러 세션을 훑었는가"가 신호다. */
    static final String STREAM = "audit";

    /**
     * 누가·언제·무엇에 접근했는가. 개인 식별자는 담지 않는다 (P3).
     *
     * <p>{@code occurredAt}은 {@link Instant}다. 문자열로 두면 기록하는 쪽이 포맷을 정하게 되고,
     * {@code Instant.toString()}은 <b>밀리초가 0이면 소수부를 통째로 생략한다</b>
     * ({@code …:00Z}) — ADR-008이 정한 "UTC · 밀리초 3자리 고정"과 어긋난다. 약 1/1000 확률로
     * 다른 형식이 섞이는데, 감사 시점에 두 형식이 보이면 그게 조작인지 포맷 차이인지 가리는 데
     * 시간이 든다. 타입으로 두면 {@link CanonicalJson}이 한 곳에서 포맷한다.
     *
     * <p>{@code actorId}는 <b>직원</b> 식별자다. P3가 막는 것은 고객 개인정보이고, 감사의
     * "누가"는 반대로 특정돼야 한다 — 특정되지 않으면 ADR-002 견제 장치가 무력해진다(10.5).
     */
    public record Entry(String actorId, String role, String action,
                        String resource, String resultCode, Instant occurredAt) {}

    private final ImmutableStore store;
    private final AuditAppender appender;

    /** 스트림 개방은 한 번이면 된다. 매 요청마다 확인하면 그만큼 트랜잭션이 늘어난다. */
    private volatile boolean streamOpened;

    public AuditLog(ImmutableStore store, AuditAppender appender) {
        this.store = store;
        this.appender = appender;
    }

    /**
     * 한 건 적재. 실패해도 예외를 밖으로 내지 않는다 — 호출자가
     * {@code afterCompletion}이라 던져도 응답을 바꿀 수 없고, 감사 실패로 창구 업무를 막는 것도
     * 맞지 않다. 대신 <b>무엇을 잃었는지</b>를 로그에 남긴다.
     */
    public void record(Entry entry) {
        try {
            // 스트림을 먼저 연다(멱등). 첫 append 는 잠글 닻 행이 없어 동시에 오면 하나가
            // 지는데, 감사 스트림은 모든 요청이 몰리므로 그 창을 미리 없앤다. append 와
            // 별개의 트랜잭션이고 순차로 부른다 — 중첩하면 커넥션을 둘 쥐고 멈춘다(실측).
            ensureStreamOpened();
            // 트랜잭션 경계는 AuditAppender 에 있다. 여기 @Transactional 을 두면 프록시가
            // 커밋하는 시점이 이 메서드 밖이라 UnexpectedRollbackException 을 못 잡는다.
            appender.append(entry);
        } catch (RuntimeException e) {
            // 기록을 잃은 사실 자체가 감사 정보다. 조용히 지나가면 "접근이 없었다"로 읽힌다.
            log.error("감사 기록 적재 실패 — 이 접근은 불변 기록에 없다: action={} actor={} resource={}",
                    entry.action(), entry.actorId(), entry.resource(), e);
        }
    }

    /**
     * 스트림을 한 번만 열되 <b>열리기 전에 다른 스레드가 append 로 들어가지 않게</b> 한다.
     * {@code compareAndSet} 으로는 안 된다 — 한 스레드만 개방을 시작하고 나머지는 그대로
     * 통과해서, 닻이 아직 없는 상태로 append 에 몰린다(20건 중 11건만 남는 것을 실측).
     */
    private void ensureStreamOpened() {
        if (streamOpened) {
            return;
        }
        synchronized (this) {
            if (!streamOpened) {
                appender.openStream();
                streamOpened = true;
            }
        }
    }

    /** 감사 스트림 재생. {@code audit:read} 가 붙을 자리이고, 지금은 검증·테스트가 쓴다. */
    public List<HashChain.ChainEntry> replay() {
        List<HashChain.ChainEntry> entries = new ArrayList<>();
        store.replay(STREAM).forEach(entries::add);
        return entries;
    }

    /** 감사 스트림 검증. {@code audit:verify} 의 구현 지점 — 꼬리 절단까지 본다. */
    public HashChain.Verification verify() {
        return store.verify(STREAM);
    }
}
