package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.AnswerRequest;
import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.api.dto.CreateSessionRequest;
import com.sphinxfin.sphinx.api.dto.SessionResponse;
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

    @PostMapping
    public ApiResponse<SessionResponse> create(@Valid @RequestBody CreateSessionRequest body) {
        Session session = sessionService.create(body.toCommand());
        return ApiResponse.ok(SessionResponse.of(session));
    }

    @GetMapping("/{sid}")
    public ApiResponse<SessionResponse> get(@PathVariable String sid) {
        return ApiResponse.ok(SessionResponse.of(sessionService.get(sid)));
    }

    @PostMapping("/{sid}/questions/next")
    public Map<String, String> nextQuestion(@PathVariable String sid) {
        // TODO(강희진): ai-service /internal/question 프록시 (F-INT-002, 윤지석)
        return Map.of("itemId", "ELS-PRINCIPAL-LOSS-WARNING",
                "question", "이 상품에서 원금 손실이 나는 상황을 본인 말씀으로 설명해 주시겠어요?");
    }

    @PostMapping("/{sid}/answers")
    public ApiResponse<Judgment> submitAnswer(@PathVariable String sid, @Valid @RequestBody AnswerRequest body) {
        // 흐름(강희진): PiiGateway.mask(text) → ai-service /internal/score → Judgment
        // TODO: ai-service 채점 연결(F-SCR-001, 윤지석) — 지금은 목 판정을 세션에 기록만 한다.
        // P1: 이 응답은 '측정'이며 게이트 판정이 아니다.
        Judgment measured = new Judgment(body.itemId(), Grade.U4, 0.91,
                new Judgment.Evidence("은행에서 파는 거니까 원금은 지켜지는 거죠",
                        "원금손실 조건: 낙인 하회 시 손실을 인지해야 함"),
                "원금이 보장된다고 진술하여 오해로 판정", "M01-PRINCIPAL-GUARANTEE");
        return ApiResponse.ok(sessionService.recordJudgment(sid, measured));
    }

    @PostMapping("/{sid}/simulate")
    public Map<String, Object> simulate(@PathVariable String sid,
                                        @RequestParam(defaultValue = "50000000") long amount) {
        // TODO(정세현): SimulatorService 연결 (F-SIM-001, 결정론 P2)
        return Map.of("scenarios", List.of(
                Map.of("name", "최선(6개월 조기상환)", "payout", 51_500_000, "pnl", 1_500_000),
                Map.of("name", "중간(3년 만기상환)", "payout", 59_000_000, "pnl", 9_000_000),
                Map.of("name", "최악(2008년 경로)", "payout", 32_000_000, "pnl", -18_000_000)));
    }

    @PostMapping("/{sid}/judge")
    public ApiResponse<GateResult> judge(@PathVariable String sid) {
        // 세션에 쌓인 판정 + 모순 + 재검증 횟수 → GateEngine (F-GTE-001).
        return ApiResponse.ok(sessionService.judge(sid));
    }

    @GetMapping("/{sid}/report")
    public Map<String, String> report(@PathVariable String sid) {
        // TODO(정세현): ReportService (F-GTE-004)
        return Map.of("reportId", "mock-report-001");
    }
}
