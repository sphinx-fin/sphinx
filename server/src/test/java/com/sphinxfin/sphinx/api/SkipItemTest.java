package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
import com.sphinxfin.sphinx.domain.SkippedItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E-INT-03 「이 항목 건너뛰기」는 <b>채점을 지나지 않는다</b>. 소유: 강희진 (이슈 #518)
 *
 * <h2>무엇이 문제였나</h2>
 *
 * <p>화면이 {@code "(응답하지 않음)"} 을 보통 발화처럼 {@code /answers} 로 보냈다. 그러면
 * 그 문자열이 채점 프롬프트까지 가는데, 계약이 근거 인용을 강제하므로
 * ({@code evidence.utterance_quote} 는 빈 문자열을 안 받는다) 모델이 <b>인용할 발화가 없는
 * 판정</b>을 만들어야 했다. 빈 인용이 오면 스키마 위반이고 그건 {@code MeasurementInvalid}
 * 가 아니라 {@code LlmError} 라 <b>재판정 루프에도 안 걸린다</b> — 곧장 502 다.
 *
 * <p>알파에서 쟀다(2026-09-07): 같은 발화 3회 <b>전부</b> 502, 정상 발화 3회 전부 200.
 * 10시간치 로그에서 {@code /internal/score} 7건 중 5건이 502 였다. <i>가끔 된다</i> 였던
 * 이유는 모델이 우연히 그 표시 문자열을 인용에 채운 회차가 있어서다 — 성공이 운이었다.
 *
 * <p>그래서 이 테스트가 잠그는 것은 <b>부르지 않는다</b> 는 사실이다. 등급만 확인하면
 * 채점을 태우면서 등급을 덮어써도 초록이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("E-INT-03 항목 건너뛰기 (이슈 #518)")
class SkipItemTest {

    private static final String ITEM = "ELS-PRINCIPAL-LOSS-WARNING";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AiServiceClient aiServiceClient;

    @BeforeEach
    void stubUpstream() {
        // 채점 스텁은 **일부러 U1 이다.** 건너뛰기가 실수로 이 경로를 타면 U1 이 나와
        // 아래 U3 단정이 깨진다 — 등급이 룰에서 나왔다는 것을 등급으로도 한 번 잰다.
        when(aiServiceClient.score(anyString(), anyString(), anyString(), any(RiskItem.class),
                anyString(), nullable(com.sphinxfin.sphinx.domain.InputMeta.class)))
                .thenAnswer(inv -> new AiServiceClient.Scored(
                        new Judgment(inv.getArgument(0), Grade.U1, new BigDecimal("0.95"),
                                new Judgment.Evidence("원금이 손실될 수 있다고 들었어요",
                                        "원금손실 조건: 낙인 하회 시 손실을 인지해야 함"),
                                "조건을 정확히 진술", null),
                        inv.getArgument(2)));
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString(), anyString(),
                nullable(AiServiceClient.InterviewContext.class)))
                .thenReturn(new AiServiceClient.Question("이 조건이 어떤 뜻인지 설명해 주시겠어요?",
                        "condition", false));
        when(aiServiceClient.detectMismatch(anyString(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyMap(), nullable(String.class)))
                .thenReturn(new com.sphinxfin.sphinx.domain.SuitabilityMismatch(
                        com.sphinxfin.sphinx.domain.SuitabilityStatus.NO_MISMATCH,
                        "테스트", null, List.of()));
    }

    private String openSession() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.data.sessionId");
    }

    @Test
    @DisplayName("❗채점을 안 부른다 — 발화가 없으면 잴 것이 없다(P1)")
    void skippingNeverReachesScoring() throws Exception {
        String sid = openSession();

        mvc.perform(post("/sessions/" + sid + "/skips").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grade").value("U3"))
                .andExpect(jsonPath("$.data.itemId").value(ITEM));

        verify(aiServiceClient, never()).score(anyString(), anyString(), anyString(),
                any(RiskItem.class), anyString(),
                nullable(com.sphinxfin.sphinx.domain.InputMeta.class));
    }

    @Test
    @DisplayName("표시 문자열은 서버가 소유한다 — 화면이 만들면 버튼 라벨과 기록이 갈린다(결정 6.26)")
    void theMarkerBelongsToTheServer() throws Exception {
        String sid = openSession();

        // 본문은 항목뿐이다. 발화를 실을 자리가 아예 없다.
        mvc.perform(post("/sessions/" + sid + "/skips").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\"}"))
                .andExpect(status().isOk());

        // 세션에 남은 발화가 서버 상수여야 한다 — F-DET-002 가 세션 전체 발화를 입력으로
        // 받으므로 이 자리가 비면 건너뛴 항목이 "안 물어본 항목" 과 같아진다.
        mvc.perform(post("/sessions/" + sid + "/judge")).andExpect(status().isOk());
        verify(aiServiceClient).detectMismatch(anyString(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.<java.util.Map<String, String>>argThat(
                        utterances -> SkippedItem.UTTERANCE.equals(utterances.get(ITEM))),
                nullable(String.class));
    }

    @Test
    @DisplayName("항목이 그 상품 것이 아니면 404 — /answers 와 같은 규약이다")
    void unknownItemIsNotFound() throws Exception {
        String sid = openSession();

        mvc.perform(post("/sessions/" + sid + "/skips").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"NOT-AN-ITEM\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("❗판정이 룰에서 왔다고 기록에 적힌다 — 문면으로는 측정된 U3 와 못 가른다")
    void theRecordSaysItWasNotMeasured() {
        Judgment judgment = SkippedItem.judgmentFor(ITEM);

        assertThat(judgment.source())
                .as("SKIPPED 가 없으면 감사 시점에 '이 등급을 모델이 잰 것인가' 에 답할 수 "
                        + "없다 — questionSource 가 질문 문면에 하는 일과 같다(#136 3항)")
                .isEqualTo(Judgment.Source.SKIPPED);
        assertThat(judgment.grade()).isEqualTo(Grade.U3);
        assertThat(judgment.promptVersion())
                .as("이 판정을 낸 프롬프트가 없다. 그 null 이 '버전 미상' 과 겹치는 것은 "
                        + "source 가 갈라 준다")
                .isNull();
        assertThat(judgment.confidence())
                .as("v2 에서 confidence 는 재현 가능성이다(PR #114) — 룰은 항상 같은 값을 "
                        + "낸다. 0 을 넣으면 R-05 가 물어 '낮은 신뢰도로 측정됐다' 로 읽힌다")
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("ai-service 응답에는 이 필드가 없다 — 없으면 MEASURED 다")
    void judgmentsFromUpstreamAreMeasured() {
        Judgment fromAiService = new Judgment(ITEM, Grade.U1, new BigDecimal("0.9"),
                new Judgment.Evidence("발화 인용", "루브릭 조항"), "사유", null);

        assertThat(fromAiService.source())
                .as("계약에서 optional 이라 상류 응답과 이 필드가 생기기 전 레코드에는 없다. "
                        + "그것들은 전부 모델이 잰 판정이므로 기본값이 사실과 같다")
                .isEqualTo(Judgment.Source.MEASURED);
    }

    /** 계약이 이 경로를 적어 두는가 — 화면은 이 문서를 읽고 짠다. */
    @Test
    @DisplayName("계약에 /skips 가 있다")
    void theContractDeclaresTheEndpoint() throws Exception {
        String spec = java.nio.file.Files.readString(
                java.nio.file.Path.of("../contracts/openapi.yaml"));

        assertThat(spec).contains("/sessions/{id}/skips");
        assertThat(List.of(spec.split("\n")))
                .as("본문에 발화를 실을 자리를 만들면 옛 경로가 그대로 돌아온다")
                .anyMatch(line -> line.contains("SkipRequest:"));
    }
}
