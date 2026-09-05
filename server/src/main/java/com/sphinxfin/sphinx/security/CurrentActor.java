package com.sphinxfin.sphinx.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 지금 요청을 보낸 사람. 소유: 강희진
 *
 * <p>{@link AccessGuard#can} 이 쓰는 {@code currentActor()} 와 다르다 — 그쪽은 <b>인가 판단</b>
 * 용이라 주체를 못 읽으면 예외를 던진다. 이건 <b>귀속 기록</b>용이라 못 읽으면 {@code null} 을
 * 돌려준다. 세션을 만들 때 인증이 없다고 생성을 막으면 dev 프로파일(permitAll)에서 화면이
 * 전부 죽는다.
 *
 * <p>❗<b>요청 본문에서 받지 않는 것이 요지다.</b> 세션의 진행 주체를 본문으로 받으면
 * 판매자가 <b>자기가 아닌 사람을 소유자로 적을 수 있고</b>, 그러면 {@code own_session} 범위가
 * 견제가 아니라 자기 신고가 된다. 오버라이드 승인자를 본문에서 뺀 것과 같은 이유다.
 *
 * <p>지금은 역할별 계정이 없어(결정 10.5, 8/29) 대개 {@code null} 이다. 그래도 배선을 먼저
 * 두는 이유는, 계정이 생긴 뒤에 귀속을 붙이면 <b>그 사이에 만들어진 세션이 영원히 주인 없는
 * 상태</b>로 남기 때문이다 — 그 세션들은 나중에 {@code own_session} 으로 아무도 못 읽는다.
 */
@Component
public class CurrentActor {

    private final DemoAccountsFile roster;

    public CurrentActor(DemoAccountsFile roster) {
        this.roster = roster;
    }

    /** 인증 주체의 식별자. 미인증·익명이면 null. */
    public String actorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (unidentified(auth)) {
            return null;
        }
        return auth.getName();
    }

    /**
     * 소속 지점. <b>명부에서 읽는다</b>(결정 10.5 · {@code demo_accounts.yaml}).
     *
     * <p>❗<b>요청에서 받지 않는다.</b> 지점을 헤더·쿼리로 받으면 MGR 이 남의 지점을 적어
     * 보내는 것으로 {@code scope: branch} 를 우회한다. 귀속은 언제나 계정에서 온다 —
     * {@code AccessGuard.targetOf} 가 세션에서 꺼내는 것과 같은 규약이다.
     *
     * <p>명부에 없는 계정이면 null 이다. 그러면 {@code scope: branch} 판단이 성립하지 않아
     * 정책이 <b>거부</b>한다 — 통과가 아니라 거부라 안전한 방향이지만, "막고 있다" 가 아니라
     * <b>"판단할 수 없다"</b> 는 뜻이다. 그 둘이 로그에서 같아 보이면 안 되므로
     * {@code AccessPolicy} 가 사유를 갈라 적는다.
     *
     * <p>전에는 <b>언제나 null</b> 이었다. 그래서 MGR 이 지점 범위 집계에 아예 못 닿았고
     * (실측: 403), 그 상태가 "정책이 막았다" 처럼 보였다.
     */
    public String branchId() {
        String id = actorId();
        return id == null ? null : roster.byId(id).map(a -> a.branchId()).orElse(null);
    }

    /**
     * 행위자를 특정할 수 없는 인증 상태인가 — null 만이 아니다(#407 리뷰 ①).
     *
     * AnonymousAuthenticationFilter 가 빈 컨텍스트에 익명 토큰을 채우므로, 미인증 요청이
     * 항상 null 로 오지 않는다: getName()="anonymousUser" · isAuthenticated()=true 로 온다.
     * "auth != null 이면 이름이 있다"는 전제로 그 이름을 기록에 남기면, 승인자 불명 승인이
     * "anonymousUser" 라는 이름을 달고 불변 기록에 남는다. 세 경우를 한 판정으로 묶는다.
     */
    public static boolean unidentified(Authentication auth) {
        return auth == null || !auth.isAuthenticated() || isAnonymous(auth);
    }

    private static boolean isAnonymous(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ANONYMOUS".equals(a.getAuthority()));
    }
}
