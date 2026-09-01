package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.api.dto.ProductSummary;
import com.sphinxfin.sphinx.api.dto.RiskItemsResponse;
import com.sphinxfin.sphinx.api.dto.UploadResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

/**
 * 문서 업로드·추출 API. 소유: 강희진 (파싱: 정세현/ai-service, 추출: 윤지석/ai-service)
 *
 * <h2>읽기와 등록·추출을 가른다 (이슈 #69 · 결정 10.36)</h2>
 *
 * <p>네 경로가 두 action 으로 갈린다. 성격이 다르기 때문이다 — <b>{@code extract} 는 게이트가
 * 물을 항목을 만든다.</b> 항목이 곧 질문이고 판정의 분모이므로, 항목을 줄이면 <b>게이트가
 * 조용히 느슨해진다.</b> 기획 7-4 가 막으려는 경로 중 가장 짧은 것이라, 등록·추출을
 * 판매 라인에서 떼어 놓는다 — SELLER·MGR 은 읽기만 한다.
 *
 * <pre>
 *   GET  /products                    product:read    SELLER·MGR·COMPL·ADMIN
 *   GET  /products/{id}/risk-items    product:read
 *   POST /products/documents          product:manage  ADMIN 만 · audited
 *   POST /products/{id}/extract       product:manage
 * </pre>
 *
 * <p>❗<b>{@code canAggregate} 를 쓴다 — 그런데 오늘은 {@code can(action, productId)} 로 써도
 * 답이 같다.</b> 두 action 다 {@code scope: org} 이고 {@code AccessPolicy} 의 ORG 갈래는
 * 대상을 안 본다({@code case ORG -> allow}). 실제로 바꿔서 돌려 봤고 테스트가 하나도 안 깨진다.
 *
 * <p>그래도 {@code canAggregate} 인 이유는 셋이다. (1) {@code can(action, id)} 는 그 값을
 * <b>세션 ID 로 조회</b>하므로 요청마다 헛되이 DB 를 친다. (2) 문면이 <i>"상품 ID 는 세션이다"</i>
 * 라고 말하게 되어 다음 사람이 그렇게 읽는다. (3) ❗<b>이게 진짜 이유다</b> — 이 action 의
 * scope 가 언젠가 {@code org} 를 벗어나면, 잘못된 헬퍼는 <b>상품 ID 를 세션 소유자와 대조하기
 * 시작한다.</b> 그날 나는 실패는 "권한이 없다" 가 아니라 <i>"대상을 알 수 없다"</i> 이고,
 * 원인이 이 줄까지 거슬러 오지 않는다.
 *
 * <p>즉 <b>지금 재는 단정으로는 이 선택이 안 잡힌다.</b> 잡히게 하려면 scope 를 바꿔야 하는데
 * 그건 이 PR 의 변경이 아니다 — 대신 왜 이렇게 골랐는지를 여기 적어 둔다.
 */
@RestController
@RequestMapping("/products")
public class ProductController {

    /**
     * S-02 상품 선택 목록. 기존 /products/* 는 전부 productId 를 이미 알아야 부를 수 있어서
     * "고를 수 있는 상품"을 주는 경로가 없었다.
     * TODO(강희진): 업로드된 상품을 DB에서 조회 (F-EXT-001 연결 후). 지금은 데모 2종 목.
     */
    @PreAuthorize("@accessGuard.canAggregate('product:read')")
    @GetMapping
    public ApiResponse<List<ProductSummary>> list() {
        return ApiResponse.ok(MockData.PRODUCTS);
    }

    /**
     * 문서 업로드. <b>audited</b> 다 — 누가 언제 상품을 등록했는지가 남아야 한다(결정 10.36).
     * 여기서 올린 문서가 {@code extract} 를 거쳐 게이트의 질문 목록이 된다.
     */
    @PreAuthorize("@accessGuard.canAggregate('product:manage')")
    @PostMapping("/documents")
    public ApiResponse<UploadResponse> upload(@RequestParam MultipartFile file,
                                              @RequestParam(defaultValue = "ELS") String productType) {
        // TODO(강희진): ai-service POST /internal/parse 프록시 (F-EXT-001)
        return ApiResponse.ok(new UploadResponse("mock-els-001", "parsed"));
    }

    /**
     * 위험항목 추출. <b>게이트의 분모를 바꾸는 자리</b>라 ADMIN 전용이고 audited 다.
     * 판매 라인이 자기가 답해야 할 질문의 목록을 편집할 수 있으면 안 된다(ADR-001 과 같은 결).
     */
    @PreAuthorize("@accessGuard.canAggregate('product:manage')")
    @PostMapping("/{productId}/extract")
    public ApiResponse<RiskItemsResponse> extract(@PathVariable String productId) {
        // TODO(강희진): ai-service POST /internal/extract 호출 (F-EXT-002)
        return ApiResponse.ok(new RiskItemsResponse(MockData.RISK_ITEMS));
    }

    /** 추출된 항목 조회. 면담이 부르는 경로라 SELLER 가 닿아야 한다({@code product:read}). */
    @PreAuthorize("@accessGuard.canAggregate('product:read')")
    @GetMapping("/{productId}/risk-items")
    public ApiResponse<RiskItemsResponse> riskItems(@PathVariable String productId) {
        return ApiResponse.ok(new RiskItemsResponse(MockData.RISK_ITEMS));
    }
}
