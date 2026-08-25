package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.AnswerRequest;
import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.api.dto.CreateSessionRequest;
import com.sphinxfin.sphinx.api.dto.JudgmentsResponse;
import com.sphinxfin.sphinx.api.dto.NextQuestionResponse;
import com.sphinxfin.sphinx.api.dto.ReExplainRequest;
import com.sphinxfin.sphinx.api.dto.SessionResponse;
import com.sphinxfin.sphinx.core.AiServiceClient;
import com.sphinxfin.sphinx.core.Session;
import com.sphinxfin.sphinx.core.SessionService;
import com.sphinxfin.sphinx.domain.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** 세션·인터뷰·게이트 API. 소유: 강희진 */
@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final AiServiceClient aiServiceClient;

    @PostMapping
    public ApiResponse<SessionResponse> create(@Valid @RequestBody CreateSessionRequest body) {
        Session session = sessionService.create(body.toCommand());
        return ApiResponse.ok(SessionResponse.of(session));
    }

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
    @GetMapping("/{sid}/judgments")
    public ApiResponse<JudgmentsResponse> judgments(@PathVariable String sid) {
        return ApiResponse.ok(JudgmentsResponse.of(sessionService.get(sid)));
    }

    @PostMapping("/{sid}/questions/next")
    public ApiResponse<NextQuestionResponse> nextQuestion(@PathVariable String sid) {
        // TODO(강희진): ai-service /internal/question 프록시 (F-INT-002, 윤지석)
        // 진행 상태(index/total/done)는 서버가 준다 — 화면이 '추출된 항목 수'로 분모를
        // 보완하면 서버가 물어볼 항목 수와 어긋나 조용히 틀린 진행률이 나온다.
        var session = sessionService.get(sid);
        var items = MockData.RISK_ITEMS;
        int answered = session.judgments().size();
        if (answered >= items.size()) {
            return ApiResponse.ok(NextQuestionResponse.done(items.size()));
        }
        var next = items.get(answered);
        return ApiResponse.ok(NextQuestionResponse.of(
                next.itemId(),
                "이 상품에서 '" + next.name() + "'에 대해 본인 말씀으로 설명해 주시겠어요?",
                answered + 1, items.size()));
    }

    @PostMapping("/{sid}/answers")
    public ApiResponse<Judgment> submitAnswer(@PathVariable String sid, @Valid @RequestBody AnswerRequest body) {
        // 흐름(강희진): PiiGateway.mask(text) → ai-service /internal/score → Judgment (F-SCR-001).
        // 마스킹은 AiServiceClient 경계 안에서 강제된다(원문 유출 경로 없음, P3).
        // P1: 이 응답은 '측정'이며 게이트 판정이 아니다.
        //
        // risk_item·question은 아직 목이다 — 추출(F-EXT-002)이 붙기 전까지 MockData에서
        // 항목을 찾고 질문을 nextQuestion과 같은 문면으로 만든다. 추출이 붙으면 세션에
        // 쌓인 항목·질문으로 교체한다.
        RiskItem item = MockData.RISK_ITEMS.stream()
                .filter(r -> r.itemId().equals(body.itemId()))
                .findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "항목을 찾을 수 없다: " + body.itemId()));
        String question = "이 상품에서 '" + item.name() + "'에 대해 본인 말씀으로 설명해 주시겠어요?";
        Judgment measured = aiServiceClient.score(
                item.itemId(), question, body.text(), item, "ELS");
        return ApiResponse.ok(sessionService.recordJudgment(sid, measured));
    }

    @PostMapping("/{sid}/re-explain")
    public ApiResponse<SessionService.ReExplanation> reExplain(
            @PathVariable String sid, @Valid @RequestBody ReExplainRequest body) {
        // F-INT-004: 이해 부족 항목 재설명 → 이후 같은 항목 재답변이 재검증이 된다.
        return ApiResponse.ok(sessionService.reExplain(sid, body.itemId()));
    }

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
                                                     @RequestParam(defaultValue = "50000000") long amount) {
        // TODO(정세현): SimulatorService 연결 (F-SIM-001, 결정론 P2)
        return ApiResponse.ok(Map.of("scenarios", List.of(
                Map.of("name", "최선(6개월 조기상환)", "payout", 51_500_000, "pnl", 1_500_000),
                Map.of("name", "중간(3년 만기상환)", "payout", 59_000_000, "pnl", 9_000_000),
                Map.of("name", "최악(2008년 경로)", "payout", 32_000_000, "pnl", -18_000_000))));
    }

    /**
     * 신호등 미리보기 — 계산만 하고 기록하지 않는다. GET 이므로 부수효과가 없다.
     *
     * 기획서 7-2 [기능 1] "황색 판정 → 재설명 → 재검증 → 녹색 통과"를 성립시키는 경로다.
     * 판매자가 황색을 보려고 /judge 를 부르면 JUDGED 로 전이되고, 거기서 RE_EXPLAIN 으로
     * 갈 수 없어 재설명 흐름 자체가 막힌다.
     */
    @GetMapping("/{sid}/gate-preview")
    public ApiResponse<SessionService.GatePreview> gatePreview(@PathVariable String sid) {
        return ApiResponse.ok(sessionService.previewGate(sid));
    }

    @PostMapping("/{sid}/judge")
    public ApiResponse<GateResult> judge(@PathVariable String sid) {
        // 세션에 쌓인 판정 + 모순 + 재검증 횟수 → GateEngine (F-GTE-001).
        // 감사 기준점을 찍는다 — 되돌릴 수 없다. 신호만 보려면 /gate-preview 를 쓴다.
        return ApiResponse.ok(sessionService.judge(sid));
    }

    /** 봉투만 씌운다. 리포트 응답 스키마는 F-GTE-004 소유자 몫이다(#46 에서 논의 중). */
    @GetMapping("/{sid}/report")
    public ApiResponse<Map<String, String>> report(@PathVariable String sid) {
        // TODO(정세현): ReportService (F-GTE-004)
        return ApiResponse.ok(Map.of("reportId", "mock-report-001"));
    }
}
