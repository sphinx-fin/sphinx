package com.sphinxfin.sphinx.evidence;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 기록 적재의 트랜잭션 경계. 소유: 정세현
 *
 * <p><b>왜 별도 빈인가.</b> {@link AuditLog#record}가 실패를 삼켜야 하는데, {@code @Transactional}을
 * 같은 메서드에 두면 <b>삼킬 수 없다</b> — {@code catch} 는 롤백 표시를 지우지 못하고,
 * {@code REQUIRES_NEW} 프록시가 커밋을 시도하는 시점은 그 메서드가 <b>리턴한 뒤</b>라
 * {@code UnexpectedRollbackException} 이 try/catch 밖에서 난다(PR #96 리뷰에서 실측).
 *
 * <p>그래서 트랜잭션 경계를 이 빈으로 밀어내고, {@code AuditLog} 는 그 <b>바깥에서</b> 잡는다.
 * 자기 자신을 호출하면 프록시를 안 거쳐 애너테이션이 무시되므로 같은 클래스에 둘 수 없다.
 */
@Component
class AuditAppender {

    private final ImmutableStore store;

    AuditAppender(ImmutableStore store) {
        this.store = store;
    }

    /** 판정 적재와 달리 새 트랜잭션이다 — 이유는 {@link AuditLog} 주석. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void append(AuditLog.Entry entry) {
        store.append(AuditLog.STREAM, entry);
    }

    /**
     * 감사 스트림을 미리 연다. {@code append} 와 <b>별개의 트랜잭션</b>이고, 서로 중첩되지
     * 않게 {@link AuditLog#record} 가 순차로 부른다 — 중첩하면 커넥션을 둘 쥐게 되고 동시
     * 요청이 풀을 넘으면 전원이 멈춘다(실측).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void openStream() {
        store.openStream(AuditLog.STREAM);
    }
}
