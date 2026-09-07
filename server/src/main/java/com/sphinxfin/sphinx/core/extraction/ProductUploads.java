package com.sphinxfin.sphinx.core.extraction;

import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.core.aiservice.DocumentUnreadableException;
import com.sphinxfin.sphinx.domain.ParsedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * F-EXT-001 업로드 실배선 — 받은 파일을 저장하고 상품으로 만든다 (이슈 #521). 소유: 강희진
 *
 * <p>예전에는 {@code POST /products/documents} 가 <b>{@code file} 과 {@code productType} 을
 * 둘 다 안 쓰고</b> {@code mock-els-001} 을 냈다. 그런데도 그 경로는 audited 라 감사 로그에
 * <i>"누가 언제 상품을 등록했다"</i> 가 남았고, {@code evidence/} 는 append-only 라 그 기록을
 * 지울 수도 없었다. 이 서비스가 그 기록이 가리킬 실물을 만든다.
 *
 * <h2>순서와 그 이유</h2>
 *
 * <pre>
 *   검증 → 저장(바이트) → 상품ID 발급 → 파스 → 행 기록
 * </pre>
 *
 * <p><b>저장이 파스보다 앞</b>이다. ai-service 는 경로를 받으므로 파일이 먼저 그 자리에
 * 있어야 한다. 그래서 파스가 실패해도 파일은 남는다 — 그것이 옳다: 운영자가 무엇을 올려서
 * 실패했는지가 남아야 문서를 다시 넣을 판단이 된다(E-EXT-03 은폐 금지와 같은 방향).
 *
 * <p><b>추출은 여기서 하지 않는다.</b> {@code POST /products/{id}/extract} 가 이미 실배선이고
 * (#355 · #401) 게이트의 분모를 바꾸는 별개 action 이다. 업로드가 추출까지 삼키면 그 둘의
 * 권한·감사 경계가 한 요청에 뭉치고, 파스만 되고 추출은 실패한 상태를 화면이 못 가른다 —
 * S-01 이 설계 판단 ③으로 가른 두 층이 정확히 그것이다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductUploads {

    /** 계약 {@code openapi.yaml} 의 {@code productType} enum. 밖의 값은 400 이다. */
    private static final Set<String> PRODUCT_TYPES = Set.of("ELS", "VARIABLE_INSURANCE");

    /**
     * PDF 만 받는다 (명세 F-EXT-001 입력: 공시 PDF).
     *
     * <p>❗<b>{@code Content-Type} 을 믿지 않는다.</b> 브라우저·OS 에 따라
     * {@code application/octet-stream} 으로 오고, 반대로 위조도 쉽다. 파일 앞 5바이트가
     * {@code %PDF-} 인지를 본다 — 파서가 열 수 있는지의 첫 번째 근거이고, 그게 아니면
     * ai-service 를 부르기 전에 여기서 끊는 게 맞다(422 를 받아 봐야 같은 결론이다).
     */
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private final UploadedDocumentStore store;
    private final UploadedProductRepository repository;
    private final AiServiceClient aiServiceClient;

    /** 업로드 명령 — web DTO 가 아니라 core 어휘로 받는다({@code api/} 절 규약). */
    public record UploadCommand(String filename, String productType, byte[] bytes) {}

    /** 업로드 결과. 계약 {@code UploadResponse} 와 같은 두 값이다. */
    public record UploadResult(String productId, String status) {}

    /**
     * 파일을 저장하고 상품으로 만든다.
     *
     * @throws UploadRejectedException 빈 파일·PDF 아님·모르는 상품유형(→ 400)
     */
    @Transactional
    public UploadResult upload(UploadCommand command) {
        String productType = normalizeType(command.productType());
        byte[] bytes = command.bytes();
        if (bytes == null || bytes.length == 0) {
            throw new UploadRejectedException("업로드된 파일이 비어 있다");
        }
        if (!looksLikePdf(bytes)) {
            throw new UploadRejectedException(
                    "PDF 만 올릴 수 있다(파일 앞머리가 %PDF- 가 아니다)");
        }

        UploadedDocumentStore.Stored stored = store.store(command.filename(), bytes);
        String productId = store.issueProductId(command.filename(), stored.sha256());

        // ❗파스 실패를 두 갈래로 가른다. 문서를 못 연 것(422)은 이 문서의 문제라 200 봉투의
        // parse_failed 로 나가고, 그 밖(연결 실패·경로 오류)은 502 로 올라간다 — 그쪽은
        // 운영자가 문서를 다시 넣어서 고칠 수 없는 것이라 같은 문면으로 접으면 안 된다.
        String status = "parsed";
        String failureReason = null;
        ParsedDocument parsed = null;
        try {
            parsed = aiServiceClient.parse(stored.documentPath(), productType);
        } catch (DocumentUnreadableException e) {
            status = "parse_failed";
            failureReason = e.getMessage();
            log.warn("업로드 문서를 파스하지 못했다 — product={} path={} : {}",
                    productId, stored.documentPath(), e.getMessage());
        }

        // 파스가 판별한 상품유형을 우선한다 — 요청값은 업로더가 고른 것이고, 문서가 실제로
        // 무엇인지는 파스가 안다. 둘이 갈리면 요청값을 믿는 쪽이 위험하다: 변액 문서를 ELS
        // 로 등록하면 오해 유형 필터(misconception.applies_to)가 조용히 틀린 채로 돈다.
        String resolvedType = parsed != null && parsed.productType() != null
                ? parsed.productType() : productType;
        if (!resolvedType.equals(productType)) {
            log.warn("업로드 상품유형이 요청과 다르다 — 파스 판별을 쓴다: product={} 요청={} 파스={}",
                    productId, productType, resolvedType);
        }

        // 같은 파일을 다시 올리면 같은 productId 다(내용 주소). 행을 늘리지 않고 갱신한다 —
        // unique 제약이라 새로 넣으면 실패하고, 그건 "같은 문서를 두 번 올렸다"에 대한
        // 답으로 500 을 주는 셈이다.
        UploadedProduct row = repository.findByProductId(productId).orElse(null);
        if (row == null) {
            repository.save(UploadedProduct.builder()
                    .productId(productId)
                    .productType(resolvedType)
                    .displayName(store.displayNameOf(command.filename()))
                    .originalFilename(command.filename())
                    .documentPath(stored.documentPath())
                    .contentSha256(stored.sha256())
                    .sizeBytes(stored.sizeBytes())
                    .status(status)
                    .failureReason(failureReason)
                    .build());
            log.info("상품 등록: product={} type={} status={} path={}",
                    productId, resolvedType, status, stored.documentPath());
        } else {
            row.reparsed(resolvedType, status, failureReason);
            log.info("같은 문서 재업로드 — 상품을 갱신했다: product={} status={}", productId, status);
        }
        return new UploadResult(productId, status);
    }

    /** {@code GET /products} 가 낼 업로드본 목록. 최근 올린 것이 위다. */
    @Transactional(readOnly = true)
    public List<UploadedProduct> catalog() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /** 업로드된 상품의 문서 경로({@code data-dir} 상대). 아니면 empty — 호출자가 폴백한다. */
    @Transactional(readOnly = true)
    public Optional<String> documentPathOf(String productId) {
        return repository.findByProductId(productId).map(UploadedProduct::documentPath);
    }

    /** 업로드된 상품의 상품유형. 아니면 empty — 기본값을 지어내지 않는다. */
    @Transactional(readOnly = true)
    public Optional<String> productTypeOf(String productId) {
        return repository.findByProductId(productId).map(UploadedProduct::productType);
    }

    private static String normalizeType(String requested) {
        String type = requested == null || requested.isBlank() ? "ELS" : requested.trim();
        if (!PRODUCT_TYPES.contains(type)) {
            throw new UploadRejectedException(
                    "모르는 상품유형이다(ELS · VARIABLE_INSURANCE 중 하나): " + type);
        }
        return type;
    }

    private static boolean looksLikePdf(byte[] bytes) {
        if (bytes.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 업로드 입력이 계약을 벗어났다 → 400 {@code VALIDATION_ERROR}.
     *
     * <p>{@code core} 가 {@code api/exception/ValidationException} 을 알면 안 되므로
     * (서비스가 web 층에 의존하지 않는다는 {@code api/} 절 규약) 여기에 전용 타입을 두고
     * 전역 핸들러가 매핑한다. {@code IllegalArgumentException} 을 쓰지 않는 이유는 그
     * 예외의 javadoc 에 있다 — 서버 설정 오류까지 "잘못된 요청" 이 된다.
     */
    public static class UploadRejectedException extends RuntimeException {
        public UploadRejectedException(String message) {
            super(message);
        }
    }
}
