package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.domain.EvidenceRequiredException;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * F-INT AiServiceClient 단위 — ai-service HTTP 계약을 MockRestServiceServer로 고정한다.
 * 검증: (a) snake_case 응답 → camelCase Judgment 역직렬화, (b) PiiGateway.mask 적용(원문 미전송),
 * (c) 상류 실패 → AiServiceException, (d) 근거 없는 판정 → EvidenceRequiredException(P4).
 */
@DisplayName("AiServiceClient (ai-service HTTP 계약)")
class AiServiceClientTest {

    private static final String BASE = "http://ai-service.test";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private AiServiceClient client;

    private static final RiskItem ITEM = RiskItem.extracted(
            "ELS-PRINCIPAL-LOSS-WARNING", "mock-els-001", "원금손실 조건", "required",
            new RiskItem.Condition("만기평가일에 …(원문 인용)", new RiskItem.SourceSpan(3, 120, 210)));

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiServiceClient(builder, BASE);
    }

    @Test
    @DisplayName("(a) snake_case 응답이 camelCase Judgment로 역직렬화된다")
    void deserializesSnakeCaseResponse() {
        server.expect(requestTo(BASE + "/internal/score"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "item_id": "ELS-PRINCIPAL-LOSS-WARNING",
                          "grade": "U4",
                          "confidence": 0.91,
                          "evidence": {
                            "utterance_quote": "원금은 지켜지죠",
                            "rubric_clause": "원금손실 조건: 낙인 하회 시 손실 인지"
                          },
                          "reason": "원금 보장으로 오해",
                          "misconception_type": "M01-PRINCIPAL-GUARANTEE"
                        }
                        """, MediaType.APPLICATION_JSON));

        Judgment j = client.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", "원금은 지켜지죠", ITEM, "ELS");

        assertThat(j.itemId()).isEqualTo("ELS-PRINCIPAL-LOSS-WARNING");
        assertThat(j.grade()).isEqualTo(Grade.U4);
        assertThat(j.confidence()).isEqualTo(0.91);
        assertThat(j.evidence().utteranceQuote()).isEqualTo("원금은 지켜지죠");
        assertThat(j.evidence().rubricClause()).isNotBlank();
        assertThat(j.misconceptionType()).isEqualTo("M01-PRINCIPAL-GUARANTEE");
        server.verify();
    }

    @Test
    @DisplayName("(b) 요청 본문은 snake_case이고 고객 텍스트는 PiiGateway.mask()로 마스킹된다 (P3)")
    void masksCustomerTextAndUsesSnakeCase() {
        String raw = "제 번호는 010-1234-5678 이고 원금은 지켜지죠";

        server.expect(requestTo(BASE + "/internal/score"))
                // snake_case 키
                .andExpect(jsonPath("$.item_id").value("ELS-PRINCIPAL-LOSS-WARNING"))
                .andExpect(jsonPath("$.product_type").value("ELS"))
                .andExpect(jsonPath("$.risk_item.item_id").value("ELS-PRINCIPAL-LOSS-WARNING"))
                .andExpect(jsonPath("$.risk_item.condition.source_span.page").value(3))
                // 원문 전화번호가 나가면 안 된다 — 마스킹 토큰으로 치환돼야 한다 (P3)
                .andExpect(jsonPath("$.answer_text").value("제 번호는 [PHONE] 이고 원금은 지켜지죠"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("010-1234-5678"))))
                .andRespond(withSuccess(validJudgmentJson(), MediaType.APPLICATION_JSON));

        client.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", raw, ITEM, "ELS");
        server.verify();
    }

    @Test
    @DisplayName("(c) 상류 5xx → AiServiceException (502로 매핑됨)")
    void upstreamServerErrorRaisesAiServiceException() {
        server.expect(requestTo(BASE + "/internal/score"))
                .andRespond(withServerError());

        assertThatThrownBy(() ->
                client.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", "원금은 지켜지죠", ITEM, "ELS"))
                .isInstanceOf(AiServiceException.class);
        server.verify();
    }

    @Test
    @DisplayName("(c) ai-service 501(미구현) → AiServiceException")
    void upstreamNotImplementedRaisesAiServiceException() {
        server.expect(requestTo(BASE + "/internal/score"))
                .andRespond(withStatus(HttpStatus.NOT_IMPLEMENTED));

        assertThatThrownBy(() ->
                client.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", "원금은 지켜지죠", ITEM, "ELS"))
                .isInstanceOf(AiServiceException.class);
        server.verify();
    }

    @Test
    @DisplayName("(d) 근거 없는 판정 응답 → EvidenceRequiredException (P4 경로 유지)")
    void emptyEvidenceRaisesEvidenceRequired() {
        // ai-service가 계약을 어기고 빈 근거를 돌려준 경우 — Judgment 생성자(P4)가 막는다.
        server.expect(requestTo(BASE + "/internal/score"))
                .andRespond(withSuccess("""
                        {
                          "item_id": "ELS-PRINCIPAL-LOSS-WARNING",
                          "grade": "U4",
                          "confidence": 0.91,
                          "evidence": { "utterance_quote": "", "rubric_clause": "" },
                          "reason": "근거 없음",
                          "misconception_type": null
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                client.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", "원금은 지켜지죠", ITEM, "ELS"))
                .isInstanceOf(EvidenceRequiredException.class);
        server.verify();
    }

    private static String validJudgmentJson() {
        return """
                {
                  "item_id": "ELS-PRINCIPAL-LOSS-WARNING",
                  "grade": "U4",
                  "confidence": 0.9,
                  "evidence": { "utterance_quote": "원금은 지켜지죠", "rubric_clause": "낙인 하회 시 손실" },
                  "reason": "오해",
                  "misconception_type": null
                }
                """;
    }
}
