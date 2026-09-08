package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.api.dto.ProductSummary;
import com.sphinxfin.sphinx.api.dto.RiskItemsResponse;
import com.sphinxfin.sphinx.api.dto.UploadResponse;
import com.sphinxfin.sphinx.core.extraction.ProductDocuments;
import com.sphinxfin.sphinx.core.extraction.ProductRiskItems;
import com.sphinxfin.sphinx.core.extraction.ProductUploads;
import com.sphinxfin.sphinx.core.extraction.UploadedProduct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 *   GET  /products/{id}/document      product:read    원문 PDF 인라인 (이슈 #412)
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
@RequiredArgsConstructor
public class ProductController {

    private final ProductRiskItems productRiskItems;
    private final ProductDocuments productDocuments;
    private final ProductUploads productUploads;

    /**
     * S-02 상품 선택 목록. 기존 /products/* 는 전부 productId 를 이미 알아야 부를 수 있어서
     * "고를 수 있는 상품"을 주는 경로가 없었다.
     *
     * <p><b>업로드된 상품이 먼저, 사전적재 데모 2종이 뒤</b>(이슈 #521). 방금 올린 것을 찾는
     * 것이 이 목록을 부르는 이유라 최근 업로드가 위로 온다.
     *
     * <p>❗<b>{@code parse_failed} 도 목록에 낸다.</b> 빼면 운영자가 올린 문서가 조용히
     * 사라지고 왜 안 보이는지 알 길이 없다 — 계약의 {@code ProductSummary.status} 가
     * {@code parsed}/{@code parse_failed} 두 값을 든 이유가 그것이다(E-EXT-03 은폐 금지).
     *
     * <p>사전적재 2종은 {@code MockData.PRODUCTS} 가 계속 낸다 — 표시명이 <b>가명</b>이고
     * (결정 1.11) 커밋된 공시 문서라 키 없는 환경에서도 데모가 돈다. 걷는 것은 #403 이다.
     */
    @PreAuthorize("@accessGuard.canAggregate('product:read')")
    @GetMapping
    public ApiResponse<List<ProductSummary>> list() {
        List<ProductSummary> all = new ArrayList<>();
        for (UploadedProduct p : productUploads.catalog()) {
            all.add(new ProductSummary(p.productId(), p.displayName(), p.productType(), p.status()));
        }
        all.addAll(MockData.PRODUCTS);
        return ApiResponse.ok(all);
    }

    /**
     * 문서 업로드. <b>audited</b> 다 — 누가 언제 상품을 등록했는지가 남아야 한다(결정 10.36).
     * 여기서 올린 문서가 {@code extract} 를 거쳐 게이트의 질문 목록이 된다.
     *
     * <p><b>실배선이다(이슈 #521).</b> 예전에는 {@code file} 과 {@code productType} 을 <b>둘 다
     * 안 읽고</b> {@code mock-els-001} 을 냈다 — 파일은 버려지고 변액을 올려도 ELS 가 돌아왔다.
     * 그런데도 이 경로는 audited 라 감사 로그에 <i>"상품을 등록했다"</i> 가 남았고,
     * {@code evidence/} 는 append-only 라 그 기록을 나중에 못 지운다. 지금은 그 기록이
     * 가리킬 실물이 있다.
     *
     * <p>❗<b>추출을 여기서 부르지 않는다.</b> {@code POST /{id}/extract} 가 게이트의 분모를
     * 바꾸는 별개 action 이고, 파스만 된 상태와 추출까지 된 상태를 화면이 갈라야 한다
     * (S-01 설계 판단 ③). 화면이 {@code status === "parsed"} 일 때만 추출로 넘어간다.
     */
    @PreAuthorize("@accessGuard.canAggregate('product:manage')")
    @PostMapping("/documents")
    public ApiResponse<UploadResponse> upload(@RequestParam MultipartFile file,
                                              @RequestParam(defaultValue = "ELS") String productType) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            // 업로드 임시 저장에서 읽지 못한 것이라 요청 문제가 아니다 → 500.
            throw new UncheckedIOException("업로드된 파일을 읽지 못했다", e);
        }
        ProductUploads.UploadResult result = productUploads.upload(
                new ProductUploads.UploadCommand(file.getOriginalFilename(), productType, bytes));
        return ApiResponse.ok(new UploadResponse(result.productId(), result.status()));
    }

    /**
     * 위험항목 추출. <b>게이트의 분모를 바꾸는 자리</b>라 ADMIN 전용이고 audited 다.
     * 판매 라인이 자기가 답해야 할 질문의 목록을 편집할 수 있으면 안 된다(ADR-001 과 같은 결).
     *
     * <p><b>실배선이다(이슈 #355 · #401 3번)</b> — 문서 경로 결정 → ai-service
     * {@code /internal/parse} → {@code /internal/extract} → 스냅샷 영속(교체). 이후
     * {@code GET /{id}/risk-items} 와 세션 면담이 이 스냅샷을 읽는다. 응답 모양은 목 시절
     * 그대로 {@code RiskItemsResponse} — 계약은 안 바꾼다. 추출 경고는 서비스가 로그로
     * 남긴다(E-EXT-03). 실 LLM 추출은 ai-service 에 LLM_API_KEY 가 있어야 돈다.
     */
    @PreAuthorize("@accessGuard.canAggregate('product:manage')")
    @PostMapping("/{productId}/extract")
    public ApiResponse<RiskItemsResponse> extract(@PathVariable String productId) {
        return ApiResponse.ok(new RiskItemsResponse(productRiskItems.extract(productId).items()));
    }

    /**
     * 추출된 항목 조회. 면담이 부르는 경로라 SELLER 가 닿아야 한다({@code product:read}).
     * 저장된 추출이 있으면 그것, 없으면 MockData 폴백 — 키 없는 환경도 계속 돈다.
     */
    @PreAuthorize("@accessGuard.canAggregate('product:read')")
    @GetMapping("/{productId}/risk-items")
    public ApiResponse<RiskItemsResponse> riskItems(@PathVariable String productId) {
        return ApiResponse.ok(new RiskItemsResponse(productRiskItems.riskItemsOf(productId)));
    }

    /**
     * 상품 원문 문서(PDF) 조회 (F-EXT · 이슈 #412). S-02 모달이 조항 원문·페이지·오프셋을
     * 그려 놓고도 대조할 <b>원본</b>이 없던 것을 채운다(P6 원문 인용) — 판매자가 손에 든
     * 설명서 대신 화면에서 바로 대조한다.
     *
     * <p>❗<b>공통 봉투({@code ApiResponse})에 담지 않는다.</b> {@code /report/preview} 가
     * 만든 선례를 그대로 따른다 — PDF 는 바이트고 봉투는 JSON 이라, base64 로 접어 넣으면
     * 브라우저가 뷰어로 못 열고 화면이 다시 풀어야 한다. <b>인라인</b>({@code Content-Disposition:
     * inline})이라 모달이 이미 든 페이지 번호로 {@code #page=N} 앵커를 붙여 열 수 있다.
     * 오류 경로는 봉투 그대로다 — 없는 상품·사전적재 안 된 파일은 {@code GlobalExceptionHandler}
     * 가 404 로 낸다({@link ProductDocuments#open} 이 {@code NoSuchElementException}).
     *
     * <p>권한은 <b>읽기 둘과 같은 {@code product:read} 를 재사용</b>한다. 원문 문서는 세션
     * 데이터가 아니라 {@code /risk-items} 가 내는 것과 같은 상품 카탈로그 데이터다 — 그 항목이
     * 뽑혀 나온 바로 그 문서라 접근 단위가 같다. {@code report:read} 처럼 세션 스코프가 아니므로
     * {@code canAggregate} 로 부른다(다른 {@code /products/*} 읽기와 같은 형태). SELLER 가
     * 자기가 안 연 상품이라도 카탈로그를 읽을 수 있는 것과 같은 근거다(scope: org).
     */
    @PreAuthorize("@accessGuard.canAggregate('product:read')")
    @GetMapping("/{productId}/document")
    public ResponseEntity<byte[]> document(@PathVariable String productId) {
        ProductDocuments.Document doc = productDocuments.open(productId);   // 없으면 404
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(doc.filename(), StandardCharsets.UTF_8)
                .build();
        // 리포트와 달리 no-store 를 걸지 않는다 — 상품설명서는 공시 문면이지 고객 데이터가
        // 아니라(ParsedDocument 주석), 리포트 PDF 의 캐시 금지 근거(발화·판정 근거 포함)가
        // 여기엔 없다. 브라우저가 정상 캐시하게 둔다.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(doc.bytes());
    }
}
