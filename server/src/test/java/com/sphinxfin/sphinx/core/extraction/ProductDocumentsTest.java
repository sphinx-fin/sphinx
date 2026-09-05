package com.sphinxfin.sphinx.core.extraction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ProductDocuments#resolveWithin} 경로 안전 (이슈 #412). 소유: 강희진
 *
 * <p>지금 매핑은 고정 상수라 {@code ..} 가 들어올 자리가 없지만, 업로드 배선(#401)이 붙으면
 * 경로가 외부 입력에서 온다 — 그때 이 가드가 없으면 기준 디렉토리 밖 파일이 조회로 새어
 * 나간다. 방어가 실제로 막는지를 여기서 고정한다.
 */
@DisplayName("상품 문서 경로 안전 (이슈 #412)")
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
}
