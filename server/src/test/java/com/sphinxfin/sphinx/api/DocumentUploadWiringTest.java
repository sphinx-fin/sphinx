package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.core.aiservice.DocumentUnreadableException;
import com.sphinxfin.sphinx.core.extraction.ExtractedRiskItemRepository;
import com.sphinxfin.sphinx.core.extraction.UploadedProduct;
import com.sphinxfin.sphinx.core.extraction.UploadedProductRepository;
import com.sphinxfin.sphinx.domain.ParsedDocument;
import com.sphinxfin.sphinx.domain.RiskItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-EXT-001 업로드 실배선 (이슈 #521). 소유: 강희진
 *
 * <p>고치기 전의 결함이 <b>조용했다</b>는 것이 이 파일의 출발점이다.
 * {@code POST /products/documents} 는 200 을 냈고 화면도 다음 단계로 넘어갔는데,
 * {@code file} 과 {@code productType} 을 <b>둘 다 안 읽었다</b> — 파일은 버려지고, 변액을
 * 올려도 ELS 가 돌아왔다. 그런데도 그 경로는 audited 라 감사 로그에는 "상품을 등록했다"
 * 가 남았다. 그래서 재는 것이 <b>응답 모양이 아니라 «올린 파일이 실제로 쓰였는가»</b> 다.
 *
 * <p>재는 것:
 * <ol>
 *   <li>올린 <b>바이트가 디스크에 그대로</b> 있고, 그 경로가 DB 행에 남는다.</li>
 *   <li>{@code documentPathOf} 가 <b>업로드본</b>을 낸다 — 그래서 {@code POST /{id}/extract}
 *       가 사전적재 문서가 아니라 방금 올린 파일을 파스한다.</li>
 *   <li>{@code GET /products} 에 그 상품이 뜬다 — 업로드가 S-02 선택 목록에 도달한다.</li>
 *   <li>같은 파일을 두 번 올려도 <b>상품이 하나</b>다(내용 주소 · unique 제약).</li>
 *   <li>문서를 못 연 것(ai-service 422)은 <b>200 + parse_failed</b> 이고, 그 행도 남는다 —
 *       502 로 새면 운영자가 «서비스 장애» 로 읽고 문서를 의심하지 않는다.</li>
 *   <li>PDF 아님·빈 파일은 <b>400 VALIDATION_ERROR</b> 이고 ai-service 를 안 부른다.</li>
 *   <li>파일명의 {@code ../} 는 기준 디렉토리 밖에 아무것도 못 만든다.</li>
 * </ol>
 *
 * <p>ai-service 는 목이다 — 재는 것은 서버의 저장·배선이지 파싱 품질이 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-EXT-001 업로드 실배선 (이슈 #521)")
class DocumentUploadWiringTest {

    /** 최소한의 PDF 처럼 보이는 바이트. 저장은 앞머리 5바이트만 본다(파싱은 목이다). */
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n"
            .getBytes(StandardCharsets.UTF_8);

    @Autowired private MockMvc mvc;
    @Autowired private UploadedProductRepository uploads;
    @Autowired private ExtractedRiskItemRepository extracted;
    @MockBean private AiServiceClient aiServiceClient;

    /** 저장 위치를 서비스와 <b>같은 설정 값</b>으로 잡는다 — 따로 적으면 둘이 갈린다. */
    @Value("${sphinx.documents.data-dir}") String dataDir;

    @AfterEach
    void cleanUp() throws IOException {
        // 이 컨텍스트(H2)는 다른 통합 테스트와 공유된다. 업로드본을 남기면 그쪽의
        // "GET /products 는 데모 2종" 전제가 깨진다 — RealExtractionWiringTest 가 스냅샷을
        // 지우는 것과 같은 이유다.
        uploads.deleteAll();
        extracted.deleteAll();
        Path uploadsDir = Path.of(dataDir, "uploads");
        if (Files.isDirectory(uploadsDir)) {
            try (Stream<Path> walk = Files.walk(uploadsDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 정리 실패는 테스트 결과가 아니다 — 다음 실행이 덮어쓴다.
                    }
                });
            }
        }
    }

    private ParsedDocument parsed(String productType) {
        return new ParsedDocument("doc-any", productType, "x.pdf", "0.3.0", null, 1,
                List.of(new ParsedDocument.Page(1, "본문", 2)), List.of(), List.of());
    }

    /* ── ①②③ 올린 파일이 저장되고, 그 경로가 추출로 흐른다 ─────────────────── */

    @Test
    @DisplayName("★ 올린 바이트가 디스크에 남고, 추출이 그 파일을 파스한다 — 사전적재 문서가 아니다")
    void uploadedBytesAreStoredAndFedToParse() throws Exception {
        when(aiServiceClient.parse(anyString(), anyString())).thenReturn(parsed("ELS"));

        String productId = upload("els_prospectus.pdf", "ELS", PDF);

        // ① 행이 남는다
        UploadedProduct row = uploads.findByProductId(productId).orElseThrow();
        assertThat(row.status()).isEqualTo("parsed");
        assertThat(row.originalFilename()).isEqualTo("els_prospectus.pdf");
        assertThat(row.documentPath())
                .as("경로는 data-dir 상대다 — ai-service SPHINX_DATA_DIR 규약. 디렉토리는 "
                        + "sha256 이고 이름은 마지막 조각으로만 들어간다")
                .matches("uploads/[0-9a-f]{64}/els_prospectus\\.pdf");

        // ① 바이트가 그대로 있다. **이 단정이 옛 스텁을 잡는 자리다** — 스텁도 200 을 냈다.
        Path stored = Path.of(dataDir, row.documentPath());
        assertThat(Files.readAllBytes(stored)).isEqualTo(PDF);

        // ② 업로드가 그 경로로 파스를 불렀다
        verify(aiServiceClient).parse(eq(row.documentPath()), eq("ELS"));

        // ② 추출도 같은 경로를 쓴다 — documentPathOf 가 업로드본을 먼저 본다는 뜻이다.
        //    사전적재 DEMO_DOCUMENTS 로 떨어지면 이 검증이 documents/… 를 보게 된다.
        when(aiServiceClient.extract(anyString(), any())).thenReturn(
                new AiServiceClient.ExtractResult(List.of(RiskItem.extracted(
                        "UP-ITEM", productId, "원금손실 조건", "required",
                        new RiskItem.Condition("원문 인용",
                                new RiskItem.SourceSpan(1, 0, 2)))), List.of()));
        mvc.perform(post("/products/{id}/extract", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].itemId").value("UP-ITEM"));
        verify(aiServiceClient, org.mockito.Mockito.times(2))
                .parse(eq(row.documentPath()), eq("ELS"));

        // ③ S-02 선택 목록에 도달한다. 데모 2종도 그대로 남는다(#403 이 걷는다).
        mvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].productId").value(productId))
                .andExpect(jsonPath("$.data[0].name").value("els prospectus"))
                .andExpect(jsonPath("$.data[0].status").value("parsed"))
                .andExpect(jsonPath("$.data[?(@.productId == 'doc-els-kiwoom-4181')]")
                        .isNotEmpty());
    }

    @Test
    @DisplayName("★ 파스가 판별한 상품유형이 요청값을 이긴다 — 변액을 ELS 로 등록하면 오해 필터가 조용히 틀린다")
    void parsedProductTypeWinsOverTheRequestedOne() throws Exception {
        when(aiServiceClient.parse(anyString(), anyString()))
                .thenReturn(parsed("VARIABLE_INSURANCE"));

        String productId = upload("var_summary.pdf", "ELS", PDF);

        assertThat(uploads.findByProductId(productId).orElseThrow().productType())
                .as("요청은 ELS 였지만 문서는 변액이다 — 요청값을 믿으면 오해 유형 필터"
                        + "(misconception.applies_to)가 틀린 채로 돈다")
                .isEqualTo("VARIABLE_INSURANCE");
    }

    /* ── ④ 같은 파일은 같은 상품 ──────────────────────────────────────────── */

    @Test
    @DisplayName("★ 같은 파일을 두 번 올려도 상품이 하나다 — productId 가 내용 주소다")
    void reUploadingTheSameFileKeepsOneProduct() throws Exception {
        when(aiServiceClient.parse(anyString(), anyString())).thenReturn(parsed("ELS"));

        String first = upload("same.pdf", "ELS", PDF);
        String second = upload("same.pdf", "ELS", PDF);

        assertThat(second).isEqualTo(first);
        assertThat(uploads.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("★ 내용이 다르면 파일명이 같아도 다른 상품이다 — 문서가 바뀌면 판정 근거가 바뀐다")
    void differentBytesMakeADifferentProduct() throws Exception {
        when(aiServiceClient.parse(anyString(), anyString())).thenReturn(parsed("ELS"));

        String first = upload("same.pdf", "ELS", PDF);
        String second = upload("same.pdf", "ELS",
                "%PDF-1.7\n(다른 문서)\n%%EOF\n".getBytes(StandardCharsets.UTF_8));

        assertThat(second).isNotEqualTo(first);
        assertThat(uploads.findAll()).hasSize(2);
    }

    /* ── ⑤ 문서를 못 연 것과 서비스 장애를 가른다 ────────────────────────── */

    @Test
    @DisplayName("★ 문서를 못 열면 200 + parse_failed 다 — 502 로 새면 운영자가 문서를 의심하지 않는다")
    void unreadableDocumentLandsAsParseFailedNotFiveOhTwo() throws Exception {
        when(aiServiceClient.parse(anyString(), anyString()))
                .thenThrow(new DocumentUnreadableException("문서를 열 수 없다: HTTP 422"));

        String body = mvc.perform(multipart("/products/documents")
                        .file(new MockMultipartFile("file", "locked.pdf",
                                "application/pdf", PDF))
                        .param("productType", "ELS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("parse_failed"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String productId = com.jayway.jsonpath.JsonPath.read(body, "$.data.productId");
        UploadedProduct row = uploads.findByProductId(productId).orElseThrow();
        assertThat(row.status()).isEqualTo("parse_failed");
        assertThat(row.failureReason()).isNotBlank();

        // ❗행을 지우지 않는다 — 감사 로그의 "등록했다" 가 가리킬 것이 없어지고, 운영자는
        //   자기가 올린 문서가 왜 안 보이는지 알 길이 없다(E-EXT-03 은폐 금지).
        mvc.perform(get("/products"))
                .andExpect(jsonPath("$.data[0].status").value("parse_failed"));
    }

    @Test
    @DisplayName("★ ai-service 가 죽은 것은 parse_failed 가 아니다 — 502 로 올라가고 상품이 안 생긴다")
    void aiServiceOutageIsNotAParseFailure() throws Exception {
        when(aiServiceClient.parse(anyString(), anyString()))
                .thenThrow(new com.sphinxfin.sphinx.core.aiservice.AiServiceException(
                        "ai-service 호출 실패"));

        mvc.perform(multipart("/products/documents")
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf", PDF))
                        .param("productType", "ELS"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("AI_SERVICE_UNAVAILABLE"));

        assertThat(uploads.findAll())
                .as("파스 실패가 문서 문제가 아니면 상품을 만들지 않는다 — 트랜잭션이 굴러야 한다")
                .isEmpty();
    }

    /* ── ⑥ 입력 검증은 ai-service 앞에서 ────────────────────────────────────── */

    @Test
    @DisplayName("★ PDF 가 아니면 400 이고 ai-service 를 부르지 않는다 — Content-Type 을 믿지 않는다")
    void nonPdfIsRejectedBeforeCallingAiService() throws Exception {
        // ❗Content-Type 은 application/pdf 라고 주장한다. 앞머리 바이트가 근거다.
        mvc.perform(multipart("/products/documents")
                        .file(new MockMultipartFile("file", "fake.pdf", "application/pdf",
                                "PK zip 입니다".getBytes(StandardCharsets.UTF_8)))
                        .param("productType", "ELS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        org.mockito.Mockito.verifyNoInteractions(aiServiceClient);
        assertThat(uploads.findAll()).isEmpty();
    }

    @Test
    @DisplayName("★ 빈 파일은 400 이다")
    void emptyFileIsRejected() throws Exception {
        mvc.perform(multipart("/products/documents")
                        .file(new MockMultipartFile("file", "empty.pdf", "application/pdf",
                                new byte[0]))
                        .param("productType", "ELS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("★ 모르는 상품유형은 400 이다 — 기본값으로 조용히 떨어뜨리지 않는다")
    void unknownProductTypeIsRejected() throws Exception {
        mvc.perform(multipart("/products/documents")
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf", PDF))
                        .param("productType", "FUND"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        org.mockito.Mockito.verifyNoInteractions(aiServiceClient);
    }

    /* ── ⑦ 파일명이 경로를 정하지 않는다 ────────────────────────────────────── */

    @Test
    @DisplayName("❗파일명의 ../ 는 기준 디렉토리 밖에 아무것도 못 만든다 — 경로는 sha256 이 정한다")
    void filenameCannotEscapeTheDataDirectory() throws Exception {
        when(aiServiceClient.parse(anyString(), anyString())).thenReturn(parsed("ELS"));

        String productId = upload("../../../../tmp/escaped.pdf", "ELS", PDF);

        UploadedProduct row = uploads.findByProductId(productId).orElseThrow();
        assertThat(row.documentPath())
                .as("업로더가 준 이름은 경로 결정에 안 쓴다 — 디렉토리는 sha256 이고, "
                        + "이름은 정제된 마지막 조각뿐이다")
                .matches("uploads/[0-9a-f]{64}/[^/]+")
                .doesNotContain("..")
                .doesNotContain("tmp/");
        Path resolved = Path.of(dataDir, row.documentPath()).toAbsolutePath().normalize();
        assertThat(resolved)
                .as("실제 파일이 기준 디렉토리 안에 있어야 한다")
                .startsWith(Path.of(dataDir).toAbsolutePath().normalize());
        assertThat(Files.isRegularFile(resolved)).isTrue();
    }

    /* ── ⑧ 원문 조회가 원래 파일명을 낸다 ──────────────────────────────────── */

    @Test
    @DisplayName("★ 원문 조회의 파일명이 «올린 이름» 이다 — 경로에서 뽑으면 9f2a….pdf 를 받는다")
    void theDownloadKeepsTheUploadersFilename() throws Exception {
        when(aiServiceClient.parse(anyString(), anyString())).thenReturn(parsed("ELS"));

        String productId = upload("els_prospectus.pdf", "ELS", PDF);

        mvc.perform(get("/products/{id}/document", productId))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition",
                                org.hamcrest.Matchers.containsString("els_prospectus.pdf")));
    }

    @Test
    @DisplayName("★ 정제로 접힌 특수문자도 받는 파일명에는 원문 그대로다 — DB 가 원문을 든다")
    void theDownloadUsesTheRawNameNotTheSanitisedPathSegment() throws Exception {
        when(aiServiceClient.parse(anyString(), anyString())).thenReturn(parsed("ELS"));

        // 저장 경로에서는 괄호가 `_` 로 접힌다. 받는 파일 이름은 올린 그대로여야 한다.
        String productId = upload("ELS(제4181회) 설명서.pdf", "ELS", PDF);

        assertThat(uploads.findByProductId(productId).orElseThrow().documentPath())
                .as("저장 경로는 정제된 이름이다")
                .doesNotContain("(");
        mvc.perform(get("/products/{id}/document", productId))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition",
                                org.hamcrest.Matchers.containsString("4181")));
    }

    @Test
    @DisplayName("★ 사전적재 상품은 예전대로 경로의 파일명을 쓴다 — 그쪽은 그게 사람이 읽는 이름이다")
    void preloadedProductsKeepTheirPathFilename() throws Exception {
        mvc.perform(get("/products/{id}/document", "doc-els-kiwoom-4181"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition",
                                org.hamcrest.Matchers.containsString(
                                        "els_kiwoom_4181_simple_prospectus.pdf")));
    }

    /** 업로드 한 번. 발급된 productId 를 돌려준다. */
    private String upload(String filename, String productType, byte[] bytes) throws Exception {
        String body = mvc.perform(multipart("/products/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", bytes))
                        .param("productType", productType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Optional<String> productId =
                Optional.ofNullable(com.jayway.jsonpath.JsonPath.read(body, "$.data.productId"));
        return productId.orElseThrow();
    }
}
