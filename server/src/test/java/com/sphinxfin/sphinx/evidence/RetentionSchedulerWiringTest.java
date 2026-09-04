package com.sphinxfin.sphinx.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.Task;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보존 정책이 <b>실제로 스케줄에 걸려 있는가</b>. 소유: 강희진 (F-GTE-004 · 기획 7-4)
 *
 * <h2>❗정책이 있는 것과 도는 것은 다르다</h2>
 *
 * <p>{@link RetentionService} 는 완성돼 있었고 테스트도 두꺼웠는데 <b>호출자가 자기 테스트
 * 뿐이었다.</b> 스케줄러도 엔드포인트도 기동 훅도 없었으므로 <b>보존기간이 지나도 발화가
 * 안 지워졌다.</b>
 *
 * <p>그리고 <b>보이지 않았다</b> — 서비스와 테스트를 본 사람은 정책이 있다고 믿는다. 감사에서
 * 물으면 코드를 보여줄 수는 있는데 돌지 않는다. 그래서 <i>"돈다"</i> 를 테스트가 잰다.
 *
 * <p>❗{@code RetentionServiceTest} 로는 이걸 못 잡는다 — 그 파일은 서비스를 <b>직접
 * 부른다.</b> 등록을 지워도 초록이다. 이 레포에서 여러 번 난 모양이라(그물이 아예 안 돈다)
 * 층을 하나 더 둔다.
 */
@SpringBootTest
@DisplayName("보존 정책 스케줄 배선 (F-GTE-004 · 기획 7-4 최소 보존)")
class RetentionSchedulerWiringTest {

    @Autowired private ScheduledAnnotationBeanPostProcessor scheduling;
    @Autowired private RetentionScheduler scheduler;

    /**
     * ❗<b>목이다.</b> 무엇을 지우는가는 {@code RetentionServiceTest} 가 잰다 — 여기서 재는
     * 것은 <b>스케줄이 서비스까지 닿는가</b> 하나다. 실물을 쓰면 {@code enforce=false} 라
     * 아무 일도 안 일어나서, 몸통을 비워도 이 파일이 아무 말을 안 한다.
     */
    @MockBean private RetentionService retention;

    @Test
    @DisplayName("❗발화 삭제가 스케줄에 걸려 있다 — 정책이 있어도 안 부르면 안 지워진다")
    void thePurgeIsActuallyScheduled() {
        Set<ScheduledTask> tasks = scheduling.getScheduledTasks();

        assertThat(tasks)
                .as("등록된 스케줄이 하나도 없다 — @EnableScheduling 이 빠졌거나 "
                        + "RetentionScheduler 가 빈이 아니다")
                .isNotEmpty();
        assertThat(tasks.stream().map(ScheduledTask::getTask).map(Task::toString))
                .as("보존 정책이 스케줄에 없다. RetentionService 가 완성돼 있어도 "
                        + "부르는 자리가 없으면 발화가 영구 보존된다(기획 7-4 「최소 보존」)")
                .anyMatch(name -> name.contains("RetentionScheduler.purge"));
    }

    @Test
    @DisplayName("cron 이 설정에서 온다 — 코드에 박으면 배포마다 못 바꾼다")
    void theCronComesFromConfiguration() {
        CronTask cron = scheduling.getScheduledTasks().stream()
                .filter(t -> t.getTask().toString().contains("RetentionScheduler.purge"))
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance).map(CronTask.class::cast)
                .findFirst().orElseThrow(() ->
                        new AssertionError("보존 정책이 cron 으로 안 걸려 있다"));

        assertThat(cron.getExpression())
                .as("application.yml 의 sphinx.retention.cron 이 그대로 와야 한다")
                .isEqualTo("0 15 3 * * *");
    }

    @Test
    @DisplayName("❗부르면 서비스까지 간다 — 스케줄만 걸고 몸통이 비면 같은 상태다")
    void callingItReachesTheService() {
        // ❗처음엔 `scheduler.purge()` 를 부르고 아무것도 단정하지 않았다. 그러면 몸통을
        // 통째로 비우는 변이가 **통과한다** — 스케줄에 걸려 있고 예외도 안 나므로.
        // 이 레포에서 여러 번 찾은 모양(결과만 재고 경로를 안 지나간다)을 내가 그대로 밟았다.
        when(retention.purgeExpiredUtterances(any(Instant.class)))
                .thenReturn(new RetentionService.PurgeResult(true, 3));

        scheduler.purge();

        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(retention).purgeExpiredUtterances(at.capture());
        assertThat(at.getValue())
                .as("서비스에 시각을 안 넘기면 '언제까지 지울 것인가' 를 서비스가 정하게 된다 "
                        + "— 그러면 테스트가 시간을 고정할 수 없다")
                .isNotNull();
    }
}
