package com.sphinxfin.sphinx.core.aiservice;

import com.sphinxfin.sphinx.domain.SuitabilityMismatch;
import com.sphinxfin.sphinx.domain.EvidenceRequiredException;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
import com.sphinxfin.sphinx.domain.SuitabilityStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import com.sphinxfin.sphinx.core.pii.PiiGateway;

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
        client = new AiServiceClient(builder, BASE, "", new com.sphinxfin.sphinx.core.pii.PiiMeter());   // 로컬 목: 토큰 없음 → 헤더 안 붙음
    }

    private static final String SCORE_OK = """
            {"item_id":"ELS-PRINCIPAL-LOSS-WARNING","grade":"U4","confidence":0.91,
             "evidence":{"utterance_quote":"원금은 지켜지죠","rubric_clause":"원금손실 조건"},
             "reason":"원금 보장으로 오해","misconception_type":"M01-PRINCIPAL-GUARANTEE"}""";

    @Test
    @DisplayName("(#41③) 토큰이 설정되면 /internal 호출에 x-sphinx-internal-token 을 붙인다")
    void sendsInternalTokenHeaderWhenConfigured() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer srv = MockRestServiceServer.bindTo(b).build();
        AiServiceClient tokened = new AiServiceClient(b, BASE, "s3cr3t-token", new com.sphinxfin.sphinx.core.pii.PiiMeter());
        srv.expect(requestTo(BASE + "/internal/score"))
                .andExpect(header(AiServiceClient.INTERNAL_TOKEN_HEADER, "s3cr3t-token"))
                .andRespond(withSuccess(SCORE_OK, MediaType.APPLICATION_JSON));

        tokened.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", "원금은 지켜지죠", ITEM, "ELS");

        srv.verify();
    }

    @Test
    @DisplayName("(#41③) 토큰이 비면(로컬 목) 헤더를 붙이지 않는다 — 받는 쪽도 인증 꺼짐이라 대칭")
    void omitsInternalTokenHeaderWhenBlank() {
        server.expect(requestTo(BASE + "/internal/score"))
                .andExpect(headerDoesNotExist(AiServiceClient.INTERNAL_TOKEN_HEADER))
                .andRespond(withSuccess(SCORE_OK, MediaType.APPLICATION_JSON));

        client.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", "원금은 지켜지죠", ITEM, "ELS");

        server.verify();
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

        Judgment j = client.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", "원금은 지켜지죠", ITEM, "ELS")
                .judgment();

        assertThat(j.itemId()).isEqualTo("ELS-PRINCIPAL-LOSS-WARNING");
        assertThat(j.grade()).isEqualTo(Grade.U4);
        // JSON 0.91 이 double 을 거치지 않고 BigDecimal 0.91 로 온다. Jackson 기본 설정으로도
        // 텍스트를 그대로 읽으므로 경계 매퍼에 USE_BIG_DECIMAL_FOR_FLOATS 를 켤 필요가 없다
        // (재생 경로는 Object 로 받아서 필요하다 — ImmutableStore 참고).
        assertThat(j.confidence()).isEqualByComparingTo("0.91");
        assertThat(j.confidence().toPlainString()).isEqualTo("0.91");
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

    // ── F-INT-002 /internal/question ────────────────────────────────────────

    @Test
    @DisplayName("question: 요청은 snake_case(risk_item·asked_types·product_type), 응답은 question+question_type로 파싱")
    void questionSendsSnakeCaseAndParsesResponse() {
        server.expect(requestTo(BASE + "/internal/question"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.risk_item.item_id").value("ELS-PRINCIPAL-LOSS-WARNING"))
                .andExpect(jsonPath("$.risk_item.condition.source_span.page").value(3))
                .andExpect(jsonPath("$.product_type").value("ELS"))
                .andExpect(jsonPath("$.asked_types[0]").value("situation"))
                .andRespond(withSuccess("""
                        {
                          "item_id": "ELS-PRINCIPAL-LOSS-WARNING",
                          "question": "낙인 아래로 떨어지면 어떻게 되나요?",
                          "question_type": "condition",
                          "fallback_used": false
                        }
                        """, MediaType.APPLICATION_JSON));

        AiServiceClient.Question q =
                client.question(ITEM, List.of("situation"), "ELS");

        assertThat(q.question()).isEqualTo("낙인 아래로 떨어지면 어떻게 되나요?");
        assertThat(q.questionType()).isEqualTo("condition");
        server.verify();
    }

    @Test
    @DisplayName("question: askedTypes가 null이면 asked_types를 빈 배열로 보낸다(#60 non-nullable)")
    void questionNullAskedTypesSerializesEmptyArray() {
        server.expect(requestTo(BASE + "/internal/question"))
                .andExpect(jsonPath("$.asked_types").isArray())
                .andExpect(jsonPath("$.asked_types").isEmpty())
                .andRespond(withSuccess("""
                        {
                          "item_id": "ELS-PRINCIPAL-LOSS-WARNING",
                          "question": "질문?",
                          "question_type": "amount",
                          "fallback_used": true
                        }
                        """, MediaType.APPLICATION_JSON));

        AiServiceClient.Question q = client.question(ITEM, null, "ELS");

        assertThat(q.questionType()).isEqualTo("amount");
        server.verify();
    }

    @Test
    @DisplayName("question: 상류 5xx → AiServiceException")
    void questionUpstreamErrorRaisesAiServiceException() {
        server.expect(requestTo(BASE + "/internal/question"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.question(ITEM, List.of(), "ELS"))
                .isInstanceOf(AiServiceException.class);
        server.verify();
    }

    // ── F-INT-004 /internal/reexplain ───────────────────────────────────────

    @Test
    @DisplayName("reExplain: 요청은 snake_case(risk_item·judgment·age_band·experience_level), 응답은 content+cited_spans로 파싱")
    void reExplainSendsSnakeCaseAndParsesResponse() {
        server.expect(requestTo(BASE + "/internal/reexplain"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.risk_item.item_id").value("ELS-PRINCIPAL-LOSS-WARNING"))
                .andExpect(jsonPath("$.judgment.item_id").value("ELS-PRINCIPAL-LOSS-WARNING"))
                .andExpect(jsonPath("$.judgment.grade").value("U4"))
                .andExpect(jsonPath("$.judgment.misconception_type").value("M01-PRINCIPAL-GUARANTEE"))
                .andExpect(jsonPath("$.age_band").value("senior"))
                .andExpect(jsonPath("$.experience_level").value("none"))
                .andRespond(withSuccess("""
                        {
                          "item_id": "ELS-PRINCIPAL-LOSS-WARNING",
                          "content": "쉽게 말하면, 원금이 보장되지 않습니다 …",
                          "cited_spans": [ { "page": 3, "start": 120, "end": 210 } ]
                        }
                        """, MediaType.APPLICATION_JSON));

        AiServiceClient.ReExplanation r =
                client.reExplain(ITEM, JUDGMENT, "senior", "none");

        assertThat(r.content()).startsWith("쉽게 말하면");
        assertThat(r.citedSpans()).hasSize(1);
        assertThat(r.citedSpans().get(0).page()).isEqualTo(3);
        assertThat(r.citedSpans().get(0).start()).isEqualTo(120);
        assertThat(r.citedSpans().get(0).end()).isEqualTo(210);
        server.verify();
    }

    @Test
    @DisplayName("reExplain: ageBand·experienceLevel이 null이면 null로 보낸다(선택 필드)")
    void reExplainNullOptionalsSerializeNull() {
        server.expect(requestTo(BASE + "/internal/reexplain"))
                .andExpect(jsonPath("$.age_band").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.experience_level").value(org.hamcrest.Matchers.nullValue()))
                .andRespond(withSuccess("""
                        {
                          "item_id": "ELS-PRINCIPAL-LOSS-WARNING",
                          "content": "설명",
                          "cited_spans": []
                        }
                        """, MediaType.APPLICATION_JSON));

        AiServiceClient.ReExplanation r = client.reExplain(ITEM, JUDGMENT, null, null);

        assertThat(r.citedSpans()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("reExplain: 상류 5xx → AiServiceException")
    void reExplainUpstreamErrorRaisesAiServiceException() {
        server.expect(requestTo(BASE + "/internal/reexplain"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.reExplain(ITEM, JUDGMENT, "senior", "none"))
                .isInstanceOf(AiServiceException.class);
        server.verify();
    }

    private static final Judgment JUDGMENT = new Judgment(
            "ELS-PRINCIPAL-LOSS-WARNING", Grade.U4, new BigDecimal("0.9"),
            new Judgment.Evidence("원금은 지켜지죠", "낙인 하회 시 손실"),
            "원금 보장으로 오해", "M01-PRINCIPAL-GUARANTEE");

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
    @Test
    @DisplayName("❗/internal/mismatch 의 근거가 경계를 넘어온다 — 상태만 꺼내면 버려진다 (#169)")
    void mismatchCarriesItsBasisAcrossTheBoundary() {
        server.expect(requestTo(BASE + "/internal/mismatch"))
                .andRespond(withSuccess("""
                        {
                          "session_id": "S-1",
                          "status": "mismatch",
                          "mismatch": true,
                          "confidence": 0.82,
                          "contradictions": [
                            {"axis": "risk_tolerance",
                             "survey": "원금 손실은 감수할 수 있다",
                             "utterance": "원금은 지켜지죠"}
                          ],
                          "reason": "설문은 손실 감수 가능인데 발화는 원금 보장을 전제한다",
                          "survey_schema_version": "s02-survey-v2"
                        }""", MediaType.APPLICATION_JSON));

        SuitabilityMismatch m = client.detectMismatch(
                "S-1", Map.of("SUIT-RISK-TOLERANCE", "원금 손실은 감수할 수 있다"),
                Map.of("A", "원금은 지켜지죠"), "s02-survey-v2");

        // 계약이 required 로 주는 근거 셋이 그대로 넘어와야 한다. 전에는 toStatus() 만
        // 돌려줘서 여기서 버려졌고, 그래서 불변 기록에 남길 것이 없었다 (#169).
        assertThat(m.status()).isEqualTo(SuitabilityStatus.MISMATCH);
        assertThat(m.reason())
                .as("게이트를 움직이는 판정인데 근거가 경계를 못 넘으면 감사 기록에 "
                        + "ruleTrace 밖에 안 남는다")
                .isEqualTo("설문은 손실 감수 가능인데 발화는 원금 보장을 전제한다");
        assertThat(m.confidence()).isEqualByComparingTo(new BigDecimal("0.82"));
        assertThat(m.contradictions())
                .as("어느 축이 어긋났는지가 근거의 실체다 — reason 은 요약이고 이쪽이 대조 대상이다")
                .hasSize(1);
        assertThat(m.contradictions().get(0)).containsEntry("axis", "risk_tolerance");
    }

    @Test
    @DisplayName("insufficient_input 은 UNKNOWN 이고 그 사유도 넘어온다 — '적합' 으로 새지 않는다")
    void insufficientInputKeepsItsReason() {
        server.expect(requestTo(BASE + "/internal/mismatch"))
                .andRespond(withSuccess("""
                        {
                          "session_id": "S-1",
                          "status": "insufficient_input",
                          "mismatch": false,
                          "confidence": 0.1,
                          "contradictions": [],
                          "reason": "발화가 두 건뿐이라 판단할 수 없다"
                        }""", MediaType.APPLICATION_JSON));

        SuitabilityMismatch m = client.detectMismatch(
                "S-1", Map.of(), Map.of("A", "네"), "s02-survey-v2");

        assertThat(m.status())
                .as("insufficient_input 이면 mismatch 가 항상 false 인데 그걸 NO_MISMATCH 로 "
                        + "옮기면 판정 실패가 '적합' 이 된다")
                .isEqualTo(SuitabilityStatus.UNKNOWN);
        assertThat(m.reason())
                .as("왜 판단할 수 없었는지가 남아야 UNKNOWN 이 그냥 빈 값과 구별된다")
                .isEqualTo("발화가 두 건뿐이라 판단할 수 없다");
    }


    @Test
    @DisplayName("❗P3 경계를 지나면 계량기가 센다 — 마스킹만 하고 안 세면 증거가 다시 없어진다")
    void theBoundaryFeedsTheMeter() {
        // ❗PiiGateway·PiiMeter 를 각각 재는 것으로는 부족하다. score() 가 maskWithHits 대신
        // mask() 를 부르면 마스킹은 그대로 되고 **계량기만 조용히 0** 이 된다 — 이슈 #326 이
        // 없애려는 상태가 그대로 돌아온다. 그 층을 여기서 지나간다.
        com.sphinxfin.sphinx.core.pii.PiiMeter meter = new com.sphinxfin.sphinx.core.pii.PiiMeter();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        AiServiceClient metered = new AiServiceClient(builder, BASE, "", meter);

        mockServer.expect(requestTo(BASE + "/internal/score"))
                .andRespond(withSuccess(SCORE_OK, MediaType.APPLICATION_JSON));

        metered.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?",
                "제 번호는 010-1234-5678 입니다", ITEM, "ELS");

        assertThat(meter.calls()).isEqualTo(1);
        assertThat(meter.removed())
                .as("경계를 지난 발화의 PII 가 안 세어지면, 마스킹이 동작했다는 증거가 "
                        + "다시 코드 읽기밖에 안 남는다")
                .containsEntry("PHONE", 1L);
    }

    @Test
    @DisplayName("❗건별 로그에 종류도 누적도 없다 — 시각이 세션 축 노릇을 한다 (#326 리뷰)")
    void thePerCallLogCarriesOnlyACount() {
        // 로그 줄에는 시각이 있고, 같은 요청의 다른 줄(UnfairSignalLog 의 session=…)과
        // 시각으로 붙는다. 종류를 실으면 로그만 읽어서 "세션 S-xxx 의 고객이 주민번호를
        // 적었다" 가 복원되는데, 그게 마스킹이 지운 그 사실이다.
        //
        // ❗누적도 안 된다 — 매 호출 찍으면 연속한 두 줄의 **차분**이 곧 그 호출의 종류별
        // 건수다. 종류를 빼도 누적이 남으면 같은 정보가 나온다.
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AiServiceClient.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
                new ch.qos.logback.core.read.ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            com.sphinxfin.sphinx.core.pii.PiiMeter meter = new com.sphinxfin.sphinx.core.pii.PiiMeter();
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
            AiServiceClient metered = new AiServiceClient(builder, BASE, "", meter);
            mockServer.expect(requestTo(BASE + "/internal/score"))
                    .andRespond(withSuccess(SCORE_OK, MediaType.APPLICATION_JSON));

            metered.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?",
                    "제 번호는 010-1234-5678 이고 메일은 hong@example.com 입니다", ITEM, "ELS");

            String printed = captured.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));

            assertThat(printed).as("경계가 일한 것은 남는다").contains("2건");
            assertThat(printed)
                    .as("종류가 새면 '주민번호가 있었다' 가 되고, 그건 '있었다' 와 민감도가 다르다")
                    .doesNotContain("PHONE").doesNotContain("EMAIL");
            assertThat(printed)
                    .as("누적을 매 호출 찍으면 차분이 곧 건별이다")
                    .doesNotContain("누적").doesNotContain("호출 ");
            assertThat(printed)
                    .as("원문 조각은 어디에도 없다")
                    .doesNotContain("010").doesNotContain("hong");
        } finally {
            logger.detachAppender(captured);
        }
    }
}
