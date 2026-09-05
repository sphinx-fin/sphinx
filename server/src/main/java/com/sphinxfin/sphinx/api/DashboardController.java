package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.aggregate.AggregateService;
import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.evidence.AuditLog;
import com.sphinxfin.sphinx.evidence.HashChain;
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
    private final AuditLog auditLog;

    /**
     * 접근 감사 집계 (F-CMN-002 · 이슈 #326 파트2). 기간별 action·resultCode·차단 역할별 건수.
     *
     * <p>❗<b>개인 식별자(actorId·resource)는 안 나간다</b> — 개방 모드(결정 10.57)에서 원시
     * 엔트리를 계약에 열면 레포·주소 공개 + 무인증 상태에서 "누가 무엇을 했는가" 가 전부
     * 읽힌다({@link AuditLog#summary} javadoc). 심사에 필요한 것은 <i>"이번 주 SELLER 집계
     * 접근 차단 N건"</i> 이라는 숫자다 — {@code deniedByRole} 가 기획서 7-4(역이용 방지)의 실물이다.
     *
     * <p>권한: {@code audit:read} (COMPL org). 원시 조회와 <b>같은 action</b> 을 쓴다 — 집계는
     * 그보다 덜 민감하지만(PII 없음) 새 action 을 만들면 같은 자료에 두 그랜트가 생겨 한쪽만
     * 좁히는 실수가 난다(취약대비 뷰가 히트맵 action 을 재사용한 것과 같은 결). {@code /dashboard}
     * 아래라 개방 모드 지도가 compl-01 을 실어 준다({@code DemoModeAccountMapTest}).
     *
     * <p>{@code from} 은 포함, {@code to} 는 제외(반열림) — 이어지는 두 기간을 합쳐도 겹치지
     * 않는다. 둘 다 생략하면 전체 스트림을 센다.
     */
    @PreAuthorize("@accessGuard.canAggregate('audit:read')")
    @GetMapping("/audit-summary")
    public ApiResponse<AuditLog.AccessSummary> auditSummary(
            // Instant 는 Spring 의 InstantFormatter 가 ISO_INSTANT(…Z)로 바인딩한다 —
            // @DateTimeFormat 은 Instant 대상 타입이 없어 no-op 이라 안 붙인다(#468 리뷰, 정세현).
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return ApiResponse.ok(auditLog.summary(from, to));
    }

    /**
     * 감사 체인 무결성 검증 (F-CMN-002 · 이슈 #326 파트2). 해시 체인을 재계산해 이어져 있는지
     * (꼬리 절단까지) 본다. S-07 이 {@code contentHash} 를 보여주지만 <b>그 해시가 속한 체인이
     * 실제로 온전한지</b>는 이것이 답한다 — 감사 무결성이 "주장" 에서 "확인된 사실" 로 바뀐다.
     *
     * <p>권한: {@code audit:verify} (COMPL org). 조회({@code audit:read})와 <b>가른다</b> —
     * "몇 건 있었나"(집계)와 "그 기록이 변조되지 않았나"(무결성)는 다른 질문이라 감사에서도
     * 갈려야 한다. 응답에 개인 식별자는 없다({@code ok}·검사 건수·끊긴 지점·사유뿐).
     */
    @PreAuthorize("@accessGuard.canAggregate('audit:verify')")
    @GetMapping("/audit-verify")
    public ApiResponse<HashChain.Verification> auditVerify() {
        return ApiResponse.ok(auditLog.verify());
    }

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
     * 게이트가 <b>무엇을 결정했는가</b> (F-DSH-001 · 이슈 #321).
     *
     * <p>지금 대시보드가 내는 것은 전부 <b>오해율</b>이다 — <i>"고객이 무엇을 모르는가"</i>.
     * 이 제품이 하는 일은 그다음이고(막았는가 · 되돌렸는가 · 예외를 뒀는가), 화면에 그
     * 답이 없었다.
     *
     * <p>❗<b>권한을 새로 만들지 않는다.</b> {@code aggregate:heatmap:read} 를 그대로 쓴다 —
     * 같은 데이터를 다른 축으로 자른 것이라 action 이 갈리면 <b>같은 사실에 그랜트가 둘</b>이
     * 되고 한쪽만 좁히는 실수가 난다({@code #335} 가 세운 판단과 같다).
     */
    /**
     * ★ 코칭 정황 — <b>기획서 7-4 2단계(사후 적발)</b>.
     *
     * <p>기획서가 세 신호를 이름으로 적어 뒀는데 <b>하나도 구현돼 있지 않았다.</b> 앞의
     * 둘(1차 통과율 · 발화 균질도)을 낸다. 셋째(응답 지연 분포)는 {@code inputMeta} 가
     * 세션에 안 남아서 지금은 셀 수 없다.
     *
     * <p>권한은 선행지표와 같은 {@code aggregate:indicator:read} 다 — <b>같은 사실</b>
     * (판매자 단위 통계 이상치)에 그랜트를 둘로 만들지 않는다. 볼 수 있는 사람과 범위가
     * 정확히 같고, 실제로 이 뷰는 기존 이상치가 <b>반대 방향이라 못 잡는 것</b>을 채운다.
     *
     * <p>❗{@code SELLER} 는 집계 그랜트가 없어 여기 못 온다(ADR-001). <b>코칭을 하는
     * 쪽이 자기 정황을 볼 수 있으면 이 기능이 없는 것과 같다.</b>
     */
    @PreAuthorize("@accessGuard.canAggregate('aggregate:indicator:read')")
    @GetMapping("/coaching-signals")
    public ApiResponse<AggregateService.CoachingView> coachingSignals() {
        return ApiResponse.ok(aggregateService.coachingSignals(
                scopeOf("aggregate:indicator:read"), currentActor.branchId()));
    }

    @PreAuthorize("@accessGuard.canAggregate('aggregate:heatmap:read')")
    @GetMapping("/decisions")
    public ApiResponse<AggregateService.DecisionView> decisions(
            @RequestParam(required = false) String product,
            @RequestParam(required = false) String ageBand,
            @RequestParam(required = false) String channel) {
        return ApiResponse.ok(aggregateService.decisions(
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
