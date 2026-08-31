package com.sphinxfin.sphinx.security;

import com.sphinxfin.sphinx.core.session.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.Optional;

/**
 * 컨트롤러 @PreAuthorize가 부르는 단일 진입점. 소유: 강희진
 *
 * 여기서 하는 일은 **연결**뿐이다 — 요청 주체와 대상을 만들어 {@link AccessPolicy}에 넘긴다.
 * 허용 규칙은 하나도 여기 없다. 역할·범위 판단은 rbac_policy.yaml이 유일한 근거이고
 * (소유: 정세현), 그 판단을 이 클래스가 흉내내기 시작하면 정책이 두 벌이 된다.
 *
 * ── 왜 @PreAuthorize 안에서 action 이름만 넘기는가 ──────────────────────
 * 컨트롤러는 "이 요청은 어떤 action인가"만 선언하고, 그 action이 누구에게 허용되는지는
 * 모른다. 그래야 정책 변경이 yaml PR 하나로 끝나고 컨트롤러를 건드리지 않는다.
 *
 *   @PreAuthorize("@accessGuard.can('session:judge', #sid)")
 *
 * 이 문자열 형식은 고정이다 — AuditInterceptor가 같은 애너테이션에서 action을 읽어
 * 감사 대상 여부를 판단하므로, 형식이 흔들리면 감사가 조용히 빠진다.
 * AccessControlWiringTest가 형식과 action 존재를 함께 고정한다.
 * ────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Component("accessGuard")
public class AccessGuard {

    private final AccessPolicy policy;
    private final SessionRepository sessions;
    private final CurrentActor actorSource;
    private final boolean enforce;

    public AccessGuard(AccessPolicy policy,
                       SessionRepository sessions,
                       CurrentActor actorSource,
                       @Value("${sphinx.security.enforce:false}") boolean enforce) {
        this.policy = policy;
        this.sessions = sessions;
        this.actorSource = actorSource;
        this.enforce = enforce;
        if (!enforce) {
            // 조용히 열려 있으면 "정책이 붙었다"고 착각하게 된다. 기동 로그에 남긴다.
            log.warn("접근 통제 비활성(sphinx.security.enforce=false) — @PreAuthorize가 통과만 시킨다. "
                    + "AccessPolicy 구현(F-CMN-002) 후 켠다.");
        }
    }

    /**
     * action이 허용되는가. resourceId는 세션 ID(없는 action이면 null).
     *
     * enforce=false면 정책을 부르지 않고 통과시킨다. 스위치를 둔 원래 이유
     * (AccessPolicy.permits() 미구현)는 #99 로 해소됐고, 지금 남은 이유는 **역할별 계정이
     * 아직 없다는 것**이다(결정 10.5) — 계정이 하나뿐인 상태로 켜면 모든 요청이 같은
     * 역할로 판단돼 차단이 의미를 잃는다. 스위치를 명시적으로 두는 것은 그대로다:
     * 조용히 통과시키면 "막고 있다"와 "안 막고 있다"가 구별되지 않는다.
     */
    public boolean can(String action, String resourceId) {
        if (!enforce) {
            return true;
        }
        AccessPolicy.Actor actor = currentActor();
        return decided(actor, action, resourceId, targetOf(resourceId));
    }

    /**
     * 집계 요청 — 귀속 주체가 없다. {@code scope: org}·{@code branch} 인 action 전용이다.
     *
     * <p>예전에는 {@code can(action)} 하나가 "리소스가 없다" 를 곧 "집계다" 로 접었다. 그런데
     * <b>리소스가 없는 이유는 둘</b>이다 — 집계라서 대상이 없는 것과, 생성이라 대상이 아직
     * 없는 것. 그 둘을 값의 부재로 추론하니 {@code session:create}(own_session)가 집계로
     * 판정돼 <b>SELLER 가 세션을 만들 수조차 없었다</b>(enforce=true 에서 403, 실측).
     * #99 가 {@code Target} 에서 고친 것과 같은 오류라 같은 방식으로 고친다 — 호출부가 말한다.
     */
    public boolean canAggregate(String action) {
        return can(action, null);
    }

    /**
     * 집계 요청에 <b>허용된 범위</b>. 불리언으로는 부족한 자리다 (결정 5.10).
     *
     * <p>{@code canAggregate} 는 <i>"닿을 수 있는가"</i> 만 말한다. 집계 서비스는 그 위에
     * <b>질의를 어디까지 좁힐지</b>를 알아야 한다 — {@code scope: branch} 인 MGR 에게 org
     * 전체를 주면 정책이 통과시킨 의미가 없다.
     *
     * <p>❗<b>요청 파라미터로 받지 않는다.</b> 범위를 쿼리로 받으면 MGR 이 {@code scope=org}
     * 를 적어 보내는 것으로 정책을 우회한다. 서버가 정해서 응답에 싣는다.
     *
     * <p>{@code enforce=false} 면 {@code ORG} 다 — 목 개발용이고, 그때는 정책 자체가 안 돈다.
     * 그 상태를 {@code Optional.empty()} 로 두면 호출부가 <i>"권한 없음"</i> 과 구별 못 한다.
     *
     * @return 허용된 범위. 닿을 수 없으면 {@code Optional.empty()}
     */
    public Optional<AccessPolicy.Scope> grantedScope(String action) {
        if (!enforce) {
            return Optional.of(AccessPolicy.Scope.ORG);
        }
        AccessPolicy.Actor actor = currentActor();
        Optional<AccessPolicy.Scope> scope =
                policy.grantedScope(actor, action, AccessPolicy.Target.aggregate());
        if (scope.isEmpty()) {
            logDenial(actor, action, null,
                    policy.decide(actor, action, AccessPolicy.Target.aggregate()).reason());
        }
        return scope;
    }

    /**
     * 생성 요청 — 대상이 <b>아직</b> 없다. 만들어질 세션은 지금 요청한 사람의 것이므로
     * 자기 자신을 귀속으로 두고 판단한다. {@code own_session} 그랜트가 그대로 성립한다.
     *
     * <p>집계와 달리 이건 "주인 없는 대상" 이 아니라 "주인이 정해진 대상" 이다.
     *
     * <p>❗익명 요청은 <b>정책에 닿지 않는다.</b> 익명도 {@code isAuthenticated()} 는 true 지만
     * 권한이 {@code ROLE_ANONYMOUS} 라 {@code Role.valueOf} 가 실패해서 {@link #currentActor()}
     * 가 먼저 던지고, {@code GlobalExceptionHandler} 가 <b>401</b> 로 매핑한다(#105). 403 이
     * 아닌 것이 맞다 — 로그인하면 해소되는 상태다. 거부가 어디서 나는지를 적어두는 이유는,
     * 401·403 이 갈리는 지점이라 문면이 틀리면 다음 사람이 잘못된 층에서 원인을 찾는다.
     */
    public boolean canCreate(String action) {
        if (!enforce) {
            return true;
        }
        AccessPolicy.Actor actor = currentActor();
        return decided(actor, action, null,
                AccessPolicy.Target.session(null, actor.actorId(), actor.branchId()));
    }

    /**
     * 판단하고, <b>거부면 사유를 남긴다.</b>
     *
     * <p>{@link AccessPolicy.Decision} 은 <i>"막았다"</i> 와 <i>"판단할 수 없다"</i> 를 가르려고
     * 사유를 만든다. 그런데 이 클래스가 {@code permits()}(불리언)만 불러서 <b>그 사유가
     * 계산되고 버려졌다</b> — 아래 {@link #targetOf} 의 javadoc 이 <i>"거부 사유가 '지점을 알 수
     * 없다' 가 아니라 '세션이 없다' 여야 운영 중에 원인을 가를 수 있다"</i> 고 적어 놓고, 정작
     * 그 사유가 프로세스 밖으로 나가는 경로가 없었다.
     *
     * <p>드러나는 자리가 리허설이다. {@code enforce=true} 아래서 403 은 전부 같은 문면
     * ({@code FORBIDDEN} · <i>"이 작업을 수행할 권한이 없습니다"</i>)이고 감사 로그도
     * {@code "403"} 만 남기므로, <b>정책이 옳게 막은 것</b>(SELLER 가 집계를 시도 — 시연할
     * 항목이다)과 <b>판단할 근거가 없어 막힌 것</b>(CUST 는 대조할 식별자가 세션에 없다,
     * 이슈 #166)이 화면·로그 어디에서도 구별되지 않는다.
     *
     * <p>❗<b>사유를 응답에 싣지 않는다.</b> <i>"세션이 없다"</i> 와 <i>"자기 세션이 아니다"</i>
     * 가 갈리면 남의 세션 ID 의 존재 여부를 응답으로 물어볼 수 있다. 서버 로그에만 남긴다 —
     * 원인을 가르는 것은 운영자이지 요청자가 아니다.
     */
    private boolean decided(AccessPolicy.Actor actor, String action,
                            String resourceId, AccessPolicy.Target target) {
        AccessPolicy.Decision decision = policy.decide(actor, action, target);
        if (!decision.allowed()) {
            logDenial(actor, action, resourceId, decision.reason());
        }
        return decision.allowed();
    }

    /**
     * 거부 한 건. 개인정보는 싣지 않는다 — {@code actorId} 는 계정 식별자이고
     * {@code resourceId} 는 세션 UUID 라 둘 다 P3 의 고객 텍스트가 아니다.
     */
    private void logDenial(AccessPolicy.Actor actor, String action,
                           String resourceId, String reason) {
        log.warn("접근 거부 actor={} role={} action={} resource={} 사유={}",
                actor.actorId(), actor.role(), action,
                resourceId == null ? "-" : resourceId, reason);
    }

    /**
     * 인증 주체 → Actor. 역할은 권한 이름 ROLE_<Role> 에서 되읽는다.
     *
     * TODO(강희진): 역할별 계정 분리(결정 10.5, 정세현 8/29) 후에는 미인증을 통과시키지
     *   않는다. 지금은 enforce=false 경로에서만 도달하므로 실질 영향이 없다.
     */
    private AccessPolicy.Actor currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedNotAuthenticatedException("인증되지 않은 요청이다");
        }
        Role role = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .flatMap(name -> {
                    try {
                        return java.util.stream.Stream.of(Role.valueOf(name));
                    } catch (IllegalArgumentException e) {
                        return java.util.stream.Stream.empty();
                    }
                })
                .findFirst()
                .orElseThrow(() -> new AccessDeniedNotAuthenticatedException(
                        "Role enum에 없는 권한이다: " + auth.getAuthorities()));
        return new AccessPolicy.Actor(auth.getName(), role, branchOf());
    }

    /**
     * 소속 지점. {@link CurrentActor} 와 <b>같은 출처</b>를 써야 한다 — 세션에 적히는 지점과
     * 인가 판단에 쓰는 지점이 갈리면, 자기가 만든 세션을 자기가 못 읽는 상태가 난다.
     * 그래서 {@code Authentication} 을 인자로 받지 않는다. 받으면 여기서 따로 읽어도 되는
     * 것처럼 보이고, 그 순간 출처가 둘이 된다.
     */
    private String branchOf() {
        return actorSource.branchId();
    }

    /**
     * 세션 귀속. 정책이 scope 를 평가할 근거다.
     *
     * <p>세션이 <b>없으면</b> 귀속을 만들지 않는다. 예전에는 없는 세션을
     * {@code new Target(null, null, null)} 로 접었는데, 그 값이 집계와 구별되지 않아
     * <b>실재하지 않는 세션이 실재하는 남의 지점 세션보다 더 허용되는</b> 역전이 났다(#99).
     * 지금은 {@code Target.Kind} 가 종류를 들고 있어 그 혼동은 없지만, 여기서도 조회 실패를
     * 그대로 드러낸다 — 거부 사유가 "지점을 알 수 없다" 가 아니라 <b>"세션이 없다"</b> 여야
     * 운영 중에 원인을 가를 수 있다.
     *
     * <p>{@code sellerId}·{@code branchId} 는 계정 분리(결정 10.5) 전까지 null 이다. 그러면
     * 정책이 "판단할 수 없다" 로 거부한다 — 통과가 아니라 거부다.
     */
    private AccessPolicy.Target targetOf(String sessionId) {
        if (sessionId == null) {
            return AccessPolicy.Target.aggregate();
        }
        return sessions.findById(sessionId)
                .map(s -> AccessPolicy.Target.session(s.id(), s.sellerId(), s.branchId()))
                .orElseGet(() -> AccessPolicy.Target.session(null, null, null));
    }

    /** 인증 자체가 없을 때. 401/403 매핑은 GlobalExceptionHandler가 한다. */
    public static class AccessDeniedNotAuthenticatedException extends RuntimeException {
        public AccessDeniedNotAuthenticatedException(String message) {
            super(message);
        }
    }
}
