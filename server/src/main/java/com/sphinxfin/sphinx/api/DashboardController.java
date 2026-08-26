package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** 오해 지도 집계 API. 라우팅: 강희진 / 집계 로직: 정세현 (F-DSH-001~002) */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    /**
     * 봉투만 씌운다. 안의 셀 스키마는 집계 소유자 몫이라 여기서 타입을 굳히지 않는다
     * (#46 에서 논의 중). 봉투는 프론트 전역 규약이므로 스키마와 무관하게 지금 맞춘다.
     */
    @PreAuthorize("@accessGuard.canAggregate('aggregate:heatmap:read')")
    @GetMapping("/heatmap")
    public ApiResponse<Map<String, Object>> heatmap() {
        // TODO(정세현): AggregateService — 개인 식별자 미전달, 소표본(n<30) 마스킹
        return ApiResponse.ok(Map.of("synthetic", true, "cells", List.of(
                Map.of("product", "mock-els-001", "item", "원금손실 조건", "misrate", 0.41, "n", 100),
                Map.of("product", "mock-els-001", "item", "조기상환 구조", "misrate", 0.33, "n", 100),
                Map.of("product", "mock-els-001", "item", "예금자보호", "misrate", 0.28, "n", 100))));
    }
}
