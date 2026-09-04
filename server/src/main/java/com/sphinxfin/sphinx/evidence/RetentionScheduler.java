package com.sphinxfin.sphinx.evidence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * {@link RetentionService} 를 <b>실제로 돌린다</b>. 소유: 강희진 (배선)
 *
 * <h2>❗정책이 만들어져 있었고 아무도 안 불렀다</h2>
 *
 * <p>기획서 7-4 「차단 장치」가 <b>최소 보존</b>을 든다.
 *
 * <blockquote>원문 발화는 보관기간을 한정하고, 이후에는 판정 결과만 남긴다.</blockquote>
 *
 * <p>{@link RetentionService#purgeExpiredUtterances} 가 그것을 정확히 구현하고 있고 테스트도
 * 두껍다. 그런데 <b>호출자가 자기 테스트뿐이었다</b> — 스케줄러도, 엔드포인트도, 기동 훅도
 * 없었다. 즉 <b>보존기간이 지나도 발화가 안 지워진다.</b>
 *
 * <p>이 모양이 특히 나쁜 이유는 <b>보이지 않기 때문</b>이다. 레포를 읽는 사람은 서비스와
 * 테스트를 보고 <i>"보존 정책이 있다"</i> 고 믿는다. 감사에서 물었을 때 코드를 보여줄 수는
 * 있는데 <b>돌지 않는다</b> — 없는 것보다 나쁜 상태다.
 *
 * <h2>왜 서비스 파일을 안 건드리나</h2>
 *
 * <p>{@code RetentionService} 는 정세현 소유다. <b>정책은 그쪽, 등록은 이쪽</b> —
 * {@code AuditInterceptor}(정세현)를 {@code WebMvcConfig}(강희진)가 등록하는 것과 같은 갈래다
 * (CLAUDE.md F-CMN-002 절). 그래서 여기는 <b>부르기만 한다.</b>
 *
 * <h2>꺼져 있어도 돈다 — 그게 요점이다</h2>
 *
 * <p>{@code sphinx.retention.enforce} 기본값이 {@code false} 라 지금은 지우지 않는다. 그래도
 * <b>스케줄은 돈다</b>: 서비스가 {@code ran=false} 를 돌려주고 그 사실이 로그에 남는다.
 * <i>"정책이 꺼져 있다"</i> 와 <i>"정책이 안 걸려 있다"</i> 는 다르고, 지금까지는 그 둘이
 * 구별되지 않았다.
 */
@Slf4j
@Component
@EnableScheduling
public class RetentionScheduler {

    private final RetentionService retention;
    private final String cron;

    public RetentionScheduler(RetentionService retention,
                              @Value("${sphinx.retention.cron:0 15 3 * * *}") String cron) {
        this.retention = retention;
        this.cron = cron;
    }

    /**
     * 기동 직후 한 번 <b>무엇이 걸려 있는지</b> 남긴다.
     *
     * <p>{@code cron} 만으로는 다음 실행까지 아무 흔적이 없어서, 기동 로그만 보는 사람에게
     * <i>"안 걸려 있다"</i> 와 구별이 안 된다 — 이 클래스가 생긴 이유가 정확히 그 혼동이다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        log.info("F-GTE-004 보존 정책 스케줄 등록: cron={} (실제 삭제 여부는 "
                + "sphinx.retention.enforce 가 정한다)", cron);
    }

    /**
     * 하루 한 번 보존기간이 지난 발화를 지운다.
     *
     * <p>❗<b>결과를 항상 남긴다.</b> {@code purged=0} 과 <i>"안 돌았다"</i> 가 로그에서
     * 같아 보이면 이 배선이 다시 조용해진다.
     */
    @Scheduled(cron = "${sphinx.retention.cron:0 15 3 * * *}")
    public void purge() {
        RetentionService.PurgeResult result = retention.purgeExpiredUtterances(Instant.now());
        log.info("F-GTE-004 보존 정책 실행: 적용={} 삭제={}건", result.ran(), result.purged());
    }
}
