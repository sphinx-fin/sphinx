package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.api.dto.ProductSummary;
import com.sphinxfin.sphinx.api.dto.RiskItemsResponse;
import com.sphinxfin.sphinx.api.dto.UploadResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

/** 문서 업로드·추출 API. 소유: 강희진 (파싱: 정세현/ai-service, 추출: 윤지석/ai-service) */
@RestController
@RequestMapping("/products")
public class ProductController {

    /**
     * S-02 상품 선택 목록. 기존 /products/* 는 전부 productId 를 이미 알아야 부를 수 있어서
     * "고를 수 있는 상품"을 주는 경로가 없었다.
     * TODO(강희진): 업로드된 상품을 DB에서 조회 (F-EXT-001 연결 후). 지금은 데모 2종 목.
     */
    @GetMapping
    public ApiResponse<List<ProductSummary>> list() {
        return ApiResponse.ok(MockData.PRODUCTS);
    }

    @PostMapping("/documents")
    public ApiResponse<UploadResponse> upload(@RequestParam MultipartFile file,
                                              @RequestParam(defaultValue = "ELS") String productType) {
        // TODO(강희진): ai-service POST /internal/parse 프록시 (F-EXT-001)
        return ApiResponse.ok(new UploadResponse("mock-els-001", "parsed"));
    }

    @PostMapping("/{productId}/extract")
    public ApiResponse<RiskItemsResponse> extract(@PathVariable String productId) {
        // TODO(강희진): ai-service POST /internal/extract 호출 (F-EXT-002)
        return ApiResponse.ok(new RiskItemsResponse(MockData.RISK_ITEMS));
    }

    @GetMapping("/{productId}/risk-items")
    public ApiResponse<RiskItemsResponse> riskItems(@PathVariable String productId) {
        return ApiResponse.ok(new RiskItemsResponse(MockData.RISK_ITEMS));
    }
}
