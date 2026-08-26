package com.sphinxfin.sphinx.security;

import com.sphinxfin.sphinx.core.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

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
    private final boolean enforce;

    public AccessGuard(AccessPolicy policy,
                       SessionRepository sessions,
                       @Value("${sphinx.security.enforce:false}") boolean enforce) {
        this.policy = policy;
        this.sessions = sessions;
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
        return policy.permits(actor, action, targetOf(resourceId));
    }

    /** 세션과 무관한 action(집계 등). */
    public boolean can(String action) {
        return can(action, null);
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
        return new AccessPolicy.Actor(auth.getName(), role, branchOf(auth));
    }

    /**
     * 소속 지점. 계정 분리(10.5) 전까지 알 수 없다.
     * null이면 scope=branch 판단이 성립하지 않으므로 정책이 거부한다 — 통과가 아니라 거부다.
     */
    private String branchOf(Authentication auth) {
        return null;
    }

    /**
     * 세션 귀속.
     *
     * ❗ ownerId·branchId를 채울 수 없다 — {@link Session}에 그 필드가 없다. 그래서 지금은
     * rbac_policy.yaml의 scope 중 own_session·branch를 **평가할 근거가 존재하지 않는다**.
     * 정책이 그 둘을 요구하면 null과 비교하게 되어 거부된다(통과가 아니라 거부라 안전한
     * 방향이지만, "막고 있다"가 아니라 "판단할 수 없다"는 뜻이다).
     *
     * TODO(강희진): 세션에 진행 주체(sellerId)·지점(branchId)을 싣는다. 요청 본문이 아니라
     *   인증 주체에서 얻어야 한다 — 본문으로 받으면 자기가 자기를 소유자로 적을 수 있고,
     *   그러면 own_session이 견제가 아니게 된다(오버라이드 승인자를 본문에서 뺀 것과 같은 이유).
     *   따라서 역할별 계정 분리(결정 10.5)가 선행이다.
     */
    private AccessPolicy.Target targetOf(String sessionId) {
        if (sessionId == null) {
            return AccessPolicy.Target.aggregate();
        }
        boolean exists = sessions.findById(sessionId).isPresent();
        return new AccessPolicy.Target(exists ? sessionId : null, null, null);
    }

    /** 인증 자체가 없을 때. 401/403 매핑은 GlobalExceptionHandler가 한다. */
    public static class AccessDeniedNotAuthenticatedException extends RuntimeException {
        public AccessDeniedNotAuthenticatedException(String message) {
            super(message);
        }
    }
}
