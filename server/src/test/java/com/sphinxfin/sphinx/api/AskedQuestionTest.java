package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import com.sphinxfin.sphinx.domain.SuitabilityMismatch;
import com.sphinxfin.sphinx.core.EvidenceRecorder;
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
    static final java.util.List<com.sphinxfin.sphinx.core.EvidenceRecorder.QuestionSource> SOURCES
            = new java.util.ArrayList<>();

    @org.springframework.boot.test.context.TestConfiguration
    static class RecordingCfg {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.sphinxfin.sphinx.core.EvidenceRecorder recordingEvidence() {
            return new com.sphinxfin.sphinx.core.EvidenceRecorder() {
                @Override public void appendJudgment(String sid, Judgment j, int r,
                                                     String askedQuestion,
                                                     QuestionSource questionSource,
                                                     com.sphinxfin.sphinx.domain.InputMeta inputMeta,
                                                     java.time.Instant at) {
                    RECORDED.add(askedQuestion);
                    SOURCES.add(questionSource);
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
    private static final String Q_REVERIFY = "방금 설명드린 것 중 가장 중요한 게 뭐라고 이해하셨는지 말씀해 주시겠어요?";
    private static final String Q_AI = "이 상품은 어떤 경우에 원금이 줄어들 수 있을까요? 아시는 대로 말씀해 주세요.";

    @Autowired private MockMvc mvc;
    @MockBean private AiServiceClient aiServiceClient;

    @BeforeEach
    void stub() {
        RECORDED.clear();
        SOURCES.clear();
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString(), anyString(), nullable(com.sphinxfin.sphinx.core.aiservice.AiServiceClient.InterviewContext.class)))
                // ❗변형별로 다른 문면을 낸다. 재검증 질문도 이제 ai-service 가 만들고
                // (7-4 1단계 — 고정 문항이면 사전에 확보돼 뚫린다), 같은 문장을 두 번
                // 내면 "다시 묻는" 것이 아니다.
                .thenAnswer(inv -> new AiServiceClient.Question(
                        "reverify".equals(inv.getArgument(3)) ? Q_REVERIFY : Q_AI,
                        "OPEN_ENDED", false));
        when(aiServiceClient.score(anyString(), anyString(), anyString(), any(RiskItem.class), anyString()))
                .thenAnswer(inv -> new AiServiceClient.Scored(
                        new Judgment(inv.getArgument(0), Grade.U1, new BigDecimal("0.9"),
                                new Judgment.Evidence("낙인 하회하면 손실", "원금손실 조건 인지"),
                                "정확히 진술", null),
                        inv.getArgument(2)));
        when(aiServiceClient.detectMismatch(anyString(), anyMap(), anyMap(), nullable(String.class)))
                .thenReturn(new SuitabilityMismatch(SuitabilityStatus.NO_MISMATCH, "테스트", null, java.util.List.of()));
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

        // ❗같은 문면이라 문면만으로는 못 가른다. 고객이 그것을 봤는지는 따로 남아야 한다.
        assertThat(SOURCES)
                .as("고객은 이 문면을 본 적이 없다 — 서버가 채점 맥락으로 지어낸 것이다. "
                        + "기록이 정상 경로와 똑같이 생기면 감사 시점에 '이 판정은 고객이 "
                        + "보고 답한 질문으로 잰 것인가' 에 답할 수 없다 (#136 3항)")
                .containsExactly(com.sphinxfin.sphinx.core.EvidenceRecorder.QuestionSource.SERVER_FALLBACK);
    }

    @Test
    @DisplayName("❗화면을 거친 답변은 DISPLAYED 로 남는다 — 폴백 표식이 항상 붙으면 뜻이 없다")
    void displayedQuestionIsRecordedAsDisplayed() throws Exception {
        String sid = createSession();
        mvc.perform(post("/sessions/{sid}/questions/next", sid)).andExpect(status().isOk());
        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\",\"text\":\"낙인 하회하면 손실 납니다\"}"))
                .andExpect(status().isOk());

        assertThat(SOURCES)
                .as("정상 경로가 SERVER_FALLBACK 으로 남으면 표식이 아무것도 안 가른다 — "
                        + "폴백만 잡는 단정은 상수 SERVER_FALLBACK 을 넣어도 통과한다")
                .containsExactly(com.sphinxfin.sphinx.core.EvidenceRecorder.QuestionSource.DISPLAYED);
    }

    @Test
    @DisplayName("❗재검증 답변은 재검증 질문으로 채점·기록된다 — 원 질문으로 재면 안 된다")
    void theReverifyAnswerIsScoredAgainstTheReverifyQuestion() throws Exception {
        // 되말하기 두 번째 바퀴다. 화면은 계약대로 재검증 문면을 띄우고 고객은 그것에 답하는데,
        // 그 문면이 기록되지 않으면 /answers 가 **원 질문**으로 채점하고 기록한다(이슈 #274).
        // 필드가 비는 것보다 나쁘다 — 기록이 다른 질문을 가리키니 아무도 의심하지 않는다.
        String sid = createSession();
        mvc.perform(post("/sessions/{sid}/questions/next", sid)).andExpect(status().isOk());

        // 1바퀴: U2 로 떨어뜨려 재설명 대상으로 만든다.
        when(aiServiceClient.score(anyString(), anyString(), anyString(), any(RiskItem.class), anyString()))
                .thenAnswer(inv -> new AiServiceClient.Scored(
                        new Judgment(inv.getArgument(0), Grade.U2, new BigDecimal("0.9"),
                                new Judgment.Evidence("일부만 말함", "원금손실 조건 인지"),
                                "부분 이해", null),
                        inv.getArgument(2)));
        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\",\"text\":\"손실이 날 수도 있다고 들었어요\"}"))
                .andExpect(status().isOk());

        when(aiServiceClient.reExplain(any(RiskItem.class), any(Judgment.class),
                nullable(String.class), nullable(String.class)))
                .thenReturn(new AiServiceClient.ReExplanation("쉬운 말 재설명", java.util.List.of()));

        String re = mvc.perform(post("/sessions/{sid}/re-explain", sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String shown = JsonPath.read(re, "$.data.reverifyQuestion");
        assertThat(shown).as("화면에 띄울 문면이 응답에 있어야 한다").isNotBlank();

        RECORDED.clear();
        SOURCES.clear();
        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\",\"text\":\"낙인 아래로 떨어지면 손실이 납니다\"}"))
                .andExpect(status().isOk());

        assertThat(RECORDED)
                .as("고객이 본 것은 재검증 문면인데 기록이 원 질문을 가리키면, "
                        + "리포트가 '왜 황색이었다가 통과했는가' 에 틀린 근거로 답한다(5.12)")
                .containsExactly(shown);
        assertThat(RECORDED).doesNotContain(Q_AI);
        assertThat(SOURCES)
                .as("서버가 만든 문면이 화면에 나갔다 — DISPLAYED 도 폴백도 아니다")
                .containsExactly(EvidenceRecorder.QuestionSource.REVERIFY);
    }

    @Test
    @DisplayName("❗템플릿 폴백 질문은 TEMPLATE_FALLBACK 으로 남는다 — 고객은 봤지만 모델이 안 만들었다")
    void templateFallbackQuestionIsRecordedApart() throws Exception {
        // ai-service 가 정답 노출 검사를 통과 못 해 템플릿 고정 문장으로 내려간 회차다
        // (F-INT-002). 화면에는 정상적으로 나가므로 고객 경험은 DISPLAYED 와 같고,
        // 갈리는 것은 **무엇을 측정했는가** 다 — 폴백 질문으로 받은 답이 성능 수치에
        // 섞이면 F-INT-002 가 사실상 안 돈 회차를 정상으로 센다(이슈 #234).
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString(), anyString(), nullable(com.sphinxfin.sphinx.core.aiservice.AiServiceClient.InterviewContext.class)))
                .thenAnswer(inv -> new AiServiceClient.Question(
                        "이 항목에 대해 이해하신 대로 말씀해 주시겠어요?", "OPEN_ENDED", true));

        String sid = createSession();
        mvc.perform(post("/sessions/{sid}/questions/next", sid)).andExpect(status().isOk());
        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\",\"text\":\"낙인 하회하면 손실 납니다\"}"))
                .andExpect(status().isOk());

        assertThat(SOURCES)
                .as("서버가 fallbackUsed 를 받아서 버리면 여기가 DISPLAYED 로 남는다 — "
                        + "그러면 폴백률을 기록에서 셀 방법이 없다")
                .containsExactly(EvidenceRecorder.QuestionSource.TEMPLATE_FALLBACK);
        assertThat(RECORDED)
                .as("문면 자체는 화면에 나간 그것이어야 한다 — 출처만 다르지 맥락은 같다")
                .containsExactly("이 항목에 대해 이해하신 대로 말씀해 주시겠어요?");
    }

    @Test
    @DisplayName("❗폴백 뒤 정상 질문을 받으면 표식이 사라진다 — 항목이 영원히 폴백으로 집계되면 안 된다")
    void aLaterNormalQuestionClearsTheMark() throws Exception {
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString(), anyString(), nullable(com.sphinxfin.sphinx.core.aiservice.AiServiceClient.InterviewContext.class)))
                .thenAnswer(inv -> new AiServiceClient.Question("고정 문장", "OPEN_ENDED", true));
        String sid = createSession();
        mvc.perform(post("/sessions/{sid}/questions/next", sid)).andExpect(status().isOk());

        // 재질문에서 모델이 정상 문면을 냈다. 채점 맥락은 이쪽이므로 표식도 이쪽을 따라야 한다.
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString(), anyString(), nullable(com.sphinxfin.sphinx.core.aiservice.AiServiceClient.InterviewContext.class)))
                .thenAnswer(inv -> new AiServiceClient.Question(
                        "낙인이 무엇인지 설명해 주시겠어요?", "OPEN_ENDED", false));
        mvc.perform(post("/sessions/{sid}/questions/next", sid)).andExpect(status().isOk());

        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\",\"text\":\"낙인 하회하면 손실 납니다\"}"))
                .andExpect(status().isOk());

        assertThat(SOURCES)
                .as("표식을 지우지 않으면 한 번 폴백난 항목이 계속 폴백으로 집계된다")
                .containsExactly(EvidenceRecorder.QuestionSource.DISPLAYED);
    }

    @Test
    @DisplayName("질문을 다시 받으면 마지막에 보여준 것이 채점 맥락이 된다")
    void reaskedQuestionOverwrites() throws Exception {
        String sid = createSession();
        mvc.perform(post("/sessions/{sid}/questions/next", sid)).andExpect(status().isOk());

        String second = "다시 여쭙겠습니다 — 원금이 줄어드는 조건을 말씀해 주시겠어요?";
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString(), anyString(), nullable(com.sphinxfin.sphinx.core.aiservice.AiServiceClient.InterviewContext.class)))
                .thenAnswer(inv -> new AiServiceClient.Question(second, "OPEN_ENDED", false));
        mvc.perform(post("/sessions/{sid}/questions/next", sid)).andExpect(status().isOk());

        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\",\"text\":\"낙인 하회하면 손실 납니다\"}"))
                .andExpect(status().isOk());

        assertThat(scoredQuestion())
                .as("고객이 답한 것은 마지막에 본 질문이다")
                .isEqualTo(second);
    }
}
