package com.sphinxfin.sphinx.core.extraction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사전적재 표가 <b>순서 있는 리터럴</b>인지 본다 (이슈 #403). 소유: 강희진
 *
 * <h2>왜 응답을 재는 대조로는 안 되나</h2>
 *
 * <p>{@code DocumentUploadWiringTest} 가 <i>"응답 순서 = 표 순서"</i> 를 이미 잰다. 그런데
 * 기대값과 실제값이 <b>같은 표</b>를 읽으므로, 표 자신이 {@code Map} 파생으로 돌아가면
 * <b>둘이 같이 움직여서 어긋날 자리가 없다</b> — 되돌려 8회 돌려 8/8 초록이었다
 * (PR #572 리뷰 실측).
 *
 * <p>리터럴 순서를 {@code containsExactly} 로 박는 것도 답이 아니다. {@code Map.of} 의 반복
 * 순서는 JVM 마다 정해지는 값이라 <b>그 순서가 나오는 JVM 에서는 초록</b>이다 —
 * <i>"초록인데 안 재고 있다"</i> 를 만드는 확률 그물이다.
 *
 * <p>그래서 <b>소스 문면</b>을 본다. 지켜야 하는 성질이 «값» 이 아니라 «선언 형태» 라서
 * 그 층에서 재는 것이 정확하다.
 *
 * <h2>무엇이 걸려 있나</h2>
 *
 * <p>이 표는 {@code GET /products} 로 나가고 그 순서가 S-02 화면의 목록 순서다. {@code Map}
 * 이면 두 상품이 <b>배포마다 뒤바뀔 수 있다</b> — 응답 스키마도 테스트도 초록인 채로, 데모
 * 리허설과 실제 시연에서 목록이 다르게 보인다.
 */
@DisplayName("사전적재 표 — 순서가 선언에 있다 (S-02 목록 순서)")
class PreloadedTableIsOrderedTest {

    /** Gradle 테스트 작업 디렉토리는 {@code server/} 다. */
    private static final Path SOURCE = Path.of(
            "src/main/java/com/sphinxfin/sphinx/core/extraction/ProductRiskItems.java");

    private String source() throws Exception {
        String src = Files.readString(SOURCE);
        // ★ 이 단정이 먼저다 — 파일이 안 읽히면 아래 대조가 아무것도 안 재면서 초록이 된다.
        assertThat(src).as("%s 를 못 읽었다 — 파일이 옮겨졌으면 이 대조도 같이 옮긴다", SOURCE)
                .contains("class ProductRiskItems");
        return src;
    }

    @Test
    @DisplayName("❗PRELOADED 가 List 리터럴이다 — Map 이면 S-02 목록 순서가 배포마다 바뀐다")
    void thePreloadedTableIsDeclaredAsAnOrderedLiteral() throws Exception {
        assertThat(source())
                .as("사전적재 표의 선언이 기대한 형태가 아니다. 둘 중 하나다 — (1) 순서 없는 "
                        + "자료구조로 바뀌었다: 이 표는 GET /products 로 나가고 그 순서가 S-02 "
                        + "목록 순서인데 Map.of 는 반복 순서를 보장하지 않아 두 상품이 배포마다 "
                        + "뒤바뀐다(조회는 파생 색인 BY_ID 가 받는다). (2) 이름·형식만 바뀌었다: "
                        + "그러면 이 대조를 그쪽으로 고친다 — 안 고치면 조용히 통과한다")
                .contains("private static final List<Preloaded> PRELOADED = List.of(");
    }

    @Test
    @DisplayName("★ 표에 두 상품이 리터럴로 있다 — 없으면 위 단정이 형식만 보고 통과한다")
    void theLiteralActuallyHoldsTheTwoDemoProducts() throws Exception {
        String src = source();
        assertThat(src).contains("\"doc-els-kiwoom-4181\"");
        assertThat(src).contains("\"doc-var-samsung-b2601\"");
    }
}
