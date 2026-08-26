package com.sphinxfin.sphinx.security;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * F-CMN-002 접근 정책. 소유: 정세현
 *
 * ── F-CMN-002 소유 경계 ──────────────────────────────────────────────
 * 이 기능은 두 덩어리다. 물리적 파일 경계를 이렇게 나눈다:
 *
 *   정세현  역할 정의({@link Role}) · 정책({@code rbac_policy.yaml}) · 이 로더
 *           · 감사 로그({@code evidence/AuditLog})
 *   강희진  {@link SecurityConfig}의 필터체인 등록 · 컨트롤러 {@code @PreAuthorize}
 *           · {@link AuditInterceptor} 등록
 *
 * 정책은 yaml에만 있고 Java 상수로 중복 정의하지 않는다. 그래야 정세현이 강희진이
 * 열지 않는 파일을 소유하고, 강희진의 어노테이션은 action 이름만 참조한다.
 * ────────────────────────────────────────────────────────────────────
 *
 * 역할 확인만으로는 역이용을 막지 못한다. 범위(scope)까지 함께 봐야 SELLER 권한으로
 * 집계가 열리는 구멍이 닫힌다 — 명세서 0.4 / 기획서 7-4, ADR-001 참고.
 *
 * <h2>판단할 수 없으면 거부한다</h2>
 *
 * <p>세 갈래 중 하나로 끝난다: <b>허용 · 거부 · 판단 불가</b>. 그런데 반환값은 둘뿐이라
 * <b>판단 불가는 거부로 접는다.</b> 예컨대 {@code scope: branch}인데 행위자의 소속 지점을
 * 모르면 비교할 대상이 없다 — 그때 통과시키면 "지점 범위로 제한했다"는 주장이 거짓이 된다.
 *
 * <p>이건 안전한 방향이지만 <b>"막고 있다"와 "판단할 수 없다"가 로그에서 같아 보인다</b>는
 * 대가가 있다. 계정에 지점이 안 실린 채 배포되면 MGR 기능이 전부 403이 되고, 원인이 정책
 * 위반처럼 보인다. 그래서 거부 사유를 {@link Decision}으로 함께 돌려준다 — {@code permits}는
 * 불리언만 주지만, 진단이 필요한 쪽은 {@link #decide}를 쓴다.
 */
public class AccessPolicy {

    private final RbacPolicyFile policyFile;

    public AccessPolicy(RbacPolicyFile policyFile) {
        this.policyFile = policyFile;
    }

    /** 요청 주체. actorId는 감사 로그의 행위자와 같은 값이어야 한다. */
    public record Actor(String actorId, Role role, String branchId) {}

    /** 접근 대상의 귀속. 개별 세션이 아닌 집계 요청은 sessionId·ownerId가 없다. */
    public record Target(String sessionId, String ownerId, String branchId) {

        /** 집계 요청 — 귀속 주체가 없다. scope: org 인 action에만 허용된다. */
        public static Target aggregate() {
            return new Target(null, null, null);
        }

        boolean isAggregate() {
            return sessionId == null && ownerId == null && branchId == null;
        }
    }

    /** 정책이 정의한 데이터 범위. yaml의 {@code scope} 값과 1:1. */
    public enum Scope {
        OWN_SESSION, BRANCH, ORG;

        static Optional<Scope> of(String raw) {
            return switch (raw == null ? "" : raw) {
                case "own_session" -> Optional.of(OWN_SESSION);
                case "branch" -> Optional.of(BRANCH);
                case "org" -> Optional.of(ORG);
                default -> Optional.empty();
            };
        }
    }

    /** yaml의 그랜트 한 줄 — {@code { roles: [...], scope: ... }}. */
    public record Grant(Set<Role> roles, Scope scope) {}

    /**
     * 판단 결과와 사유. 사유가 필요한 이유는 위 주석대로 <b>거부와 판단 불가가 구별되어야</b>
     * 하기 때문이다. 감사 로그는 결과 코드만 남기지만, 운영 중에 403이 쏟아질 때 원인을
     * 가르는 것은 이 사유다.
     */
    public record Decision(boolean allowed, String reason) {

        static Decision allow(Scope scope) {
            return new Decision(true, "허용 (scope=" + scope + ")");
        }

        static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    /**
     * action이 허용되는가. rbac_policy.yaml이 유일한 근거.
     * 역할이 맞아도 scope가 어긋나면 거부한다 (own_session / branch / org).
     */
    public boolean permits(Actor actor, String action, Target target) {
        return decide(actor, action, target).allowed();
    }

    /** {@link #permits}와 같은 판단이되 사유를 함께 낸다. */
    public Decision decide(Actor actor, String action, Target target) {
        if (actor == null || actor.role() == null) {
            return Decision.deny("행위자를 알 수 없다 — 인증 주체가 없으면 판단하지 않는다");
        }
        List<Grant> grants = policyFile.grants(action);
        if (grants.isEmpty()) {
            // 미정의 action은 오타이거나 정책이 아직 없는 것이다. 둘 다 통과시킬 이유가 없다.
            return Decision.deny("정책에 없는 action 이다: " + action);
        }

        List<Grant> forRole = grants.stream().filter(g -> g.roles().contains(actor.role())).toList();
        if (forRole.isEmpty()) {
            // ADR-001의 요지 — 역할에 그랜트가 아예 없으면 범위를 볼 것도 없다.
            return Decision.deny(actor.role() + " 에게 " + action + " 그랜트가 없다");
        }

        // 같은 action에 역할별로 다른 scope가 붙는다(집계는 COMPL=org, MGR=branch).
        // 하나라도 통과하면 허용이고, 전부 막히면 마지막 사유를 낸다.
        Decision lastDenial = null;
        for (Grant grant : forRole) {
            Decision decision = withinScope(actor, grant.scope(), target);
            if (decision.allowed()) {
                return decision;
            }
            lastDenial = decision;
        }
        return lastDenial;
    }

    /**
     * 허용된 범위. 집계 서비스가 <b>질의를 어디까지 좁힐지</b> 알아야 하므로 불리언으로는
     * 부족하다 — {@code scope: branch}인 MGR에게 org 전체를 주면 정책이 통과시킨 의미가 없다
     * (결정 5.10: scope는 요청 파라미터가 아니라 서버가 정해 응답에 싣는 값이다).
     */
    public Optional<Scope> grantedScope(Actor actor, String action, Target target) {
        if (actor == null || actor.role() == null) {
            return Optional.empty();
        }
        return policyFile.grants(action).stream()
                .filter(g -> g.roles().contains(actor.role()))
                .filter(g -> withinScope(actor, g.scope(), target).allowed())
                .map(Grant::scope)
                .findFirst();
    }

    private static Decision withinScope(Actor actor, Scope scope, Target target) {
        if (target == null) {
            return Decision.deny("대상을 알 수 없다");
        }
        return switch (scope) {
            case ORG -> Decision.allow(scope);

            case OWN_SESSION -> {
                if (target.isAggregate()) {
                    // 집계에는 귀속 주체가 없다. own_session 그랜트로는 닿을 수 없다 — 이게
                    // "SELLER 권한으로 집계가 열리는 구멍"을 닫는 지점이다(기획 7-4).
                    yield Decision.deny("own_session 그랜트로 집계에 접근할 수 없다");
                }
                if (target.ownerId() == null) {
                    yield Decision.deny("세션의 진행 주체를 알 수 없어 own_session 을 판단할 수 없다");
                }
                yield target.ownerId().equals(actor.actorId())
                        ? Decision.allow(scope)
                        : Decision.deny("자기 세션이 아니다");
            }

            case BRANCH -> {
                if (actor.branchId() == null) {
                    // 계정에 지점이 안 실린 상태. 통과시키면 "지점으로 제한했다"가 거짓이 된다.
                    yield Decision.deny("행위자의 소속 지점을 알 수 없어 branch 를 판단할 수 없다 (결정 10.5)");
                }
                if (target.isAggregate()) {
                    // 집계에서 branch 는 비교할 대상이 아니라 질의를 좁히는 값이다.
                    // 지점을 아는 것으로 충분하고, 좁히는 것은 grantedScope 를 받은 쪽이 한다.
                    yield Decision.allow(scope);
                }
                if (target.branchId() == null) {
                    yield Decision.deny("세션의 지점을 알 수 없어 branch 를 판단할 수 없다");
                }
                yield actor.branchId().equals(target.branchId())
                        ? Decision.allow(scope)
                        : Decision.deny("다른 지점의 세션이다");
            }
        };
    }
}
