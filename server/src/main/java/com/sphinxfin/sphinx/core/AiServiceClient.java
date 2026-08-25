package com.sphinxfin.sphinx.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sphinxfin.sphinx.domain.EvidenceRequiredException;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * ai-service(FastAPI) 호출 클라이언트. 소유: 강희진 (엔드포인트 스펙: 윤지석과 협의)
 *
 * 규칙:
 * - 고객 텍스트는 반드시 이 클래스 안에서 {@link PiiGateway#mask(String)}를 거친 뒤에만
 *   나간다 (P3). 원문이 나갈 경로를 만들지 않기 위해 마스킹을 경계(이 클라이언트) 안에 둔다.
 *   ai-service도 입구에서 방어적으로 PII를 재검사하지만, 서버가 먼저 마스킹한다.
 * - ai-service는 snake_case로 말한다(contracts/*.schema.json). Java domain 레코드는 camelCase다.
 *   그래서 이 경계 전용 ObjectMapper(SNAKE_CASE)로만 (역)직렬화한다. 전역/웹 Jackson은
 *   camelCase 그대로 둔다 — 웹 응답은 계속 camelCase.
 * - 응답은 *측정값*(Judgment)이다. 게이트 판정이 아니다 (P1). 근거 없는 판정은
 *   Judgment 생성자가 EvidenceRequiredException으로 막는다 (P4) — 이 경로를 그대로 둔다.
 *
 * base-url은 application.yml(sphinx.ai-service.base-url).
 * 엔드포인트: /internal/score (구현됨). /internal/question·/internal/reexplain은
 * ai-service에서 아직 NotImplementedError(501)라 여기서 연결하지 않는다.
 */
@Component
public class AiServiceClient {

    private final RestClient restClient;

    public AiServiceClient(RestClient.Builder builder,
                           @Value("${sphinx.ai-service.base-url}") String baseUrl) {
        // 이 경계 전용 매퍼 — 전역 Jackson과 분리한다(웹은 camelCase 유지).
        ObjectMapper snakeMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        MappingJackson2HttpMessageConverter snakeConverter =
                new MappingJackson2HttpMessageConverter(snakeMapper);
        this.restClient = builder
                .baseUrl(baseUrl)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                    converters.add(snakeConverter);
                })
                .build();
    }

    /**
     * F-SCR-001 채점 — 고객 발화를 ai-service로 보내 항목별 이해도 판정(측정값)을 받는다.
     *
     * answerText는 여기서 PiiGateway.mask()를 거친 뒤에만 나간다 (P3). 호출자는 원문을 넘겨도
     * 되며(경계 안에서 마스킹하므로 원문 유출 경로가 없다), 이중 마스킹은 멱등이라 무해하다.
     *
     * @param answerText 고객 발화 원문(마스킹은 이 메서드가 한다)
     * @return ai-service의 Judgment(측정값). 근거가 비면 Judgment 생성자가 P4로 막는다.
     * @throws EvidenceRequiredException ai-service가 근거 없는 판정을 돌려준 경우 (P4 → 502)
     * @throws AiServiceException        호출 실패(non-2xx·연결 오류 등, → 502)
     */
    public Judgment score(String itemId, String question, String answerText,
                          RiskItem riskItem, String productType) {
        String masked = PiiGateway.mask(answerText);   // P3 — 원문은 절대 나가지 않는다
        ScoreRequest request = new ScoreRequest(itemId, question, masked, riskItem, productType);
        try {
            return restClient.post()
                    .uri("/internal/score")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, resp) -> {
                        throw new AiServiceException(
                                "ai-service /internal/score 실패: HTTP " + resp.getStatusCode());
                    })
                    .body(Judgment.class);
        } catch (EvidenceRequiredException | AiServiceException e) {
            throw e;   // P4/상류 실패는 그대로 502로 매핑되게 둔다
        } catch (RestClientException e) {
            // 역직렬화 중 P4 위반(빈 근거)이 감싸여 올 수 있다 — 원인 체인에서 풀어낸다.
            EvidenceRequiredException p4 = unwrap(e);
            if (p4 != null) {
                throw p4;
            }
            throw new AiServiceException("ai-service 호출 실패: " + e.getMessage(), e);
        }
    }

    /** 예외 원인 체인에서 EvidenceRequiredException을 찾아낸다(Jackson이 감쌌을 때 대비). */
    private static EvidenceRequiredException unwrap(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof EvidenceRequiredException e) {
                return e;
            }
        }
        return null;
    }

    /**
     * /internal/score 요청 본문. snake_case 매퍼로 직렬화되어 ai-service의 ScoreRequest
     * (item_id, question, answer_text, risk_item, product_type)와 1:1로 맞는다.
     */
    record ScoreRequest(String itemId, String question, String answerText,
                        RiskItem riskItem, String productType) {}
}
