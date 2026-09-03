package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.InputMeta;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 화면이 보낸 입력 메타데이터가 <b>기록까지 실제로 간다</b>. 소유: 강희진 (이슈 #325)
 *
 * <h2>왜 이 파일이 따로 필요한가</h2>
 *
 * <p>{@code InputMetaRecordedTest} 가 DTO 를 재고 {@code StoredEvidenceRecorderTest} 가
 * 저장을 잰다. <b>그 둘만으로는 컨트롤러가 값을 버려도 전부 초록이다</b> — 실제로 변이를
 * 걸어 확인했다.
 *
 * <pre>
 * SessionController: body.domainInputMeta() → null      →  전건 통과 🟢
 * </pre>
 *
 * <p>그리고 <b>그게 이 이슈가 보고한 결함 그 자체</b>다 — 서버가 받아서 버렸다. 양끝을
 * 재고 가운데를 안 지나가면, 고친 것을 다시 되돌려도 아무도 안 말한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-INT-003 입력 메타데이터 배선 (이슈 #325)")
class InputMetaWiringTest {

    static final List<InputMeta> RECORDED = new ArrayList<>();

    @org.springframework.boot.test.context.TestConfiguration
    static class RecordingCfg {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.sphinxfin.sphinx.core.EvidenceRecorder recordingEvidence() {
            return new com.sphinxfin.sphinx.core.EvidenceRecorder() {
                @Override public void appendJudgment(String sid, Judgment j, int r,
                                                     String askedQuestion,
                                                     QuestionSource questionSource,
                                                     InputMeta inputMeta,
                                                     java.time.Instant at) {
                    RECORDED.add(inputMeta);
                }
                @Override public void appendMismatch(String sid,
                        com.sphinxfin.sphinx.domain.SuitabilityMismatch m,
                        String v, java.util.Map<String, Object> r, java.time.Instant at) { }
                @Override public void appendGate(String sid,
                        com.sphinxfin.sphinx.domain.GateResult res, java.time.Instant at) { }
                @Override public void appendOverride(String sid, String reason,
                        String approver, java.time.Instant at) { }
            };
        }
    }

    private static final String ITEM = "ELS-PRINCIPAL-LOSS-WARNING";

    @Autowired private MockMvc mvc;
    @MockBean private AiServiceClient aiServiceClient;

    @BeforeEach
    void stub() {
        RECORDED.clear();
        when(aiServiceClient.score(anyString(), anyString(), anyString(),
                any(RiskItem.class), anyString()))
                .thenReturn(new AiServiceClient.Scored(
                        new Judgment(ITEM, Grade.U1, new BigDecimal("0.9"),
                                new Judgment.Evidence("발화 인용", "루브릭 조항"), "사유", null),
                        "마스킹된 답변"));
    }

    @Test
    @DisplayName("❗화면이 보낸 것이 기록 호출까지 간다 — 서버가 받아서 버리고 있었다")
    void whatTheScreenSendsReachesTheRecorder() throws Exception {
        answer("""
                {"itemId":"%s","text":"제 말로 설명하면 원금이 줄 수 있습니다",
                 "inputMeta":{"firstKeystrokeDelayMs":1200,"totalInputMs":8000,
                              "pasteDetected":true,"backspaceCount":3,"charCount":42,
                              "elderlyMode":false}}""".formatted(ITEM));

        assertThat(RECORDED).hasSize(1);
        assertThat(RECORDED.get(0))
                .as("붙여넣기로 채운 되말하기는 발화 내용만 보면 완벽한 U1 이다. "
                        + "컨트롤러가 이 값을 버리면 그 행동을 구분할 방법이 아예 없다")
                .isNotNull();
        assertThat(RECORDED.get(0).pasteDetected()).isTrue();
        assertThat(RECORDED.get(0).charCount()).isEqualTo(42);
        assertThat(RECORDED.get(0).firstKeystrokeDelayMs()).isEqualTo(1200);
    }

    @Test
    @DisplayName("안 보내면 null 로 간다 — 0 으로 채우면 '즉답' 과 '안 보냈다' 가 같아진다")
    void anAbsentMetaArrivesAsNull() throws Exception {
        answer("{\"itemId\":\"" + ITEM + "\",\"text\":\"답변\"}");

        assertThat(RECORDED).hasSize(1);
        assertThat(RECORDED.get(0)).isNull();
    }

    @Test
    @DisplayName("❗판매자 응답에 안 실린다 — 잡히는 걸 알면 손으로 옮겨 적는다")
    void theResponseDoesNotEchoIt() throws Exception {
        String body = answer("""
                {"itemId":"%s","text":"답변",
                 "inputMeta":{"firstKeystrokeDelayMs":10,"totalInputMs":20,
                              "pasteDetected":true,"backspaceCount":0,"charCount":2,
                              "elderlyMode":false}}""".formatted(ITEM));

        assertThat(body)
                .as("응답이 되비추면 판매자가 무엇이 잡히는지 알게 된다 — 신호만 죽고 "
                        + "행동은 그대로다(#144 와 같은 결)")
                .doesNotContain("pasteDetected")
                .doesNotContain("inputMeta");
    }

    private String answer(String json) throws Exception {
        String sid = JsonPath.read(mvc.perform(post("/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8), "$.data.sessionId");

        return mvc.perform(post("/sessions/{sid}/answers", sid)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
