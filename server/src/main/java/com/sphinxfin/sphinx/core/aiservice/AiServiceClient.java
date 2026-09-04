package com.sphinxfin.sphinx.core.aiservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.sphinxfin.sphinx.domain.MeasurementInvalidException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient.ResponseSpec.ErrorHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sphinxfin.sphinx.domain.EvidenceRequiredException;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
import com.sphinxfin.sphinx.domain.SuitabilityMismatch;
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

    /** 오류 본문 전용. 경계 매퍼(SNAKE_CASE·엄격)와 섞지 않는다 — 오류는 계약 밖 모양이다. */
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();


    private final RestClient restClient;

    /** /internal/* 공유 시크릿 헤더명 — ai-service(PR #198)와 문자열이 같아야 한다. */
    static final String INTERNAL_TOKEN_HEADER = "x-sphinx-internal-token";

    public AiServiceClient(RestClient.Builder builder,
                           @Value("${sphinx.ai-service.base-url}") String baseUrl,
                           @Value("${sphinx.ai-service.internal-token:}") String internalToken) {
        // 이 경계 전용 매퍼 — 전역 Jackson과 분리한다(웹은 camelCase 유지).
        ObjectMapper snakeMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        MappingJackson2HttpMessageConverter snakeConverter =
                new MappingJackson2HttpMessageConverter(snakeMapper);
        RestClient.Builder configured = builder
                .baseUrl(baseUrl)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                    converters.add(snakeConverter);
                });
        // #41③ /internal/* 공유 시크릿(결정 10.4) — ai-service가 x-sphinx-internal-token 을
        // 검증한다(PR #198). 토큰이 설정된 배포에서만 붙인다. 비어 있으면(로컬 목 개발) 안 붙인다
        // — 받는 쪽도 토큰이 비면 인증을 끄므로(internal_auth_enabled=bool(token)) 대칭이다.
        if (internalToken != null && !internalToken.isBlank()) {
            configured = configured.defaultHeader(INTERNAL_TOKEN_HEADER, internalToken);
        }
        this.restClient = configured.build();
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
                    .onStatus(HttpStatusCode::isError, failure("/internal/score"))
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
    public SuitabilityMismatch detectMismatch(String sessionId,
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
                    .onStatus(HttpStatusCode::isError, failure("/internal/mismatch"))
                    .body(MismatchResponse.class);
        } catch (AiServiceException e) {
            throw e;
        } catch (RestClientException e) {
            throw new AiServiceException("ai-service 호출 실패: " + e.getMessage(), e);
        }
        if (response == null) {
            throw new AiServiceException("ai-service /internal/mismatch 응답이 비었다");
        }
        return response.toMismatch();
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
        return question(riskItem, askedTypes, productType, "initial", null);
    }

    /**
     * 면담 맥락을 실어 질문을 만든다 (F-INT-002).
     *
     * <p>{@code variant="reverify"} 는 재설명 뒤 다시 묻는 질문이다. 그 문면이 <b>서버에
     * 항목별 고정 문항으로</b> 있었는데, 그 자리 주석이 스스로 <i>"사전에 확보하면 그대로
     * 뚫린다"</i> 고 적어 두고 있었다 — 기획서 7-4 1단계(우회 비용 상향)가 요구하는 것은
     * <b>고정 문항을 사전에 확보하는 것이 불가능한 상태</b>다. 재검증은 판매자가 미리 답을
     * 준비시킬 동기가 가장 큰 자리라(첫 질문에서 이미 한 번 막혔으므로) 여기가 고정이면
     * 게이트가 뚫린다.
     */
    public Question question(RiskItem riskItem, List<String> askedTypes, String productType,
                             String variant, InterviewContext context) {
        List<String> asked = askedTypes == null ? List.of() : askedTypes;
        QuestionRequest request = new QuestionRequest(riskItem, asked, productType,
                variant, context);
        QuestionResponse response;
        try {
            response = restClient.post()
                    .uri("/internal/question")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, failure("/internal/question"))
                    .body(QuestionResponse.class);
        } catch (AiServiceException e) {
            throw e;
        } catch (RestClientException e) {
            throw new AiServiceException("ai-service 호출 실패: " + e.getMessage(), e);
        }
        if (response == null) {
            throw new AiServiceException("ai-service /internal/question 응답이 비었다");
        }
        return new Question(response.question(), response.questionType(), response.fallbackUsed());
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
                    .onStatus(HttpStatusCode::isError, failure("/internal/reexplain"))
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
    /**
     * 생성된 질문. {@code fallbackUsed} 는 ai-service 가 정답 노출 검사를 통과 못 해
     * <b>템플릿 고정 문장</b>으로 내려갔다는 뜻이다(F-INT-002 · 이슈 #234).
     *
     * <p>예전에는 이 값을 여기서 버렸다 — {@code QuestionResponse} 가 받아 두고 이 레코드가
     * 안 옮겼다. 그래서 <b>서버가 받아서 의도적으로 버리는</b> 상태였고, 폴백률을 화면에도
     * 로그에도 기록에도 볼 방법이 없었다.
     */
    public record Question(String question, String questionType, boolean fallbackUsed) {}

    /** F-INT-004 재설명 결과. citedSpans는 P6 원문 인용 스팬(없으면 빈 리스트). */
    public record ReExplanation(String content, List<RiskItem.SourceSpan> citedSpans) {}

    /**
     * /internal/mismatch 요청 본문. snake_case 매퍼로 직렬화된다.
     * utterances 는 계약상 [{item_id, text}] 형태다 — 문자열 배열로 보내면 Strict 스키마가
     * 422 로 거절한다.
     */
    record MismatchRequest(String sessionId, Map<String, Object> surveyResult,
                           List<Utterance> utterances, String surveySchemaVersion) {}

    /**
     * non-2xx 한 곳 처리 — <b>본문의 {@code detail.code} 를 읽는다</b> (이슈 #280 ③).
     *
     * <p>❗예전에는 네 경로가 각자 상태 코드만 보고 {@link AiServiceException} 을 던졌다.
     * 그래서 ai-service 가 <i>"모델이 인용을 지어냈고 다시 물어도 그랬다"</i>
     * ({@code MEASUREMENT_INVALID})를 실어 보내도 화면에는 <b>"AI 서비스를 사용할 수
     * 없습니다"</b> 가 떴다 — <b>고칠 곳이 반대편인데 반대편을 가리킨다.</b>
     *
     * <p>{@code MEASUREMENT_INVALID} 와 {@code AI_SERVICE_UNAVAILABLE} 은 계약에서 둘 다
     * 502 라 상태 코드로는 못 가른다. 그래서 ai-service 가 본문에 코드를 싣고(PR #286)
     * 여기서 읽는다.
     *
     * <p>❗<b>방어적으로 읽는다.</b> {@code code} 를 못 찾으면 구조화가 아닌 것으로 본다 —
     * 내부 오류 응답 형식이 아직 계약에 없어서(결정 10.40) <b>한 자리만 구조화돼 있고
     * 나머지는 문자열</b>이다. 그 상태에서 전부 객체로 가정하면 나머지 경로가 죽는다.
     */
    private static ErrorHandler failure(String endpoint) {
        return (req, resp) -> {
            String code = errorCode(resp);
            String where = "ai-service " + endpoint + " 실패: HTTP " + resp.getStatusCode();
            if ("MEASUREMENT_INVALID".equals(code)) {
                throw new MeasurementInvalidException(where + " — " + code);
            }
            throw new AiServiceException(where);
        };
    }

    /** {@code {"detail": {"code": …}}} 에서 code 만. 그 모양이 아니면 {@code null}. */
    private static String errorCode(org.springframework.http.client.ClientHttpResponse resp) {
        // ❗**실제로 던져지는 것만 잡는다.** 처음엔 `catch (Exception)` 이었는데, 그러면
        // 아래 읽기가 터져도 삼켜져서 **방어가 도는지 아무도 못 잰다** — `isObject()` 를
        // 지우거나 `path()` 를 `get()` 으로 바꿔도 테스트가 전부 초록이었다(#293 리뷰,
        // 윤지석 실측). 넓은 catch 가 "문자열 detail 도 안전하다" 를 **두 가지 이유로**
        // 참으로 만들고 둘을 구별하지 못했다: 읽기가 방어적이라서인지, 터졌는데 삼켜서인지.
        try {
            // ❗**방어는 `path()` 하나다.** 처음엔 `isObject() ? … : null` 을 앞에 뒀는데
            // 그 가드를 지워도 답이 안 바뀐다 — `path()` 는 객체가 아닌 노드에도
            // {@code MissingNode} 를 주고 `asText(null)` 이 null 이 된다. **방어처럼 생긴
            // 죽은 줄**이라 지웠다: 다음 사람이 그걸 믿고 `get()` 으로 바꾸면 문자열
            // detail 에서 NPE 다(#293 리뷰에서 실제로 그 변이가 걸렸다).
            //
            // `get()` 을 쓰면 안 되는 이유가 여기 있다 — 없는 키에 null 을 준다.
            JsonNode detail = ERROR_MAPPER.readTree(resp.getBody()).path("detail");
            return detail.path("code").asText(null);
        } catch (java.io.IOException e) {
            // 본문이 없거나 JSON 이 아니다. 코드가 없는 것이고 그 자체가 정보다 —
            // 호출자는 AiServiceException 을 받는다.
            return null;
        }
    }

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
        /** 상태와 근거를 함께 넘긴다 — 근거를 버리면 불변 기록에 남길 것이 없다 (#169). */
        SuitabilityMismatch toMismatch() {
            return new SuitabilityMismatch(toStatus(), reason, confidence, contradictions);
        }

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
    record QuestionRequest(RiskItem riskItem, List<String> askedTypes, String productType,
                           String variant, InterviewContext context) {}

    /**
     * 면담이 지금까지 알아낸 것 — 질문 생성이 이걸 보고 다음 질문을 정한다.
     *
     * <p>❗<b>정답을 싣지 않는다.</b> 등급과 오해 유형 ID 뿐이다 — 발화도 루브릭 조항도
     * 조건 원문도 안 간다. 그건 {@code answer_fragments} 가 질문에서 걸러내는 바로 그
     * 값이고, 맥락으로 넣으면 모델이 다음 질문에 옮겨 쓴다(유도심문).
     *
     * @param vulnerable            코칭 정황 스코어가 임계 이상 — 눈높이를 낮춘다
     * @param priorGrades           이 세션에서 이미 나온 등급
     * @param matchedMisconceptions 이미 걸린 오해 유형 ID
     */
    public record InterviewContext(boolean vulnerable, List<String> priorGrades,
                                   List<String> matchedMisconceptions) {}

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
