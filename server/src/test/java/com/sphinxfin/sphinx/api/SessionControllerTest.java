package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import com.sphinxfin.sphinx.domain.SuitabilityMismatch;
import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.core.aiservice.AiServiceException;
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

import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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

    /**
     * ai-service(F-SCR-001)는 이 통합 테스트의 대상이 아니라 상류 의존성이다 — 실제
     * HTTP(:8100)에 붙이지 않고 목으로 대신한다. 채점 결과는 예전 컨트롤러 목과 동일하게
     * U4(오해)로 고정해 이 파일의 기존 단정(U4·RED·R-01·재검증 상한)을 그대로 유지한다.
     * AiServiceClient 자체의 HTTP 계약(snake_case·PII 마스킹·실패 매핑)은 AiServiceClientTest가 검증한다.
     */
    @MockBean
    private AiServiceClient aiServiceClient;

    @BeforeEach
    void stubScoring() {
        // 어떤 항목이든 U4로 채점 — 넘어온 itemId를 그대로 판정에 싣는다.
        when(aiServiceClient.score(anyString(), anyString(), anyString(), any(RiskItem.class), eq("ELS"), nullable(com.sphinxfin.sphinx.domain.InputMeta.class)))
                .thenAnswer(inv -> new AiServiceClient.Scored(
                        new Judgment(inv.getArgument(0), Grade.U4, new BigDecimal("0.91"),
                                new Judgment.Evidence("은행에서 파는 거니까 원금은 지켜지는 거죠",
                                        "원금손실 조건: 낙인 하회 시 손실을 인지해야 함"),
                                "원금이 보장된다고 진술하여 오해로 판정", "M01-PRINCIPAL-GUARANTEE"),
                        inv.getArgument(2)));
        // 모순 판정 기본 스텁 — 판정 없음. 실패 경로는 별도 테스트에서 본다.
        when(aiServiceClient.detectMismatch(anyString(), anyMap(), anyMap(), nullable(String.class)))
                .thenReturn(new SuitabilityMismatch(SuitabilityStatus.NO_MISMATCH, "테스트", null, java.util.List.of()));
        // 질문 생성(F-INT-002) 기본 스텁 — nextQuestion 이 이제 ai-service 문면을 쓴다.
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString(), anyString(), nullable(com.sphinxfin.sphinx.core.aiservice.AiServiceClient.InterviewContext.class)))
                .thenReturn(new AiServiceClient.Question("이 조건이 어떤 뜻인지 설명해 주시겠어요?", "condition", false));
        // 재설명(F-INT-004) 기본 스텁 — re-explain 콘텐츠가 이제 ai-service 에서 온다.
        when(aiServiceClient.reExplain(any(RiskItem.class), any(Judgment.class),
                nullable(String.class), nullable(String.class)))
                .thenReturn(new AiServiceClient.ReExplanation("다시 쉽게 설명드릴게요.", List.of()));
    }

    @Test
    @DisplayName("❗ai-service 모순 판정이 죽어도 /judge 는 살아 있고, 결과가 GREEN 으로 새지 않는다")
    void mismatchFailureBecomesYellowNotGreen() throws Exception {
        // U1(이해)만 있는 세션이라 원래는 R-06 GREEN 이다. 모순 판정이 실패하면
        // "확인 못 함"이므로 통과가 아니라 재확인(R-02b YELLOW)이어야 한다.
        when(aiServiceClient.score(anyString(), anyString(), anyString(), any(RiskItem.class), eq("ELS"), nullable(com.sphinxfin.sphinx.domain.InputMeta.class)))
                .thenAnswer(inv -> new AiServiceClient.Scored(
                        new Judgment(inv.getArgument(0), Grade.U1, new BigDecimal("0.95"),
                                new Judgment.Evidence("낙인 하회하면 원금 손실 난다고 들었어요",
                                        "원금손실 조건: 낙인 하회 시 손실을 인지해야 함"),
                                "조건을 정확히 진술", null),
                        inv.getArgument(2)));
        when(aiServiceClient.detectMismatch(anyString(), anyMap(), anyMap(), nullable(String.class)))
                .thenThrow(new AiServiceException("ai-service 다운"));

        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        String sid = JsonPath.read(created, "$.data.sessionId");

        mvc.perform(post("/sessions/" + sid + "/answers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemId":"ELS-PRINCIPAL-LOSS-WARNING","text":"낙인 하회하면 원금 손실 난다고 들었어요"}"""))
                .andExpect(status().isOk());

        mvc.perform(post("/sessions/" + sid + "/judge"))
                .andExpect(status().isOk())          // 502 로 판매를 멈추지 않는다
                .andExpect(jsonPath("$.data.signal").value("YELLOW"))
                .andExpect(jsonPath("$.data.ruleTrace[*].id", hasItem("R-02b")));
    }

    @Test
    @DisplayName("모순 판정에는 마스킹된 발화가 항목별로 넘어간다 — 근거 스팬만 넘기면 모순이 안 보인다")
    void utterancesReachMismatchDetection() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        String sid = JsonPath.read(created, "$.data.sessionId");

        mvc.perform(post("/sessions/" + sid + "/answers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemId":"ELS-PRINCIPAL-LOSS-WARNING","text":"사실 원금 잃으면 큰일 나요"}"""))
                .andExpect(status().isOk());
        mvc.perform(post("/sessions/" + sid + "/judge")).andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(aiServiceClient).detectMismatch(eq(sid), anyMap(), captor.capture(),
                nullable(String.class));
        assertThat(captor.getValue())
                .as("발화가 안 넘어가면 판정기가 볼 게 없어 insufficient_input 이 난다")
                .containsEntry("ELS-PRINCIPAL-LOSS-WARNING", "사실 원금 잃으면 큰일 나요");
    }

    @Test
    @DisplayName("변액 세션은 VARIABLE_INSURANCE로 채점된다 — ELS로 하드코딩하면 M02가 오판한다")
    void productTypeComesFromSession() throws Exception {
        // product_type은 ai-service에서 오해 유형 필터의 입력이다(misconception.applies_to).
        // PR #57(결정 10.24)이 M02-DEPOSIT-INSURANCE를 products:[ELS]로 좁힌 이유가 변액에서의
        // 이해→오해 오판이었는데, 호출부가 변액 세션에도 "ELS"를 보내면 라이브러리에서 닫은
        // 구멍이 배선에서 다시 열린다. 에러도 로그도 없이 판정만 틀리는 종류다.
        when(aiServiceClient.score(anyString(), anyString(), anyString(), any(RiskItem.class),
                eq("VARIABLE_INSURANCE"), nullable(com.sphinxfin.sphinx.domain.InputMeta.class)))
                .thenAnswer(inv -> new AiServiceClient.Scored(
                        new Judgment(inv.getArgument(0), Grade.U1, new BigDecimal("0.9"),
                                new Judgment.Evidence("최저사망지급금까지만 보호된다고 들었어요",
                                        "예금자보호 범위: 보호되는 급부와 한도를 인지해야 함"),
                                "부분 보호 범위를 정확히 진술", null),
                        inv.getArgument(2)));

        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-var-samsung-b2601","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        String sid = JsonPath.read(created, "$.data.sessionId");

        mvc.perform(post("/sessions/" + sid + "/answers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemId":"ELS-PRINCIPAL-LOSS-WARNING","text":"최저사망지급금까지만 보호된다고 들었어요"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grade").value("U1"));

        // 하드코딩이면 "ELS" 스텁이 잡혀 U4가 나온다 — 넘어간 값을 직접 확인한다.
        verify(aiServiceClient).score(anyString(), anyString(), anyString(), any(RiskItem.class),
                eq("VARIABLE_INSURANCE"), nullable(com.sphinxfin.sphinx.domain.InputMeta.class));
    }

    @Test
    @DisplayName("상품 목록에 없는 productId → 404. 조용한 기본값을 두지 않는다")
    void unknownProductTypeFailsLoudly() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-unknown-9999","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        String sid = JsonPath.read(created, "$.data.sessionId");

        mvc.perform(post("/sessions/" + sid + "/answers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemId":"ELS-PRINCIPAL-LOSS-WARNING","text":"원금은 지켜지죠"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("생성 성공 → 200 + 봉투(success:true, data.state=CREATED)")
    void createSuccess() throws Exception {
        mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대","contractRef":"CT-1"}"""))
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
                                {"productId":"doc-els-kiwoom-4181","channel":"대면","ageBand":"60대"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("/simulate는 amount를 body로 받고 기본값이 없다")
    void simulateBodyRequired() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"ELS-001","channel":"MOBILE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        String sid = com.jayway.jsonpath.JsonPath.read(created, "$.data.sessionId");

        // body로 금액 → 200
        mvc.perform(post("/sessions/" + sid + "/simulate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50000000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scenarios").isArray());

        // 금액 누락 → 400 (기본값으로 조용히 덮이지 않음)
        mvc.perform(post("/sessions/" + sid + "/simulate").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("데모 흐름: 세션 생성 → U4 답변 기록 → /judge가 RED(R-01)")
    void answerThenJudge_isRed() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
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
                .andExpect(jsonPath("$.data.ruleTrace[0].id").value("R-01"))
                // ID 옆에 사람이 읽는 문면이 같이 나간다 — 화면이 "R-01" 만 받으면
                // 판정 근거를 못 말한다(이슈 #320).
                .andExpect(jsonPath("$.data.ruleTrace[0].label").isNotEmpty());
        mvc.perform(get("/sessions/" + sid))
                .andExpect(jsonPath("$.data.state").value("JUDGED"));
    }

    @Test
    @DisplayName("데모 흐름(전체): 생성→U4답변→RED→오버라이드 요청→MGR 승인. 승인해도 신호는 RED로 남는다")
    void demoFlow_redThenOverrideApproved() throws Exception {
        // 기획 7-2 관통 — 조각 테스트가 못 잡는 create→judge→override 배선 회귀를 리허설 전에 잡는다.
        String reason = "적색이지만 고객이 재설명 후 원금손실 위험을 서면으로 재확인하여 진행을 요청합니다.";  // 30자 이상

        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        String sid = JsonPath.read(created, "$.data.sessionId");

        mvc.perform(post("/sessions/" + sid + "/answers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemId":"ELS-PRINCIPAL-LOSS-WARNING","text":"은행에서 파니까 원금은 지켜지죠"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grade").value("U4"));

        mvc.perform(post("/sessions/" + sid + "/judge"))
                .andExpect(jsonPath("$.data.signal").value("RED"));

        // 적색 세션에 오버라이드 요청 → 승인 대기
        mvc.perform(post("/sessions/" + sid + "/override").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));

        // MGR 승인
        mvc.perform(post("/sessions/" + sid + "/override/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        // 세션 응답이 승인 사실을 노출한다(S-06/S-07 입력): 상태·사유·승인자
        mvc.perform(get("/sessions/" + sid))
                .andExpect(jsonPath("$.data.overrideStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.overrideReason").value(reason))
                .andExpect(jsonPath("$.data.overrideApprover").isNotEmpty());

        // ❗불변식: 오버라이드는 게이트 신호를 바꾸지 않는다 — /judge는 멱등이고 여전히 RED다.
        // "적색인데 승인으로 진행" 이 별도로 기록될 뿐, 신호가 녹색이 되는 게 아니다.
        mvc.perform(post("/sessions/" + sid + "/judge"))
                .andExpect(jsonPath("$.data.signal").value("RED"))
                .andExpect(jsonPath("$.data.ruleTrace[0].id").value("R-01"))
                // ID 옆에 사람이 읽는 문면이 같이 나간다 — 화면이 "R-01" 만 받으면
                // 판정 근거를 못 말한다(이슈 #320).
                .andExpect(jsonPath("$.data.ruleTrace[0].label").isNotEmpty());
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
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
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
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
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
    @DisplayName("다음 질문 문면은 ai-service 가 만든 질문에서 온다 (F-INT-002 배선)")
    void nextQuestionUsesAiServiceQuestion() throws Exception {
        // 목 문면("… 본인 말씀으로 설명해 주시겠어요?")이 아니라 ai-service 응답이 실려야 한다.
        when(aiServiceClient.question(any(RiskItem.class), anyList(), eq("ELS"), anyString(), nullable(com.sphinxfin.sphinx.core.aiservice.AiServiceClient.InterviewContext.class)))
                .thenReturn(new AiServiceClient.Question("낙인 아래로 떨어지면 어떻게 되나요?", "condition", false));
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andReturn().getResponse().getContentAsString();
        String sid = JsonPath.read(created, "$.data.sessionId");

        mvc.perform(post("/sessions/" + sid + "/questions/next"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.question").value("낙인 아래로 떨어지면 어떻게 되나요?"));
        // 상품유형은 세션에서 온다(하드코딩 아님) — ELS 로 넘어갔는지 직접 확인한다.
        verify(aiServiceClient).question(any(RiskItem.class), anyList(), eq("ELS"), anyString(), nullable(com.sphinxfin.sphinx.core.aiservice.AiServiceClient.InterviewContext.class));
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

    // ── F-INT-004 재설명 에러 코드 (PR #28 리뷰 ②③) ────────────────────
    // 프론트가 error.code를 유니온 타입으로 분기하므로 와이어 레벨에서 고정한다.
    // 메시지 문면이 바뀌어도 이 테스트는 깨지지 않아야 한다 — 그게 코드를 가른 이유다.

    /** 세션 하나 만들고 목 채점(U4)까지 태워 재설명 가능한 상태로 만든다. */
    private String sessionWithMisunderstoodItem() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
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
    @DisplayName("재설명 콘텐츠는 ai-service 응답에서 온다 (F-INT-004 배선)")
    void reExplainUsesAiServiceContent() throws Exception {
        when(aiServiceClient.reExplain(any(RiskItem.class), any(Judgment.class),
                nullable(String.class), nullable(String.class)))
                .thenReturn(new AiServiceClient.ReExplanation("ai-service 가 만든 재설명 콘텐츠", List.of()));
        String sid = sessionWithMisunderstoodItem();

        mvc.perform(post("/sessions/" + sid + "/re-explain").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemId":"ELS-PRINCIPAL-LOSS-WARNING"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("ai-service 가 만든 재설명 콘텐츠"));
        // 판정(측정값)과 risk_item 이 실제로 ai-service 로 넘어가야 눈높이 재설명이 가능하다.
        verify(aiServiceClient).reExplain(any(RiskItem.class), any(Judgment.class),
                nullable(String.class), nullable(String.class));
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
