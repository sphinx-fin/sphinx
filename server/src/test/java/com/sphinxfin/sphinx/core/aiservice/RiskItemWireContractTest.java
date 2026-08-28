package com.sphinxfin.sphinx.core.aiservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 서버가 <b>실제로 보내는</b> {@code risk_item} 이 계약과 같은가. 소유: 강희진
 *
 * <h2>왜 이 테스트가 필요한가 — 이슈 #165</h2>
 *
 * <p>세 모듈을 같이 띄우니 인터뷰가 첫 요청에서 죽었다. `ai-service` 가 서버 본문을
 * {@code 422 extra_forbidden} 으로 거절했고, 원인은 {@code failure_reason} 한 필드였다 —
 * <b>계약에는 있고</b>(E-EXT-03 실패 은폐 금지) 서버는 그대로 실어 보내는데, 파이썬 모델에
 * 그 필드가 없었다.
 *
 * <p>그때 <b>양쪽 테스트가 전부 초록</b>이었다. 이유가 대칭이다.
 *
 * <pre>
 * server    AiServiceClientTest 가 요청 본문을 jsonPath 로 몇 개만 집는다.
 *           그 본문을 계약 스키마와 대조하지는 않는다.
 * ai-svc    pytest 가 RiskItem 을 파이썬에서 직접 만든다.
 *           failure_reason 은 레포 전체에서 한 번도 안 쓰였다.
 * </pre>
 *
 * <p><b>계약 파일은 있는데 어느 쪽도 자기 모델을 그 파일과 대조하지 않았다.</b> 이 테스트가
 * 서버 쪽 절반을 덮는다 — 나가는 JSON 의 키 집합을 {@code risk_item.schema.json} 과 맞춘다.
 *
 * <p>양방향으로 본다. 계약에 없는 키를 보내면 상대가 {@code extra_forbidden} 으로 거절하고,
 * 계약의 required 를 빼먹으면 상대가 <b>다른 것을 재게</b> 된다. 둘 다 통합 전에는 안 보인다.
 *
 * <p>❗{@code jsonPath} 단정을 늘리는 것으로는 이걸 못 막는다. 그건 <b>내가 지금 생각한 키</b>
 * 만 보고, 이번에 문제가 된 것은 아무도 생각 안 한 키다.
 */
@DisplayName("나가는 risk_item ≡ contracts/risk_item.schema.json (이슈 #165)")
class RiskItemWireContractTest {

    private static final String BASE = "http://ai:8100";
    private static final Path SCHEMA = Path.of("../contracts/risk_item.schema.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 추출 성공 항목 — status=extracted, failure_reason 은 null 이다. */
    private static final RiskItem EXTRACTED = RiskItem.extracted(
            "ELS-PRINCIPAL-LOSS-WARNING", "mock-els-001", "원금손실 조건", "required",
            new RiskItem.Condition("만기평가일에 …(원문 인용)", new RiskItem.SourceSpan(3, 120, 210)));

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private AiServiceClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiServiceClient(builder, BASE);
    }

    @Test
    @DisplayName("❗/internal/question 이 싣는 risk_item 의 키가 계약과 정확히 같다")
    void questionRequestMatchesContract() throws Exception {
        JsonNode riskItem = captureRiskItem("/internal/question",
                """
                {"question": "질문", "question_type": "OPEN_ENDED", "fallback_used": false}""",
                () -> client.question(EXTRACTED, List.of(), "60대"));

        assertKeysMatchContract(riskItem);
    }

    @Test
    @DisplayName("❗/internal/score 도 같은 모양을 싣는다 — 한 경로만 맞추면 다른 경로가 죽는다")
    void scoreRequestMatchesContract() throws Exception {
        JsonNode riskItem = captureRiskItem("/internal/score",
                """
                {"item_id": "ELS-PRINCIPAL-LOSS-WARNING", "grade": "U1", "confidence": 0.9,
                 "evidence": {"utterance_quote": "인용", "rubric_clause": "조항"},
                 "reason": "사유", "misconception_type": null}""",
                () -> client.score(EXTRACTED.itemId(), "질문", "답변", EXTRACTED, "ELS"));

        assertKeysMatchContract(riskItem);
    }

    /**
     * 나가는 키 집합이 계약과 <b>모든 겹에서</b> 정확히 같아야 한다.
     *
     * <p>부분집합으로 두지 않는다. 계약에 없는 키는 상대가 {@code extra_forbidden} 으로
     * 거절하고(#165 가 그것이다), 계약에 있는데 안 보내면 상대가 그 필드를 못 본다 —
     * {@code failure_reason} 이 안 가면 추출 실패가 은폐된다(E-EXT-03).
     *
     * <p>❗<b>재귀로 내려간다.</b> 처음에는 맨 위 한 겹만 봤는데, 스키마가 3단이고
     * ({@code root → condition → source_span}) <b>상대 모델도 중첩까지 {@code extra="forbid"}</b>
     * 다. 중첩에 계약에 없는 키가 하나 끼면 {@code #165} 와 문자 그대로 같은 422 가 나는데,
     * 한 겹만 보는 대조는 그것을 통과시킨다. 실측으로 확인됐다(#167 리뷰) —
     * 루트에 키를 얹으면 2건 실패, 같은 키를 {@code source_span} 에 얹으면 BUILD SUCCESSFUL.
     * <b>두 변이는 상대에게 도달했을 때 같은 결과를 낸다. 차이는 깊이 하나뿐이다.</b>
     */
    private static void assertKeysMatchContract(JsonNode riskItem) throws Exception {
        JsonNode schema = MAPPER.readTree(Files.readString(SCHEMA));
        assertThat(schema.get("properties"))
                .as("risk_item.schema.json 에서 properties 를 못 읽었다 — 스키마 모양이 "
                        + "바뀌었으면 이 테스트도 같이 고친다. 안 그러면 양쪽이 다 비어서 "
                        + "집합이 같아지고 조용히 통과한다")
                .isNotNull();
        assertKeysMatch(riskItem, schema, "risk_item");
    }

