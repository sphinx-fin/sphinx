package com.sphinxfin.sphinx.api;

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
}
