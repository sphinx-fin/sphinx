package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.api.dto.OverrideResponse;
import com.sphinxfin.sphinx.core.OverrideService;
import com.sphinxfin.sphinx.core.Session;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
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
    @PostMapping("/approve")
    public ApiResponse<OverrideResponse> approve(@PathVariable String sid, Authentication auth) {
        String approver = (auth != null) ? auth.getName() : "MGR(데모-미인증)";
        Session session = overrideService.approve(sid, approver);
        return ApiResponse.ok(new OverrideResponse(session.overrideStatus().name()));
    }
}
