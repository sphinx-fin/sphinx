package com.sphinxfin.sphinx.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 감사 적재의 동시성. 소유: 정세현
 *
 * <p><b>이 테스트가 막는 것은 "검증은 통과하는데 기록이 없는" 상태다.</b> 닻에 잠금이 없으면
 * 두 트랜잭션이 같은 {@code count} 를 읽어 같은 seq 를 만들고, {@code uk_evidence_stream_seq}
 * 에서 하나가 진다. 사슬 무결성은 유니크 제약이 지켜 주므로 {@code verify()} 는 <b>ok 를
 * 돌려주는데 진 쪽 기록이 사라진다</b> — 20건 중 8건만 남는 것을 리뷰에서 실측했다(PR #96).
 *
 * <p>그게 이 모듈의 존재 이유를 무너뜨린다. <b>"감사 로그가 검증을 통과했다"가 "기록이 다
 * 있다"를 뜻하지 않게 되고</b>, 빠진 건 로그 파일에만 남고 불변 기록에는 없다.
 *
 * <p>단일 스트림 경합이 상시화되는 것은 {@code AuditInterceptor} 가 <b>모든 HTTP 요청</b>을
 * 하나의 {@code audit} 스트림에 밀어 넣기 때문이다. 데모에서 화면 몇 개를 동시에 여는 것으로
 * 재현된다.
 */
@SpringBootTest
@DisplayName("AuditLog 동시성 — 검증 통과가 기록 존재를 뜻해야 한다")
class AuditLogConcurrencyTest {

    private static final int THREADS = 20;

    @Autowired
    private AuditLog auditLog;
    @Test
    @DisplayName("20건 동시 적재 — 하나도 유실되지 않고 사슬이 검증된다")
    void concurrentAppendsLoseNothing() throws Exception {
        // 지우지 않고 증가분을 본다. 닻을 지우면 "스트림이 열려 있다"는 전제가 깨지고,
        // 그건 이 테스트가 보려는 것(열린 스트림에 동시 적재)이 아니다.
        int before = auditLog.replay().size();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger failures = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int i = 0; i < THREADS; i++) {
                int n = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        auditLog.record(new AuditLog.Entry(
                                "seller-" + n, "ROLE_SELLER", "report:read",
                                "/sessions/S-" + n + "/report", "200",
                                Instant.parse("2026-08-27T01:00:00.000Z")));
                    } catch (RuntimeException | InterruptedException e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("적재가 30초 안에 끝나야 한다").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures.get())
                .as("record() 는 실패를 삼키므로 예외가 나오면 안 된다 — 나오면 프록시 경계가 잘못됐다")
                .isZero();
        assertThat(auditLog.replay().size() - before)
                .as("잠금이 없으면 여기서 20보다 작은 수가 나온다. 그런데 아래 verify 는 통과한다 "
                        + "— 그 조합이 이 테스트가 막는 상태다")
                .isEqualTo(THREADS);

        HashChain.Verification verification = auditLog.verify();
        assertThat(verification.ok()).isTrue();
    }
}
