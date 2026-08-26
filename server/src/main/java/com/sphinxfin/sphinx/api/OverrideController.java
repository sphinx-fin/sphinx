package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.api.dto.OverrideResponse;
import com.sphinxfin.sphinx.core.OverrideService;
import com.sphinxfin.sphinx.core.Session;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * F-GTE-002 적색 오버라이드. 소유: 강희진
 * 사유 30자 미만 거부는 API에서도 강제한다(UI 검증과 이중, ADR-002 견제 장치).
 */
@RestController
@RequestMapping("/sessions/{sid}/override")
@RequiredArgsConstructor
public class OverrideController {

    private final OverrideService overrideService;

    public record OverrideRequest(@Size(min = 30) String reason) {}

    /** 판매자의 적색 진행 요청(사유 포함). 적색이 아니면 409 OVERRIDE_NOT_ELIGIBLE. */
    @PreAuthorize("@accessGuard.can('override:request', #sid)")
    @PostMapping
    public ApiResponse<OverrideResponse> request(@PathVariable String sid,
                                                 @Valid @RequestBody OverrideRequest body) {
        Session session = overrideService.request(sid, body.reason());
        return ApiResponse.ok(new OverrideResponse(session.overrideStatus().name()));
    }

    /**
     * 관리자(MGR) 승인 — 사유는 요청 시 이미 기록됐으므로 본문이 필요 없다.
     * 승인자는 인증 주체에서 얻는다. 역할 제약(MGR, ADR-002)은 F-CMN-002에서 action
     * 'override:approve' 어노테이션으로 붙는다 — 지금 SecurityConfig는 permitAll(목)이다.
     */
    @PreAuthorize("@accessGuard.can('override:approve', #sid)")
    @PostMapping("/approve")
    public ApiResponse<OverrideResponse> approve(@PathVariable String sid, Authentication auth) {
        // TODO(강희진): 역할별 계정 분리(10.5, 8/29 설계 예정)가 붙으면 이 폴백을 **실패로
        //   바꾼다.** 지금은 SecurityConfig 가 permitAll() 이라 미인증이 정상 상태이고,
        //   가짜 이름 대신 미인증임을 말하는 문자열을 남기는 게 정직하다. 계정 분리 후에는
        //   auth == null 이 데모 상태가 아니라 설정 사고이며, 승인자를 특정할 수 없는 승인이
        //   지워지지 않는 기록으로 남으면 ADR-002 의 견제 장치가 무력해진다.
        String approver = (auth != null) ? auth.getName() : "MGR(데모-미인증)";
        Session session = overrideService.approve(sid, approver);
        return ApiResponse.ok(new OverrideResponse(session.overrideStatus().name()));
    }
}
