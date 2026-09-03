package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.aggregate.AggregateService;
import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.api.exception.ValidationException;
import com.sphinxfin.sphinx.security.AccessGuard;
import com.sphinxfin.sphinx.security.AccessPolicy;
import com.sphinxfin.sphinx.security.CurrentActor;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * 오해 지도 집계 API. 라우팅: 강희진 / 집계 로직: 정세현 (F-DSH-001~002)
 *
 * <h2>범위는 요청이 정하지 않는다 (결정 5.10)</h2>
 *
 * <p>두 엔드포인트 다 {@code scope} 를 <b>쿼리로 받지 않는다.</b> 받으면 MGR 이
 * {@code scope=org} 를 적어 보내는 것으로 정책을 우회한다. {@link AccessGuard#grantedScope}
 * 가 정책에서 꺼내 오고, 그 값이 그대로 응답에 실린다 — 화면이 <i>"무엇을 보고 있는지"</i>
 * 를 알아야 하기 때문이다.
 *
 * <p>{@code branchId} 도 마찬가지로 {@link CurrentActor} 에서 온다. 요청 본문·쿼리 어디에도
 * 귀속을 받는 자리가 없다 — {@code AccessGuard.targetOf} 가 세션에서 귀속을 꺼내는 것과
 * 같은 규약이다.
 *
 * <p>{@code @PreAuthorize} 가 먼저 걸러 주므로 {@code grantedScope} 가 비는 것은
 * <b>정상 경로에서 일어나지 않는다.</b> 그래도 비면 통과시키지 않고 던진다 — 통과시키면
 * 서비스가 {@code null} 범위로 전체를 훑는다.
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final AggregateService aggregateService;
    private final AccessGuard accessGuard;
    private final CurrentActor currentActor;

    /**
     * 오해 지도 히트맵 (F-DSH-001).
     *
     * <p>목을 걷었다(이슈 #54 ⑤). 목은 {@code n=100} 짜리 셀 셋을 늘 돌려줘서
     * <b>소표본 마스킹이 한 번도 안 걸렸다</b> — 화면이 "가려짐" 상태를 만난 적이 없다.
     */
    @PreAuthorize("@accessGuard.canAggregate('aggregate:heatmap:read')")
    @GetMapping("/heatmap")
    public ApiResponse<AggregateService.HeatmapView> heatmap(
            @RequestParam(required = false) String product,
            @RequestParam(required = false) String ageBand,
            @RequestParam(required = false) String channel) {
        return ApiResponse.ok(aggregateService.heatmap(
                scopeOf("aggregate:heatmap:read"), currentActor.branchId(),
                new AggregateService.Filters(product, ageBand, channel)));
    }

    /**
     * 선행지표 뷰 — 주별 추이와 이상치 (F-DSH-002, 이슈 #178).
     *
     * <p>히트맵은 한 시점의 단면이라 <i>"지난주보다 나빠졌는가"</i> 를 못 말한다. 그게 이
     * 뷰가 따로 있는 이유이고, 명세 192행이 코칭 스코어를 <i>"지점 단위 통계 이상치
     * 탐지"</i> 에 쓴다고 적은 경로도 이쪽이다.
     *
     * <p>{@code groupBy} 는 <b>집계 축</b>이고 {@code scope} 는 <b>데이터 범위</b>다 — 다른
     * 개념이라 하나가 다른 하나를 정하지 않는다. 축은 요청이 고르고 범위는 정책이 정한다.
     */
    @PreAuthorize("@accessGuard.canAggregate('aggregate:indicator:read')")
    @GetMapping("/leading-indicators")
    public ApiResponse<AggregateService.IndicatorView> leadingIndicators(
            @RequestParam(required = false, defaultValue = "branch") String groupBy,
            @RequestParam(required = false, defaultValue = "8") int periods) {
        return ApiResponse.ok(aggregateService.leadingIndicators(
                scopeOf("aggregate:indicator:read"), currentActor.branchId(),
                groupByOf(groupBy), periods, Instant.now()));
    }

    /**
     * 취약 고객 대비 (F-DSH-001 · 이슈 #321 의 1번).
     *
     * <p>권한은 히트맵과 같은 {@code aggregate:heatmap:read} 를 쓴다. <b>새 action 을 만들지
     * 않는 이유</b>는 이것이 히트맵과 같은 데이터를 다른 축으로 자른 것이기 때문이다 —
     * action 이 갈리면 <b>같은 사실에 두 개의 그랜트</b>가 생기고, 한쪽만 좁히는 실수가
     * 나온다. 볼 수 있는 사람과 볼 수 있는 범위가 정확히 같다.
     *
     * <p>필터도 히트맵과 같은 셋이다. {@code ageBand} 필터를 함께 걸 수 있는 것이 이상해
     * 보일 수 있는데, <b>취약 판정은 연령만이 아니라 네 요인의 합</b>이라 같은 연령대 안에서도
     * 두 줄이 갈린다(예: 50대 + 투자경험 없음).
     */
    @PreAuthorize("@accessGuard.canAggregate('aggregate:heatmap:read')")
    @GetMapping("/vulnerability-contrast")
    public ApiResponse<AggregateService.ContrastView> vulnerabilityContrast(
            @RequestParam(required = false) String product,
            @RequestParam(required = false) String ageBand,
            @RequestParam(required = false) String channel) {
        return ApiResponse.ok(aggregateService.vulnerabilityContrast(
                scopeOf("aggregate:heatmap:read"), currentActor.branchId(),
                new AggregateService.Filters(product, ageBand, channel)));
    }

    /**
     * 정책이 허용한 범위. 비면 던진다 — {@code null} 로 넘기면 서비스가 전체를 훑는다.
     *
     * <p>{@code @PreAuthorize} 가 먼저 걸러 주므로 여기 오는 것은 <b>정책과 어노테이션이
     * 어긋난 상태</b>다. 조용히 넓히지 않고 500 으로 드러낸다.
     */
    private AccessPolicy.Scope scopeOf(String action) {
        return accessGuard.grantedScope(action).orElseThrow(() -> new IllegalStateException(
                "허용된 범위가 없는데 " + action + " 이 통과했다 — @PreAuthorize 와 정책이 어긋났다"));
    }

    /** 계약의 enum 밖 값은 400 이다. 조용히 기본값으로 떨어지면 화면이 다른 축을 그린다. */
    private static AggregateService.GroupBy groupByOf(String raw) {
        try {
            return AggregateService.GroupBy.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(
                    "groupBy 는 branch·seller·item 중 하나다: " + raw);
        }
    }
}
