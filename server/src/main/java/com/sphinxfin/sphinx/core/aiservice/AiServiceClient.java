package com.sphinxfin.sphinx.core.aiservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sphinxfin.sphinx.domain.EvidenceRequiredException;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
import com.sphinxfin.sphinx.domain.SuitabilityStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.sphinxfin.sphinx.core.pii.PiiGateway;

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
 * 엔드포인트: /internal/score·/internal/mismatch (구현됨). /internal/question·
 * /internal/reexplain 클라이언트도 여기 있다(ai-service PR #60 계약 기준). 다만 실제
 * SessionController·SessionService 배선은 #60 머지 후 별도 단계로 붙인다 — 이 클래스는
 * 호출 능력만 추가할 뿐 목 데모 흐름을 바꾸지 않는다.
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
     * @return 측정값 Judgment + **마스킹된 발화**. 마스킹본을 함께 돌려주는 이유는
     *         F-DET-002 가 세션 전체 발화를 다시 필요로 하는데, 호출부가 저장하려고
     *         PiiGateway.mask() 를 또 부르면 마스킹 호출 지점이 둘로 늘기 때문이다 —
     *         한쪽만 규칙이 바뀌면 저장본과 전송본의 마스킹이 갈린다 (P3).
     * @throws EvidenceRequiredException ai-service가 근거 없는 판정을 돌려준 경우 (P4 → 502)
     * @throws AiServiceException        호출 실패(non-2xx·연결 오류 등, → 502)
     */
    public Scored score(String itemId, String question, String answerText,
                        RiskItem riskItem, String productType) {
        String masked = PiiGateway.mask(answerText);   // P3 — 원문은 절대 나가지 않는다
        ScoreRequest request = new ScoreRequest(itemId, question, masked, riskItem, productType);
        try {
            Judgment judgment = restClient.post()
                    .uri("/internal/score")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, resp) -> {
                        throw new AiServiceException(
                                "ai-service /internal/score 실패: HTTP " + resp.getStatusCode());
                    })
                    .body(Judgment.class);
            return new Scored(judgment, masked);
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

    /**
     * F-DET-002 적합성 모순 판정. 설문 기재와 발화 전체를 넘겨 모순 여부를 받는다.
     *
     * 반환은 SuitabilityStatus 다 — 계약(suitability_mismatch.schema.json)의 status·mismatch
     * 두 필드를 **세 상태로 합쳐서** 돌려준다. 불리언 하나로 돌려주면 호출자가
     * insufficient_input 의 mismatch=false 를 "적합" 으로 읽는다. 계약이 스스로 경고한 지점이라
     * 그 오독이 가능한 형태를 아예 만들지 않는다.
     *
     * 발화는 여기서 PiiGateway.mask() 를 거친 뒤에만 나간다 (P3, score() 와 같다).
     *
     * @throws AiServiceException 호출 실패(non-2xx·연결 오류 등, → 502)
     */
    public SuitabilityStatus detectMismatch(String sessionId,
                                            Map<String, Object> surveyResult,
                                            Map<String, String> maskedUtterances,
                                            String surveySchemaVersion) {
        // 이미 마스킹된 값이지만 mask() 는 멱등이라 한 번 더 태운다 — 저장 경로가 나중에
        // 바뀌어 원문이 섞여 들어와도 여기서 걸린다 (P3 를 관행이 아니라 구조로).
        List<Utterance> utterances = maskedUtterances.entrySet().stream()
                .map(e -> new Utterance(e.getKey(), PiiGateway.mask(e.getValue())))
                .toList();
        MismatchRequest request = new MismatchRequest(
                sessionId, surveyResult, utterances, surveySchemaVersion);
        MismatchResponse response;
        try {
            response = restClient.post()
                    .uri("/internal/mismatch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, resp) -> {
                        throw new AiServiceException(
                                "ai-service /internal/mismatch 실패: HTTP " + resp.getStatusCode());
                    })
                    .body(MismatchResponse.class);
        } catch (AiServiceException e) {
            throw e;
        } catch (RestClientException e) {
            throw new AiServiceException("ai-service 호출 실패: " + e.getMessage(), e);
        }
        if (response == null) {
            throw new AiServiceException("ai-service /internal/mismatch 응답이 비었다");
        }
        return response.toStatus();
    }

    /**
     * F-INT-002 질문 생성 — risk_item으로 다음 검증 질문을 만든다.
     *
     * 입력은 상품에서 유도된 값(risk_item·이미 쓴 질문유형·상품유형)뿐이고 고객 텍스트가
     * 없다. 그래서 score()·detectMismatch()와 달리 PiiGateway.mask()를 거치지 않는다 (P3는
     * 고객 텍스트가 나가는 경로에만 걸린다). 응답은 게이트 판정이 아니라 질문 초안이다.
     *
     * askedTypes가 null이면 빈 리스트로 보낸다 — ai-service ScoreRequest/QuestionRequest
     * (PR #60)의 asked_types는 non-nullable(default=[])이라, null을 그대로 보내면 Strict
     * 스키마가 422로 거절한다.
     *
     * @param askedTypes 이미 사용한 질문유형(반복 방지). null이면 [] 로 보낸다
     * @throws AiServiceException 호출 실패(non-2xx·연결 오류 등, → 502)
     */
    public Question question(RiskItem riskItem, List<String> askedTypes, String productType) {
        List<String> asked = askedTypes == null ? List.of() : askedTypes;
        QuestionRequest request = new QuestionRequest(riskItem, asked, productType);
        QuestionResponse response;
        try {
            response = restClient.post()
                    .uri("/internal/question")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, resp) -> {
                        throw new AiServiceException(
                                "ai-service /internal/question 실패: HTTP " + resp.getStatusCode());
                    })
                    .body(QuestionResponse.class);
        } catch (AiServiceException e) {
            throw e;
        } catch (RestClientException e) {
            throw new AiServiceException("ai-service 호출 실패: " + e.getMessage(), e);
        }
        if (response == null) {
            throw new AiServiceException("ai-service /internal/question 응답이 비었다");
        }
        return new Question(response.question(), response.questionType());
    }

    /**
     * F-INT-004 재설명 — 판정(측정값)과 risk_item으로 눈높이 재설명 콘텐츠를 만든다.
     *
     * ❗<b>고객 텍스트가 있다</b> — {@code judgment.evidence().utteranceQuote()} 가 발화 인용
     * 그대로다. question() 과 달리 이 경로는 고객의 말을 ai-service 로 보낸다.
     *
     * <p>그런데도 여기서 {@link PiiGateway#mask(String)} 를 부르지 않는 이유는 <b>없어서가
     * 아니라 이미 마스킹된 것이기 때문</b>이다. 그 인용은 채점 경로에서 만들어진다 —
     * {@code score()} 가 mask() 를 태워 보낸 발화를 ai-service 의 인용 대조
     * (verify_quote_is_verbatim)가 검증하므로, 인용은 <b>구성상</b> 마스킹 상태다. 같은 파일
     * {@code Scored(judgment, maskedAnswer)} 가 "그때 실제로 나간 마스킹 발화" 인 것이 그
     * 근거다. ai-service 의 PiiGuardMiddleware 가 {@code /internal/*} 본문의 모든 문자열을
     * 재검사하는 것이 두 번째 방어선이다.
     *
     * <p><b>이 전제가 깨지는 변경</b>: {@code Evidence} 에 원문 필드가 추가되거나,
     * {@code Judgment} 가 mask() 밖 경로로 만들어지는 것. 그러면 이 호출이 원문을 내보낸다.
     * P3 문면("고객 텍스트가 나가는 유일한 경로는 mask() → AiServiceClient")에 형식상 걸리는
     * 자리라 근거를 여기 남긴다 (#119 리뷰).
     *
     * <p>ageBand·experienceLevel은 선택이며(#60 스키마도 nullable), null이면 그대로 null로 보낸다.
     *
     * @throws AiServiceException 호출 실패(non-2xx·연결 오류 등, → 502)
     */
    public ReExplanation reExplain(RiskItem riskItem, Judgment judgment,
                                   String ageBand, String experienceLevel) {
        ReExplainRequest request =
                new ReExplainRequest(riskItem, judgment, ageBand, experienceLevel);
        ReExplainResponse response;
        try {
            response = restClient.post()
                    .uri("/internal/reexplain")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, resp) -> {
                        throw new AiServiceException(
                                "ai-service /internal/reexplain 실패: HTTP " + resp.getStatusCode());
                    })
                    .body(ReExplainResponse.class);
        } catch (AiServiceException e) {
            throw e;
        } catch (RestClientException e) {
            throw new AiServiceException("ai-service 호출 실패: " + e.getMessage(), e);
        }
        if (response == null) {
            throw new AiServiceException("ai-service /internal/reexplain 응답이 비었다");
        }
        return new ReExplanation(response.content(), response.citedSpans());
    }

    /** 채점 결과 — 측정값과 그때 실제로 나간 마스킹 발화를 함께 돌려준다. */
    public record Scored(Judgment judgment, String maskedAnswer) {}

    /** F-INT-002 질문 생성 결과. question_type ∈ {situation, amount, condition}. */
    public record Question(String question, String questionType) {}

    /** F-INT-004 재설명 결과. citedSpans는 P6 원문 인용 스팬(없으면 빈 리스트). */
    public record ReExplanation(String content, List<RiskItem.SourceSpan> citedSpans) {}

    /**
     * /internal/mismatch 요청 본문. snake_case 매퍼로 직렬화된다.
     * utterances 는 계약상 [{item_id, text}] 형태다 — 문자열 배열로 보내면 Strict 스키마가
     * 422 로 거절한다.
     */
    record MismatchRequest(String sessionId, Map<String, Object> surveyResult,
                           List<Utterance> utterances, String surveySchemaVersion) {}

    /** 세션 내 발화 한 건. text 는 이미 마스킹된 값이다. */
    record Utterance(String itemId, String text) {}

    /**
     * /internal/mismatch 응답. contracts/suitability_mismatch.schema.json 과 1:1.
     * confidence·contradictions 는 게이트 배선에서 쓰지 않지만 **필드로 받아 둔다** —
     * 이 클라이언트 전용 매퍼는 FAIL_ON_UNKNOWN_PROPERTIES 가 기본값(on)이라 빠뜨리면
     * 역직렬화가 통째로 실패한다. 계약이 필드를 늘리면 여기도 늘려야 하고,
     * 그 어긋남은 MismatchContractTest 가 잡는다.
     */
    record MismatchResponse(String sessionId, String status, boolean mismatch,
                            BigDecimal confidence, List<Map<String, Object>> contradictions,
                            String reason, String surveySchemaVersion) {

        /**
         * 계약의 두 필드를 세 상태로 합친다.
         *
         * insufficient_input 이면 mismatch 가 항상 false 인데 그걸 NO_MISMATCH 로 옮기면
         * 판정 실패가 "적합" 이 된다 — 계약 주석이 명시적으로 금지한 오독이다.
         */
        SuitabilityStatus toStatus() {
            if ("insufficient_input".equals(status)) {
                return SuitabilityStatus.UNKNOWN;
            }
            return mismatch ? SuitabilityStatus.MISMATCH : SuitabilityStatus.NO_MISMATCH;
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

    /**
     * /internal/question 요청 본문. snake_case로 ai-service QuestionRequest
     * (risk_item, asked_types, product_type)와 1:1 (PR #60).
     */
    record QuestionRequest(RiskItem riskItem, List<String> askedTypes, String productType) {}

    /**
     * /internal/question 응답. ai-service QuestionResponse(item_id, question, question_type,
     * fallback_used)와 1:1 (PR #60). itemId·fallbackUsed는 배선에서 쓰지 않지만 **필드로 받아
     * 둔다** — 이 클라이언트 전용 매퍼는 FAIL_ON_UNKNOWN_PROPERTIES가 기본값(on)이라 빠뜨리면
     * 역직렬화가 통째로 실패한다(MismatchResponse와 같은 이유).
     */
    record QuestionResponse(String itemId, String question, String questionType,
                            boolean fallbackUsed) {}

    /**
     * /internal/reexplain 요청 본문. snake_case로 ai-service ReexplainRequest
     * (risk_item, judgment, age_band, experience_level)와 1:1 (PR #60).
     * age_band·experience_level은 선택이라 null이면 null로 나간다.
     */
    record ReExplainRequest(RiskItem riskItem, Judgment judgment,
                            String ageBand, String experienceLevel) {}

    /**
     * /internal/reexplain 응답. ai-service ReexplainResponse(item_id, content, cited_spans)와
     * 1:1 (PR #60). cited_spans는 [{page, start, end}] 형태라 domain의 SourceSpan을 재사용한다.
     * itemId는 배선에서 쓰지 않지만 FAIL_ON_UNKNOWN_PROPERTIES 때문에 필드로 받아 둔다.
     */
    record ReExplainResponse(String itemId, String content,
                             List<RiskItem.SourceSpan> citedSpans) {}
}
