package com.sphinxfin.sphinx.core.aiservice;

import com.sphinxfin.sphinx.core.pii.PiiGateway;
import com.sphinxfin.sphinx.domain.MeasurementInvalidException;
import com.sphinxfin.sphinx.domain.RiskItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * ai-service 의 {@code detail.code} 를 서버가 읽는가 (이슈 #280 ③). 소유: 강희진
 *
 * <h2>상태 코드로는 못 가른다</h2>
 *
 * <p>{@code MEASUREMENT_INVALID} 와 {@code AI_SERVICE_UNAVAILABLE} 이 계약에서 <b>둘 다
 * 502</b> 다. 그래서 ai-service 가 본문에 코드를 싣고(PR #286) 서버가 그것을 읽는다.
 *
 * <p>❗<b>응답 코드만 보는 테스트로는 이 배선이 안 잡힌다.</b> 읽든 안 읽든 화면에는 502 가
 * 간다 — 갈리는 것은 <b>어느 코드로 가느냐</b> 이고 그게 <i>"AI 가 죽었다"</i> 와
 * <i>"모델이 인용을 지어냈다"</i> 를 나눈다. 그래서 예외 타입으로 잰다.
 */
@DisplayName("ai-service 오류 본문의 code 를 서버가 읽는다 (이슈 #280 ③)")
class MeasurementInvalidWireTest {

    private static final String BASE = "http://ai-service.test";
    private static final RiskItem ITEM = RiskItem.extracted(
            "ELS-PRINCIPAL-LOSS-WARNING", "mock-els-001", "원금손실 조건", "required",
            new RiskItem.Condition("만기평가일에 …(원문 인용)", new RiskItem.SourceSpan(3, 120, 210)));

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private AiServiceClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiServiceClient(builder, BASE, "", new com.sphinxfin.sphinx.core.pii.PiiMeter());
    }

    private void respond(String body) {
        server.expect(requestTo(BASE + "/internal/score"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private Throwable score() {
        return catchThrowable(
                () -> client.score("ELS-PRINCIPAL-LOSS-WARNING", "질문", "답변", ITEM, "ELS"));
    }

    @Test
    @DisplayName("❗code=MEASUREMENT_INVALID 면 측정 무효로 던진다 — 'AI 가 죽었다' 가 아니다")
    void aMachineReadableCodeIsHonoured() {
        respond("""
                {"detail":{"code":"MEASUREMENT_INVALID","message":"2회 재판정 후에도 측정이 무효다"}}""");

        assertThat(score())
                .as("본문을 안 읽으면 AiServiceException 이 되고 화면이 "
                        + "'AI 서비스를 사용할 수 없습니다' 를 띄운다 — 고칠 곳이 반대편이다")
                .isInstanceOf(MeasurementInvalidException.class);
    }

    @Test
    @DisplayName("❗문자열 detail 은 그대로 상류 장애다 — 한 자리만 구조화돼 있다")
    void aPlainStringDetailStaysAnOutage() {
        // 내부 오류 응답 형식이 아직 계약에 없어서(결정 10.40) 나머지 실패의 detail 은
        // 사람이 읽는 문자열이다. 전부 객체로 가정하면 그 경로가 파싱에서 죽는다.
        respond("""
                {"detail":"ai-service 내부 오류"}""");

        assertThat(score())
                .isInstanceOf(AiServiceException.class);
    }

    @Test
    @DisplayName("❗본문이 JSON 이 아니어도 죽지 않는다 — 코드가 없는 것도 정보다")
    void aNonJsonBodyIsNotAParsingFailure() {
        respond("<html>502 Bad Gateway</html>");

        assertThat(score())
                .isInstanceOf(AiServiceException.class);
    }

    @Test
    @DisplayName("❗모르는 code 는 상류 장애로 둔다 — 계약에 없는 값을 서버가 지어 읽지 않는다")
    void anUnknownCodeFallsBack() {
        respond("""
                {"detail":{"code":"SOMETHING_NEW","message":"…"}}""");

        assertThat(score())
                .isInstanceOf(AiServiceException.class);
    }
}