    /**
     * 한 겹을 대조하고 객체인 자식으로 내려간다.
     *
     * <p>{@code path} 를 들고 다니는 이유는 실패 메시지가 <b>어느 겹인지</b>를 말해야 하기
     * 때문이다. 없으면 "키가 어긋났다" 만 나오고 3단 중 어디인지는 안 나온다.
     *
     * <p>{@code isObject()} 로 거르는 이유는 {@code status=extraction_failed} 일 때
     * {@code condition} 이 null 이기 때문이다 — 그때 내려가면 NPE 로 죽는데, 그건 계약
     * 위반이 아니라 <b>정상 상태</b>다.
     */
    private static void assertKeysMatch(JsonNode wire, JsonNode schema, String path) {
        JsonNode props = schema.get("properties");
        if (props == null || props.isEmpty()) {
            return;   // leaf. 0건 가드는 최상위에서 한 번만 건다
        }

        Set<String> contract = new TreeSet<>();
        props.fieldNames().forEachRemaining(contract::add);
        Set<String> keys = new TreeSet<>();
        wire.fieldNames().forEachRemaining(keys::add);

        assertThat(keys)
                .as(path + " 의 키가 계약과 어긋났다. 계약에 없는 키는 ai-service 가 "
                        + "422 extra_forbidden 으로 거절하고(#165), 계약에 있는데 안 보내면 "
                        + "상대가 그 필드를 못 본다 — failure_reason 이 안 가면 추출 실패가 "
                        + "은폐된다(E-EXT-03)")
                .isEqualTo(contract);

        props.fields().forEachRemaining(e -> {
            JsonNode child = wire.get(e.getKey());
            if (child != null && child.isObject()) {
                assertKeysMatch(child, e.getValue(), path + "." + e.getKey());
            }
        });
    }

    @Test
    @DisplayName("추출 실패 항목도 통과한다 — condition 이 null 인 것은 계약 위반이 아니다")
    void extractionFailedItemAlsoMatches() throws Exception {
        RiskItem failed = RiskItem.failed(
                "ELS-BROKEN", "mock-els-001", "파싱 실패 항목", "required", "표 구조를 못 읽었다");

        JsonNode riskItem = captureRiskItem("/internal/question",
                """
                {"question": "질문", "question_type": "OPEN_ENDED", "fallback_used": true}""",
                () -> client.question(failed, List.of(), "60대"));

        // 재귀가 isObject() 로 안 거르면 여기서 NPE 로 죽는다 — 그건 계약 위반이 아니라
        // E-EXT-03 이 정의한 정상 상태다.
        assertKeysMatchContract(riskItem);
    }

    @Test
    @DisplayName("❗/internal/reexplain 도 같은 모양을 싣는다 — risk_item 을 싣는 경로는 셋이다")
    void reExplainRequestMatchesContract() throws Exception {
        // 오늘은 셋이 같은 RiskItem 을 같은 매퍼로 직렬화하므로 구조적으로 갈릴 수 없다.
        // 갈리는 날은 누가 ReExplainRequest 에 risk_item 대신 축약 투영을 싣는 순간이고,
        // 그때 이 경로만 조용히 죽는다 (#167 리뷰).
        Judgment judgment = new Judgment(EXTRACTED.itemId(), Grade.U3, new BigDecimal("0.8"),
                new Judgment.Evidence("인용", "조항"), "사유", null, null);

        JsonNode riskItem = captureRiskItem("/internal/reexplain",
                """
                {"item_id": "ELS-PRINCIPAL-LOSS-WARNING", "content": "다시 설명", "cited_spans": []}""",
                () -> client.reExplain(EXTRACTED, judgment, "60대", "none"));

        assertKeysMatchContract(riskItem);
    }

    /** 요청을 한 번 태우고 그 본문의 {@code risk_item} 노드를 집어 온다. */
    private JsonNode captureRiskItem(String path, String response, Runnable call) throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo(BASE + path))
                .andExpect(request -> body.set(request.getBody().toString()))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        call.run();
        server.verify();

        JsonNode root = MAPPER.readTree(body.get());
        assertThat(root.has("risk_item"))
                .as("요청 본문에 risk_item 이 없다 — 이 테스트가 아무것도 안 본 상태다. "
                        + "본문: " + body.get())
                .isTrue();
        return root.get("risk_item");
    }
}
