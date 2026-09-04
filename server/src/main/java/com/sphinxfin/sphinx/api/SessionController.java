package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.domain.SuitabilityMismatch;
import com.sphinxfin.sphinx.api.dto.AnswerRequest;
import com.sphinxfin.sphinx.api.dto.JudgmentView;
import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.api.dto.CreateSessionRequest;
import com.sphinxfin.sphinx.api.dto.JudgmentsResponse;
import com.sphinxfin.sphinx.api.dto.NextQuestionResponse;
import com.sphinxfin.sphinx.api.dto.RiskItemsResponse;
import com.sphinxfin.sphinx.api.dto.ProductSummary;
import com.sphinxfin.sphinx.api.dto.ReExplainRequest;
import com.sphinxfin.sphinx.api.dto.SessionResponse;
import com.sphinxfin.sphinx.api.dto.SimulateRequest;
import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.security.CurrentActor;
import com.sphinxfin.sphinx.core.EvidenceRecorder;
import com.sphinxfin.sphinx.core.aiservice.AiServiceException;
import com.sphinxfin.sphinx.core.session.Session;
import com.sphinxfin.sphinx.core.session.SessionService;
import com.sphinxfin.sphinx.evidence.ReportService;
import com.sphinxfin.sphinx.simulator.SimulationScenarios;
import com.sphinxfin.sphinx.domain.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;

