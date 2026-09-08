package com.sphinxfin.sphinx.core.extraction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ProductDocuments} 경로 안전(#412)과 실패 구별(#433). 소유: 강희진
 *
 * <p>지금 매핑은 고정 상수라 {@code ..} 가 들어올 자리가 없지만, 업로드 배선(#401)이 붙으면
 * 경로가 외부 입력에서 온다 — 그때 이 가드가 없으면 기준 디렉토리 밖 파일이 조회로 새어
 * 나간다. 방어가 실제로 막는지를 여기서 고정한다.
 */
@DisplayName("상품 문서 경로 안전·실패 구별 (이슈 #412 · #433)")
class ProductDocumentsTest {

    private static final Path BASE = Path.of("/srv/data");

    @Test
    @DisplayName("정상 상대경로는 기준 디렉토리 안으로 해석된다")
    void resolvesNormalRelativePathWithinBase() {
        Path resolved = ProductDocuments.resolveWithin(BASE, "documents/a.pdf");
        assertThat(resolved).isEqualTo(BASE.toAbsolutePath().normalize().resolve("documents/a.pdf"));
    }

    @Test
    @DisplayName("❗상위 이탈(..)은 거부한다")
    void rejectsParentTraversal() {
        assertThatThrownBy(() -> ProductDocuments.resolveWithin(BASE, "../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("벗어난다");
    }

    @Test
    @DisplayName("❗절대경로도 기준 밖이면 거부한다")
    void rejectsAbsoluteEscape() {
        assertThatThrownBy(() -> ProductDocuments.resolveWithin(BASE, "/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("❗매핑엔 있는데 파일이 없으면 500(IllegalState) — 없는 상품 404 와 구별한다 (이슈 #433)")
    void missingFileOnDiskIsIllegalStateNot404(@TempDir Path dataDir) {
        // 상품은 매핑에 있어 documentPathOf 가 경로를 주는데(=없는 상품 아님), 그 파일이
        // 디스크에 없다 = 컨테이너에 data/documents 미마운트(배포 문제). 없는 상품(404)과
        // 같은 코드로 접으면 S-02 에서 원본 404 가 상품 문제인지 배포 문제인지 안 갈린다.
        ProductRiskItems risk = mock(ProductRiskItems.class);
        when(risk.documentPathOf("doc-x")).thenReturn("documents/missing.pdf");
        // 업로드 조회는 이 단정과 무관하다 — 파일명(Content-Disposition)에만 쓰이고, 여기서
        // 재는 것은 파일이 없을 때의 예외 종류다. 목이 empty 를 내면 예전 경로 그대로다.
        ProductUploads uploads = mock(ProductUploads.class);
        ProductDocuments docs = new ProductDocuments(dataDir.toString(), risk, uploads);

        assertThatThrownBy(() -> docs.open("doc-x"))
                .isInstanceOf(IllegalStateException.class)   // → 500 INTERNAL_ERROR, 404 아님
                .hasMessageContaining("data/documents");
    }
}
