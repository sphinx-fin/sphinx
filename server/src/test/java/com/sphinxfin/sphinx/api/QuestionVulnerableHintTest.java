package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ★ <b>취약 힌트가 첫 질문에 실린다</b> — 이슈 #319. 소유: 강희진 (F-INT-002)
 *
 * <h2>무엇이 문제였나</h2>
 *
 * <p>S-03 이 큰 글씨를 켜는 경로가 <b>하나뿐</b>이었고 그것이 재설명 응답의
 * {@code vulnerable} 이었다. 재설명은 <b>고객이 한 번 못 알아들은 뒤</b>에 온다 — 세션
 * 시작에서 이미 「70대」를 받아 놓고 인터뷰는 작은 글씨로 시작했다.
 *
 * <h2>❗화면이 연령대로 대신 가르는 안은 기각됐다</h2>
 *
 * <p>취약 판정의 근거는 {@code vulnerability_weights.yaml}(연령·가입금액대·투자경험·채널
 * 네 요인의 합 ≥ 임계값)이고 <b>연령대만 보는 근사가 아니다.</b> 화면이 다시 계산하면
 * 임계값이 web 에 두 벌이 되고, F-DET-002 소유자가 그 파일을 움직이는 날 <b>조용히</b>
 * 갈린다. 그래서 서버 판단 하나를 실어 화면은 켜기만 한다.
 *
 * <p>{@code QuestionContextWiringTest} 는 같은 값이 <b>ai-service 쪽으로</b> 가는 것을
 * 재고, 여기는 <b>화면 쪽으로</b> 나가는 것을 잰다. 두 수요자가 다르므로 한쪽이 초록이어도
 * 다른 쪽은 빌 수 있다 — 실제로 화면 쪽이 비어 있었던 것이 이 이슈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-INT-002 취약 힌트 (화면 쪽)")
class QuestionVulnerableHintTest {

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
                        new Judgment(inv.getArgument(0), Grade.U1, new BigDecimal("0.9"),
                                new Judgment.Evidence("인용", "조항"), "사유", null),
                        "마스킹"));
    }

    @Test
    @DisplayName("★ 취약 고객이면 첫 질문 응답이 이미 그렇게 말한다 — 재설명을 기다리지 않는다")
    void theFirstQuestionAlreadyCarriesTheHint() throws Exception {
        String sid = session("70대", "없음", "5천만원대");   // 4+3+1 = 8 ≥ 임계 4

        assertThat(JsonPath.<Boolean>read(ask(sid), "$.data.vulnerable"))
                .as("여기가 false 면 화면은 재설명 응답이 올 때까지 작은 글씨다 — 즉 "
                        + "고객이 한 번 못 알아들은 뒤에야 커진다(이슈 #319)")
                .isTrue();
    }

    @Test
    @DisplayName("일반 고객이면 안 켠다 — 정황이 없는데 큰 글씨로 바꾸면 그것도 틀린 것이다")
    void anOrdinaryCustomerIsNotFlagged() throws Exception {
        String sid = session("30대", "3년이상", "1천만원미만");

        assertThat(JsonPath.<Boolean>read(ask(sid), "$.data.vulnerable")).isFalse();
    }

    @Test
    @DisplayName("❗done=true 응답에도 실린다 — 세션의 성질이지 이번 질문의 성질이 아니다")
    void theDoneResponseCarriesItToo() throws Exception {
        String sid = session("70대", "없음", "5천만원대");
        for (RiskItem item : MockData.RISK_ITEMS) {
            answer(sid, item.itemId());
        }

        String body = ask(sid);

        assertThat(JsonPath.<Boolean>read(body, "$.data.done")).isTrue();
        assertThat(JsonPath.<Boolean>read(body, "$.data.vulnerable"))
                .as("마지막 항목 뒤에만 힌트가 비면 화면이 그 시점에 모드를 되돌릴 근거를 "
                        + "갖게 된다 — 큰 글씨는 켜기만 하고 되돌리지 않는 것이 규칙이다")
                .isTrue();
    }

    // ── 거들기 ────────────────────────────────────────────────────────────────
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

    private String ask(String sid) throws Exception {
        return mvc.perform(post("/sessions/{sid}/questions/next", sid))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private void answer(String sid, String itemId) throws Exception {
        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + itemId + "\",\"text\":\"답변입니다\"}"))
                .andExpect(status().isOk());
    }
}
