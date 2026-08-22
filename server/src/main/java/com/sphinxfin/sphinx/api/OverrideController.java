package com.sphinxfin.sphinx.api;

import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** F-GTE-002 적색 오버라이드. 소유: 강희진. 사유 30자 미만 거부는 API에서도 강제 */
@RestController
@RequestMapping("/sessions/{sid}/override")
public class OverrideController {

    public record OverrideRequest(@Size(min = 30) String reason) {}

    @PostMapping
    public Map<String, String> request(@PathVariable String sid, @RequestBody OverrideRequest body) {
        // TODO(강희진): MGR 알림 + COMPL 자동 통보 + 불변 기록
        return Map.of("status", "PENDING_APPROVAL");
    }

    @PostMapping("/approve")
    public Map<String, String> approve(@PathVariable String sid, @RequestBody OverrideRequest body) {
        return Map.of("status", "APPROVED");
    }
}
