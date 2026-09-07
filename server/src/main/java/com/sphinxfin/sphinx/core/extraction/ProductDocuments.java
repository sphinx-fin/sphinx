package com.sphinxfin.sphinx.core.extraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 상품 원문 문서(PDF) 조회 (F-EXT · 이슈 #412). 소유: 강희진
 *
 * <p>S-02 상품 모달이 조항 원문·페이지·오프셋까지 그려 놓고도 대조할 <b>원본</b>으로 갈
 * 길이 없었다(P6 원문 인용). 이 서비스가 상품의 원본 문서 바이트를 디스크에서 읽어
 * {@code GET /products/{id}/document} 가 인라인으로 내리게 한다 — 판매자가 손에 든
 * 설명서 대신 화면에서 바로 대조한다.
 *
 * <h2>경로 결정과 안전</h2>
 *
 * <p>productId → 문서 경로는 {@link ProductRiskItems#documentPathOf}가 단일 출처다 —
 * 추출이 파스에 넘긴 그 경로와 같아야 화면이 대조하는 문서가 추출이 읽은 문서와 같다.
 * 그 경로는 {@code sphinx.documents.data-dir}(=ai-service SPHINX_DATA_DIR 규약) 상대이고,
 * 시뮬레이터 시계열과 같은 이유로 클래스패스에 굽지 않고 파일로 읽는다(P2·#37).
 *
 * <p>❗<b>기준 디렉토리를 벗어나는 경로는 거부한다.</b> 지금 매핑은 고정 상수라
 * {@code ..} 가 들어올 자리가 없지만, 업로드 배선(#401)이 붙으면 경로가 외부 입력에서
 * 오게 된다 — 그때 이 가드가 없으면 {@code ../../etc/passwd} 류가 파일 조회로 새어 나간다.
 * 방어를 경계(이 서비스) 안에 미리 둔다.
 */
@Service
public class ProductDocuments {

    private final Path dataDir;
    private final ProductRiskItems productRiskItems;
    private final ProductUploads productUploads;

    /**
     * 경로를 String 으로 받아 직접 {@link Path#of}로 변환한다 — {@code SimulatorProperties}가
     * 같은 이유(리소스 경로 변환기가 상대경로의 {@code ..}를 null 로 정규화)로 그렇게 한다.
     */
    public ProductDocuments(@Value("${sphinx.documents.data-dir}") String dataDir,
                            ProductRiskItems productRiskItems,
                            ProductUploads productUploads) {
        // 빈 값은 설정 오류다 — Spring 의 ${VAR:기본값} 은 환경변수가 빈 문자열이면 그것을
        // 값으로 취급해 기본값이 죽는다(SimulatorProperties 주석과 같은 함정).
        if (dataDir == null || dataDir.isBlank()) {
            throw new IllegalStateException(
                    "sphinx.documents.data-dir 이 비어 있다. 환경변수 SPHINX_DATA_DIR 을 빈 값으로 "
                    + "두면 기본값이 적용되지 않는다 — 지우거나 실제 경로를 넣는다.");
        }
        this.dataDir = Path.of(dataDir);
        this.productRiskItems = productRiskItems;
        this.productUploads = productUploads;
    }

    /** 조회 결과 — 파일명(Content-Disposition 용)과 바이트. */
    public record Document(String filename, byte[] bytes) {}

    /**
     * 상품의 원문 문서를 읽는다.
     *
     * <p>❗<b>두 실패를 다른 코드로 가른다(이슈 #433).</b> <i>없는 상품</i>은 404
     * ({@code NoSuchElementException}, {@link ProductRiskItems#documentPathOf} — 클라이언트가
     * 잘못된 상품을 물은 것)이고, <i>매핑엔 있는데 파일이 디스크에 없다</i>는 <b>500</b>
     * ({@code IllegalStateException} → INTERNAL_ERROR — 컨테이너에 {@code data/documents} 가
     * 마운트 안 된 배포 설정 문제다). 둘 다 404 면 S-02 에서 원문 버튼이 404 일 때 상품이
     * 잘못된 건지 배포가 잘못된 건지 알 수 없다 — 같은 코드가 두 뜻을 갖는다.
     *
     * @throws NoSuchElementException 등록된 문서가 없는 상품(→ 404, 클라이언트 오류)
     * @throws IllegalStateException 파일이 디스크에 없다(→ 500, 배포 설정 오류)
     */
    public Document open(String productId) {
        String relative = productRiskItems.documentPathOf(productId);   // 없는 상품이면 404
        Path resolved = resolveWithin(dataDir, relative);
        if (!Files.isRegularFile(resolved)) {
            // 매핑엔 있는데 사전적재 파일이 없다 — 배포 설정 문제(컨테이너에 documents/ 미마운트,
            // #433)라 404(없는 상품)가 아니라 500 이다. 로그로 배포를 고치라고 알린다.
            throw new IllegalStateException("상품 문서 파일이 없다(배포에 data/documents 가 "
                    + "마운트됐는지 · SPHINX_DATA_DIR 을 확인하라, #433): " + resolved);
        }
        try {
            return new Document(filenameOf(productId, resolved), Files.readAllBytes(resolved));
        } catch (IOException e) {
            // 파일이 있는데 못 읽는 것은 설정·권한 문제다 — 400 이 아니라 500(INTERNAL_ERROR).
            throw new UncheckedIOException("상품 문서를 읽지 못했다: " + resolved, e);
        }
    }

    /**
     * {@code Content-Disposition} 에 낼 파일명.
     *
     * <p>❗<b>업로드본은 경로에서 뽑지 않는다</b>(이슈 #521). 저장 경로의 이름은
     * {@link UploadedDocumentStore#safeFilename} 이 걷은 값이라 한글 아닌 특수문자가
     * {@code _} 로 접혀 있다 — 판매자가 받는 파일 이름은 <b>올린 그대로</b>여야 하므로
     * 업로드가 DB 행에 남긴 원문을 쓴다.
     *
     * <p>사전적재 데모 2종은 그 행이 없다 — 그쪽은 경로의 파일명이 곧 사람이 읽는 이름이라
     * (예: {@code els_kiwoom_4181_simple_prospectus.pdf}) 예전 동작을 그대로 둔다.
     */
    private String filenameOf(String productId, Path resolved) {
        // ❗DB 의 값은 **업로더가 준 원문**이라 개행·제어문자가 있을 수 있다. 그대로 헤더에
        //   실으면 응답 헤더가 갈라지므로 저장에 쓰는 것과 같은 정제를 거친다 — 한글은 살고
        //   경로 구분자·제어문자만 죽는다(UploadedDocumentStore.safeFilename).
        return productUploads.originalFilenameOf(productId)
                .map(UploadedDocumentStore::safeFilename)
                .orElseGet(() -> resolved.getFileName().toString());
    }

    /**
     * {@code base} 안으로만 상대경로를 해석한다. {@code ..} 등으로 기준을 벗어나면 거부한다.
     * 입력을 통제할 수 있도록 정적·패키지 가시성으로 둔다 — 경로 안전 단위 테스트가 직접 부른다.
     */
    static Path resolveWithin(Path base, String relative) {
        Path baseAbs = base.toAbsolutePath().normalize();
        Path resolved = baseAbs.resolve(relative).normalize();
        if (!resolved.startsWith(baseAbs)) {
            throw new IllegalArgumentException("문서 경로가 기준 디렉토리를 벗어난다: " + relative);
        }
        return resolved;
    }
}
