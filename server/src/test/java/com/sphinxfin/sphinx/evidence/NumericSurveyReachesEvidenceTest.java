package com.sphinxfin.sphinx.evidence;

import com.jayway.jsonpath.JsonPath;
import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.core.session.Session;
import com.sphinxfin.sphinx.core.session.SessionService;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
import com.sphinxfin.sphinx.domain.SuitabilityMismatch;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ❗숫자 설문이 <b>진짜 요청으로</b> 들어와도 judge 가 살아 있는가 (이슈 #466). 소유: 정세현
 *
 * <h2>왜 단위 테스트로 부족한가</h2>
 *
 * <p>{@code StoredEvidenceRecorderTest} 는 {@code appendMismatch} 를 직접 부른다. 그것만으로는
 * <b>이 크래시가 실제로 닿는 경로였는지</b>를 증명하지 못한다 — 값이 {@code Double} 이 되는
 * 것은 전역 Jackson 의 역직렬화 결과이고, 그 사실은 <b>진짜 HTTP 요청을 통과시켜야</b> 보인다.
 * {@code #461} 리뷰가 <i>"타입 단정만으로는 부족하다"</i> 로 짚은 자리이기도 하다.
 *
 * <p>그래서 여기서는 {@link com.sphinxfin.sphinx.core.EvidenceRecorder} 를 <b>가짜로 바꾸지
 * 않는다.</b> 다른 통합 테스트들은 기록 내용을 보려고 대체하는데, 그러면 정작
 * {@link CanonicalJson} 이 안 돌아 이 결함이 통과한다.
 *
 * <h2>무엇이 걸려 있었나</h2>
 *
 * <pre>
 * {"surveyResult": {"SUIT-LOSS-TOLERANCE": 20.0}}   계약이 허용한다 (additionalProperties: true)
 *   → 전역 Jackson 이 Double 로 역직렬화
 *   → judge → recordSuitability → appendMismatch → CanonicalJson.write(Double) → 던진다
 *   → INTERNAL_ERROR · 전이 미커밋 · 세션이 IN_PROGRESS 에 갇힌다   (#453 과 같은 증상)
 * </pre>
 *
 * <p>지금 안 터지고 있던 이유는 화면 관례 하나다 — {@code web/src/lib/survey.ts} 가 값을
 * 문장으로 보낸다. 그건 F-DET-002 를 위한 선택이지 이 크래시를 막으려던 것이 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("숫자 설문이 들어와도 judge 가 죽지 않는다 (이슈 #466)")
class NumericSurveyReachesEvidenceTest {

    @Autowired private MockMvc mvc;
    @Autowired private JpaImmutableStore store;
    @Autowired private StoredEvidenceRecorder recorder;
    @Autowired private SessionService sessionService;
    @MockBean private AiServiceClient aiServiceClient;

    @BeforeEach
    void stub() {
        when(aiServiceClient.question(any(RiskItem.class), anyList(), anyString(), anyString(),
                nullable(AiServiceClient.InterviewContext.class)))
                .thenReturn(new AiServiceClient.Question(
                        "이 상품은 어떤 경우에 원금이 줄어들 수 있을까요?", "OPEN_ENDED", false));
        when(aiServiceClient.score(anyString(), anyString(), anyString(), any(RiskItem.class),
                anyString(), nullable(com.sphinxfin.sphinx.domain.InputMeta.class)))
                .thenAnswer(inv -> new AiServiceClient.Scored(
                        new Judgment(inv.getArgument(0), Grade.U1, new BigDecimal("0.9"),
                                new Judgment.Evidence("낙인 하회하면 손실", "원금손실 조건 인지"),
                                "정확히 진술", null),
                        inv.getArgument(2)));
        when(aiServiceClient.detectMismatch(anyString(), anyMap(), anyMap(), nullable(String.class)))
                .thenReturn(new SuitabilityMismatch(SuitabilityStatus.MISMATCH,
                        "설문은 손실 감수 가능인데 발화는 원금 보장을 전제한다",
                        new BigDecimal("0.82"),
                        List.of()));
    }

    /** ❗{@code 20.0}·{@code 0.35} 가 소수 리터럴이다 — 전역 Jackson 이 {@code Double} 로 만든다. */
    private String createSessionWithNumericSurvey() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE",
                                 "ageBand":"60대","surveySchemaVersion":"s02-survey-v2",
                                 "surveyResult":{"SUIT-LOSS-TOLERANCE":20.0,
                                                 "SUIT-ASSET-RATIO":0.35,
                                                 "SUIT-HOLDING-YEARS":3,
                                                 "SUIT-RISK-TOLERANCE":"원금 손실은 감수할 수 있다"}}"""))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(created, "$.data.sessionId");
    }

    @Test
    @DisplayName("★❗진짜 요청이 Double 을 만든다 — 이 크래시는 가정이 아니라 닿는 경로였다")
    void aRealRequestActuallyProducesADouble() throws Exception {
        String sid = createSessionWithNumericSurvey();

        Session session = sessionService.get(sid);

        // ❗**이것이 이 파일의 존재 이유다.** 단위 테스트는 `Double` 을 손으로 넣지만, 그것만으로는
        //   *"실제로 그런 값이 들어오나"* 를 증명하지 못한다. 여기서는 진짜 HTTP 본문이
        //   전역 Jackson 을 지나 세션에 앉은 값을 본다.
        //
        //   `AiServiceClient` 전용 매퍼에는 USE_BIG_DECIMAL_FOR_FLOATS 가 켜져 있지만(#461)
        //   **이 경로는 그 매퍼를 안 지난다** — 그래서 여기는 Double 이다. 그 사실이 #466 이다.
        assertThat(session.surveyResult().get("SUIT-LOSS-TOLERANCE"))
                .as("계약이 숫자를 허용하고(additionalProperties: true) 전역 Jackson 이 "
                        + "Double 로 만든다. 이 단정이 깨지면 #466 의 전제가 사라진 것이니 "
                        + "이 파일을 지워도 된다")
                .isInstanceOf(Double.class);
        assertThat(session.surveyResult())
                .containsEntry("SUIT-HOLDING-YEARS", 3)
                .containsEntry("SUIT-RISK-TOLERANCE", "원금 손실은 감수할 수 있다");
    }

    @Test
    @DisplayName("❗그 값 그대로 기록에 적재되고 사슬이 verify 를 통과한다")
    void thatVeryMapIsRecordedAndTheChainVerifies() throws Exception {
        String sid = createSessionWithNumericSurvey();
        Session session = sessionService.get(sid);

        // 손으로 만든 표본이 아니라 **방금 요청이 만든 그 맵**을 적재한다.
        // recordSuitability 가 하는 것과 같은 호출이다(SessionService — judge 경로).
        recorder.appendMismatch(sid,
                new SuitabilityMismatch(SuitabilityStatus.MISMATCH,
                        "설문은 손실 감수 가능인데 발화는 원금 보장을 전제한다",
                        new BigDecimal("0.82"), List.of()),
                session.surveySchemaVersion(), session.surveyResult(), java.time.Instant.now());

        // 전에는 여기서 IllegalArgumentException 이 났고, 그 예외가 judge 를 INTERNAL_ERROR 로
        // 만들면서 상태 전이가 커밋되지 않아 **세션이 IN_PROGRESS 에 갇혔다**(#453 과 같은 증상).
        assertThat(store.verify(StoredEvidenceRecorder.streamOf(sid)).ok())
                .as("적재만 되고 되읽기·재직렬화에서 깨지면 증상이 「테스트 초록 + 감사 시점 "
                        + "verify 실패」다 (#327)")
                .isTrue();
    }
}
