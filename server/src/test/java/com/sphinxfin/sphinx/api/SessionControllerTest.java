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

    @Test
    @DisplayName("매핑 안 된 경로 → 404 NOT_FOUND — 포괄 핸들러가 500으로 삼키지 않는다")
    void unmappedPathIs404() throws Exception {
        // 오타 난 URL 이 500 INTERNAL_ERROR 로 나가면 프론트는 "서버가 죽었다"로 읽고
        // 모니터링은 장애로 집계한다.
        mvc.perform(get("/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
    // ── S-05 계약 (#39) ────────────────────────────────────────────────

    @Test
    @DisplayName("판정 목록 조회 — 다른 기기/탭에서도 항목별 판정을 받을 수 있다")
    void judgmentsAreRetrievable() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"ELS-001","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        String sid = JsonPath.read(created, "$.data.sessionId");

        // 판정 전에는 빈 목록(404 아님) — 화면이 "아직 없음"과 "세션 없음"을 구분해야 한다
        mvc.perform(get("/sessions/" + sid + "/judgments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.judgments").isArray())
                .andExpect(jsonPath("$.data.judgments.length()").value(0))
                .andExpect(jsonPath("$.data.state").value("CREATED"));

        mvc.perform(post("/sessions/" + sid + "/answers").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"itemId":"ELS-PRINCIPAL-LOSS-WARNING","text":"원금은 지켜지죠"}"""));

        mvc.perform(get("/sessions/" + sid + "/judgments"))
                .andExpect(jsonPath("$.data.judgments.length()").value(1))
                .andExpect(jsonPath("$.data.judgments[0].itemId").value("ELS-PRINCIPAL-LOSS-WARNING"))
                .andExpect(jsonPath("$.data.judgments[0].grade").value("U4"))
                // P4 — 근거가 함께 나와야 화면이 "신호등 + 근거"를 그린다(명세 8절 S-05)
                .andExpect(jsonPath("$.data.judgments[0].evidence.utteranceQuote").isNotEmpty())
                // 항목별 signal 은 싣지 않는다 — 게이트 판정은 /judge 가 단독 소유(P1)
                .andExpect(jsonPath("$.data.judgments[0].signal").doesNotExist());
    }

    @Test
    @DisplayName("없는 세션의 판정 목록 → 404 (빈 목록과 구분된다)")
    void judgmentsOfMissingSession() throws Exception {
        mvc.perform(get("/sessions/does-not-exist/judgments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("진행 상태 — index/total 을 서버가 주고, 다 물으면 done=true")
    void nextQuestionCarriesProgress() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"ELS-001","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        String sid = JsonPath.read(created, "$.data.sessionId");

        String first = mvc.perform(post("/sessions/" + sid + "/questions/next"))
                .andExpect(jsonPath("$.data.index").value(1))
                .andExpect(jsonPath("$.data.done").value(false))
                .andExpect(jsonPath("$.data.itemId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        int total = JsonPath.read(first, "$.data.total");
        String item1 = JsonPath.read(first, "$.data.itemId");

        // 전 항목에 답하면 done=true — 화면이 인터뷰 종료를 알 유일한 근거
        mvc.perform(post("/sessions/" + sid + "/answers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\":\"" + item1 + "\",\"text\":\"모르겠어요\"}"));
        String second = mvc.perform(post("/sessions/" + sid + "/questions/next"))
                .andExpect(jsonPath("$.data.index").value(2))
                .andReturn().getResponse().getContentAsString();
        String item2 = JsonPath.read(second, "$.data.itemId");
        mvc.perform(post("/sessions/" + sid + "/answers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\":\"" + item2 + "\",\"text\":\"모르겠어요\"}"));

        mvc.perform(post("/sessions/" + sid + "/questions/next"))
                .andExpect(jsonPath("$.data.done").value(true))
                .andExpect(jsonPath("$.data.index").value(total))
                .andExpect(jsonPath("$.data.itemId").doesNotExist());
    }

    @Test
    @DisplayName("상품 목록 — id 를 모르는 상태에서 고를 수 있어야 한다 (S-02)")
    void productListIsSelectable() throws Exception {
        mvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].productId").isNotEmpty())
                .andExpect(jsonPath("$.data[0].productType").isNotEmpty());
    }
}
