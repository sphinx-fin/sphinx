package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 모든 엔드포인트가 공통 응답 봉투를 씌우는지 확인한다. 소유: 강희진
 *
 * 봉투가 반만 씌워져 있으면 프론트의 unwrap 계층이 "봉투의 모양을 보고" 벗기는 추측을 하게
 * 되고, raw 응답이 우연히 success 불리언을 갖는 순간 조용히 깨진다. 그 추측을 없애려면
 * 예외 없이 전부 씌워야 하므로, 예외가 생기면 여기서 실패하게 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("응답 봉투 일괄 적용")
class EnvelopeContractTest {

    @Autowired
    private MockMvc mvc;

    /** 봉투 3요소: success=true · data 존재 · error 없음(null). */
    private void assertEnveloped(ResultActions r) throws Exception {
        r.andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    private String newSession() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.data.sessionId");
    }

    @Test
    @DisplayName("products 계열 전부 봉투")
    void productEndpoints() throws Exception {
        assertEnveloped(mvc.perform(get("/products")));
        assertEnveloped(mvc.perform(get("/products/mock-els-001/risk-items")));
        assertEnveloped(mvc.perform(post("/products/mock-els-001/extract")));
        assertEnveloped(mvc.perform(multipart("/products/documents")
                .file(new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes()))));
    }

    @Test
    @DisplayName("session 계열 전부 봉투 — simulate·report 포함")
    void sessionEndpoints() throws Exception {
        String sid = newSession();
        assertEnveloped(mvc.perform(get("/sessions/" + sid)));
        assertEnveloped(mvc.perform(get("/sessions/" + sid + "/judgments")));
        assertEnveloped(mvc.perform(post("/sessions/" + sid + "/questions/next")));
        assertEnveloped(mvc.perform(post("/sessions/" + sid + "/simulate")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":50000000}")));
        assertEnveloped(mvc.perform(get("/sessions/" + sid + "/report")));
    }

    @Test
    @DisplayName("override·dashboard 봉투")
    void overrideAndDashboard() throws Exception {
        String sid = newSession();
        String reason = "적색이지만 고객이 충분히 이해했다고 판단하여 진행을 요청합니다. 근거는 재설명 후 재검증 통과입니다.";
        assertEnveloped(mvc.perform(post("/sessions/" + sid + "/override")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}")));
        assertEnveloped(mvc.perform(post("/sessions/" + sid + "/override/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}")));
        assertEnveloped(mvc.perform(get("/dashboard/heatmap")));
    }

    @Test
    @DisplayName("오버라이드 사유 30자 미만 → 400 VALIDATION_ERROR (봉투 실패형)")
    void overrideShortReasonRejected() throws Exception {
        String sid = newSession();
        mvc.perform(post("/sessions/" + sid + "/override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"그냥요"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
