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
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 상품별 이해항목의 단일 출처 (F-EXT-002 배선, 이슈 #355). 소유: 강희진
 *
 * <h2>❗폴백이 없다 — 추출을 안 돌렸으면 404 다 (이슈 #478)</h2>
 *
 * <p>추출({@link #extract})이 한 번이라도 성공한 상품은 그 스냅샷이 답이고, <b>없으면
 * 404</b> 다. 예전에는 {@code MockDataFallbackCatalog} 가 조용히 목 2건을 냈고 화면·게이트·
 * 교부 문서가 그것을 실물로 받았다.
 *
 * <p>❗<b>문제는 출처 표시가 «없다» 가 아니라 있는 칸이 «거짓말을 했다» 는 것이다</b>
 * (PR #562 리뷰, 윤지석). {@code RiskItem.status} 는 <i>"이 항목이 문서에서 나왔나"</i> 에
 * 답하려고 만든 유일한 칸인데({@code #152} 가 {@code failure_reason} 을 같이 낸 이유가
 * 그것이다) 폴백은 거기에 {@code extracted} 를 적었다.
 *
 * <pre>
 *   폴백이 낸 목 2건   status = "extracted"                      ← 아무것도 추출하지 않았는데
 *   추출 실패한 항목   status = "extraction_failed" + failure_reason
 * </pre>
 *
 * <p>문서 단위 출처 표시는 있다 — ai-service 의 {@code MANUAL_SOURCE} 경고가 <i>"파스 출력이
 * 사람이 만든 것"</i> 을 알린다. 그런데 그건 <b>파스 출력</b>에 대한 것이고 폴백은 파싱조차
 * 안 지나므로 그 경고도 안 붙는다. 즉 <b>어느 층에서도 표시가 안 됐다.</b>
 *
 * <p>그 폴백이 스스로 <i>"키 없는 데모용 임시 가드"</i> 라고 적어 뒀는데 <b>그 전제가
 * 사라졌다</b> — LLM 키가 살아 있고 실추출이 두 상품 다 돈다. 즉 아무것도 안 지키면서
 * 조용히 틀릴 수만 있는 상태였다.
 *
 * <p>❗<b>prod 에서 반드시 물렸다.</b> 빈 DB 에 폴백이 있으면 extract 를 돌리기 전에 목
 * 2건이 나가고, 그게 «추출을 안 돌렸다» 를 감춘다. 없으면 404 로 드러난다 —
 * {@code #463}(추출 실패 required → 미측정 RED)과 같은 결이다.
 *
 * <p>컨트롤러 셋(Product·Session)이 전부 여기서 항목을 받는다. 항목 출처가 두 곳이면
 * 게이트가 물을 분모가 화면과 어긋난다 — 진행률이 조용히 틀리는 그 결함이다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductRiskItems {

    /**
     * <b>사전적재</b> 데모 상품 — 상품ID·가명 표시명·상품유형·문서 경로(SPHINX_DATA_DIR 상대).
     *
     * <p>{@link #documentPathOf}·{@link #productTypeOf} 가 업로드된 상품을 먼저 보고 없으면
     * 여기로 떨어진다. <b>이 표는 걷을 대상이 아니다</b> — 커밋된 공시 문서 2종이라
     * <b>키 없는 환경에서도 데모가 도는 근거</b>이고, 아래 두 문단이 합쳐 온 것들의 정본이다.
     * 예전에는 이 자리에 <i>"걷는 것은 #403 이 따로 한다"</i> 가 적혀 있었는데, 그 이슈는
     * 목 클래스를 지우는 것이었고 <b>이 표에 표시명을 얹으면서 닫혔다</b>.
     *
     * <h2>❗상품유형을 여기로 합쳤다 (이슈 #478)</h2>
     *
     * <p>예전에는 경로가 여기, 상품유형이 {@code MockData.PRODUCTS} 에 있었다 — <b>같은 두
     * 상품에 출처가 둘</b>이었고 한쪽만 고쳐질 수 있었다. 폴백 카탈로그를 걷으면서 합쳤다.
     *
     * <h2>❗표시명도 여기로 합쳤다 — 셋째 사본이었다 (이슈 #403)</h2>
     *
     * <p>같은 두 상품의 <b>가명 표시명</b>이 {@code MockData.PRODUCTS} 에 남아 S-02 목록을
     * 냈다. 위 문단이 걷어 낸 것과 같은 모양이라 같은 자리로 합치고 그 파일을 지웠다.
     *
     * <p>❗<b>표시명은 가명이다</b>(결정 1.11). 기획서가 <i>"데모와 제출물에서는 상품명과
     * 발행사를 가명 처리하고 조건만 인용한다"</i> 로 요구하는 값이라, 실명을 여기 적으면
     * 화면이 이 목록을 쓰는 순간 실명이 데모로 되돌아온다. 상품ID 는 파싱 산출물의
     * {@code document_id} 라 가명 대상이 아니다.
     *
     * <p>❗<b>순서가 S-02 목록의 순서다</b> — 그래서 {@code Map} 이 아니라 {@code List} 다.
     * {@code Map.of} 는 반복 순서를 보장하지 않으므로, 목록으로 내보내는 순간 두 상품이
     * 실행마다 뒤바뀔 수 있다. 조회는 아래 {@link #BY_ID} 가 받는다.
     *
     * <p>❗<b>이 표는 이해항목을 내지 않는다.</b> 그게 걷어 낸 폴백과의 차이다 — 경로와
     * 상품유형은 <b>커밋된 코퍼스의 사실</b>이고 추출을 시작하는 데 필요한 값인데, 이해항목은
     * <b>측정 결과</b>라 지어내면 화면·게이트·교부 문서가 그것을 실물로 받는다.
     */
    private static final List<Preloaded> PRELOADED = List.of(
            new Preloaded("doc-els-kiwoom-4181", "doc-els-kiwoom-4181",
                    "A증권 제4181회 ELS (원금비보장형)", "ELS",
                    "documents/els_kiwoom_4181_simple_prospectus.pdf"),
            new Preloaded("doc-var-samsung-b2601", "doc-var-samsung-b2601",
                    "B생명 변액연금보험 (최저연금보증형)", "VARIABLE_INSURANCE",
                    "documents/var_samsung_b2601_product_summary.pdf"));

    /** 상품ID 로 찾는 자리. 위 목록이 정본이고 이건 그 색인이다. */
    private static final Map<String, Preloaded> BY_ID = PRELOADED.stream()
            .collect(Collectors.toUnmodifiableMap(Preloaded::productId, p -> p));

    /**
     * 사전적재 상품 하나. 이해항목은 여기 없다(위 javadoc).
     *
     * <p>{@code displayName} 은 <b>가명</b>이고 {@code productId} 는 가명 대상이 아니다.
     *
     * <p>❗<b>{@code documentId} 를 {@code productId} 와 따로 든다</b>(결정 1.37 · 이슈 #528).
     * 오늘 두 값이 같은 것은 <b>이 표가 상품마다 문서를 한 건만 들어서 나는 우연</b>이고
     * 계약이 아니다 — {@code var_samsung_b2601} 은 실물 문서가 3편이고 계약 샘플 둘이
     * 서로 다른 {@code document_id}({@code doc-var-samsung-b2601} ·
     * {@code doc-var-samsung-b2601-ops})를 든다. 한 필드로 합치면 두 번째 문서를 등록하는
     * 날 «상품이 둘» 이나 «문서가 한 값으로 뭉침» 중 하나가 된다.
     *
     * <p>값은 계약 샘플과 맞춰 둔다 —
     * {@code PreloadedTableMatchesParseSamplesTest} 가 대조한다.
     */
    public record Preloaded(String productId, String documentId, String displayName,
                            String productType, String documentPath) {}

    /**
     * S-02 목록이 쓰는 사전적재 2종 — 목록에 나가는 순서 그대로.
     *
     * <p>{@code static} 인 이유는 이 표가 <b>커밋된 코퍼스의 사실</b>이라 DB·ai-service 를
     * 안 타기 때문이다. 표시명 대조({@code ProductDisplayNameTest})가 스프링 문맥 없이
     * 이 값을 읽어야 하고, 그 테스트가 목을 걷은 뒤 유일하게 남은 그물이다.
     */
    public static List<Preloaded> preloaded() {
        return PRELOADED;
    }

    private final ExtractedRiskItemRepository repository;
    private final AiServiceClient aiServiceClient;
    private final ProductUploads productUploads;

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
        String documentPath = documentPathOf(productId);
        // 파스에 넘길 상품유형은 카탈로그(저장 우선)에서 온다 — 하드코딩하면 변액 문서를
        // ELS 템플릿으로 읽는 종류의 오판이 조용히 생긴다(SessionController.productTypeOf 주석).
        String productType = productTypeOf(productId);

        // ❗파스에 넘기는 document_id 는 호출자 몫이다(결정 1.37) — 안 넘기면 파서가 파일명에서
        //   만들고, 그 값이 extracted_risk_items.document_id 에 쌓여 규칙이 두 벌이 된다.
        ParsedDocument parsed = aiServiceClient.parse(documentPath, productType, documentIdOf(productId));
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
     * 상품의 이해항목 — <b>저장된 추출뿐</b>이다. 없으면 404 (이슈 #478 · 결정 10.81 · #427).
     *
     * <p>❗<b>목으로 채우지 않는다.</b> 예전에는 폴백이 ELS 목 2건을 냈고, 그 값이
     * {@code RiskItem} 으로 나가는 순간 <b>출처 표시가 없다</b> — 화면·게이트·교부 문서가
     * 실물로 받고 <i>"이 항목이 진짜 문서에서 나온 것인가"</i> 에 답할 수 없다. 리포트에
     * 남는 값이라 그 답이 필요하다.
     *
     * <p>404 는 <b>«추출을 안 돌렸다» 를 드러내는 신호</b>다. 빈 DB(신규 배포)에서 폴백이
     * 있으면 그 사실이 감춰지고, 그건 조용한 오답이다.
     *
     * @throws NoSuchElementException 저장된 추출이 없는 상품(→ 404)
     */
    @Transactional(readOnly = true)
    public List<RiskItem> riskItemsOf(String productId) {
        List<ExtractedRiskItem> stored = repository.findByProductIdOrderByItemIndexAsc(productId);
        if (stored.isEmpty()) {
            throw new NoSuchElementException(
                    "이해항목을 알 수 없다(저장된 추출이 없다 — 추출을 먼저 돌려라): " + productId);
        }
        return stored.stream().map(ExtractedRiskItem::toDomain).toList();
    }

    /**
     * 게이트가 세는 <b>기대 항목</b> — {@code required} 전부 (status 무관, 이슈 #435 · #432 · #462).
     * {@code recommended} 는 루브릭이 없어(결정 10.1) 채점 대상이 아니므로 분모에서 뺀다.
     *
     * <p>❗<b>추출 실패한 required 도 여기 든다.</b> required 인데 검증 못 한 것이라, 분모에서
     * 빼면 게이트가 통과시킨다 — 그건 "required 를 못 봤는데 GREEN" 이라 위험하다. 실패 항목은
     * {@link #interviewItemsOf} 에서 빠져 <b>안 물어지고 판정도 없으니</b>, 분모에 남겨 두면
     * 미측정으로 잡혀 {@code R-00}(RED) 이 막는다 — 안전한 방향이다.
     */
    @Transactional(readOnly = true)
    public List<RiskItem> requiredItemsOf(String productId) {
        return riskItemsOf(productId).stream()
                .filter(r -> "required".equals(r.importance()))
                .toList();
    }

    /**
     * 면담(nextQuestion)이 <b>물을 수 있는</b> 항목 — {@code required} 이고 {@code status=extracted}
     * 인 것 (이슈 #435 · #462). {@link #requiredItemsOf} 에서 실패 항목을 더 뺀다.
     *
     * <p>❗<b>status 를 봐야 한다.</b> {@code extraction_failed} 는 조건 원문(condition)이 없어
     * 물을 것도 채점 대상도 없다 — 그걸 물으면 ai-service {@code /score} 가 {@code require_condition()}
     * 에서 던져 <b>면담 도중 502</b> 다(#462). 그래서 면담은 물을 수 있는 것만, <b>분모는
     * {@link #requiredItemsOf} 로 실패까지</b> 센다 — 두 기준이 다른 것이 여기서는 옳다(실패한
     * required 는 안 물어지되 미측정으로 게이트가 막아야 하므로).
     *
     * <p>실패 항목도 화면({@code GET /risk-items})에는 그대로 뜬다(E-EXT-03 은폐 금지) —
     * 이 필터는 면담 대상만 좁힌다. 데이터가 전부 extracted 이면 이 집합은 {@code requiredItemsOf}
     * 와 같아 동작이 바뀌지 않는다.
     */
    @Transactional(readOnly = true)
    public List<RiskItem> interviewItemsOf(String productId) {
        return requiredItemsOf(productId).stream()
                .filter(r -> "extracted".equals(r.status()))
                .toList();
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
     * 상품유형 — 저장된 추출이 있으면 그 파스가 판별한 값, 다음이 업로드본(#521), 없으면 사전적재 표.
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
        // 추출 전 업로드본은 파스가 판별한 유형을 들고 있다(이슈 #521) — 이 자리가 없으면
        // 올린 직후 추출(POST /{id}/extract)이 상품유형을 못 찾아 404 로 죽는다.
        return productUploads.productTypeOf(productId)
                .or(() -> Optional.ofNullable(BY_ID.get(productId)).map(Preloaded::productType))
                .orElseThrow(() -> new NoSuchElementException(
                        "상품유형을 알 수 없다(업로드본도 사전적재도 아니다): " + productId));
    }

    /**
     * 상품의 원문 문서 경로(SPHINX_DATA_DIR 상대). 추출({@link #extract})이 파스에 넘기는
     * 그 경로이자, 원본 조회({@code GET /products/{id}/document}, 이슈 #412)가 파일을 찾는
     * 값이다. 출처가 하나여야 추출이 읽은 문서와 화면이 대조하는 문서가 같다 — 그래서
     * 매핑을 여기 한 벌만 둔다({@link #PRELOADED}). 업로드(F-EXT-001)가 실배선되면
     * 그 산출물 경로로 이 매핑이 대체된다.
     *
     * @throws NoSuchElementException 등록된 문서가 없는 상품(→ 404)
     */
    public String documentPathOf(String productId) {
        // ❗업로드본이 먼저다. 순서가 반대면 업로드한 파일명이 우연히 사전적재 상품ID 와
        // 같아지는 날 «올린 문서가 아닌 것» 을 파스하고, 그 결과가 그 상품의 항목이 된다.
        String documentPath = productUploads.documentPathOf(productId)
                .orElseGet(() -> Optional.ofNullable(BY_ID.get(productId))
                        .map(Preloaded::documentPath).orElse(null));
        if (documentPath == null) {
            throw new NoSuchElementException("등록된 문서가 없는 상품이다: " + productId);
        }
        return documentPath;
    }

    /**
     * 파스에 넘길 <b>업로드 단위 식별자</b>(결정 1.37 · 이슈 #528).
     *
     * <p>업로드본은 <b>{@code productId} 자체가 내용 주소</b>다
     * ({@code doc-<슬러그>-<sha256 앞 16자>}) — 같은 바이트면 같은 값이고 한 바이트만 달라도
     * 다른 값이라, 문서 1건을 가리키는 식별자로 그대로 쓴다. 사전적재는 표가 든 값이고, 그
     * 값은 계약 샘플의 {@code document_id} 와 맞춰져 있다.
     *
     * <p>❗<b>기본값을 두지 않는다.</b> 여기서 못 찾은 것을 파서 폴백에 맡기면 규칙이 두
     * 벌이 된다({@link #documentPathOf} 가 이미 404 로 드러내는 것과 같은 규약).
     *
     * @throws NoSuchElementException 업로드본도 사전적재도 아닌 상품(→ 404)
     */
    public String documentIdOf(String productId) {
        if (productUploads.documentPathOf(productId).isPresent()) {
            return productId;
        }
        Preloaded preloaded = BY_ID.get(productId);
        if (preloaded == null) {
            throw new NoSuchElementException(
                    "업로드 단위 식별자를 알 수 없다(업로드본도 사전적재도 아니다): " + productId);
        }
        return preloaded.documentId();
    }
}
