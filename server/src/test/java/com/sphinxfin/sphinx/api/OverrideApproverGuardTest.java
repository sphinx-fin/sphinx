package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.core.session.OverrideService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 승인자 불명 가드 (ADR-002). 소유: 강희진
 *
 * enforce=true 에서 auth==null 로 컨트롤러까지 오는 것은 정상 경로가 아니라 설정 사고다
 * (@PreAuthorize 가 미인증을 401 로 먼저 막는다). 그 사고가 "MGR(데모-미인증)" 폴백으로
 * 흘러 불변 기록에 승인자 불명 승인이 남으면 ADR-002 의 견제 장치가 무력해진다 — 기록
 * 대신 죽는 것을 단정한다. enforce=false(로컬 개발)는 기존 폴백을 유지한다.
 */
@DisplayName("오버라이드 승인자 불명 가드 (ADR-002)")
class OverrideApproverGuardTest {

    @Test
    @DisplayName("enforce=true + 미인증 → 기록 없이 실패한다(폴백 승인자가 불변 기록에 못 남는다)")
    void enforcedAndUnauthenticatedFailsWithoutRecording() {
        OverrideService service = mock(OverrideService.class);
        OverrideController controller = new OverrideController(service, true);

        assertThatThrownBy(() -> controller.approve("S-1", null))
                .isInstanceOf(IllegalStateException.class);
        // 핵심 단정 — 예외가 났다는 것보다 **승인이 기록되지 않았다**는 것이 요지다.
        verify(service, never()).approve(anyString(), anyString());
    }

    @Test
    @DisplayName("enforce=false(로컬 개발) + 미인증 → 미인증임을 말하는 폴백으로 승인된다(기존 동작)")
    void devModeKeepsHonestFallback() {
        OverrideService service = mock(OverrideService.class,
                Mockito.RETURNS_DEEP_STUBS);   // session.overrideStatus().name() 체인만 필요
        OverrideController controller = new OverrideController(service, false);

        controller.approve("S-1", null);

        verify(service).approve("S-1", "MGR(데모-미인증)");
    }
}
