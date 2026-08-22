package com.sphinxfin.sphinx.security;

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
 * 집계가 열리는 구멍이 닫힌다 — 명세서 0.4 / 기획서 7-4, ADR-0001 참고.
 */
public class AccessPolicy {

    /** 요청 주체. actorId는 감사 로그의 행위자와 같은 값이어야 한다. */
    public record Actor(String actorId, Role role, String branchId) {}

    /** 접근 대상의 귀속. 개별 세션이 아닌 집계 요청은 sessionId·ownerId가 없다. */
    public record Target(String sessionId, String ownerId, String branchId) {

        /** 집계 요청 — 귀속 주체가 없다. scope: org 인 action에만 허용된다. */
        public static Target aggregate() {
            return new Target(null, null, null);
        }
    }

    /**
     * action이 허용되는가. rbac_policy.yaml이 유일한 근거.
     * 역할이 맞아도 scope가 어긋나면 거부한다 (own_session / branch / org).
     */
    public boolean permits(Actor actor, String action, Target target) {
        // TODO(정세현): rbac_policy.yaml 로드 (gate_rules.yaml과 동일한 방식)
        //   1) permissions[action].roles 에 actor.role 포함 여부
        //   2) permissions[action].scope 검사:
        //      own_session → target.ownerId == actor.actorId
        //      branch      → target.branchId == actor.branchId
        //      org         → 통과 (집계는 개인 식별자 미포함이어야 한다)
        //   3) action이 정의돼 있지 않으면 거부 — 기본값은 항상 deny
        throw new UnsupportedOperationException("not implemented");
    }
}
