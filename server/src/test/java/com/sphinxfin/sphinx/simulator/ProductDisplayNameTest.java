package com.sphinxfin.sphinx.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sphinxfin.sphinx.api.MockData;
import com.sphinxfin.sphinx.api.dto.ProductSummary;
import com.sphinxfin.sphinx.core.simulator.SimulatorProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가명 규약 — 상품 실명이 응답으로 나가지 않는다. 소유: 정세현 (PR #202 리뷰)
 *
 * <p>기획서: <b>"데모와 제출물에서는 상품명과 발행사를 가명 처리하고 조건만 인용한다."</b>
 * 공시 문서라 열람은 자유롭지만 제출물에 실명을 싣는 건 다른 문제다.
 *
 * <h2>왜 이 대조가 따로 있나</h2>
 *
 * <p>{@code #202} 에서 {@code SimulationScenarios} 가 {@code KIWOOM_4181.name()} 을 그대로
 * {@code productName} 에 실었다. 컴파일도 되고 테스트도 전부 초록이었는데, 화면에서는
 * S-04 머리말이 발행사 실명을 달고 S-02 목록은 가명이라 <b>한 화면 안에서 규약이 갈렸다.</b>
 * 규약은 문서에만 있었고 코드에는 없었다.
 *
 * <p>지금은 필드가 {@code sourceName}(원문) / {@code displayName}(가명) 으로 갈려 있어서
 * 반사적으로 실명을 꺼내기는 어려워졌다. 그래도 <b>새 필드·새 응답이 생길 때</b>는 같은 일이
 * 다시 가능하므로, 문면 하나가 아니라 <b>직렬화된 응답 전체</b>를 본다.
 */
@DisplayName("가명 규약 — 상품 실명이 응답으로 나가지 않는다")
class ProductDisplayNameTest {

    /** Gradle 테스트 작업 디렉토리는 {@code server/} 다. */
    private static final String TIMESERIES_DIR = "../data/timeseries";

    /** S-02 목록에서 이 상품을 찾는 키. 파싱 산출물의 document_id 라 가명 대상이 아니다. */
    private static final String PRODUCT_ID = "doc-els-kiwoom-4181";

    private final SimulationScenarios scenarios =
            new SimulationScenarios(new SimulatorProperties(TIMESERIES_DIR));

    /**
     * ❗<b>이 단정이 먼저다.</b> {@code sourceName} 이 실명이 아니게 되면 아래 누출 검사들이
     * 전부 <b>아무것도 안 재면서 초록</b>이 된다 — 찾을 문자열이 없어지기 때문이다.
     */
    @Test
    @DisplayName("★ sourceName 은 실제로 발행사 실명이다 — 아니면 아래 검사가 공회전한다")
    void theSourceNameIsActuallyTheRealName() {
        assertThat(SimulatorService.KIWOOM_4181.sourceName())
                .as("원문 상품명이 아니게 됐다면 조건 출처 대조가 근거를 잃은 것이고, "
                        + "동시에 이 파일의 누출 검사가 전부 무의미해진다")
                .contains("키움증권");
    }

    @Test
    @DisplayName("❗직렬화된 /simulate 응답 어디에도 발행사 실명이 없다")
    void theRealNameNeverReachesTheWire() throws Exception {
        assertThat(Path.of(TIMESERIES_DIR)).as("커밋된 디렉토리다 — 없으면 작업 디렉토리가 server/ 가 아니다")
                .matches(Files::isDirectory);

        // `findAndRegisterModules()` 가 있어야 `PathMeta` 의 LocalDate 가 직렬화된다.
        // 날짜 표기(ISO 문자열 / 배열)는 Boot 설정에 달렸고 여기서 재는 것이 아니다 —
        // 이 테스트가 보는 것은 **어떤 문자열이 응답에 들어 있는가** 뿐이다.
        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(scenarios.view(50_000_000L));

        // 문면 하나가 아니라 응답 전체를 본다. `productName` 만 재면 나중에 시나리오 이름이나
        // 새 필드로 같은 값이 새어 나가는 것을 못 잡는다 — #202 가 그 모양이었다.
        assertThat(json)
                .as("S-04 머리말은 이 값을 그대로 찍는다(web/src/pages/S04_Simulator.tsx). "
                        + "발행사 실명이 여기 있으면 시연·제출 캡처에 그대로 남는다")
                .doesNotContain(SimulatorService.KIWOOM_4181.sourceName())
                .doesNotContain("키움");
    }

    /**
     * ❗<b>같은 상품이 화면마다 다른 이름으로 보이면 안 된다.</b> S-02 목록에서 고른 상품을
     * S-04 에서 시뮬레이션하는 것이 데모 흐름이라, 두 문면이 갈리면 같은 상품인지가 안 보인다.
     *
     * <p>{@code MockData} 는 각 모듈 구현이 붙으면 <b>삭제될 파일</b>이다(CLAUDE.md). 그때 이
     * 테스트는 컴파일에서 깨지고, 그게 의도다 — 목을 걷는 사람이 S-02 표시명의 새 근거가
     * 어디인지 정하고 여기를 그쪽으로 다시 걸어야 한다. 조용히 두 벌로 갈리는 것보다 낫다.
     */
    @Test
    @DisplayName("❗S-02 목록과 S-04 머리말이 같은 문면을 쓴다")
    void oneProductHasOneNameAcrossScreens() {
        String listName = MockData.PRODUCTS.stream()
                .filter(p -> PRODUCT_ID.equals(p.productId()))
                .map(ProductSummary::name)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        PRODUCT_ID + " 가 MockData.PRODUCTS 에 없다 — 목을 걷었다면 S-02 표시명의 "
                                + "새 근거를 정하고 이 테스트를 그쪽으로 건다"));

        assertThat(SimulatorService.KIWOOM_4181.displayName())
                .as("S-02 목록(%s)과 S-04 머리말이 갈렸다", PRODUCT_ID)
                .isEqualTo(listName);
    }
}
