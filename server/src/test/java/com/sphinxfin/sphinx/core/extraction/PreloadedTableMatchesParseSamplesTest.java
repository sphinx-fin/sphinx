package com.sphinxfin.sphinx.core.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사전적재 표의 <b>상품유형·문서 경로</b>가 계약 샘플과 같은지 본다 (이슈 #403). 소유: 강희진
 *
 * <h2>❗업로드본에는 있고 사전적재에는 없던 방어</h2>
 *
 * <p>업로드 경로는 <b>파스가 판별한 상품유형이 요청값을 이긴다</b>({@code DocumentUploadWiringTest}) —
 * 올린 사람이 변액을 ELS 로 등록해도 파스 결과가 그것을 덮는다. 그런데 <b>사전적재 2종은 그
 * 방어를 안 지난다.</b> 유형이 {@code ProductRiskItems} 의 표에서 오고, {@link
 * ProductRiskItems#extract} 는 그 값을 <b>파스에 넘기는 입력</b>으로 쓴다.
 *
 * <p>그래서 표의 한 줄이 틀리면 <b>그 유형의 템플릿으로 문서를 읽는다</b> — 변액 문서를 ELS
 * 템플릿으로 읽으면 항목이 어긋나고, 그 항목이 오해 유형 필터
 * ({@code misconception.applies_to})의 입력이라 <b>판정이 조용히 틀린다.</b> 에러도 로그도
 * 없다(PR #572 리뷰 지적).
 *
 * <h2>대조할 기준이 계약에 이미 있다</h2>
 *
 * <p>{@code contracts/samples/parsed_*.json} 은 <b>그 두 문서의 파스 출력</b>이다. 즉 «이
 * 문서를 파스하면 어떤 유형이 나오는가» 의 계약값이 이미 커밋돼 있다 — ai-service 를 띄우지
 * 않고 표를 그것과 맞출 수 있다.
 *
 * <pre>
 *   parsed_els_sample.json       doc-els-kiwoom-4181    ELS                  els_kiwoom_4181_simple_prospectus.pdf
 *   parsed_variable_sample.json  doc-var-samsung-b2601  VARIABLE_INSURANCE   var_samsung_b2601_product_summary.pdf
 * </pre>
 *
 * <p>❗{@code documentId} 도 같이 맞춘다(결정 1.37 · 이슈 #528). 그 값이
 * {@code /internal/parse} 로 나가고 {@code extracted_risk_items.document_id} 에 쌓이므로,
 * 표와 계약이 다른 값을 들면 <b>그 열에 두 규칙의 값이 섞인다.</b>
 *
 * <p>문서 경로까지 보는 이유는 <b>유형만 맞추면 짝이 안 잡히기 때문</b>이다 — 두 상품의
 * 유형이 서로 다르므로 유형만으로도 뒤바뀜은 잡히지만, 표가 <b>같은 유형의 다른 문서</b>를
 * 가리키게 되는 것은 경로를 봐야 잡힌다({@code var_samsung_b2601} 은 문서가 3편이다).
 */
@DisplayName("사전적재 표 — 상품유형·문서 경로가 계약 샘플과 같다")
class PreloadedTableMatchesParseSamplesTest {

    /** Gradle 테스트 작업 디렉토리는 {@code server/} 다. */
    private static final Path SAMPLES = Path.of("../contracts/samples");

    private final ObjectMapper mapper = new ObjectMapper();

    /** 계약 샘플의 {@code document_id} → (상품유형, 원본 파일명). */
    private Map<String, String[]> parseSamples() throws Exception {
        Map<String, String[]> out = new LinkedHashMap<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.list(SAMPLES)) {
            walk.filter(p -> p.getFileName().toString().startsWith("parsed_")
                            && p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(files::add);
        }
        // ★ 이 단정이 먼저다 — 샘플을 못 찾으면 아래 대조가 아무것도 안 재면서 초록이 된다.
        assertThat(files).as("%s 에 parsed_*.json 이 없다 — 샘플이 옮겨졌으면 이 대조도 같이 "
                + "옮긴다", SAMPLES).isNotEmpty();

        for (Path f : files) {
            JsonNode n = mapper.readTree(Files.readString(f));
            JsonNode id = n.get("document_id");
            if (id == null) {
                continue;
            }
            out.put(id.asText(), new String[] {
                    n.path("product_type").asText(), n.path("source_file").asText(),
                    id.asText() });
        }
        return out;
    }

    @Test
    @DisplayName("❗표의 상품유형이 그 문서의 파스 출력과 같다 — 틀리면 그 템플릿으로 문서를 읽는다")
    void theProductTypeMatchesTheContractSample() throws Exception {
        Map<String, String[]> samples = parseSamples();
        int matched = 0;
        for (ProductRiskItems.Preloaded p : ProductRiskItems.preloaded()) {
            String[] sample = samples.get(p.documentId());
            assertThat(sample)
                    .as("사전적재 %s 의 documentId(%s)에 대응하는 계약 샘플이 없다 — 표에 "
                            + "문서를 더했으면 contracts/samples 에 그 문서의 파스 출력도 "
                            + "있어야 한다. 없으면 유형이 맞는지 확인할 기준이 없다",
                            p.productId(), p.documentId())
                    .isNotNull();
            assertThat(p.productType())
                    .as("사전적재 표의 상품유형이 계약 샘플과 다르다(%s). 업로드본은 파스가 "
                            + "요청값을 이겨서 막히는데 사전적재는 이 표가 파스의 입력이다 — "
                            + "틀리면 그 유형의 템플릿으로 문서를 읽고, 그 항목이 오해 필터의 "
                            + "입력이라 판정이 조용히 틀린다", p.productId())
                    .isEqualTo(sample[0]);
            matched++;
        }
        // ★ 표가 비면 위 루프가 한 번도 안 돌고 초록이 된다.
        assertThat(matched).as("대조한 상품이 없다 — 사전적재 표가 비었다").isEqualTo(2);
    }

    @Test
    @DisplayName("❗표의 documentId 가 그 샘플의 document_id 다 — 파스에 넘기는 값이라 규칙이 하나여야 한다")
    void theDocumentIdMatchesTheContractSample() throws Exception {
        Map<String, String[]> samples = parseSamples();
        for (ProductRiskItems.Preloaded p : ProductRiskItems.preloaded()) {
            // ❗재는 것은 «그 documentId 가 어느 계약 샘플의 document_id 다» 다. 값 비교로
            //   적으면 조회 키와 같은 값을 견주게 되어 구성상 항상 참이 된다(PR #576 리뷰).
            assertThat(samples.get(p.documentId()))
                    .as("사전적재 %s 의 documentId(%s)가 어느 계약 샘플의 document_id 도 "
                            + "아니다. 이 값이 /internal/parse 로 나가고 "
                            + "extracted_risk_items.document_id 에 쌓인다 — 규칙이 두 벌이면 "
                            + "«이 항목이 어느 파스에서 왔나» 에 답할 수 없다(결정 1.37)",
                            p.productId(), p.documentId())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("❗표의 문서 경로가 그 샘플의 원본 파일명으로 끝난다 — 같은 유형의 다른 문서를 가리킬 수 있다")
    void theDocumentPathMatchesTheContractSample() throws Exception {
        Map<String, String[]> samples = parseSamples();
        for (ProductRiskItems.Preloaded p : ProductRiskItems.preloaded()) {
            String[] sample = samples.get(p.documentId());
            // ★ 먼저 짚는다 — 없는 채로 색인하면 NullPointerException 이 나고, 그 실패는
            //   무엇을 고쳐야 하는지 아무것도 안 알려준다(실측: 이 가드 전에 그랬다).
            assertThat(sample)
                    .as("사전적재 %s 의 documentId(%s)에 대응하는 계약 샘플이 없다",
                            p.productId(), p.documentId())
                    .isNotNull();
            String sourceFile = sample[1];
            assertThat(sourceFile)
                    .as("계약 샘플에 source_file 이 없다 — 그러면 경로를 맞출 기준이 없다")
                    .isNotEmpty();
            assertThat(p.documentPath())
                    .as("사전적재 표의 문서 경로가 계약 샘플의 원본과 다르다(%s). 같은 유형의 "
                            + "다른 문서를 가리키면 유형 대조만으로는 안 잡힌다 — "
                            + "var_samsung_b2601 은 문서가 3편이다", p.productId())
                    .endsWith("/" + sourceFile);
        }
    }
}
