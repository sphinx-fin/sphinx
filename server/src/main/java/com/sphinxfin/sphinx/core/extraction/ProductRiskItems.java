package com.sphinxfin.sphinx.core.extraction;

import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.domain.ParsedDocument;
import com.sphinxfin.sphinx.domain.RiskItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 상품별 이해항목의 단일 출처 (F-EXT-002 배선, 이슈 #355). 소유: 강희진
 *
 * <p><b>저장 우선, MockData 폴백.</b> 추출({@link #extract})이 한 번이라도 성공한 상품은
 * 그 스냅샷이 답이고, 없는 상품은 {@link FallbackCatalog}(=MockData)로 떨어진다 — LLM 키가
 * 없는 환경에서도 데모 흐름이 계속 돌아야 해서 목을 아직 안 걷는다(걷는 건 후속).
 *
 * <p>컨트롤러 셋(Product·Session)이 전부 여기서 항목을 받는다. 항목 출처가 두 곳이면
 * 게이트가 물을 분모가 화면과 어긋난다 — 진행률이 조용히 틀리는 그 결함이다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductRiskItems {

    /**
     * 데모 상품 → 문서 경로 (SPHINX_DATA_DIR 상대 — ai-service /internal/parse 규약).
     * 업로드(F-EXT-001 POST /documents)가 실배선되면 업로드 산출물 경로로 대체된다.
     */
    private static final Map<String, String> DEMO_DOCUMENTS = Map.of(
            "doc-els-kiwoom-4181", "documents/els_kiwoom_4181_simple_prospectus.pdf",
            "doc-var-samsung-b2601", "documents/var_samsung_b2601_product_summary.pdf");

    private final ExtractedRiskItemRepository repository;
    private final AiServiceClient aiServiceClient;
    private final FallbackCatalog fallbackCatalog;

    /** 추출 결과 — 영속된 항목과 경고. 경고는 실패 은폐 금지(E-EXT-03)의 통로다. */
    public record Extraction(List<RiskItem> items, List<AiServiceClient.Warning> warnings) {}

    /**
     * 실추출: 문서 경로 결정 → parse → extract → <b>그 상품의 기존 스냅샷을 통째로 교체</b>.
     *
     * <p>ai-service 호출이 실패하면({@code AiServiceException} → 502) 트랜잭션이 굴러
     * 기존 스냅샷이 남는다 — 실패한 추출이 멀쩡한 스냅샷을 지우면 안 된다.
     *
     * @throws NoSuchElementException 등록된 문서가 없는 상품(→ 404)
     */
    @Transactional
    public Extraction extract(String productId) {
        String documentPath = DEMO_DOCUMENTS.get(productId);
        if (documentPath == null) {
            throw new NoSuchElementException("등록된 문서가 없는 상품이다: " + productId);
        }
        // 파스에 넘길 상품유형은 카탈로그(저장 우선)에서 온다 — 하드코딩하면 변액 문서를
        // ELS 템플릿으로 읽는 종류의 오판이 조용히 생긴다(SessionController.productTypeOf 주석).
        String productType = productTypeOf(productId);

        ParsedDocument parsed = aiServiceClient.parse(documentPath, productType);
        AiServiceClient.ExtractResult result = aiServiceClient.extract(productId, parsed);

        for (AiServiceClient.Warning warning : result.warnings()) {
            // 은폐하지 않고 남긴다(E-EXT-03). 응답 계약(RiskItemsResponse)은 안 바꾼다 —
            // 항목 자체의 실패는 status=extraction_failed 로 이미 응답에 실려 있다.
            log.warn("추출 경고 [{}] product={} item={} — {}",
                    warning.code(), productId, warning.itemId(), warning.message());
        }
        if (result.items().isEmpty()) {
            log.warn("추출이 항목을 하나도 못 냈다 — 스냅샷을 비우고 폴백으로 돌아간다 (product={})",
                    productId);
        }

        repository.deleteByProductId(productId);
        List<ExtractedRiskItem> rows = new ArrayList<>(result.items().size());
        for (int i = 0; i < result.items().size(); i++) {
            rows.add(ExtractedRiskItem.of(productId, i, result.items().get(i), parsed));
        }
        repository.saveAll(rows);
        log.info("추출 스냅샷 교체: product={} items={} (documentId={} parserVersion={})",
                productId, rows.size(), parsed.documentId(), parsed.parserVersion());

        return new Extraction(rows.stream().map(ExtractedRiskItem::toDomain).toList(),
                result.warnings());
    }

    /**
     * 상품의 이해항목 — 저장된 추출이 있으면 그것, 없으면 폴백(MockData). <b>어느 출처도
     * 모르는 상품이면 404</b>({@link #productTypeOf} 와 같은 규약, 결정 10.81) — 없는 상품ID
     * 에도 폴백 목록을 내주면 카탈로그가 둘 이상이 되는 순간 조용히 틀린 목록이 된다.
     *
     * @throws NoSuchElementException 어느 출처도 모르는 상품(→ 404)
     */
    @Transactional(readOnly = true)
    public List<RiskItem> riskItemsOf(String productId) {
        List<ExtractedRiskItem> stored = repository.findByProductIdOrderByItemIndexAsc(productId);
        if (!stored.isEmpty()) {
            return stored.stream().map(ExtractedRiskItem::toDomain).toList();
        }
        return fallbackCatalog.riskItems(productId)
                .orElseThrow(() -> new NoSuchElementException(
                        "상품 목록에 없음(이해항목을 알 수 없다): " + productId));
    }

    /**
     * 항목 하나 — {@link #riskItemsOf} 와 같은 목록에서 찾는다. 출처가 갈리면 채점 항목과
     * 질문 항목이 다른 목록에서 나온다.
     *
     * @throws NoSuchElementException 목록에 없으면(→ 404)
     */
    @Transactional(readOnly = true)
    public RiskItem itemOf(String productId, String itemId) {
        return riskItemsOf(productId).stream()
                .filter(r -> r.itemId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("항목을 찾을 수 없다: " + itemId));
    }

    /**
     * 상품유형 — 저장된 추출이 있으면 그 파스가 판별한 값, 없으면 카탈로그(MockData).
     * 둘 다 모르면 404 다. <b>기본값을 두지 않는다</b> — product_type 은 오해 유형 필터의
     * 입력이라(misconception.applies_to) 지어낸 값이 판정을 조용히 틀리게 한다.
     *
     * @throws NoSuchElementException 어느 출처도 모르는 상품(→ 404)
     */
    @Transactional(readOnly = true)
    public String productTypeOf(String productId) {
        List<ExtractedRiskItem> stored = repository.findByProductIdOrderByItemIndexAsc(productId);
        if (!stored.isEmpty()) {
            return stored.get(0).productType();
        }
        return fallbackCatalog.productType(productId)
                .orElseThrow(() -> new NoSuchElementException(
                        "상품유형을 알 수 없다(상품 목록에 없음): " + productId));
    }
}
