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
 * 이미 쓴 질문 유형이 <b>다음 질문 생성까지 간다</b>. 소유: 강희진 (이슈 #325 · F-INT-002)
 *
 * <h2>왜 유형이 문제인가</h2>
 *
 * <p>ai-service 는 {@code asked_types} 를 받아 <b>남은 유형에서 고르게</b> 만들어져 있었다
 * (PR #60). 그런데 서버가 그 자리에 늘 {@code List.of()} 를 넣었다 — <b>기능이 있는데 입력이
 * 안 갔다.</b> 그래서 같은 항목을 두 번 물어보는 자리(재검증)에서 <b>유형이 겹칠 수 있었다.</b>
 *
 * <p>유형이 겹치면 두 가지가 동시에 깨진다.
 *
 * <ul>
 *   <li><b>측정</b> — 같은 각도로 두 번 재는 것이라 재검증이 새로 아는 것이 없다. 재검증은
 *       <i>"재설명을 듣고 이해가 올라갔나"</i> 를 재려는 것인데, 같은 질문이면 <b>기억력</b>을
 *       잰다.</li>
 *   <li><b>기획서 7-4 1단계</b> — 문면이 매번 생성돼도 <b>유형이 고정이면 대비할 수 있다.</b>
 *       재검증은 판매자가 미리 답을 준비시킬 동기가 가장 큰 자리다(첫 질문에서 이미 한 번
 *       막혔으므로).</li>
 * </ul>
 *
 * <h2>❗세 층을 한 번에 지나간다</h2>
 *
 * <p>{@code Session} 이 유형을 담는가 · 컨트롤러가 그것을 읽는가 · 재검증이 그것을 넘기는가 —
 * 셋을 각각 재면 <b>가운데가 끊겨도 양쪽이 초록</b>이다. 여기서는 HTTP 로 들어가서
 * ai-service 경계에 실제로 도착한 값을 잡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-INT-002 이미 쓴 질문 유형 배선 (이슈 #325)")
class AskedTypesWiringTest {

    private static final String ITEM = "ELS-PRINCIPAL-LOSS-WARNING";

    @Autowired private MockMvc mvc;
    @MockBean private AiServiceClient aiServiceClient;

    /** 이번 호출이 무슨 유형을 냈다고 답할지. 회차마다 바꿔 가며 쓴다. */
    private String generatedType = "situation";

    @BeforeEach
    void stub() {
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString(), anyString(),
                nullable(AiServiceClient.InterviewContext.class)))
                .thenAnswer(inv -> new AiServiceClient.Question("질문?", generatedType, false));
        when(aiServiceClient.score(anyString(), anyString(), anyString(),
                any(RiskItem.class), anyString(),
                nullable(com.sphinxfin.sphinx.domain.InputMeta.class)))
                .thenAnswer(inv -> new AiServiceClient.Scored(
                        new Judgment(inv.getArgument(0), Grade.U4, new BigDecimal("0.9"),
                                new Judgment.Evidence("인용", "조항"), "사유", null),
                        "마스킹"));
        when(aiServiceClient.reExplain(any(RiskItem.class), any(Judgment.class),
                nullable(String.class), nullable(String.class)))
                .thenReturn(new AiServiceClient.ReExplanation("쉬운 설명", List.of()));
    }

    @Test
    @DisplayName("❗재검증 질문 생성이 첫 질문의 유형을 받는다 — 같은 각도로 두 번 재지 않는다")
    void theReverifyCallKnowsWhichTypeWasAlreadyUsed() throws Exception {
        String sid = session();
        generatedType = "amount";
        ask(sid);                     // 1회차: amount 로 물었다
        answer(sid);

        mvc.perform(post("/sessions/{sid}/re-explain", sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\"}"))
                .andExpect(status().isOk());

        assertThat(lastAskedTypes())
                .as("빈 목록이 가면 생성기가 amount 를 다시 고를 수 있다 — 그러면 재검증이 "
                        + "이해가 아니라 기억력을 잰다")
                .contains("amount");
    }

    @Test
    @DisplayName("첫 질문에는 빈 목록이 간다 — 쓴 유형이 없으니 셋 다 후보다")
    void theFirstQuestionHasNothingToAvoid() throws Exception {
        String sid = session();

        ask(sid);

        assertThat(lastAskedTypes())
                .as("안 쓴 유형을 배제하면 후보만 줄고 좁은 질문이 나온다")
                .isEmpty();
    }

    @Test
    @DisplayName("❗기록되는 유형은 생성기가 답한 값이다 — 서버가 정해 두면 커버리지가 조용히 틀린다")
    void theRecordedTypeIsWhateverTheGeneratorSaid() throws Exception {
        String sid = session();
        generatedType = "condition";
        ask(sid);
        answer(sid);

        mvc.perform(post("/sessions/{sid}/re-explain", sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\"}"))
                .andExpect(status().isOk());

        assertThat(lastAskedTypes()).containsExactly("condition");
    }

    // ── 거들기 ────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private List<String> lastAskedTypes() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiServiceClient, atLeastOnce()).question(any(RiskItem.class), captor.capture(),
                anyString(), anyString(), nullable(AiServiceClient.InterviewContext.class));
        return captor.getValue();
    }

    private String session() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE",
                                 "ageBand":"30대","experienceLevel":"3년이상",
                                 "amountBand":"1천만원미만"}"""))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(created, "$.data.sessionId");
    }

    private void ask(String sid) throws Exception {
        mvc.perform(post("/sessions/{sid}/questions/next", sid)).andExpect(status().isOk());
    }

    private void answer(String sid) throws Exception {
        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\",\"text\":\"답변입니다\"}"))
                .andExpect(status().isOk());
    }
}
