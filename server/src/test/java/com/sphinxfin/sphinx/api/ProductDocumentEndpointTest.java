package com.sphinxfin.sphinx.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품 원문 문서 조회 엔드포인트 (F-EXT · 이슈 #412). 소유: 강희진
 *
 * <p>S-02 모달이 대조할 원본으로 가는 경로다(P6). 재는 것 셋 — 원본이 인라인 PDF 로
 * 나온다 · 없는 상품은 404 · 봉투를 깨는 것은 성공 본문뿐이고 오류는 봉투 그대로다.
 *
 * <p>데모 문서(사전적재)는 레포 {@code data/documents/} 에 있고 기본 경로가 거기를 가리킨다
 * (작업 디렉토리 {@code server/} 기준 {@code ../data}). 실추출 배선이 아니라 파일을 그대로
 * 내리는 경로라 ai-service 없이도 돈다 — 이슈 #412 의 "사전적재로 화면이 선다" 그대로다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("상품 원문 문서 조회 (이슈 #412)")
class ProductDocumentEndpointTest {

    @Autowired private MockMvc mvc;

    @Test
    @DisplayName("❗원본이 인라인 PDF 로 나온다 — 화면이 #page=N 로 열 수 있다")
    void servesTheOriginalPdfInline() throws Exception {
        byte[] body = mvc.perform(get("/products/{id}/document", "doc-els-kiwoom-4181"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                // 인라인이어야 브라우저가 뷰어로 열고 #page 앵커가 동작한다.
                .andExpect(header().string("Content-Disposition", startsWith("inline")))
                // 파일명이 실려야 받은 사람이 무슨 문서인지 안다.
                .andExpect(header().string("Content-Disposition",
                        containsString("els_kiwoom_4181_simple_prospectus.pdf")))
                .andReturn().getResponse().getContentAsByteArray();

        // 실제 PDF 바이트다 — 키 존재만 재면 빈 응답도 초록이다(ReportEndpointWiringTest 와 같은 결).
        assertThat(new String(body, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("변액 상품 문서도 같은 경로로 나온다")
    void servesTheVariableInsuranceDocument() throws Exception {
        mvc.perform(get("/products/{id}/document", "doc-var-samsung-b2601"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        containsString("var_samsung_b2601_product_summary.pdf")));
    }

    @Test
    @DisplayName("❗없는 상품은 404 — 오류는 봉투 그대로다")
    void unknownProductIsNotFoundInEnvelope() throws Exception {
        mvc.perform(get("/products/{id}/document", "doc-does-not-exist"))
                .andExpect(status().isNotFound())
                // 성공만 봉투를 깬다 — 오류는 공통 봉투(success=false, error.code=NOT_FOUND)로 온다.
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
