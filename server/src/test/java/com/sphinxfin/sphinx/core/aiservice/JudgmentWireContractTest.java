package com.sphinxfin.sphinx.core.aiservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * <b>들어오는</b> 판정 ≡ {@code contracts/judgment.schema.json}. 소유: 강희진 (이슈 #609 ①)
 *
 * <h2>왜 이 테스트가 필요한가 — 계약이 늘면 채점이 통째로 죽는다</h2>
 *
 * <p>{@code RiskItemWireContractTest} 가 <b>나가는</b> 절반을 덮는다. 들어오는 절반은
 * 아무도 안 봤는데, 그쪽 실패가 더 크다.
 *
 * <pre>
 * AiServiceClient:239   .body(Judgment.class)      ← 경계 매퍼(SNAKE_CASE·엄격)
 * </pre>
 *
 * <p>그 매퍼는 {@code FAIL_ON_UNKNOWN_PROPERTIES} 가 기본값(on)이라, ai-service 가 계약에
 * 필드를 더하고 이 레코드가 안 따라오면 <b>모든 채점 응답이 역직렬화에서 터진다.</b> 한
 * 항목이 아니라 경로 전체이고, 운영자가 받는 문면은 {@code AI_SERVICE_UNAVAILABLE} 이라
 * <b>ai-service 를 의심하게 된다.</b> 고칠 자리는 여기인데.
 *
 * <p>실측(PR #623 리뷰): {@code rubric_status} 하나가 실린 판정을 이 레코드로 읽으면
 * {@code UnrecognizedPropertyException: Unrecognized field "rubric_status" … not marked as
 * ignorable}. 그래서 <b>계약·레코드가 ai-service 보다 먼저 들어가야 했다</b> — 이 테스트가
 * 그 순서를 다음부터 자동으로 알려준다.
 *
 * <p>반대 방향도 같은 자리다. {@code #518}({@code source} 를 나가는 본문에 실어 저쪽
 * {@code extra="forbid"} 가 422) 이 대칭이고, 알파에서 재설명이 전부 죽었다.
 */
@DisplayName("들어오는 판정 ≡ contracts/judgment.schema.json (이슈 #609 ①)")
class JudgmentWireContractTest {

    private static final String BASE = "http://ai:8100";
    private static final Path SCHEMA = Path.of("../contracts/judgment.schema.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RiskItem EXTRACTED = RiskItem.extracted(
            "ELS-PRINCIPAL-LOSS-WARNING", "mock-els-001", "원금손실 조건", "required",
            new RiskItem.Condition("만기평가일에 …(원문 인용)", new RiskItem.SourceSpan(3, 120, 210)));

    /**
     * 계약의 <b>모든</b> 필드에 값을 하나씩 준다 — 이 표가 모자라면 아래 첫 단정이 잡는다.
     *
     * <p>❗값을 여기 손으로 적는 이유는 스키마에서 만들어 내면 <b>대조가 스스로를 증명하는</b>
     * 모양이 되기 때문이다. 계약이 필드를 늘리면 <b>사람이 여기 값을 적으면서</b> 그 필드가
     * 무엇인지 보게 된다 — 그 자리가 이 대조의 값이다.
     */
    private static final Map<String, Object> CONTRACT_SAMPLE = new LinkedHashMap<>() {{
        put("item_id", "ELS-PRINCIPAL-LOSS-WARNING");
        put("grade", "U1");
        put("confidence", 0.9);
        put("evidence", Map.of("utterance_quote", "원금이 깎일 수 있다고 들었어요",
                "rubric_clause", "원금손실 조건"));
        put("reason", "핵심 조건을 자기 말로 설명했다");
        put("misconception_type", null);
        put("escalate", false);
        put("prompt_version", "F-SCR-001_v2");
        put("source", "MEASURED");
        put("rubric_status", "draft");
    }};

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private AiServiceClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiServiceClient(builder, BASE, "", new com.sphinxfin.sphinx.core.pii.PiiMeter());
    }

    @Test
    @DisplayName("★ 표본이 계약의 모든 필드를 든다 — 모자라면 아래 대조가 덜 재고 통과한다")
    void theSampleCoversEveryContractField() throws Exception {
        JsonNode properties = MAPPER.readTree(Files.readString(SCHEMA)).get("properties");
        assertThat(properties)
                .as("judgment.schema.json 에서 properties 를 못 읽었다 — 스키마 모양이 바뀌었으면 "
                        + "이 대조도 같이 고친다. 안 그러면 양쪽이 다 비어 조용히 통과한다")
                .isNotNull();

        Set<String> contract = new TreeSet<>();
        properties.fieldNames().forEachRemaining(contract::add);

        assertThat(new TreeSet<>(CONTRACT_SAMPLE.keySet()))
                .as("계약이 필드를 늘렸는데 이 표본이 안 따라왔다. 값을 적으면서 그 필드가 "
                        + "무엇인지 보고, 레코드에 칸이 필요한지 정한다")
                .isEqualTo(contract);
    }

    @Test
    @DisplayName("❗계약이 허용하는 판정을 이 레코드가 읽는다 — 못 읽으면 채점 경로가 통째로 죽는다")
    void everyContractFieldSurvivesTheBoundary() throws Exception {
        Judgment judgment = scoreWith(MAPPER.writeValueAsString(CONTRACT_SAMPLE));

        assertThat(judgment.itemId()).isEqualTo("ELS-PRINCIPAL-LOSS-WARNING");
        assertThat(judgment.rubricStatus())
                .as("계약의 값이 레코드까지 와야 한다 — 칸만 만들고 안 실으면 "
                        + "검토 전 기준으로 낸 판정이 기록에서 확정 기준과 똑같이 생긴다")
                .isEqualTo("draft");
    }

    @Test
    @DisplayName("★ 계약에 없는 필드는 터진다 — 위 단정이 빈 통과가 아니라는 근거다")
    void aFieldTheContractDoesNotHaveBlowsUp() throws Exception {
        Map<String, Object> extra = new LinkedHashMap<>(CONTRACT_SAMPLE);
        extra.put("rubric_version", "v9");   // 계약에 없는 키

        assertThatThrownBy(() -> scoreWith(MAPPER.writeValueAsString(extra)))
                .as("경계 매퍼가 엄격하지 않다면 위 대조는 아무것도 안 막는다 — "
                        + "계약이 늘어도 조용히 통과하고, 그 사실을 배포에서 처음 만난다")
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("❗레코드에만 있는 칸은 없다 — 계약이 약속 안 한 값을 상류에서 기대하면 영원히 null 이다")
    void theRecordHasNoFieldTheContractLacks() throws Exception {
        JsonNode properties = MAPPER.readTree(Files.readString(SCHEMA)).get("properties");
        Set<String> contract = new TreeSet<>();
        properties.fieldNames().forEachRemaining(contract::add);

        Set<String> record = Arrays.stream(Judgment.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(JudgmentWireContractTest::snake)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(record)
                .as("레코드에 있는데 계약에 없는 칸이다. 상류는 계약만 보므로 그 값은 절대 "
                        + "안 온다 — 계약에 올리든지 레코드에서 빼든지 여기서 정한다")
                .isEqualTo(contract);
    }

    /** 목 응답 본문을 주고 {@code /internal/score} 를 한 번 태운다. */
    private Judgment scoreWith(String responseBody) {
        server.expect(requestTo(BASE + "/internal/score"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
        return client.score(EXTRACTED.itemId(), "질문", "답변", EXTRACTED, "ELS").judgment();
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
