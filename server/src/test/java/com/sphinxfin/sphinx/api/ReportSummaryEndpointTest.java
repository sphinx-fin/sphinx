package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-GTE-004 고객 교부용 요약 엔드포인트 (이슈 #413). 소유: 강희진
 *
 * <p>계약·권한·서비스는 다 있었는데 서버 매핑만 0건이던 유일한 경로였다. 이 파일이 잠그는 것:
 * 전문과 <b>같은 contentHash</b>(고객 대조용) · 요약에 전문 필드(downloadUrl)가 안 실림 ·
 * 미발행 404 가 전문과 같은 코드.
 *
 * <p>enforce=false(테스트 기본)라 권한은 통과한다. enforce=true 에서 CUST 도달 가능 여부는
 * #166(세션에 고객 식별자 없음)에 달려 있고 이 엔드포인트 밖의 문제다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-GTE-004 리포트 요약 엔드포인트 (#413)")
class ReportSummaryEndpointTest {

    @Autowired private MockMvc mvc;

    private String createSession() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.data.sessionId");
    }

    @Test
    @DisplayName("❗요약은 전문과 같은 contentHash 를 싣는다 — 고객이 받은 문서를 나중에 대조한다")
    void summaryCarriesSameHashAsFull() throws Exception {
        String sid = createSession();
        String full = mvc.perform(post("/sessions/" + sid + "/report"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fullHash = JsonPath.read(full, "$.data.contentHash");

        mvc.perform(get("/sessions/" + sid + "/report/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentHash").value(fullHash))
                .andExpect(jsonPath("$.data.reportId").isNotEmpty())
                .andExpect(jsonPath("$.data.sessionId").value(sid))
                .andExpect(jsonPath("$.data.previewUrl").isNotEmpty());
    }

    @Test
    @DisplayName("요약은 전문 전용 필드(downloadUrl)를 담지 않는다 — 다른 문서다")
    void summaryOmitsFullOnlyFields() throws Exception {
        String sid = createSession();
        mvc.perform(post("/sessions/" + sid + "/report")).andExpect(status().isOk());

        mvc.perform(get("/sessions/" + sid + "/report/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downloadUrl").doesNotExist());
    }

    @Test
    @DisplayName("발행 전 요약 GET 은 404 — 전문과 같은 코드(범위 밖 세션 존재 여부 비노출)")
    void unissuedSummaryIsNotFound() throws Exception {
        String sid = createSession();

        mvc.perform(get("/sessions/" + sid + "/report/summary"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/sessions/S-없는세션/report/summary"))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
