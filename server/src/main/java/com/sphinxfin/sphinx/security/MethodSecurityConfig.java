package com.sphinxfin.sphinx.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * 컨트롤러 @PreAuthorize를 실제로 평가하게 만든다. 소유: 강희진
 *
 * 이게 없으면 애너테이션이 붙어 있어도 아무 일도 일어나지 않는다 — 코드를 읽는 사람은
 * 막혀 있다고 읽는데 실제로는 전부 통과하는, 가장 나쁜 상태가 된다.
 *
 * 평가는 항상 켜 두고, 실제 차단 여부는 {@link AccessGuard}의
 * {@code sphinx.security.enforce} 스위치가 정한다. 그래야 "애너테이션이 도는가"와
 * "정책이 막는가"가 분리돼서, 배선이 빠진 것과 정책이 미구현인 것을 구별할 수 있다.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {

    /**
     * {@link AccessPolicy}를 빈으로 등록한다.
     *
     * 클래스 자체는 정세현 소유라 그 파일에 @Component를 붙이지 않는다 — 등록은 강희진 몫이고
     * (CLAUDE.md F-CMN-002 분담), 그 경계를 지키면 정책 파일과 등록 방식이 서로를 안 건드린다.
     */
    @Bean
    public AccessPolicy accessPolicy(RbacPolicyFile policyFile) {
        // 정책 파일은 읽기 전용 로더(RbacPolicyFile)가 읽고 해석은 AccessPolicy가 한다.
        // 두 곳에서 파싱하면 같은 yaml 의 두 해석이 생긴다 — 결정 10.5 구현 시 추가된 의존이다.
        return new AccessPolicy(policyFile);
    }
}
