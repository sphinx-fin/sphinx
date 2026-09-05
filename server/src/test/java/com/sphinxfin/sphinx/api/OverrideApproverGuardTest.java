package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.core.session.OverrideService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 승인자 불명 가드 (ADR-002). 소유: 강희진
 *
 * enforce=true 에서 승인자를 특정할 수 없는 요청이 컨트롤러까지 오는 것은 정상 경로가
 * 아니라 설정 사고다(@PreAuthorize 가 먼저 401 로 막는다). 그 사고가 폴백으로 흘러 불변
 * 기록에 승인자 불명 승인이 남으면 ADR-002 의 견제 장치가 무력해진다 — 기록 대신 죽는
 * 것을 단정한다.
 *
 * ❗null 케이스만으로는 부족하다(#407 리뷰 ①) — 그 사고에서 실제로 오는 것은 null 이
 * 아니라 AnonymousAuthenticationFilter 가 채운 **익명 토큰**이다(getName()="anonymousUser"
 * · isAuthenticated()=true). null 만 걸면 "anonymousUser" 가 승인자로 기록된다. 그래서
 * 익명 토큰 케이스를 실제 토큰 타입으로 재고, dev 폴백도 익명에서 가짜 이름 대신 미인증
 * 문자열을 남기는 것까지 단정한다.
 */
@DisplayName("오버라이드 승인자 불명 가드 (ADR-002)")
class OverrideApproverGuardTest {

    private static Authentication anonymous() {
        // AnonymousAuthenticationFilter 가 만드는 실제 모양 그대로 — isAuthenticated()=true 다.
        return new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    }

    @Test
    @DisplayName("❗enforce=true + 익명 토큰 → 기록 없이 실패 — null 검사만으로는 못 잡는 그 사고다 (#407 ①)")
    void enforcedAndAnonymousFailsWithoutRecording() {
        OverrideService service = mock(OverrideService.class);
        OverrideController controller = new OverrideController(service, true);

        assertThatThrownBy(() -> controller.approve("S-1", anonymous()))
                .isInstanceOf(OverrideController.UnidentifiedApproverException.class);
        // 핵심 단정 — "anonymousUser" 가 승인자로 불변 기록에 닿지 않았다.
        verify(service, never()).approve(anyString(), anyString());
    }

    @Test
    @DisplayName("enforce=true + null → 기록 없이 실패")
    void enforcedAndNullFailsWithoutRecording() {
        OverrideService service = mock(OverrideService.class);
        OverrideController controller = new OverrideController(service, true);

        assertThatThrownBy(() -> controller.approve("S-1", null))
                .isInstanceOf(OverrideController.UnidentifiedApproverException.class);
        verify(service, never()).approve(anyString(), anyString());
    }

    @Test
    @DisplayName("enforce=false(로컬) + null → 미인증임을 말하는 폴백으로 승인(기존 동작)")
    void devModeKeepsHonestFallback() {
        OverrideService service = mock(OverrideService.class, Mockito.RETURNS_DEEP_STUBS);
        OverrideController controller = new OverrideController(service, false);

        controller.approve("S-1", null);

        verify(service).approve("S-1", "MGR(데모-미인증)");
    }

    @Test
    @DisplayName("enforce=false + 익명 토큰 → 'anonymousUser' 가 아니라 미인증 문자열이 기록된다")
    void devModeAnonymousRecordsHonestFallbackNotAnonymousUser() {
        // 익명 토큰의 getName() 은 "anonymousUser" 다 — 그 가짜 이름이 기록에 남으면
        // 미인증 폴백의 "정직함"(미인증임이 문면에 보인다)이 사라진다.
        OverrideService service = mock(OverrideService.class, Mockito.RETURNS_DEEP_STUBS);
        OverrideController controller = new OverrideController(service, false);

        controller.approve("S-1", anonymous());

        verify(service).approve("S-1", "MGR(데모-미인증)");
    }
}
