package com.sphinxfin.sphinx.core.extraction;

import com.sphinxfin.sphinx.domain.ParsedDocument;
import com.sphinxfin.sphinx.domain.RiskItem;

import java.util.List;

/**
 * 데모 상품의 추출 스냅샷을 심는다 — 통합 테스트용 픽스처. 소유: 강희진 (이슈 #478)
 *
 * <h2>왜 생겼나 — 프로덕션 폴백이 테스트 픽스처 노릇을 하고 있었다</h2>
 *
 * <p>{@code MockDataFallbackCatalog} 를 걷기 전에는, 추출을 안 돌린 상품에도 목 2건이
 * 나왔다. 그래서 세션·질문·판정을 재는 통합 테스트들이 <b>그 폴백에 얹혀</b> 돌았다 —
 * 아무것도 심지 않고 세션을 만들면 항목이 있었다.
 *
 * <p>❗<b>그게 폴백을 걷기 어렵게 만든 이유다.</b> 프로덕션 코드가 «테스트를 편하게 하려고»
 * 남아 있는 상태였고, 그 값은 화면·게이트·교부 문서로도 흘렀다({@code RiskItem} 에 출처
 * 필드가 없어 구별되지 않았다). 픽스처가 필요하면 <b>테스트가 심는 것</b>이 맞다.
 *
 * <p>{@code core.extraction} 안에 두는 이유는 {@link ExtractedRiskItem#of} 가 패키지
 * 가시성이어서다 — 테스트 편의로 그 가시성을 넓히면 프로덕션 코드가 테스트 때문에 열린다.
 *
 * <p>여기서 심는 것은 {@code extract} 가 실제로 저장하는 것과 <b>같은 모양</b>이다
 * ({@link ExtractedRiskItem#of}) — 테스트 전용 우회로를 만들지 않는다. 그래야 이 픽스처로
 * 초록인 것이 실추출에서도 초록이다.
 */
public final class DemoExtractionFixture {

    /** 데모 ELS 상품. 여러 테스트가 세션을 이 상품으로 만든다. */
    public static final String ELS_PRODUCT = "doc-els-kiwoom-4181";

    /**
     * 이 픽스처가 심는 항목 ID — 옛 폴백({@code MockData.RISK_ITEMS})과 같은 둘이다.
     * 테스트가 「몇 개를 답해야 끝나나」를 알아야 하는 자리가 있어 공개한다.
     */
    public static final List<String> ELS_ITEM_IDS =
            List.of("ELS-PRINCIPAL-LOSS-WARNING", "ELS-NO-DEPOSIT-INSURANCE");

    private DemoExtractionFixture() {}

    /**
     * 데모 ELS 상품에 required 항목 둘을 심는다. 이미 있으면 지우고 다시 심는다 —
     * {@code extract} 가 스냅샷을 통째로 교체하는 규약과 같다.
     */
    public static void seedEls(ExtractedRiskItemRepository repository) {
        seed(repository, ELS_PRODUCT, "ELS", ELS_ITEM_IDS);
    }

    /** 상품유형과 항목 ID 를 지정해 심는다. 변액 등 다른 상품을 재는 테스트가 쓴다. */
    public static void seed(ExtractedRiskItemRepository repository, String productId,
                     String productType, List<String> itemIds) {
        // 파스 산출물의 출처 표시 — 실추출이 저장하는 것과 같은 자리에 같은 모양으로 넣는다.
        ParsedDocument parsed = new ParsedDocument(
                "doc-fixture", productType, "fixture.pdf", "0.3.0", null, 1,
                List.of(new ParsedDocument.Page(1, "원문 인용", 5)), List.of(), List.of());
        // ❗`deleteByProductId` 는 파생 삭제 쿼리라 **주변 트랜잭션을 요구한다**
        //   (`TransactionRequiredException`). 픽스처는 테스트 메서드 밖(@BeforeEach)에서
        //   불리므로 트랜잭션이 없다 — `deleteAll(Iterable)` 은 `SimpleJpaRepository` 가
        //   스스로 `@Transactional` 이라 단독으로 돈다.
        repository.deleteAll(repository.findByProductIdOrderByItemIndexAsc(productId));
        List<ExtractedRiskItem> rows = new java.util.ArrayList<>();
        for (int i = 0; i < itemIds.size(); i++) {
            RiskItem item = RiskItem.extracted(itemIds.get(i), productId,
                    "픽스처 항목 " + itemIds.get(i), "required",
                    new RiskItem.Condition("원문 인용", new RiskItem.SourceSpan(1, 0, 5)));
            rows.add(ExtractedRiskItem.of(productId, i, item, parsed));
        }
        repository.saveAll(rows);
    }
}
