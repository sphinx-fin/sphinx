package com.sphinxfin.sphinx.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품 카탈로그 접근통제 배선 (이슈 #69 · 결정 10.36). 소유: 강희진
 *
 * <h2>정책 테스트로는 부족하다</h2>
 *
 * <p>{@code AccessPolicyTest} 가 {@code product:read}·{@code product:manage} 의 그랜트를
 * 잰다. 그건 <i>"정책이 무엇을 허용하는가"</i> 이고, 이 파일은 <b>그 정책이 이 컨트롤러에
 * 실제로 걸려 있는가</b>를 잰다 — 어노테이션을 지워도 정책 테스트는 전부 초록이다.
 *
 * <p>{@code enforce=true} 로 돌린다. 기본값에서는 {@code AccessGuard} 가 정책을 아예 안
 * 부르므로 이 파일이 재려는 것을 못 잰다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sphinx.security.enforce=true")
@DisplayName("상품 카탈로그 접근통제 (이슈 #69)")
class ProductAccessWiringTest {

    @Autowired private MockMvc mvc;

    private static RequestPostProcessor as(String id, String role) {
        return user(id).roles(role);
    }

    @Test
    @DisplayName("❗판매자는 상품을 읽는다 — 면담이 여기서 항목을 받는다")
    void theSellerCanReadTheCatalog() throws Exception {
        mvc.perform(get("/products").with(as("seller-01", "SELLER")))
                .andExpect(status().isOk());
        mvc.perform(get("/products/{id}/risk-items", "doc-els-kiwoom-4181").with(as("seller-01", "SELLER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("❗판매자는 문서를 올리지 못한다 — 게이트가 물을 항목을 판매 라인이 만들면 안 된다")
    void theSellerCannotRegisterProducts() throws Exception {
        // 허용만 재는 단정으로는 과허용 변이가 안 잡힌다. 무서운 쪽은 덜 허용하는 변이가
        // 아니라 **더 허용하는 변이**다 — 어노테이션이 없으면 이 줄이 200 이 된다.
        mvc.perform(multipart("/products/documents")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8)))
                        .with(as("seller-01", "SELLER")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/products/{id}/extract", "doc-els-kiwoom-4181").with(as("seller-01", "SELLER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("❗지점장·준법감시도 등록은 못 한다 — 읽기 권한과 다른 자리다")
    void supervisorsReadButDoNotRegister() throws Exception {
        mvc.perform(get("/products").with(as("mgr-01", "MGR"))).andExpect(status().isOk());
        mvc.perform(get("/products").with(as("compl-01", "COMPL"))).andExpect(status().isOk());

        mvc.perform(post("/products/{id}/extract", "doc-els-kiwoom-4181").with(as("mgr-01", "MGR")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/products/{id}/extract", "doc-els-kiwoom-4181").with(as("compl-01", "COMPL")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리자는 등록·추출을 한다 — 막기만 하는 구현도 위 단정들을 통과한다")
    void theAdminCanRegister() throws Exception {
        mvc.perform(post("/products/{id}/extract", "doc-els-kiwoom-4181").with(as("admin-01", "ADMIN")))
                .andExpect(status().isOk());
    }
}
