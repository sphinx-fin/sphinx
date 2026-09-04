package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
import com.sphinxfin.sphinx.domain.SuitabilityMismatch;
import com.sphinxfin.sphinx.domain.SuitabilityStatus;
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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재검증에서 <b>같은 답을 되풀이했는데 등급만 올라간</b> 세션. 소유: 강희진 (이슈 #268 (d))
 *
 * <h2>❗이 세션이 GREEN 이었다</h2>
 *
 * <p>게이트가 보는 것은 <b>최종 등급</b>뿐이다. 1회차 U3 → 재설명 → 2회차에 <b>같은 말</b>을
 * 냈는데 U1 이 나오면, 세션에 남는 판정은 U1 하나라서 {@code R-06} 이 GREEN 을 낸다.
 * 재설명이 이해를 올린 것이 아니라 <b>채점이 흔들린 것</b>인데 통과한다.
 *
 * <p>그리고 <b>되돌아오지 않는다</b> — 판정이 서면 세션은 {@code JUDGED} 이고 거기서 나가는
 * 전이는 {@code CLOSE} 뿐이다. 미탐이 오탐보다 비싸다는 P5 가 말하는 자리가 이것이다.
 *
 * <h2>이 파일이 재는 것</h2>
 *
 * <p>세 층을 한 번에 지나간다 — 세션이 되풀이를 알아보는가 · 그 값이 게이트 입력으로 가는가 ·
 * 룰이 무는가. {@code GateEngineTest} 로 룰만 재면 <b>세션이 0 을 넘겨도 초록</b>이고,
 * 세션 단위 테스트만 두면 <b>게이트가 그 값을 안 읽어도 초록</b>이다.
 *
 * <p>반대 방향도 같이 잰다 — <b>표현을 다듬은 답</b>은 안 걸려야 한다. 이 신호가 정상적인
 * 이해 향상을 의심하면 재설명 기능 자체가 무의미해진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-GTE-001 되풀이 답변 게이트 (이슈 #268 (d))")
class RepeatedAnswerGateTest {

    private static final String ITEM = "ELS-PRINCIPAL-LOSS-WARNING";
    private static final String FIRST = "낙인 하회하면 원금 손실 난다고 들었어요";

    @Autowired private MockMvc mvc;
    @MockBean private AiServiceClient aiServiceClient;

    /** 이번 채점이 낼 등급. 회차마다 바꾼다. */
    private Grade grade = Grade.U3;

    @BeforeEach
    void stub() {
        when(aiServiceClient.score(anyString(), anyString(), anyString(),
                any(RiskItem.class), anyString()))
                .thenAnswer(inv -> new AiServiceClient.Scored(
                        new Judgment(inv.getArgument(0), grade, new BigDecimal("0.95"),
                                new Judgment.Evidence("인용", "조항"), "사유", null),
                        inv.getArgument(2)));
        when(aiServiceClient.detectMismatch(anyString(), anyMap(), anyMap(),
                nullable(String.class)))
                .thenReturn(new SuitabilityMismatch(
                        SuitabilityStatus.NO_MISMATCH, "테스트", null, List.of()));
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString(), anyString(),
                nullable(AiServiceClient.InterviewContext.class)))
                .thenReturn(new AiServiceClient.Question("질문?", "situation", false));
        when(aiServiceClient.reExplain(any(RiskItem.class), any(Judgment.class),
                nullable(String.class), nullable(String.class)))
                .thenReturn(new AiServiceClient.ReExplanation("다시 설명", List.of()));
    }

    @Test
    @DisplayName("❗같은 답을 되풀이했는데 U1 이 되면 통과시키지 않는다 — 이 세션이 GREEN 이었다")
    void aRepeatedAnswerDoesNotPassTheGate() throws Exception {
        String sid = session();
        grade = Grade.U3;
        answer(sid, FIRST);
        reExplain(sid);
        grade = Grade.U1;
        answer(sid, FIRST + ".");          // 마침표만 붙였다 — 같은 말이다

        mvc.perform(post("/sessions/{sid}/judge", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signal").value("YELLOW"))
                .andExpect(jsonPath("$.data.ruleTrace[*].id", hasItem("R-07")));
    }

    @Test
    @DisplayName("❗표현을 다듬은 답은 안 걸린다 — 여기까지 의심하면 재설명이 무의미해진다")
    void aRephrasedAnswerPassesNormally() throws Exception {
        String sid = session();
        grade = Grade.U3;
        answer(sid, FIRST);
        reExplain(sid);
        grade = Grade.U1;
        answer(sid, "기초자산이 처음의 절반 아래로 내려가면 제가 넣은 돈이 줄어든다는 뜻이네요");

        mvc.perform(post("/sessions/{sid}/judge", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signal").value("GREEN"))
                .andExpect(jsonPath("$.data.ruleTrace[*].id", not(hasItem("R-07"))));
    }

    @Test
    @DisplayName("같은 답인데 등급이 그대로면 여기서 안 센다 — 그건 이미 다른 룰이 받는다")
    void anUnchangedGradeIsNotThisSignal() throws Exception {
        String sid = session();
        grade = Grade.U3;
        answer(sid, FIRST);
        reExplain(sid);
        answer(sid, FIRST);

        mvc.perform(post("/sessions/{sid}/judge", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ruleTrace[*].id", not(hasItem("R-07"))));
    }

    // ── 거들기 ────────────────────────────────────────────────────────────────
    private String session() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE",
                                 "ageBand":"30대"}"""))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(created, "$.data.sessionId");
    }

    private void answer(String sid, String text) throws Exception {
        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\",\"text\":\"" + text + "\"}"))
                .andExpect(status().isOk());
    }

    private void reExplain(String sid) throws Exception {
        mvc.perform(post("/sessions/{sid}/re-explain", sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\"}"))
                .andExpect(status().isOk());
    }
}
