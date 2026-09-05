package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.core.extraction.ExtractedRiskItemRepository;
import com.sphinxfin.sphinx.domain.ParsedDocument;
import com.sphinxfin.sphinx.domain.RiskItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-EXT 실추출 배선 (이슈 #355 · #401 3번). 소유: 강희진
 *
 * <p>재는 것 네 가지:
 * <ol>
 *   <li>{@code POST /products/{id}/extract} 가 parse→extract 결과를 <b>영속</b>하고,
 *       {@code GET /products/{id}/risk-items} 가 목이 아니라 <b>그 스냅샷</b>을 낸다 —
 *       두 상태(extracted·extraction_failed)가 모두 왕복한다.</li>
 *   <li>재추출이 기존 스냅샷을 <b>교체</b>한다 — 누적이면 이전 추출의 항목이 게이트
 *       분모에 유령으로 남는다.</li>
 *   <li>세션 면담({@code questions/next}·세션 경유 risk-items)이 저장된 항목을 쓴다 —
 *       목에는 없는 itemId 가 질문으로 나온다.</li>
 *   <li>저장된 추출이 없는 상품은 MockData 폴백이다 — LLM 키 없는 환경의 데모가
 *       계속 돌아야 한다(목 삭제는 후속).</li>
 * </ol>
 *
 * <p>ai-service 는 목이다 — 이 파일이 재는 것은 서버의 영속·배선이지 추출 품질이 아니다.
 * 실제 /internal/parse·/internal/extract HTTP 계약은 AiServiceClientTest 가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-EXT 실추출 배선 (이슈 #355)")
class RealExtractionWiringTest {

    private static final String ELS = "doc-els-kiwoom-4181";
    private static final String ELS_DOC = "documents/els_kiwoom_4181_simple_prospectus.pdf";

    @Autowired private MockMvc mvc;
    @Autowired private ExtractedRiskItemRepository repository;
    @MockBean private AiServiceClient aiServiceClient;

    @AfterEach
    void cleanUp() {
        // 이 컨텍스트(H2)는 다른 통합 테스트 클래스와 공유된다 — 스냅샷을 남기면
        // 그쪽의 MockData 전제(항목 2개·ELS-PRINCIPAL-LOSS-WARNING 이 첫 항목)가 깨진다.
        repository.deleteAll();
    }

    @Test
    @DisplayName("❗extract 가 영속하고, risk-items 가 목이 아니라 그 스냅샷을 낸다 — 두 상태 다 왕복")
    void extractPersistsAndRiskItemsReadStored() throws Exception {
        stubExtraction(List.of(
                RiskItem.extracted("ELS-STORED-ONLY", ELS, "저장 전용 항목", "required",
                        new RiskItem.Condition("원문 인용", new RiskItem.SourceSpan(1, 3, 8))),
                RiskItem.failed("ELS-FAILED-ITEM", ELS, "실패 항목", "recommended",
                        "스팬을 원문에서 확정하지 못했다")));

        mvc.perform(post("/products/{id}/extract", ELS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].itemId").value("ELS-STORED-ONLY"));

        // 문서 경로·상품유형이 배선의 값이다 — 아무 문자열로 불러도 목이 응답하므로
        // 호출 인자를 직접 잰다.
        verify(aiServiceClient).parse(ELS_DOC, "ELS");

        assertThat(repository.count())
                .as("추출 결과가 영속돼야 재기동·다른 라우트가 같은 항목을 본다")
                .isEqualTo(2);

        String body = mvc.perform(get("/products/{id}/risk-items", ELS))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<String> ids = JsonPath.read(body, "$.data.items[*].itemId");
        assertThat(ids)
                .as("저장된 스냅샷이 있으면 목(MockData)이 아니라 그것을 낸다")
                .containsExactly("ELS-STORED-ONLY", "ELS-FAILED-ITEM");

        // extracted 항목 — 조건·스팬이 그대로 왕복한다 (P6: 원문 인용과 좌표가 근거다).
        assertThat((String) JsonPath.read(body, "$.data.items[0].condition.valueText"))
                .isEqualTo("원문 인용");
        assertThat((int) JsonPath.read(body, "$.data.items[0].condition.sourceSpan.page")).isEqualTo(1);
        assertThat((int) JsonPath.read(body, "$.data.items[0].condition.sourceSpan.start")).isEqualTo(3);
        assertThat((int) JsonPath.read(body, "$.data.items[0].condition.sourceSpan.end")).isEqualTo(8);

        // extraction_failed 항목 — condition 없이 사유만 남는 쪽도 왕복해야 한다.
        // RiskItem 생성자가 두 불변식을 강제하므로, 복원이 틀리면 여기가 200 이 아니라 500 이다.
        assertThat((String) JsonPath.read(body, "$.data.items[1].status"))
                .isEqualTo("extraction_failed");
        assertThat((String) JsonPath.read(body, "$.data.items[1].failureReason"))
                .isEqualTo("스팬을 원문에서 확정하지 못했다");
        assertThat(JsonPath.<Object>read(body, "$.data.items[1].condition"))
                .as("실패 항목에 condition 이 살아나면 지어낸 문장이 원문 인용으로 보인다 (P6)")
                .isNull();
    }

    @Test
    @DisplayName("❗재추출은 교체다 — 이전 스냅샷의 항목이 남으면 게이트 분모에 유령이 생긴다")
    void reExtractReplacesThePreviousSnapshot() throws Exception {
        stubExtraction(List.of(item("ELS-FIRST-RUN")));
        mvc.perform(post("/products/{id}/extract", ELS)).andExpect(status().isOk());

        stubExtraction(List.of(item("ELS-SECOND-RUN")));
        mvc.perform(post("/products/{id}/extract", ELS)).andExpect(status().isOk());

        String body = mvc.perform(get("/products/{id}/risk-items", ELS))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[*].itemId"))
                .containsExactly("ELS-SECOND-RUN");
    }

    @Test
    @DisplayName("❗면담이 저장된 항목을 묻는다 — 목에는 없는 itemId 가 질문으로 나온다")
    void theInterviewAsksStoredItems() throws Exception {
        stubExtraction(List.of(item("ELS-STORED-ONLY")));
        mvc.perform(post("/products/{id}/extract", ELS)).andExpect(status().isOk());

        String sid = createSession();

        mvc.perform(post("/sessions/{sid}/questions/next", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemId").value("ELS-STORED-ONLY"))
                .andExpect(jsonPath("$.data.total").value(1));

        // 세션 경유 risk-items 도 같은 스냅샷이다 — 화면(S-03)이 물을 것과 게이트 분모가
        // 같은 목록이어야 한다.
        mvc.perform(get("/sessions/{sid}/risk-items", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].itemId").value("ELS-STORED-ONLY"));
    }

    @Test
    @DisplayName("저장된 추출이 없으면 MockData 폴백이다 — 키 없는 환경의 데모가 계속 돈다")
    void fallsBackToMockDataWithoutAStoredExtraction() throws Exception {
        stubQuestion();

        String body = mvc.perform(get("/products/{id}/risk-items", ELS))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[*].itemId"))
                .containsExactly("ELS-PRINCIPAL-LOSS-WARNING", "ELS-NO-DEPOSIT-INSURANCE");

        String sid = createSession();
        mvc.perform(post("/sessions/{sid}/questions/next", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemId").value("ELS-PRINCIPAL-LOSS-WARNING"))
                .andExpect(jsonPath("$.data.total").value(2));
    }

    /** 목에는 없는 이름의 extracted 항목 하나. */
    private static RiskItem item(String itemId) {
        return RiskItem.extracted(itemId, ELS, "저장 전용 항목", "required",
                new RiskItem.Condition("원문 인용", new RiskItem.SourceSpan(1, 3, 8)));
    }

    private void stubExtraction(List<RiskItem> items) {
        ParsedDocument parsed = new ParsedDocument("doc-parse-001", "ELS", null, "parser-v1",
                "2026-09-01T00:00:00Z", 1,
                List.of(new ParsedDocument.Page(1, "…원문 인용…", 10)), List.of(), List.of());
        when(aiServiceClient.parse(anyString(), anyString())).thenReturn(parsed);
        when(aiServiceClient.extract(anyString(), any(ParsedDocument.class)))
                .thenReturn(new AiServiceClient.ExtractResult(items, List.of()));
        stubQuestion();
    }

    private void stubQuestion() {
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString(), anyString(),
                nullable(AiServiceClient.InterviewContext.class)))
                .thenReturn(new AiServiceClient.Question(
                        "이 조건이 어떤 뜻인지 설명해 주시겠어요?", "condition", false));
    }

    private String createSession() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(created, "$.data.sessionId");
    }
}
