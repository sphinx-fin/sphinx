package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-INT-001 세션 API 통합. 공통 응답 봉투와 전역 예외 처리를 실제 요청으로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-INT-001 SessionController (통합)")
class SessionControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("생성 성공 → 200 + 봉투(success:true, data.state=CREATED)")
    void createSuccess() throws Exception {
        mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"ELS-001","channel":"FACE_TO_FACE","ageBand":"60대","contractRef":"CT-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.data.state").value("CREATED"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("없는 세션 조회 → 404 + error.code=NOT_FOUND")
    void getMissing() throws Exception {
        mvc.perform(get("/sessions/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("필수값 누락 → 400 + error.code=VALIDATION_ERROR")
    void missingRequired() throws Exception {
        mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"FACE_TO_FACE"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("허용되지 않은 채널 값 → 400 + error.code=MALFORMED_REQUEST")
    void invalidChannel() throws Exception {
        mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"ELS-001","channel":"대면","ageBand":"60대"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("데모 흐름: 세션 생성 → U4 답변 기록 → /judge가 RED(R-01)")
    void answerThenJudge_isRed() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"ELS-001","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        String sid = JsonPath.read(created, "$.data.sessionId");

        // 답변 제출 — 목 채점이 U4(오해)로 기록, 세션은 IN_PROGRESS로
        mvc.perform(post("/sessions/" + sid + "/answers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemId":"ELS-PRINCIPAL-LOSS-WARNING","text":"은행에서 파니까 원금은 지켜지죠"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grade").value("U4"));
        mvc.perform(get("/sessions/" + sid))
                .andExpect(jsonPath("$.data.state").value("IN_PROGRESS"));

        // 게이트 판정 — U4 있으니 RED, 세션은 JUDGED로
        mvc.perform(post("/sessions/" + sid + "/judge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signal").value("RED"))
                .andExpect(jsonPath("$.data.ruleTrace[0]").value("R-01"));
        mvc.perform(get("/sessions/" + sid))
                .andExpect(jsonPath("$.data.state").value("JUDGED"));
    }

    // ── F-INT-004 재설명 에러 코드 (PR #28 리뷰 ②③) ────────────────────
    // 프론트가 error.code를 유니온 타입으로 분기하므로 와이어 레벨에서 고정한다.
    // 메시지 문면이 바뀌어도 이 테스트는 깨지지 않아야 한다 — 그게 코드를 가른 이유다.

    /** 세션 하나 만들고 목 채점(U4)까지 태워 재설명 가능한 상태로 만든다. */
    private String sessionWithMisunderstoodItem() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"ELS-001","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        String sid = JsonPath.read(created, "$.data.sessionId");
        mvc.perform(post("/sessions/" + sid + "/answers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemId":"ELS-PRINCIPAL-LOSS-WARNING","text":"은행에서 파니까 원금은 지켜지죠"}"""))
                .andExpect(status().isOk());
        return sid;
    }

    @Test
    @DisplayName("재설명 성공 → 200 + 재검증용 변형 질문 동봉")
    void reExplainCarriesReverifyQuestion() throws Exception {
        String sid = sessionWithMisunderstoodItem();

        mvc.perform(post("/sessions/" + sid + "/re-explain").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemId":"ELS-PRINCIPAL-LOSS-WARNING"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isNotEmpty())
                .andExpect(jsonPath("$.data.reverifyQuestion").isNotEmpty())
                .andExpect(jsonPath("$.data.vulnerable").isBoolean());
    }

    @Test
    @DisplayName("판정 없는 항목 재설명 → 400 + error.code=REEXPLAIN_NOT_ELIGIBLE")
    void reExplainNotEligible() throws Exception {
        String sid = sessionWithMisunderstoodItem();

        mvc.perform(post("/sessions/" + sid + "/re-explain").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemId":"ELS-NO-DEPOSIT-INSURANCE"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("REEXPLAIN_NOT_ELIGIBLE"));
    }

    @Test
    @DisplayName("재검증 상한 도달 후 재설명 → 400 + error.code=REVERIFY_EXHAUSTED")
    void reverifyExhausted() throws Exception {
        String sid = sessionWithMisunderstoodItem();
        String item = "ELS-PRINCIPAL-LOSS-WARNING";
        String reExplainBody = "{\"itemId\":\"" + item + "\"}";
        String answerBody = "{\"itemId\":\"" + item + "\",\"text\":\"여전히 잘 모르겠어요\"}";

        // 목 채점이 항상 U4라 재검증은 2회 모두 실패한다 → 상한 도달
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/sessions/" + sid + "/re-explain")
                    .contentType(MediaType.APPLICATION_JSON).content(reExplainBody))
                    .andExpect(status().isOk());
            mvc.perform(post("/sessions/" + sid + "/answers")
                    .contentType(MediaType.APPLICATION_JSON).content(answerBody))
                    .andExpect(status().isOk());
        }

        mvc.perform(post("/sessions/" + sid + "/re-explain")
                .contentType(MediaType.APPLICATION_JSON).content(reExplainBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REVERIFY_EXHAUSTED"));
    }
}
