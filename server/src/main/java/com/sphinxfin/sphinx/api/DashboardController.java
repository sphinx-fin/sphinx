package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.aggregate.AggregateService;
import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.evidence.AuditLog;
import com.sphinxfin.sphinx.evidence.EvidenceMetrics;
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
    private final EvidenceMetrics evidenceMetrics;
    private final com.sphinxfin.sphinx.core.pii.PiiMeter piiMeter;

    /**
     * 실세션 운영 지표 (F-CMN-002 · 이슈 #327 · #479). 폴백률·confidence 분포·소요시간·
     * 재설명 전후 전환 — {@code EvidenceMetrics.summary()} 를 그대로 낸다.
     *
     * <p>❗<b>실세션만 센다 — 합성 세션은 여기 없다.</b> 합성 세션은 집계용이라 불변 기록을
     * 안 쌓으므로(결정 5.16), 이 집계는 자동으로 실세션 전용이고 합성/실이 섞일 수가 없다.
     * 오해 지도(합성, {@code synthetic=true} 상수)와 <b>성질이 정반대</b>라, 시연에서 합성
     * 옆에 "방금 돌린 실세션" 을 한 화면에 세울 수 있다.
     *
     * <p>❗<b>{@code unreadable} 을 0 으로 접으면 안 된다</b>(결정 5.40 · AuditLog 와 같은 규약)
     * — 못 잰 것과 0 은 다르다. 그리고 evidence 는 프로세스 수명이라 이 값은 "이 프로세스가
     * 뜬 뒤로" 다 — 화면이 그렇게 말해야 오해가 없다.
     *
     * <p>권한: {@code audit:read} 를 재사용한다(COMPL org). 상품×항목 축(aggregate:*)이 아니라
     * <b>운영 지표</b>라 성격이 audit-summary(#468)와 같은 자리다 — 같은 자료(불변 기록)를
     * org 범위로 읽는다. 새 action 을 만들면 같은 성질에 그랜트가 둘이 된다.
     */
    @PreAuthorize("@accessGuard.canAggregate('audit:read')")
    @GetMapping("/evidence-metrics")
    public ApiResponse<EvidenceMetrics.Summary> evidenceMetrics() {
        return ApiResponse.ok(evidenceMetrics.summary());
    }

    /**
     * P3 마스킹 계량 (F-CMN-001 · 이슈 #326 파트1). 경계를 지나간 호출 수와 종류별 삭제 건수.
     *
     * <p>보안·개인정보 주장이 전부 코드 주석과 정책 파일로만 있었다. 심사에서 <i>"개인정보는
     * 어떻게 처리하나요"</i> 가 나오면 답이 <b>주석</b>이었고, 계량기가 붙은 뒤에도 꺼낼 길이
     * {@code @PreDestroy} 종료 로그 하나였다 — <i>"프로세스가 끝날 때 로그를 보세요"</i> 가
     * 답이 될 수 없다. 이 경로가 그것을 숫자로 만든다.
     *
     * <h2>❗{@code /audit-summary} 에 합치지 않는다 — 앞서 그러려고 했다</h2>
     *
     * <p>이슈 #326 에서 내가 <i>"audit 응답을 {access, pii, gate} 래퍼로 바꾼다"</i> 고 적었다.
     * <b>그 판단을 되돌린다.</b> 이유가 응답 모양의 취향이 아니라 <b>기간</b>이다.
     *
     * <pre>
     *   /audit-summary   from·to 를 받는다   불변 기록을 재생한다 (기간 질의가 성립한다)
     *   PiiMeter         기간을 못 받는다     프로세스 메모리다 (누적 하나뿐이다)
     * </pre>
     *
     * <p>한 응답에 담으면 <b>같은 {@code from}·{@code to} 아래 한쪽만 필터가 걸린다</b> —
     * 사람은 두 숫자를 같은 기간으로 읽는데 {@code pii} 절은 언제나 프로세스 누적이다.
     * 그건 <i>"한 응답에 두 뜻"</i> 이고 이 레포가 반복해서 막아 온 모양이다. 갈라 두면
     * {@code since} 가 그 값의 창을 스스로 말한다.
     *
     * <p>기존 계약을 안 깨는 것은 부수 이득이다 — {@code /audit-summary} 는 이미 alpha 에
     * 살아 있고 {@code web/src/api/types.ts} 가 미러한다.
     *
     * <h2>권한과 노출</h2>
     *
     * <p>{@code audit:read}(COMPL org)를 재사용한다. 같은 질문(<i>"경계가 실제로 작동했나"</i>)에
     * 그랜트가 둘이 되면 한쪽만 좁히는 실수가 난다 — {@code /evidence-metrics} 가 같은 근거로
     * 이 action 을 재사용한다. {@code /dashboard} 아래라 개방 모드 지도가 compl-01 을 실어 준다.
     *
     * <p>❗<b>이 응답에 개인이 식별될 조각이 하나도 없다</b> — 종류 이름·개수·시각뿐이다.
     * 계량기가 원문·세션 축을 애초에 안 쌓기 때문이고(그 javadoc), <b>그것이 이 경로를 열 수
     * 있는 근거</b>다. 세션별 집계를 여기 더하면 <i>"이 고객이 주민번호를 적었다"</i> 가
     * 되므로, 그건 응답을 넓히는 것이 아니라 계량기를 고치는 일이다.
     */
    @PreAuthorize("@accessGuard.canAggregate('audit:read')")
    @GetMapping("/pii-summary")
    public ApiResponse<com.sphinxfin.sphinx.core.pii.PiiMeter.Summary> piiSummary() {
        // ❗기간 파라미터를 받지 않는다. 받아 놓고 무시하면 화면이 좁힌 줄 알고 그린다 —
        //   계량기가 답할 수 없는 질문은 애초에 받지 않는 편이 낫다(since 가 창을 말한다).
        return ApiResponse.ok(piiMeter.snapshot());
    }

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
