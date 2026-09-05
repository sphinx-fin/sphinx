package com.sphinxfin.sphinx.core.extraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;

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

    /**
     * 경로를 String 으로 받아 직접 {@link Path#of}로 변환한다 — {@code SimulatorProperties}가
     * 같은 이유(리소스 경로 변환기가 상대경로의 {@code ..}를 null 로 정규화)로 그렇게 한다.
     */
    public ProductDocuments(@Value("${sphinx.documents.data-dir}") String dataDir,
                            ProductRiskItems productRiskItems) {
        // 빈 값은 설정 오류다 — Spring 의 ${VAR:기본값} 은 환경변수가 빈 문자열이면 그것을
        // 값으로 취급해 기본값이 죽는다(SimulatorProperties 주석과 같은 함정).
        if (dataDir == null || dataDir.isBlank()) {
            throw new IllegalStateException(
                    "sphinx.documents.data-dir 이 비어 있다. 환경변수 SPHINX_DATA_DIR 을 빈 값으로 "
                    + "두면 기본값이 적용되지 않는다 — 지우거나 실제 경로를 넣는다.");
        }
        this.dataDir = Path.of(dataDir);
        this.productRiskItems = productRiskItems;
    }

    /** 조회 결과 — 파일명(Content-Disposition 용)과 바이트. */
    public record Document(String filename, byte[] bytes) {}

    /**
     * 상품의 원문 문서를 읽는다.
     *
     * @throws NoSuchElementException 등록된 문서가 없는 상품이거나 파일이 디스크에 없다(→ 404)
     */
    public Document open(String productId) {
        String relative = productRiskItems.documentPathOf(productId);   // 없는 상품이면 404
        Path resolved = resolveWithin(dataDir, relative);
        if (!Files.isRegularFile(resolved)) {
            // 매핑엔 있는데 사전적재 파일이 없는 배포 상태다 — "없는 상품"과 같은 404 로 낸다.
            throw new NoSuchElementException("상품 문서 파일이 없다: " + resolved);
        }
        try {
            return new Document(resolved.getFileName().toString(), Files.readAllBytes(resolved));
        } catch (IOException e) {
            // 파일이 있는데 못 읽는 것은 설정·권한 문제다 — 400 이 아니라 500(INTERNAL_ERROR).
            throw new UncheckedIOException("상품 문서를 읽지 못했다: " + resolved, e);
        }
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
