package com.sphinxfin.sphinx.api;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** 오해 지도 집계 API. 라우팅: 강희진 / 집계 로직: 정세현 (F-DSH-001~002) */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    @GetMapping("/heatmap")
    public Map<String, Object> heatmap() {
        // TODO(정세현): AggregateService — 개인 식별자 미전달, 소표본(n<30) 마스킹
        return Map.of("synthetic", true, "cells", List.of(
                Map.of("product", "mock-els-001", "item", "원금손실 조건", "misrate", 0.41, "n", 100),
                Map.of("product", "mock-els-001", "item", "조기상환 구조", "misrate", 0.33, "n", 100),
                Map.of("product", "mock-els-001", "item", "예금자보호", "misrate", 0.28, "n", 100)));
    }
}
