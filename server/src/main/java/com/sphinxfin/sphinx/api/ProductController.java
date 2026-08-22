package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.domain.RiskItem;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

/** 문서 업로드·추출 API. 소유: 강희진 (파싱: 정세현/ai-service, 추출: 윤지석/ai-service) */
@RestController
@RequestMapping("/products")
public class ProductController {

    @PostMapping("/documents")
    public Map<String, String> upload(@RequestParam MultipartFile file,
                                      @RequestParam(defaultValue = "ELS") String productType) {
        // TODO(강희진): ai-service POST /internal/parse 프록시 (F-EXT-001)
        return Map.of("productId", "mock-els-001", "status", "parsed");
    }

    @PostMapping("/{productId}/extract")
    public Map<String, List<RiskItem>> extract(@PathVariable String productId) {
        // TODO(강희진): ai-service POST /internal/extract 호출 (F-EXT-002)
        return Map.of("items", MockData.RISK_ITEMS);
    }

    @GetMapping("/{productId}/risk-items")
    public Map<String, List<RiskItem>> riskItems(@PathVariable String productId) {
        return Map.of("items", MockData.RISK_ITEMS);
    }
}
