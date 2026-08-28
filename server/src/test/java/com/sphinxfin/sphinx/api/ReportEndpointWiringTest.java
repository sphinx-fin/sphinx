package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import com.sphinxfin.sphinx.evidence.ReportService;
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
 * F-GTE-004 리포트 엔드포인트가 {@link ReportService} 를 실제로 부르는가. 소유: 정세현
 *
 * <p>이 파일이 있는 이유는 <b>목이 계약보다 관대했다</b>는 것이다. 배선 전 컨트롤러는
 * 발행 여부를 모르므로 GET 이 <b>늘</b> 리포트를 돌려줬고, {@code contentHash} 는 0 을
 * 64개 채운 값이었다. 스키마 검증은 통과하고 화면도 그려지는데, 계약이 GET 404 로 표현한
 * <i>"아직 교부하지 않았다"</i> 상태가 존재하지 않았다.
 *
 * <p>그 상태를 처음 만나는 시점이 배선이 붙는 날이면 곤란하다 — S-07(PR #150)이 그 경로를
 * 목에 맞추지 않고 계약대로 지은 이유가 이것이고, 여기가 그 반대편이다.
 *
 * <p><b>목이 통과시키던 것들을 하나씩 막는다</b>: 미발행 404 · 진짜 해시 · 멱등 재발행에서
 * 시각이 안 흔들리는 것 · URL 둘이 null 인 것.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-GTE-004 리포트 배선 ↔ 계약")
class ReportEndpointWiringTest {

    @Autowired private MockMvc mvc;
    @Autowired private ReportService reportService;

    private String createSession() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.data.sessionId");
    }

    @Test
    @DisplayName("❗발행 전 GET 은 404 다 — 목은 늘 200 이었고 그게 S-07 이 못 만나던 상태다")
    void unissuedSessionIsNotFound() throws Exception {
        String sid = createSession();

        mvc.perform(get("/sessions/" + sid + "/report"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("❗404 코드가 '세션 없음'과 같다 — 범위 밖 세션의 존재 여부를 알려주지 않는다")
    void missingSessionAndUnissuedReportShareTheCode() throws Exception {
        String sid = createSession();

        // 코드가 갈리면 그 코드가 나온다는 사실만으로 "그 세션은 있다" 가 새어 나간다.
        // 다음 행동을 가르는 것은 화면 몫이고(S-07 이 세션을 한 번 더 조회한다), 사유는
        // message 로만 구별된다.
        mvc.perform(get("/sessions/" + sid + "/report"))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/sessions/S-없는세션/report"))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("❗contentHash 가 기록에서 나온 진짜 값이다 — 목의 0 64개가 아니다")
    void contentHashComesFromTheRecord() throws Exception {
        String sid = createSession();

        String body = mvc.perform(post("/sessions/" + sid + "/report"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String hash = JsonPath.read(body, "$.data.contentHash");

        assertThat(hash)
                .as("목은 '0' 을 64개 채웠다. 스키마 검증은 통과하고 화면은 그린다 — "
                        + "고객이 대조할 때야 드러난다")
                .isNotEqualTo("0".repeat(64))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
        assertThat(hash)
                .as("문서만 가진 사람이 재계산할 수 있어야 한다 — 기록을 다시 조립한 해시와 같다")
                .isEqualTo(reportService.contentHash(reportService.render(sid)));
    }

    @Test
    @DisplayName("❗멱등 재발행 — 내용이 같으면 reportId·해시·발행시각이 전부 그대로다")
    void reissuingUnchangedContentReturnsTheSameReport() throws Exception {
        String sid = createSession();

        String first = mvc.perform(post("/sessions/" + sid + "/report"))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/sessions/" + sid + "/report"))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.data.reportId"))
                .isEqualTo(JsonPath.read(first, "$.data.reportId"));
        assertThat((String) JsonPath.read(second, "$.data.contentHash"))
                .isEqualTo(JsonPath.read(first, "$.data.contentHash"));
        assertThat((String) JsonPath.read(second, "$.data.generatedAt"))
                .as("발행 시각은 발행 **기록**의 시각이지 지금 시각이 아니다 — 여기서 now() 를 "
                        + "쓰면 같은 문서가 부를 때마다 다른 교부 시각을 갖는다")
                .isEqualTo(JsonPath.read(first, "$.data.generatedAt"));
    }

    @Test
    @DisplayName("❗GET 이 돌려주는 것이 POST 가 낸 것과 같다 — 조회가 새로 만들지 않는다")
    void readReturnsWhatWasIssued() throws Exception {
        String sid = createSession();

        String issued = mvc.perform(post("/sessions/" + sid + "/report"))
                .andReturn().getResponse().getContentAsString();
        String read = mvc.perform(get("/sessions/" + sid + "/report"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(read, "$.data.reportId"))
                .isEqualTo(JsonPath.read(issued, "$.data.reportId"));
        assertThat((String) JsonPath.read(read, "$.data.generatedAt"))
                .isEqualTo(JsonPath.read(issued, "$.data.generatedAt"));
    }

    @Test
    @DisplayName("previewUrl·downloadUrl 은 키가 있고 값이 null 이다 — 생략과 다르다")
    void pdfUrlsAreNullNotAbsent() throws Exception {
        String sid = createSession();

        // 계약이 nullable 로 둔 이유는 값을 채우면 "이 URL 로 가면 문서가 있다" 를 계약이
        // 보장하는데 404 가 나기 때문이다. 반대로 키를 빼면 화면이 "없음" 과 "필드가 생기기
        // 전 응답" 을 구별할 수 없다. 그래서 **키는 있고 값이 null** 이어야 한다.
        mvc.perform(post("/sessions/" + sid + "/report"))
                .andExpect(jsonPath("$.data.previewUrl").doesNotExist())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.hasKey("previewUrl")))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.hasKey("downloadUrl")));
    }

    @Test
    @DisplayName("없는 세션에 발행하면 404 다 — 세션 없이 리포트가 생기지 않는다")
    void issuingForMissingSessionIsNotFound() throws Exception {
        mvc.perform(post("/sessions/S-없는세션/report"))
                .andExpect(status().isNotFound());
    }
}
