package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import com.sphinxfin.sphinx.domain.SuitabilityMismatch;
import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.core.aiservice.AiServiceException;
import com.sphinxfin.sphinx.domain.Grade;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 모순 판정이 실패했을 때 <b>왜 근거가 없는지</b>가 기록에 남는가 (이슈 #169). 소유: 강희진
 *
 * <p>ai-service 를 못 부르면 {@code UNKNOWN} 으로 진행시킨다 — 그 실패에 게이트를 막는 것은
 * 비례하지 않고, {@code UNKNOWN} 은 R-02b 로 황색이 되므로 "확인 못 했다" 가 통과로 새지도
 * 않는다. <b>다만 그 경로에는 근거가 없다.</b>
 *
 * <p>그때 사유를 안 남기면 기록에서 <b>"근거가 비었다" 와 "못 받았다" 가 같아 보인다</b> —
 * 감사 시점에 판정 품질을 의심할지 인프라를 의심할지 가를 수 없다. E-EXT-03 이
 * {@code failure_reason} 으로 하는 일과 같은 자리다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-DET-002 모순 판정 실패의 사유가 남는다 (이슈 #169)")
class MismatchFallbackReasonTest {

    /** 기록으로 넘어간 모순 판정. 폴백 경로가 무엇을 남기는지 본다. */
    static final List<SuitabilityMismatch> RECORDED = new java.util.ArrayList<>();

    @org.springframework.boot.test.context.TestConfiguration
    static class RecordingCfg {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.sphinxfin.sphinx.core.EvidenceRecorder recordingEvidence() {
            return new com.sphinxfin.sphinx.core.EvidenceRecorder() {
                @Override public void appendJudgment(String sid, Judgment j, int r,
                        String q, QuestionSource s, com.sphinxfin.sphinx.domain.InputMeta inputMeta, java.time.Instant at) { }
                @Override public void appendMismatch(String sid, SuitabilityMismatch m,
                        String v, java.util.Map<String, Object> r, java.time.Instant at) {
                    RECORDED.add(m);
                }
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
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString(), anyString(), nullable(com.sphinxfin.sphinx.core.aiservice.AiServiceClient.InterviewContext.class)))
                .thenAnswer(inv -> new AiServiceClient.Question("질문", "OPEN_ENDED", false));
        when(aiServiceClient.score(anyString(), anyString(), anyString(), any(RiskItem.class), anyString()))
                .thenAnswer(inv -> new AiServiceClient.Scored(
                        new Judgment(inv.getArgument(0), Grade.U1, new BigDecimal("0.9"),
                                new Judgment.Evidence("인용", "조항"), "사유", null, null),
                        inv.getArgument(2)));
        // ❗모순 판정만 실패시킨다 — 그래야 폴백 경로가 실제로 돈다.
        when(aiServiceClient.detectMismatch(anyString(), anyMap(), anyMap(), nullable(String.class)))
                .thenThrow(new AiServiceException("연결 실패"));
    }

    @Test
    @DisplayName("❗판정은 진행하되 왜 근거가 없는지를 남긴다 — 비어 있는 것과 다르다")
    void theFallbackRecordsWhyItHasNoBasis() throws Exception {
        String sid = createSession();
        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\",\"text\":\"낙인 하회하면 손실 납니다\"}"))
                .andExpect(status().isOk());

        // 모순 판정 실패가 게이트 판정 자체를 막지는 않는다 — 그 실패에 비례하지 않는다.
        mvc.perform(post("/sessions/{sid}/judge", sid)).andExpect(status().isOk());

        assertThat(RECORDED).hasSize(1);
        assertThat(RECORDED.get(0).status())
                .as("UNKNOWN 은 R-02b 로 황색이 된다 — '확인 못 했다' 가 통과로 새지 않는다")
                .isEqualTo(com.sphinxfin.sphinx.domain.SuitabilityStatus.UNKNOWN);
        assertThat(RECORDED.get(0).reason())
                .as("사유가 비면 기록에서 '근거가 비었다' 와 '못 받았다' 가 같아 보인다 — "
                        + "감사 시점에 판정 품질을 의심할지 인프라를 의심할지 가를 수 없다")
                .isNotBlank()
                .contains("실패");
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