/** 세션·인터뷰·게이트 API. 소유: 강희진 */
@RestController
@RequestMapping("/sessions")
@Slf4j
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final AiServiceClient aiServiceClient;
    private final CurrentActor currentActor;
    private final ReportService reportService;
    private final SimulationScenarios simulationScenarios;

    @PreAuthorize("@accessGuard.canCreate('session:create')")
    @PostMapping
    public ApiResponse<SessionResponse> create(@Valid @RequestBody CreateSessionRequest body) {
        // 귀속은 인증 주체에서만 온다 — 본문에 없다(CreateSessionRequest 주석).
        Session session = sessionService.create(
                body.toCommand(currentActor.actorId(), currentActor.branchId()));
        return ApiResponse.ok(SessionResponse.of(session));
    }

    /**
     * 세션 조회. <b>읽기와 진행을 가른 action 이다</b>(#129 · 이슈 #124).
     *
     * <p>{@code session:interview} 를 쓰면 MGR 에게 그 그랜트를 주는 순간 같은 action 이
     * 덮는 {@code questions/next}·{@code re-explain}·{@code abort} 까지 열린다 — 승인하려고
     * 읽어야 하는 것과 <b>세션을 몰 수 있는 것</b>은 다르다. 그래서 읽기만 별도 action 이고,
     * 그 덕에 승인자(MGR)가 승인 대상을 읽을 수 있으면서 진행에는 못 닿는다.
     */
    @PreAuthorize("@accessGuard.can('session:read', #sid)")
    @GetMapping("/{sid}")
    public ApiResponse<SessionResponse> get(@PathVariable String sid) {
        return ApiResponse.ok(SessionResponse.of(sessionService.get(sid)));
    }

    /**
     * 이 세션이 검증할 이해항목 — <b>고객 화면(S-03·S-04)이 받는 경로</b>다 (이슈 #158).
     *
     * <h2>왜 카탈로그 경로를 안 쓰는가</h2>
     *
     * <p>화면 둘이 지금 {@code GET /sessions/{sid}} 로 productId 를 알아낸 뒤
     * {@code GET /products/{productId}/risk-items} 를 부른다. 그 경로는
     * {@code product:read}(scope org)라, 고객에게 열어 주면 <b>자기 계약 건과 무관한 상품까지
     * 전 카탈로그를 열거</b>할 수 있게 된다. 고객이 필요한 것은 <i>"내 세션이 다루는 항목"</i>
     * 이지 카탈로그가 아니다 — 지금 카탈로그를 쓰는 것은 이 라우트가 없어서지 그게 맞아서가
     * 아니다.
     *
     * <p>여기서는 대상이 세션이므로 <b>범위가 자연히 {@code own_session}</b> 이 되고, 왕복도
     * 하나 준다. action 은 {@code session:read} 를 쓴다 — 세션을 읽을 수 있으면 그 세션이
     * 무엇을 묻는지도 읽을 수 있다는 뜻이고, 새 action 을 만들면 정책이 그만큼 넓어진다.
     *
     * <p>❗{@code CUST} 에게 {@code session:read} 가 아직 없다. 라우트를 옮겨도 고객 화면은
     * 그 그랜트가 생겨야 열린다(이슈 #158 3항 · 결정 10.5 역할별 계정 분리). <b>이 라우트가
     * 먼저인 이유</b>는 그 그랜트를 카탈로그 action 에 주지 않기 위해서다.
     */
    @PreAuthorize("@accessGuard.can('session:read', #sid)")
    @GetMapping("/{sid}/risk-items")
    public ApiResponse<RiskItemsResponse> riskItems(@PathVariable String sid) {
        Session session = sessionService.get(sid);   // 없는 세션이면 404
        // TODO(강희진): 추출(F-EXT-002)이 붙으면 session.productId() 로 실제 항목을 읽는다.
        //   그때 이 라우트와 카탈로그 라우트의 목록이 갈린다 — 여기는 "이 세션이 검증할 항목",
        //   저기는 "상품의 전체 항목"이다. SessionRiskItemsTest 의 카탈로그 대조도 같이 지운다.
        //   지금은 목이지만 **세션을 실제로 조회한 뒤** 낸다 — 그래야 없는 세션과 남의 세션이
        //   여기서 걸린다. 목을 그냥 돌려주면 @PreAuthorize 만 남고 404 가 사라진다.
        log.debug("세션 {} (상품 {}) 의 이해항목을 낸다", session.id(), session.productId());
        return ApiResponse.ok(new RiskItemsResponse(MockData.RISK_ITEMS));
    }

    /**
     * S-05 판정 결과 화면 입력 — 세션에 쌓인 항목별 판정 목록.
     * S-03(고객 화면)과 S-05(판매자 화면)는 다른 기기·다른 탭이라 화면이 메모리에 들고
     * 갈 수 없다. 새로고침에도 살아남아야 한다.
     *
     * 항목별 signal 은 싣지 않는다 — 게이트 판정은 /judge 의 signal 이 단독 소유한다(P1).
     * grade → 색 매핑은 표시 관례이며 판정이 아니다.
     */
    @PreAuthorize("@accessGuard.can('session:judgment:read', #sid)")
    @GetMapping("/{sid}/judgments")
    public ApiResponse<JudgmentsResponse> judgments(@PathVariable String sid) {
        return ApiResponse.ok(JudgmentsResponse.of(sessionService.get(sid)));
    }

    @PreAuthorize("@accessGuard.can('session:interview', #sid)")
    @PostMapping("/{sid}/questions/next")
    public ApiResponse<NextQuestionResponse> nextQuestion(@PathVariable String sid) {
        // 질문 문면은 ai-service /internal/question 이 만든다 (F-INT-002, #60·#64).
        // 진행 상태(index/total/done)는 서버가 준다 — 화면이 '추출된 항목 수'로 분모를
        // 보완하면 서버가 물어볼 항목 수와 어긋나 조용히 틀린 진행률이 나온다.
        //
        // TODO(강희진): risk_item 은 아직 목이다 — 추출(F-EXT-002)이 서버에 붙으면 세션에
        //   쌓인 항목으로 교체한다. 지금은 MockData.RISK_ITEMS 를 순서대로 물어본다.
        var session = sessionService.get(sid);
        var items = MockData.RISK_ITEMS;
        int answered = session.judgments().size();
        if (answered >= items.size()) {
            return ApiResponse.ok(NextQuestionResponse.done(items.size()));
        }
        var next = items.get(answered);
        // ❗면담 맥락을 실어 보낸다 (F-INT-002). 지금까지 질문 생성이 받는 것은 항목과
        // 상품 유형뿐이라 **이 고객이 취약한지, 방금 무엇을 틀렸는지 모른 채** 매번 첫
        // 질문처럼 만들었다. 되말하기가 재려는 것이 "이 사람이 이 문장을 만들어 낼 수
        // 있었나" 인데, 같은 문장이라도 요구되는 난이도가 사람마다 다르다.
        //
        // 지금 물으려는 항목(next)의 앞선 판정은 뺀다 — 자기 직전 등급이 맥락으로 가면
        // 그것이 질문에 실려 고객이 자기 점수를 알게 된다.
        var generated = aiServiceClient.question(next, List.of(), productTypeOf(session),
                "initial", sessionService.interviewContext(session, next.itemId()));
        String question = generated.question();
        // 보여준 질문을 남긴다 — 채점이 같은 문면을 써야 한다. ai-service 가 매번 생성하므로
        // 저장하지 않으면 submitAnswer 가 재현할 방법이 없다.
        // 출처를 **여기서** 정한다 — 질문을 만든 자리가 그것을 아는 유일한 곳이다.
        sessionService.recordAskedQuestion(sid, next.itemId(), question,
                generated.fallbackUsed()
                        ? EvidenceRecorder.QuestionSource.TEMPLATE_FALLBACK
                        : EvidenceRecorder.QuestionSource.DISPLAYED);
        return ApiResponse.ok(NextQuestionResponse.of(
                next.itemId(),
                question,
                answered + 1, items.size()));
    }

    @PreAuthorize("@accessGuard.can('session:answer', #sid)")
    @PostMapping("/{sid}/answers")
    public ApiResponse<JudgmentView> submitAnswer(@PathVariable String sid,
                                                  @Valid @RequestBody AnswerRequest body) {
        // 흐름(강희진): PiiGateway.mask(text) → ai-service /internal/score → Judgment (F-SCR-001).
        // 마스킹은 AiServiceClient 경계 안에서 강제된다(원문 유출 경로 없음, P3).
        // P1: 이 응답은 '측정'이며 게이트 판정이 아니다.
        //
        // risk_item 은 아직 목이다 — 추출(F-EXT-002)이 붙으면 세션에 쌓인 항목으로 교체한다.
        Session session = sessionService.get(sid);
        RiskItem item = riskItemOf(body.itemId());
        // 한 번 구해서 채점과 기록에 같이 쓴다 — 두 번 구하면 폴백에서 갈린다(#137 리뷰).
        // 문면과 출처를 한 값으로 묶은 것도 같은 이유다: 둘을 따로 구하면 한쪽만 폴백이 된다.
        AskedQuestion asked = askedQuestionFor(session, item);
        var scored = aiServiceClient.score(
                item.itemId(), asked.text(), body.text(), item, productTypeOf(session));
        // 마스킹본을 함께 넘겨 세션에 남긴다 — F-DET-002 가 세션 전체 발화를 입력으로 받는다.
        // 화면에는 JudgmentView 로 낸다 — misconceptionType 이 신호 그 자체라 판매자에게
        // 안 보낸다 (#144). 도메인 판정은 그대로 기록·재설명 경로로 간다.
        // ❗inputMeta 를 여기서 버리지 않는다 (이슈 #325). 화면이 매 답변마다 보내는데
        // 서버가 역직렬화하고 버리고 있었다 — 붙여넣기로 채운 되말하기는 발화 내용만
        // 보면 완벽한 U1 이라, 이 값이 없으면 그 행동을 구분할 방법이 아예 없다.
        return ApiResponse.ok(JudgmentView.of(sessionService.recordJudgment(
                sid, scored.judgment(), scored.maskedAnswer(), asked.text(), asked.source(),
                body.domainInputMeta())));
    }

    /**
     * 판정 직전 1회, 적합성 모순을 판정한다 (F-DET-002, 이슈 #65).
     *
     * 여기서 부르는 이유: 모순 판정은 항목 단위가 아니라 설문 + 세션 전체 발화가 입력이라
     * (suitability_mismatch.schema.json) 답변이 다 모인 뒤여야 한다. 이미 판정된 세션은
     * 다시 부르지 않는다 — /judge 는 멱등이고, 재호출하면 같은 입력에 다른 답이 나올 수 있다.
     *
     * ❗ai-service 호출이 실패하면 502 로 올리지 않고 UNKNOWN 으로 적는다. 모순 판정이
     * 안 됐다고 게이트 판정 자체를 막으면 판매가 멈추는데, 그건 이 실패에 비례하지 않는다.
     * 대신 UNKNOWN 은 R-02b 로 황색이 되므로 "확인 못 했다"가 통과로 새지 않는다 —
     * 실패를 은폐하지 않으면서(E-EXT-03) 흐름은 유지하는 자리다.
     */
    private void detectSuitabilityMismatch(String sid) {
        Session session = sessionService.get(sid);
        if (!session.suitabilityNotEvaluated()) {
            return;
        }
        SuitabilityMismatch mismatch;
        try {
            mismatch = aiServiceClient.detectMismatch(
                    sid, session.surveyResult(), session.maskedUtterances(),
                    session.surveySchemaVersion());
        } catch (AiServiceException e) {
            log.warn("적합성 모순 판정 실패 — UNKNOWN 으로 기록한다 (session={})", sid, e);
            // 근거가 없는 이유를 사유로 남긴다 — 기록에서 "근거가 비었다" 와 "못 받았다" 가
            // 같아 보이면 안 된다(E-EXT-03 과 같은 결). #169
            mismatch = SuitabilityMismatch.unknown(
                    "ai-service /internal/mismatch 호출 실패 — 판정하지 못했다");
        }
        sessionService.recordSuitability(sid, mismatch);
    }

    /**
     * 채점에 실을 질문 문면. submitAnswer 가 ai-service /internal/score 에 넘기는 question 이다.
     *
     * ❗nextQuestion 은 이제 ai-service 가 생성한 질문을 화면에 보여주지만(F-INT-002 배선),
     * submitAnswer 는 여전히 이 목 문면을 채점 질문으로 쓴다 — 세션이 '방금 무슨 질문을
     * 물었는지'를 보관하지 않아서(MVP 는 항목당 질문 1개, 세션 상태를 늘리지 않는다) 재현할
     * 수 없기 때문이다. 그래서 화면에 보인 질문과 채점된 질문이 갈릴 수 있다.
     * TODO(강희진): 세션이 '물은 질문'을 저장하면(F-EXT-002 항목 세션 적재와 함께) submitAnswer
     *   도 그 질문을 채점에 실어 이 목을 없앤다. 그전까지는 이 문면이 채점 기준 질문이다.
     */
    private static String questionFor(RiskItem item) {
        return "이 상품에서 '" + item.name() + "'에 대해 본인 말씀으로 설명해 주시겠어요?";
    }

    /**
     * 채점에 넘길 질문 — <b>고객이 실제로 본 것</b>을 쓴다 (이슈 #120).
     *
     * <p>전에는 여기서 {@link #questionFor} 로 목 문면을 새로 만들었다. 그런데 화면에 나간
     * 질문은 ai-service 가 생성한 것이라 <b>둘이 다르다</b> — 고객은 Q_ai 에 답했는데 채점은
     * Q_mock 맥락으로 돈다. 루브릭 기반이라 명백한 오해(U4)는 그대로 잡히고, 어긋나는 것은
     * <b>경계 사례의 채점</b>이다.
     *
     * <p>근거(evidence)는 오염되지 않는다 — {@code Evidence(utteranceQuote, rubricClause)} 에
     * 질문이 들어가지 않고 인용은 고객 답변에서 잘라낸 것이라 그대로 유효하다(#133 리뷰에서
     * 확인). 어긋난 것은 근거가 아니라 <b>판정의 맥락</b>이다.
     *
     * <p>저장된 질문이 없으면 목 문면으로 떨어진다 — 화면을 거치지 않고 {@code /answers} 를
     * 직접 부른 경우(테스트·직접 호출)다. 그때는 채점을 막는 것보다 진행시키는 편이 낫다:
     * 질문 맥락이 없다고 답변을 버리면 세션 데이터가 사라진다(명세 10절).
     * <b>다만 막지 않는 것과 남기지 않는 것은 다르다</b> — 이제 그 사실이 로그가 아니라
     * 불변 기록에 {@code SERVER_FALLBACK} 으로 남는다(#136 3항). 로그는 운영 중 빈도를 보는
     * 용도로 남겨 둔다 — 로그는 지워지고 감사는 기록만 본다.
     */
    private AskedQuestion askedQuestionFor(Session session, RiskItem item) {
        String asked = session.askedQuestion(item.itemId());
        if (asked != null) {
            // ❗**출처를 여기서 유도하지 않는다** (이슈 #274). 저장된 값을 그대로 쓴다 —
            // 질문을 만든 자리가 자기 출처를 아는 유일한 곳이고, 여기서 되유도하면 규칙과
            // 실제 경로가 갈린다. 실제로 갈렸다: 재검증 질문이 DISPLAYED 로 유도되고 있었다.
            return new AskedQuestion(asked, session.askedQuestionSource(item.itemId()));
        }
        log.warn("표시 질문이 없어 목 문면으로 채점한다 — /questions/next 를 거치지 않았다 "
                + "(session={} item={}). 기록에는 SERVER_FALLBACK 으로 남는다.",
                session.id(), item.itemId());
        return new AskedQuestion(questionFor(item), EvidenceRecorder.QuestionSource.SERVER_FALLBACK);
    }

    /**
     * 채점에 넘긴 질문 문면과 그 출처. <b>한 값으로 묶어 둔다</b> — 따로 구하면 문면은 폴백인데
     * 출처는 정상으로 남는 경로가 생기고, 그건 아예 안 남기는 것보다 나쁘다(#137 리뷰와 같은 모양).
     */
    private record AskedQuestion(String text, EvidenceRecorder.QuestionSource source) {
    }

    /**
     * 목(MockData)에서 risk_item 을 찾는다. 목록에 없으면 404(NoSuchElementException)로 드러낸다 —
     * submitAnswer·reExplain 이 같은 규약을 쓰도록 한 곳으로 모은다.
     * TODO(강희진): 추출(F-EXT-002)이 붙으면 세션에 쌓인 항목에서 찾는다.
     */
    private static RiskItem riskItemOf(String itemId) {
        return MockData.RISK_ITEMS.stream()
                .filter(r -> r.itemId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "항목을 찾을 수 없다: " + itemId));
    }

    /**
     * 세션의 상품에서 상품유형을 끌어온다. **하드코딩하면 안 되는 값이다.**
     *
     * product_type은 ai-service에서 그냥 흘러가는 값이 아니라 오해 유형 필터의 입력이다
     * (misconception.applies_to). PR #57이 M02(예금자보호 오해)를 products:[ELS]로 좁힌 이유가
     * 변액에서의 오판이었는데 — 변액은 최저사망지급금·특약에 한하여 부분 보호라(#53)
     * "예금자보호 되는 줄 알았어요"가 부분적으로 참이다 — 호출부가 변액 세션에도 "ELS"를
     * 보내면 라이브러리에서 닫은 구멍이 배선에서 다시 열린다. 에러도 로그도 없이 판정만 틀린다.
     *
     * TODO(강희진): 추출(F-EXT-002)이 붙으면 세션 필드로 옮긴다. 지금은 MockData의 상품 목록에서
     *   productId로 찾는다 — 목록에 없으면 404(NoSuchElementException)로 드러낸다. 기본값을 두면
     *   위 오판이 조용히 되살아나므로 폴백을 만들지 않는다.
     */
    private static String productTypeOf(Session session) {
        return MockData.PRODUCTS.stream()
                .filter(p -> p.productId().equals(session.productId()))
                .findFirst()
                .map(ProductSummary::productType)
                .orElseThrow(() -> new NoSuchElementException(
                        "상품유형을 알 수 없다(상품 목록에 없음): " + session.productId()));
    }

    @PreAuthorize("@accessGuard.can('session:interview', #sid)")
    @PostMapping("/{sid}/re-explain")
    public ApiResponse<SessionService.ReExplanation> reExplain(
            @PathVariable String sid, @Valid @RequestBody ReExplainRequest body) {
        // F-INT-004: 이해 부족 항목 재설명 → 이후 같은 항목 재답변이 재검증이 된다.
        // risk_item 은 목(MockData)에서 찾아 넘긴다 — 서비스가 ai-service /internal/reexplain
        // 에 실어 눈높이 재설명을 생성한다. 적격성(대상 아님·상한 도달) 판단은 서비스가 한다.
        // 재검증 질문도 ai-service 가 만든다 — 고정 문항이면 사전에 확보돼 게이트가 뚫린다
        // (기획서 7-4 1단계). 상품 유형은 여기서 넘긴다 — core 가 MockData 를 모르게.
        return ApiResponse.ok(sessionService.reExplain(
                sid, body.itemId(), riskItemOf(body.itemId()),
                productTypeOf(sessionService.get(sid))));
    }

    @PreAuthorize("@accessGuard.can('session:interview', #sid)")
    @PostMapping("/{sid}/abort")
    public ApiResponse<SessionResponse> abort(@PathVariable String sid) {
        return ApiResponse.ok(SessionResponse.of(sessionService.abort(sid)));
    }

    /**
     * 역사 시뮬레이션 3열 (F-SIM-001 · 이슈 #45 · #54 ④).
     *
     * <p><b>목을 걷었다.</b> 목은 기획서 7-2 표의 수치를 손으로 옮겨 둔 것이었는데, 그 값이
     * 실제로 {@link SimulationScenarios} 가 내는 값과 같다(최악 상환비율 0.5066 · 비중
     * 0.857·0.029·0.015). 즉 <b>답은 맞았고 근거가 코드에 없었다</b> — 시계열을 바꿔도 표가
     * 안 움직이므로 P2 재현성의 근거로는 쓸 수 없는 상태였다. 이제 {@code data/timeseries/}
     * 스냅샷에서 나오고, 스냅샷 값도 {@code VERSION} 에서 읽는다.
     *
     * <p>금액은 화면 슬라이더가 정한다. 서버 기본값을 두지 않는 이유는 계약이 적어 뒀다 —
     * 기본값이 있으면 프론트 버그가 조용히 그럴듯한 숫자로 덮인다.
     */
    @PreAuthorize("@accessGuard.can('session:simulate', #sid)")
    @PostMapping("/{sid}/simulate")
    public ApiResponse<SimulationScenarios.SimulationView> simulate(
            @PathVariable String sid, @Valid @RequestBody SimulateRequest body) {
        // 없는 세션이면 404. 시뮬레이션 자체는 (금액, 조건, 지수 스냅샷)의 순수 함수라
        // 세션을 안 읽어도 답이 나오지만(P2), 그러면 없는 세션에 200 이 나가고
        // AuditInterceptor 가 **그 세션에 대한 감사 항목을 만든다** — 세션 기록과 대응하지
        // 않는 로그가 체인에 남는다(#214 · #222). 다른 세션 하위 경로와 같은 규약이다.
        sessionService.get(sid);
        return ApiResponse.ok(simulationScenarios.view(body.amount()));
    }

    /**
     * 신호등 미리보기 — 계산만 하고 기록하지 않는다. GET 이므로 부수효과가 없다.
     *
     * 기획서 7-2 [기능 1] "황색 판정 → 재설명 → 재검증 → 녹색 통과"를 성립시키는 경로다.
     * 판매자가 황색을 보려고 /judge 를 부르면 JUDGED 로 전이되고, 거기서 RE_EXPLAIN 으로
     * 갈 수 없어 재설명 흐름 자체가 막힌다.
     */
    @PreAuthorize("@accessGuard.can('session:judgment:read', #sid)")
    @GetMapping("/{sid}/gate-preview")
    public ApiResponse<SessionService.GatePreview> gatePreview(@PathVariable String sid) {
        return ApiResponse.ok(sessionService.previewGate(sid));
    }

    @PreAuthorize("@accessGuard.can('session:judge', #sid)")
    @PostMapping("/{sid}/judge")
    public ApiResponse<GateResult> judge(@PathVariable String sid) {
        detectSuitabilityMismatch(sid);
        // 세션에 쌓인 판정 + 모순 + 재검증 횟수 → GateEngine (F-GTE-001).
        // 감사 기준점을 찍는다 — 되돌릴 수 없다. 신호만 보려면 /gate-preview 를 쓴다.
        return ApiResponse.ok(sessionService.judge(sid));
    }

    /**
     * 리포트 발행. 기록에서 이력을 조립하고 발행 사실을 체인에 남긴다(F-GTE-004).
     *
     * 발행을 GET 에서 분리한 이유는 감사다 — GET 이 발행까지 하면 로그에서 "읽었다"와
     * "발행했다"가 구별되지 않고, MGR·COMPL 이 남의 세션을 열람하는 것만으로 발행 기록이
     * 생긴다. 프리페치·재시도·중복 클릭도 상태를 바꾼다.
     *
     * ❗<b>메서드를 가르는 것만으로는 부족하다.</b> AuditInterceptor 는 @PreAuthorize 문면에서
     * action 을 읽으므로(#76), 두 엔드포인트가 같은 action 이면 감사 로그가 둘을 못 가른다 —
     * resource(URI)도 같고 HTTP 메서드는 담기지 않는다. 분쟁 시점에 답해야 하는 것은
     * "언제 누가 교부했는가" 이지 "누가 열어봤는가" 가 아니다.
     *
     * report:issue 는 SELLER own_session 만이다(#95). MGR·COMPL 은 감독을 위해 남의 세션을
     * *읽는* 역할이고 교부는 그 세션을 진행한 창구 직원이 한다 — 조회와 같은 action 이면
     * COMPL 이 org 전체 세션에 대해 발행할 수 있다.
     */
    @PreAuthorize("@accessGuard.can('report:issue', #sid)")
    @PostMapping("/{sid}/report")
    public ApiResponse<Map<String, Object>> issueReport(@PathVariable String sid) {
        sessionService.get(sid);   // 없는 세션이면 404
        return ApiResponse.ok(reportPayload(
                reportService.issue(sid, Instant.now().truncatedTo(ChronoUnit.MILLIS))));
    }

    /**
     * 발행된 리포트 조회. 상태를 바꾸지 않는다.
     * 발행한 적 없으면 404 — "아직 교부하지 않았다"와 "교부했다"는 감사에서 구별돼야 한다.
     *
     * <p>❗<b>404 두 가지를 같은 코드로 낸다</b> — 세션이 없는 것과 아직 발행하지 않은 것.
     * 계약이 그렇게 적어 뒀고(*"후자는 오류가 아니라 상태다"*), 화면은 세션을 한 번 더 조회해
     * 다음 행동을 가른다(S-07). 여기서 코드를 가르지 않는 이유는 <b>범위 밖 세션의 존재
     * 여부를 알려주지 않기 위해서</b>다 — `REPORT_NOT_ISSUED` 같은 코드를 따로 내면 그 코드가
     * 나온다는 사실만으로 "그 세션은 있다"가 새어 나간다. 대신 사유를 message 로 가른다.
     */
    @PreAuthorize("@accessGuard.can('report:read', #sid)")
    @GetMapping("/{sid}/report")
    public ApiResponse<Map<String, Object>> report(@PathVariable String sid) {
        sessionService.get(sid);   // 없는 세션이면 404
        return ApiResponse.ok(reportPayload(reportService.latest(sid).orElseThrow(
                () -> new NoSuchElementException("아직 발행된 리포트가 없다: " + sid))));
    }

    /**
     * 발행된 리포트를 PDF 로 본다 (F-GTE-004 2번 · 이슈 #233).
     *
     * <p><b>인라인이다</b> — 브라우저가 뷰어로 연다. 내려받기는 {@link #downloadReportPdf}
     * 이고, <b>바이트는 같다.</b> 두 엔드포인트를 가르는 것은 {@code Content-Disposition}
     * 하나뿐이라, 미리 본 문서와 받은 문서가 다를 수 없다.
     *
     * <p>❗<b>공통 봉투({@code ApiResponse})에 담지 않는다.</b> PDF 는 바이트고 봉투는 JSON
     * 이다. base64 로 접어 넣으면 브라우저가 뷰어로 못 열고 화면이 다시 풀어야 한다 —
     * 계약이 {@code previewUrl} 을 <i>"브라우저 미리보기(PDF 인라인)"</i> 로 적은 것과
     * 어긋난다. <b>오류 경로는 봉투 그대로다</b>(403·404 는 {@code GlobalExceptionHandler}
     * 가 낸다) — 규약이 깨지는 것은 성공 응답의 본문뿐이고, 그것이 이 경계의 목적이다.
     *
     * <p>권한은 {@code report:read} 다. <b>발행({@code report:issue})과 가르는 이유가
     * 그대로 적용된다</b> — 이건 조회이고 상태를 안 바꾼다. 감사 로그에서도 "열어봤다" 로
     * 남아야 하지 "교부했다" 로 남으면 안 된다(#94 리뷰).
     */
    @PreAuthorize("@accessGuard.can('report:read', #sid)")
    @GetMapping("/{sid}/report/preview")
    public ResponseEntity<byte[]> previewReportPdf(@PathVariable String sid) {
        return pdfResponse(sid, false);
    }

    /** 발행된 리포트를 내려받는다. {@link #previewReportPdf} 와 <b>같은 바이트</b>다. */
    @PreAuthorize("@accessGuard.can('report:read', #sid)")
    @GetMapping("/{sid}/report/download")
    public ResponseEntity<byte[]> downloadReportPdf(@PathVariable String sid) {
        return pdfResponse(sid, true);
    }

    /**
     * 두 경로의 공통 본체. 발행 안 된 세션은 {@code ReportService.pdf} 가
     * {@link NoSuchElementException} 을 던져 <b>GET /report 와 같은 404</b> 가 된다 —
     * 그쪽 javadoc 이 적은 "세션이 없다 · 아직 발행 안 했다" 를 한 코드로 내는 이유가
     * 여기도 그대로다(범위 밖 세션의 존재 여부를 알려주지 않는다).
     *
     * <p>파일명에 {@code reportId} 를 쓴다. 세션 ID 를 쓰면 <b>같은 세션의 서로 다른 발행이
     * 같은 이름</b>이 되어, 받은 사람의 폴더에서 두 교부본이 덮어써진다.
     */
    private ResponseEntity<byte[]> pdfResponse(String sid, boolean asAttachment) {
        sessionService.get(sid);   // 없는 세션이면 404
        ReportService.Report report = reportService.latest(sid).orElseThrow(
                () -> new NoSuchElementException("아직 발행된 리포트가 없다: " + sid));
        byte[] bytes = reportService.pdf(sid);

        ContentDisposition disposition = (asAttachment
                ? ContentDisposition.attachment()
                : ContentDisposition.inline())
                .filename("sphinx-report-" + report.reportId() + ".pdf", StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // 판정 근거와 고객 발화가 실린 문서다. 중간 캐시에 남기지 않는다
                // (web/app.conf 가 사이트 전체에 거는 것과 같은 이유, 결정 10.57).
                .cacheControl(CacheControl.noStore())
                .body(bytes);
    }

    /**
     * 계약({@code ReportResponse}) 응답.
     *
     * <p>❗<b>URL 둘이 이제 값을 갖는다</b>(이슈 #233 · PR #244). 예전에는 null 이 계약이었다 —
     * 채우면 계약이 <i>"이 URL 로 가면 문서가 있다"</i> 를 보장하는데 404 가 났기 때문이다.
     * 지금은 그 보장이 참이다: 발행된 리포트가 있어야 이 payload 자체가 만들어지고, 두 URL 은
     * 그 리포트의 PDF 를 가리킨다.
     *
     * <p>❗<b>API 기준 경로다 — 사이트 기준이 아니다.</b> 이 문자열을 그대로
     * {@code <a href>} 에 넣으면 브라우저는 <b>web 오리진</b>을 치고, 거기서 {@code /sessions/…}
     * 는 API 가 아니다. nginx 의 {@code location /} 이 {@code index.html} 로 떨어뜨려
     * <b>404 가 아니라 200 text/html</b> 이 나온다 — 미리보기는 빈 탭, 내려받기는 PDF 대신
     * HTML 파일이다. 소비자가 그 배포의 API 접두어({@code /api})를 붙여야 한다.
     *
     * <p>그럼에도 서버가 접두어를 안 붙이는 이유는 그것이 <b>프론트 배포의 값</b>이기
     * 때문이다({@code web/src/api/client.ts} 의 {@code BASE}). 서버가 지어내기 시작하면 같은
     * 값이 두 곳에 살고, 갈리는 날 링크만 조용히 깨진다. 절대 URL 은 더 나쁘다 — 배포 호스트
     * 까지 서버가 알아야 한다.
     *
     * <p>{@code generatedAt} 은 발행 기록의 시각이지 지금 시각이 아니다. 같은 내용을 다시
     * 발행하면 기존 것을 돌려주므로(멱등), 그때 이 값이 <b>안 바뀌는 것</b>이 맞다.
     */
    private static Map<String, Object> reportPayload(ReportService.Report report) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportId", report.reportId());
        out.put("sessionId", report.sessionId());
        out.put("generatedAt", report.generatedAt().toString());
        out.put("contentHash", report.contentHash());
        out.put("previewUrl", "/sessions/" + report.sessionId() + "/report/preview");
        out.put("downloadUrl", "/sessions/" + report.sessionId() + "/report/download");
        return out;
    }
}
