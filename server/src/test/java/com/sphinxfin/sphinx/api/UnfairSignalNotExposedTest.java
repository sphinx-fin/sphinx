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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 불공정영업 신호가 <b>판매자가 받는 HTTP 응답</b>에 안 실린다 (기획 7-4 · 이슈 #63). 소유: 강희진
 *
 * <h2>왜 서비스 층 단정으로는 부족한가</h2>
 *
 * <p>{@code SessionServiceTest} 가 이미 <i>"{@code Judgment} 레코드에 신호 관련 컴포넌트가
 * 없다"</i> 를 잰다. 그건 맞는 단정인데 <b>판매자가 보는 것은 그 레코드가 아니라 JSON</b> 이다.
 * 컨트롤러가 응답 DTO 를 하나 감싸거나 필드를 얹으면 그 단정은 <b>초록인 채로</b> 신호가 나간다.
 *
 * <p>역이용 방지는 범위 축소 대상이 아니라고 CLAUDE.md 가 못박고 있다. 그러면 <b>실제로 나가는
 * 바이트</b>를 재는 단정이 하나 있어야 한다 — 층이 하나 늘 때마다 새는 자리가 하나 는다.
 *
 * <h2>키 이름이 아니라 응답 전체를 본다</h2>
 *
 * <p>{@code jsonPath("$.data.unfair").doesNotExist()} 류는 <b>내가 지금 상상한 이름</b>만 막는다.
 * 누출은 상상 못 한 이름으로 난다. 그래서 직렬화된 본문 전체에서 신호를 가리키는 어휘를 찾는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-GTE-003 불공정영업 신호 비노출 (판매자 응답)")
class UnfairSignalNotExposedTest {

    private static final String ITEM = "ELS-PRINCIPAL-LOSS-WARNING";

    /** 서버가 실제로 받아 기록으로 넘긴 유형. 화면에서 가린 것이 수신까지 끊었는지 본다. */
    static final List<String> RECORDED_TYPES = new java.util.ArrayList<>();

    @org.springframework.boot.test.context.TestConfiguration
    static class RecordingCfg {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.sphinxfin.sphinx.core.EvidenceRecorder recordingEvidence() {
            return new com.sphinxfin.sphinx.core.EvidenceRecorder() {
                @Override public void appendJudgment(String sid, Judgment j, int r,
                        String askedQuestion,
                        com.sphinxfin.sphinx.core.EvidenceRecorder.QuestionSource src,
                        java.time.Instant at) {
                    RECORDED_TYPES.add(j.misconceptionType());
                }
                @Override public void appendGate(String sid,
                        com.sphinxfin.sphinx.domain.GateResult res, java.time.Instant at) { }
                @Override public void appendOverride(String sid, String reason,
                        String approver, java.time.Instant at) { }
            };
        }
    }

    /** 신호를 가리킬 법한 어휘. 하나라도 응답에 있으면 판매자가 우회 단서를 얻는다. */
    private static final List<String> LEAK_WORDS =
            List.of("unfair", "escalate", "compliance", "compl", "tying", "m08", "signal", "꺾기", "불공정");

    @Autowired private MockMvc mvc;
    @MockBean private AiServiceClient aiServiceClient;

    @BeforeEach
    void stub() {
        RECORDED_TYPES.clear();
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString()))
                .thenAnswer(inv -> new AiServiceClient.Question("질문", "OPEN_ENDED"));
        // ❗꺾기(M08-TYING)로 채점된다 — 이 요청이 COMPL 이벤트를 실제로 발행하는 경로다.
        when(aiServiceClient.score(anyString(), anyString(), anyString(), any(RiskItem.class), anyString()))
                .thenAnswer(inv -> new AiServiceClient.Scored(
                        new Judgment(inv.getArgument(0), Grade.U4, new BigDecimal("0.9"),
                                new Judgment.Evidence("대출받으려면 이것도 들어야 한다고 해서요",
                                        "끼워팔기 인지 실패"),
                                "판매자 발화 인용", "M08-TYING", null),
                        inv.getArgument(2)));
        when(aiServiceClient.detectMismatch(anyString(), anyMap(), anyMap(), nullable(String.class)))
                .thenReturn(SuitabilityStatus.NO_MISMATCH);
    }

    @Test
    @DisplayName("❗꺾기로 채점돼도 판매자 응답에는 신호 어휘가 없다")
    void tyingJudgmentLeavesNoTraceInTheSellerResponse() throws Exception {
        String sid = createSession();

        String body = mvc.perform(post("/sessions/{sid}/answers", sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\","
                                + "\"text\":\"대출받으려면 이것도 들어야 한다고 해서요\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .as("응답이 비었으면 이 단정이 공짜로 통과한다 — 무엇을 검사했는지 먼저 고정한다")
                .contains("\"grade\":\"U4\"");

        String lower = body.toLowerCase();
        assertThat(LEAK_WORDS)
                .as("판매자가 어느 답변이 신호를 냈는지 알면 문면만 바꿔 우회한다 — "
                        + "탐지가 도는데 아무도 안 걸리는 상태가 되고, 그건 탐지가 없는 것보다 "
                        + "나쁘다(있다고 믿게 되므로). 실제 응답 본문: " + body)
                .noneMatch(lower::contains);
    }

    @Test
    @DisplayName("검사 어휘가 실제로 걸러진다 — 통과가 '아무것도 안 본 것' 이 아님을 고정한다")
    void theLeakWordsWouldActuallyMatch() {
        String pretend = "{\"data\":{\"grade\":\"U4\",\"misconceptionType\":\"M08-TYING\"}}";

        assertThat(LEAK_WORDS)
                .as("응답에 신호가 실제로 실렸다면 위 단정이 잡아야 한다 — 어휘 목록이 비거나 "
                        + "대소문자가 어긋나면 위 테스트는 무엇을 하든 초록이다")
                .anyMatch(pretend.toLowerCase()::contains);
    }

    @Test
    @DisplayName("❗목록 경로로도 안 샌다 — 단건만 막으면 GET 으로 그대로 나간다")
    void judgmentListDoesNotLeakEither() throws Exception {
        String sid = createSession();
        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\",\"text\":\"대출 때문에요\"}"))
                .andExpect(status().isOk());

        String body = mvc.perform(get("/sessions/{sid}/judgments", sid))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).as("목록이 비면 이 단정이 공짜다").contains("\"grade\":\"U4\"");
        String lower = body.toLowerCase();
        assertThat(LEAK_WORDS)
                .as("S-05 가 목록으로도 판정을 받는다 — 단건 응답만 막으면 같은 값이 "
                        + "다른 엔드포인트로 그대로 나간다. 실제 응답 본문: " + body)
                .noneMatch(lower::contains);
    }

    @Test
    @DisplayName("❗ai-service 가 준 유형은 서버 안에 그대로 살아 있다 — 안 실은 것이지 버린 게 아니다")
    void theValueStillReachesTheServer() throws Exception {
        String sid = createSession();
        mvc.perform(post("/sessions/{sid}/answers", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\",\"text\":\"대출 때문에요\"}"))
                .andExpect(status().isOk());

        // ❗이게 위험한 방향이다. 화면에서 가리려다 수신·보관까지 끊으면 COMPL 사건이
        //   영영 안 나가고 아무도 모른다 — 탐지가 있다고 믿는 상태가 되므로 없느니만 못하다.
        assertThat(RECORDED_TYPES)
                .as("판매자에게 안 보내는 것과 서버가 모르는 것은 다르다. 불변 기록과 "
                        + "COMPL 발행이 이 값을 쓴다(F-GTE-003 · #144)")
                .containsExactly("M08-TYING");
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
