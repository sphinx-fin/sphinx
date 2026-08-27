package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
import com.sphinxfin.sphinx.domain.SuitabilityStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 채점이 <b>고객이 실제로 본 질문</b>으로 도는가 (이슈 #120). 소유: 강희진
 *
 * <p>질문은 ai-service 가 매번 생성한다. 저장하지 않으면 채점 시점에 재현할 수 없어서
 * 화면에 나간 질문(Q_ai)과 채점에 넘어간 질문이 갈린다. 루브릭 기반이라 명백한 오해는
 * 그대로 잡히지만 경계 사례에서 맥락이 어긋나고, 무엇보다 <b>근거가 "묻지 않은 질문에 대한
 * 답"을 인용</b>하게 된다 — 인용 대조는 답변만 보므로 그걸 못 잡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-INT-002 채점 질문 ↔ 표시 질문 일치")
class AskedQuestionTest {

    /** 불변 기록에 실제로 넘어간 질문을 본다 — 채점값과 갈리는지가 요지다(#137 리뷰). */
    static final java.util.List<String> RECORDED = new java.util.ArrayList<>();

    @org.springframework.boot.test.context.TestConfiguration
    static class RecordingCfg {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.sphinxfin.sphinx.core.EvidenceRecorder recordingEvidence() {
            return new com.sphinxfin.sphinx.core.EvidenceRecorder() {
                @Override public void appendJudgment(String sid, Judgment j, int r,
                                                     String askedQuestion, java.time.Instant at) {
                    RECORDED.add(askedQuestion);
                }
                @Override public void appendGate(String sid,
                        com.sphinxfin.sphinx.domain.GateResult res, java.time.Instant at) { }
                @Override public void appendOverride(String sid, String reason,
                        String approver, java.time.Instant at) { }
            };
        }
    }

    private static final String ITEM = "ELS-PRINCIPAL-LOSS-WARNING";
    private static final String Q_AI = "이 상품은 어떤 경우에 원금이 줄어들 수 있을까요? 아시는 대로 말씀해 주세요.";

    @Autowired private MockMvc mvc;
    @MockBean private AiServiceClient aiServiceClient;

    @BeforeEach
    void stub() {
        RECORDED.clear();
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString()))
                .thenAnswer(inv -> new AiServiceClient.Question(Q_AI, "OPEN_ENDED"));
        when(aiServiceClient.score(anyString(), anyString(), anyString(), any(RiskItem.class), anyString()))
                .thenAnswer(inv -> new AiServiceClient.Scored(
                        new Judgment(inv.getArgument(0), Grade.U1, new BigDecimal("0.9"),
                                new Judgment.Evidence("낙인 하회하면 손실", "원금손실 조건 인지"),
                                "정확히 진술", null),
                        inv.getArgument(2)));
        when(aiServiceClient.detectMismatch(anyString(), anyMap(), anyMap(), nullable(String.class)))
                .thenReturn(SuitabilityStatus.NO_MISMATCH);
    }

    private String createSession() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return JsonPath.read(created, "$.data.sessionId");
    }

    private String scoredQuestion() {
        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(aiServiceClient).score(anyString(), q.capture(), anyString(),
                any(RiskItem.class), anyString());
        return q.getValue();
    }

    @Test
    @DisplayName("❗채점에 넘어간 질문이 화면에 나간 질문과 같다")
    void scoringUsesTheQuestionTheCustomerSaw() throws Exception {
        String sid = createSession();

        String shown = JsonPath.read(mvc.perform(post("/sessions/{sid}/questions/next", sid))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                "$.data.question");
        assertThat(shown).isEqualTo(Q_AI);

        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\",\"text\":\"낙인 하회하면 손실 납니다\"}"))
                .andExpect(status().isOk());

        assertThat(scoredQuestion())
                .as("고객은 Q_ai 에 답했는데 채점이 다른 문면으로 돌면, 근거가 "
                        + "'묻지 않은 질문에 대한 답'을 인용하게 된다 — 인용 대조는 답변만 본다")
                .isEqualTo(Q_AI);
    }

    @Test
    @DisplayName("질문을 안 받고 답변만 오면 채점을 막지 않는다 — 답변을 버리는 게 더 나쁘다")
    void answerWithoutAskedQuestionStillScores() throws Exception {
        String sid = createSession();

        // /questions/next 를 안 부르고 바로 답변한다(테스트·직접 호출 경로).
        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\",\"text\":\"낙인 하회하면 손실 납니다\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grade").value("U1"));

        assertThat(scoredQuestion())
                .as("질문 맥락이 없다고 답변을 버리면 세션 데이터가 사라진다(명세 10절)")
                .isNotBlank();

        // ❗폴백에서도 채점값과 기록값이 같아야 한다. 값을 두 곳에서 따로 구하면 여기서
        // 갈리고(채점은 목 문면, 기록은 null), 그러면 null 이 "필드 이전 레코드" 와
        // "폴백이었다" 두 뜻을 갖는다 — append-only 라 섞인 뒤에는 못 가른다 (#137 리뷰).
        assertThat(RECORDED)
                .as("기록에 남은 질문이 채점에 쓴 질문과 같아야 한다")
                .containsExactly(scoredQuestion());
    }

    @Test
    @DisplayName("질문을 다시 받으면 마지막에 보여준 것이 채점 맥락이 된다")
    void reaskedQuestionOverwrites() throws Exception {
        String sid = createSession();
        mvc.perform(post("/sessions/{sid}/questions/next", sid)).andExpect(status().isOk());

        String second = "다시 여쭙겠습니다 — 원금이 줄어드는 조건을 말씀해 주시겠어요?";
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString()))
                .thenAnswer(inv -> new AiServiceClient.Question(second, "OPEN_ENDED"));
        mvc.perform(post("/sessions/{sid}/questions/next", sid)).andExpect(status().isOk());

        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\",\"text\":\"낙인 하회하면 손실 납니다\"}"))
                .andExpect(status().isOk());

        assertThat(scoredQuestion())
                .as("고객이 답한 것은 마지막에 본 질문이다")
                .isEqualTo(second);
    }
}
