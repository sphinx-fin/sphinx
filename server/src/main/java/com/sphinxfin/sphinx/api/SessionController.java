package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.domain.*;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** 세션·인터뷰·게이트 API. 소유: 강희진 */
@RestController
@RequestMapping("/sessions")
public class SessionController {

    /** F-INT-001. 비식별 속성만 받는다 — 성명·주민번호 필드는 존재하지 않음 (P3) */
    public record CreateSession(@NotBlank String productId, @NotBlank String channel,
                                @NotBlank String ageBand, String experienceLevel,
                                String amountBand, Map<String, Object> surveyResult) {}

    @PostMapping
    public Map<String, String> create(@RequestBody CreateSession body) {
        // TODO(강희진): SessionFsm 생성
        return Map.of("sessionId", "mock-session-001", "state", "CREATED");
    }

    @PostMapping("/{sid}/questions/next")
    public Map<String, String> nextQuestion(@PathVariable String sid) {
        // TODO(강희진): ai-service /internal/question 프록시 (F-INT-002, 윤지석)
        return Map.of("itemId", "ELS-PRINCIPAL-LOSS",
                "question", "이 상품에서 원금 손실이 나는 상황을 본인 말씀으로 설명해 주시겠어요?");
    }

    /** F-INT-003: 텍스트 응답 + 입력 메타(붙여넣기·지연·수정빈도) */
    public record Answer(@NotBlank String itemId, @NotBlank String text, Map<String, Object> inputMeta) {}

    @PostMapping("/{sid}/answers")
    public Judgment submitAnswer(@PathVariable String sid, @RequestBody Answer body) {
        // 흐름(강희진): PiiGateway.mask(text) → ai-service /internal/score → Judgment 반환
        // 채점 자체는 윤지석(ai-service). P1: 이 응답은 '측정'이며 게이트 판정이 아니다.
        return new Judgment("ELS-PRINCIPAL-LOSS", Grade.U4, 0.91,
                new Judgment.Evidence("은행에서 파는 거니까 원금은 지켜지는 거죠",
                        "원금손실 조건: 낙인 하회 시 손실을 인지해야 함"),
                "원금이 보장된다고 진술하여 오해로 판정", "M01-PRINCIPAL-GUARANTEE");
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
    public GateResult judge(@PathVariable String sid) {
        // TODO(강희진): GateEngine (F-GTE-001)
        return new GateResult(Signal.RED, List.of("R-01: U4 존재 → RED"));
    }

    @GetMapping("/{sid}/report")
    public Map<String, String> report(@PathVariable String sid) {
        // TODO(정세현): ReportService (F-GTE-004)
        return Map.of("reportId", "mock-report-001");
    }
}
