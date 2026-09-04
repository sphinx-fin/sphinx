package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
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
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 면담 맥락이 <b>질문 생성까지 실제로 간다</b>. 소유: 강희진 (F-INT-002)
 *
 * <h2>무엇이 문제였나</h2>
 *
 * <p>질문 생성이 받는 것은 항목과 상품 유형뿐이었고, 서버는 {@code askedTypes} 마저
 * {@code List.of()} 로 하드코딩해 넘겼다. <b>이 고객이 취약한지, 방금 무엇을 틀렸는지,
 * 어떤 오해가 이미 걸렸는지 모른 채</b> 매번 첫 질문처럼 만들었다.
 *
 * <h2>❗재검증 질문이 7-4 1단계를 어기고 있었다</h2>
 *
 * <p>재설명 뒤 다시 묻는 질문이 {@code SessionService} 에 <b>항목별 고정 문항</b>으로
 * 있었고, 그 자리 주석이 스스로 <i>"사전에 확보하면 그대로 뚫린다"</i> 고 적었다.
 * 재검증은 <b>판매자가 미리 답을 준비시킬 동기가 가장 큰 자리</b>다 — 첫 질문에서 이미
 * 한 번 막혔으므로.
 *
 * <p>이 파일은 <b>그 층을 지나가는</b> 것을 잰다. 맥락을 만드는 코드와 그것을 쓰는
 * 프롬프트를 각각 재면, <b>컨트롤러가 안 넘겨도 양쪽이 초록</b>이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-INT-002 면담 맥락 배선")
class QuestionContextWiringTest {

    private static final String ITEM = "ELS-PRINCIPAL-LOSS-WARNING";
    private static final String OTHER = "ELS-NO-DEPOSIT-INSURANCE";

    @Autowired private MockMvc mvc;
    @MockBean private AiServiceClient aiServiceClient;

    @BeforeEach
    void stub() {
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString(), anyString(),
                nullable(AiServiceClient.InterviewContext.class)))
                .thenReturn(new AiServiceClient.Question("질문?", "situation", false));
        when(aiServiceClient.score(anyString(), anyString(), anyString(),
                any(RiskItem.class), anyString(),
                nullable(com.sphinxfin.sphinx.domain.InputMeta.class)))
                .thenAnswer(inv -> new AiServiceClient.Scored(
                        new Judgment(inv.getArgument(0), Grade.U4, new BigDecimal("0.9"),
                                new Judgment.Evidence("인용", "조항"), "사유", "M02-DEPOSIT-INSURANCE"),
                        "마스킹"));
        when(aiServiceClient.reExplain(any(RiskItem.class), any(Judgment.class),
                nullable(String.class), nullable(String.class)))
                .thenReturn(new AiServiceClient.ReExplanation("쉬운 설명", List.of()));
    }

    @Test
    @DisplayName("❗취약 고객이면 그 사실이 질문 생성까지 간다 — 지금까지 글자 크기만 달랐다")
    void vulnerabilityReachesTheGenerator() throws Exception {
        String sid = session("60대", "없음", "5천만원대");   // 3+3+1 = 7 ≥ 임계 4

        ask(sid);

        assertThat(lastContext().vulnerable())
                .as("고령자 배려가 화면 설정에만 있고 질문에는 안 닿으면, 같은 문장을 "
                        + "60대에게도 30대에게도 똑같이 낸다")
                .isTrue();
    }

    @Test
    @DisplayName("일반 고객이면 안 간다 — 정황이 없는데 눈높이를 낮추면 그것도 틀린 것이다")
    void anOrdinaryCustomerIsNotFlagged() throws Exception {
        String sid = session("30대", "3년이상", "1천만원미만");

        ask(sid);

        assertThat(lastContext().vulnerable()).isFalse();
    }

    @Test
    @DisplayName("❗앞 항목에서 걸린 오해가 다음 질문 맥락에 실린다")
    void anEarlierMisconceptionTravelsToTheNextQuestion() throws Exception {
        String sid = session("30대", "3년이상", "1천만원미만");
        answer(sid, ITEM);

        ask(sid);

        assertThat(lastContext().matchedMisconceptions())
                .as("이미 확인된 오해를 다음 질문이 모르면 같은 오해를 항목마다 새로 발견한다")
                .contains("M02-DEPOSIT-INSURANCE");
        assertThat(lastContext().priorGrades()).contains("U4");
    }

    @Test
    @DisplayName("❗지금 물으려는 항목의 앞선 판정은 빼고 넘긴다 — 고객이 자기 점수를 알게 된다")
    void theItemBeingAskedIsExcluded() throws Exception {
        String sid = session("30대", "3년이상", "1천만원미만");
        answer(sid, ITEM);

        // 같은 항목을 재검증한다 — 이때 그 항목의 U4 가 맥락으로 가면 안 된다.
        mvc.perform(post("/sessions/{sid}/re-explain", sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\"}"))
                .andExpect(status().isOk());

        assertThat(lastContext().priorGrades())
                .as("재검증에서 자기 직전 등급이 맥락으로 가면 '방금 U4 였다' 가 질문에 실린다")
                .isEmpty();
    }

    @Test
    @DisplayName("❗재검증 질문을 ai-service 가 만든다 — 고정 문항이면 사전에 확보돼 뚫린다 (7-4)")
    void theReverifyQuestionIsGeneratedNotHardcoded() throws Exception {
        String sid = session("30대", "3년이상", "1천만원미만");
        answer(sid, ITEM);

        String body = mvc.perform(post("/sessions/{sid}/re-explain", sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        ArgumentCaptor<String> variant = ArgumentCaptor.forClass(String.class);
        verify(aiServiceClient, atLeastOnce()).question(any(RiskItem.class), anyList(),
                anyString(), variant.capture(), nullable(AiServiceClient.InterviewContext.class));

        assertThat(variant.getAllValues())
                .as("재검증이 생성 경로를 안 지나면 서버에 고정 문항이 남아 있는 것이다")
                .contains("reverify");
        assertThat(JsonPath.<String>read(body, "$.data.reverifyQuestion"))
                .isEqualTo("질문?");
    }

    // ── 거들기 ────────────────────────────────────────────────────────────────
    private AiServiceClient.InterviewContext lastContext() {
        ArgumentCaptor<AiServiceClient.InterviewContext> captor =
                ArgumentCaptor.forClass(AiServiceClient.InterviewContext.class);
        verify(aiServiceClient, atLeastOnce()).question(any(RiskItem.class), anyList(),
                anyString(), anyString(), captor.capture());
        AiServiceClient.InterviewContext last = captor.getValue();
        assertThat(last).as("맥락을 안 넘기면 생성기가 첫 질문처럼 만든다").isNotNull();
        return last;
    }

    private String session(String ageBand, String experience, String amount) throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE",
                                 "ageBand":"%s","experienceLevel":"%s","amountBand":"%s"}"""
                                .formatted(ageBand, experience, amount)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(created, "$.data.sessionId");
    }

    private void ask(String sid) throws Exception {
        mvc.perform(post("/sessions/{sid}/questions/next", sid)).andExpect(status().isOk());
    }

    private void answer(String sid, String itemId) throws Exception {
        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + itemId + "\",\"text\":\"답변입니다\"}"))
                .andExpect(status().isOk());
    }
}
