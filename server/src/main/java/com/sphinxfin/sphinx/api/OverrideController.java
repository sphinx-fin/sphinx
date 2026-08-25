package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.api.dto.OverrideResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

/** F-GTE-002 적색 오버라이드. 소유: 강희진. 사유 30자 미만 거부는 API에서도 강제 */
@RestController
@RequestMapping("/sessions/{sid}/override")
public class OverrideController {

    public record OverrideRequest(@Size(min = 30) String reason) {}

    @PostMapping
    public ApiResponse<OverrideResponse> request(@PathVariable String sid,
                                                 @Valid @RequestBody OverrideRequest body) {
        // TODO(강희진): MGR 알림 + COMPL 자동 통보 + 불변 기록
        return ApiResponse.ok(new OverrideResponse("PENDING_APPROVAL"));
    }

    @PostMapping("/approve")
    public ApiResponse<OverrideResponse> approve(@PathVariable String sid,
                                                 @Valid @RequestBody OverrideRequest body) {
        return ApiResponse.ok(new OverrideResponse("APPROVED"));
    }
}
