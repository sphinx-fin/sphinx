package com.sphinxfin.sphinx.api;

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
import com.sphinxfin.sphinx.domain.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
     * S-05 판정 결과 화면 입력 — 세션에 쌓인 항목별 판정 목록.
     * S-03(고객 화면)과 S-05(판매자 화면)는 다른 기기·다른 탭이라 화면이 메모리에 들고
     * 갈 수 없다. 새로고침에도 살아남아야 한다.
     *
     * 항목별 signal 은 싣지 않는다 — 게이트 판정은 /judge 의 signal 이 단독 소유한다(P1).
     * grade → 색 매핑은 표시 관례이며 판정이 아니다.
     */
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
        //   지금은 목이지만 **세션을 실제로 조회한 뒤** 낸다 — 그래야 없는 세션과 남의 세션이
        //   여기서 걸린다. 목을 그냥 돌려주면 @PreAuthorize 만 남고 404 가 사라진다.
        log.debug("세션 {} (상품 {}) 의 이해항목을 낸다", session.id(), session.productId());
        return ApiResponse.ok(new RiskItemsResponse(MockData.RISK_ITEMS));
    }

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
        // askedTypes=[] — MVP 는 항목당 질문 1개라 항목별 사용 유형 추적을 두지 않는다.
        String question = aiServiceClient
                .question(next, List.of(), productTypeOf(session))
                .question();
        // 보여준 질문을 남긴다 — 채점이 같은 문면을 써야 한다. ai-service 가 매번 생성하므로
        // 저장하지 않으면 submitAnswer 가 재현할 방법이 없다.
        sessionService.recordAskedQuestion(sid, next.itemId(), question);
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
        return ApiResponse.ok(JudgmentView.of(sessionService.recordJudgment(
                sid, scored.judgment(), scored.maskedAnswer(), asked.text(), asked.source())));
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
        SuitabilityStatus status;
        try {
            status = aiServiceClient.detectMismatch(
                    sid, session.surveyResult(), session.maskedUtterances(),
                    session.surveySchemaVersion());
        } catch (AiServiceException e) {
            log.warn("적합성 모순 판정 실패 — UNKNOWN 으로 기록한다 (session={})", sid, e);
            status = SuitabilityStatus.UNKNOWN;
        }
        sessionService.recordSuitability(sid, status);
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
            return new AskedQuestion(asked, EvidenceRecorder.QuestionSource.DISPLAYED);
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
        return ApiResponse.ok(sessionService.reExplain(sid, body.itemId(), riskItemOf(body.itemId())));
    }

    @PreAuthorize("@accessGuard.can('session:interview', #sid)")
    @PostMapping("/{sid}/abort")
    public ApiResponse<SessionResponse> abort(@PathVariable String sid) {
        return ApiResponse.ok(SessionResponse.of(sessionService.abort(sid)));
    }

    /**
     * 봉투만 씌운다. 안의 시나리오 스키마는 F-SIM-001 소유자 몫이라 여기서 타입을 굳히지
     * 않는다(#45 에서 논의 중). 봉투는 프론트 전역 규약이라 스키마와 무관하게 지금 맞춘다.
     */
    @PostMapping("/{sid}/simulate")
    public ApiResponse<Map<String, Object>> simulate(@PathVariable String sid,
                                                     @Valid @RequestBody SimulateRequest body) {
        // TODO(정세현): SimulatorService 연결 (F-SIM-001, 결정론 P2).
        // 목이지만 **계약(SimulateApiResponse)의 필수 필드를 전부 채운다.** 빠뜨리면 S-04가
        // 백지가 되고, 특히 severity 가 없으면 카드가 최선→중간→최악 순으로 서서 기획서
        // 4절이 요구하는 것("최선만 강조하는 관행의 정반대")과 반대가 된다.
        //
        // 수치는 기획서 7-2 표 확정본이다(낙인 45%·쿠폰 연 11.00%·만기 3년). 가입금액이
        // 5,000만 원이 아니면 표 비율로 환산한다 — 목이라도 슬라이더를 움직였을 때 금액이
        // 안 바뀌면 시뮬레이터로 안 보인다.
        long amount = body.amount();
        return ApiResponse.ok(Map.of(
                "timeseriesVersion", TIMESERIES_SNAPSHOT,
                "productName", "A증권 제4181회 ELS (원금비보장형)",
                "scenarios", List.of(
                        scenario("worst", "loss", "낙인 45% 하회 후 만기 손실", amount, 0.5066, 0.029,
                                "2007-10-31", "2010-10-29", "eurostoxx50", 0.507, true),
                        scenario("mid", "early_1", "6개월 뒤 첫 조기상환", amount, 1.055, 0.857,
                                "2012-05-02", "2012-11-01", "nikkei225", 1.031, false),
                        scenario("best", "maturity", "조기상환 없이 만기 상환", amount, 1.33, 0.015,
                                "2013-01-04", "2016-01-04", "sp500", 1.142, false))));
    }

    /** data/timeseries/VERSION 의 snapshot. 화면에 표시해 P2 재현성의 근거를 보인다. */
    private static final String TIMESERIES_SNAPSHOT = "2026-08-24";

    /**
     * 계약(SimScenario)의 필수 6필드를 채운 목 시나리오.
     *
     * severity 는 화면 배치·정렬 키이지 표시 라벨이 아니다 — 기획서 7-2 표가 평가어를 버리고
     * 금액 순으로 간 이유가 스텝다운은 조기상환 시점과 무관하게 연 수익률이 같아서
     * "가장 자주 일어나는 전개(85.7%)가 금액으로는 중간"이기 때문이다. 사람에게 보일 문면은
     * name 을 쓴다.
     */
    private static Map<String, Object> scenario(String severity, String result, String name,
                                                long amount, double payoutRatio, double share,
                                                String startDate, String endDate,
                                                String worstUnderlying, double worstFinal,
                                                boolean knockedIn) {
        long payout = Math.round(amount * payoutRatio);
        return Map.of(
                "severity", severity,
                "result", result,
                "name", name,
                "payout", payout,
                "pnl", payout - amount,
                "share", share,
                "pathMeta", Map.of(
                        "startDate", startDate,
                        "endDate", endDate,
                        "underlyings", List.of("sp500", "nikkei225", "eurostoxx50"),
                        "worstUnderlying", worstUnderlying,
                        "worstFinal", worstFinal,
                        "knockedIn", knockedIn));
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
     * 계약({@code ReportResponse}) 응답. <b>URL 둘은 PDF 생성 전까지 null 이 계약이다</b> —
     * 채우면 계약이 "이 URL 로 가면 문서가 있다"를 보장하는데 404 가 나고, 스키마 검증은
     * 통과하며 화면은 링크를 그린다. 눌러야 드러나는 종류의 결함이다.
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
        out.put("previewUrl", null);
        out.put("downloadUrl", null);
        return out;
    }
}
