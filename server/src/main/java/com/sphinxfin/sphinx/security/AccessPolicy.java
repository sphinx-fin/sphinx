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
 * 열지 않는 파일을 소유하고, 강희진의 어노테이션은 역할 이름만 참조한다.
 * ────────────────────────────────────────────────────────────────────
 */
public class AccessPolicy {

    /** 이 역할이 해당 액션을 수행할 수 있는가. rbac_policy.yaml이 유일한 근거. */
    public boolean permits(Role role, String action) {
        // TODO(정세현): rbac_policy.yaml 로드 (gate_rules.yaml과 동일한 방식)
        throw new UnsupportedOperationException("not implemented");
    }
}
