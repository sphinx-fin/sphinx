package com.sphinxfin.sphinx.core.gate;

import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-GTE-001 게이트 판정.
 * 실제 resources/gate_rules.yaml을 로드하는 GateEngine()으로 검증한다 —
 * 엔진 로직과 룰 파일을 함께 회귀 검증하기 위함.
 *
 * 명세: U4 1건 → RED / U2·U3만 → YELLOW / 전부 U1 → GREEN / 모순 → RED / 재검증 2회 실패 → RED
 */
@DisplayName("F-GTE-001 게이트 룰엔진")
class GateEngineTest {

    private final GateEngine engine = new GateEngine();

    @Test
    @DisplayName("모순 확인 → RED (R-02)")
    void mismatchConfirmedIsRed() {
        GateResult r = engine.judge(List.of(judgment(Grade.U1)), true, false, 0);
        assertThat(r.signal()).isEqualTo(Signal.RED);
        assertThat(r.ruleTrace()).contains("R-02");
    }

    @Test
    @DisplayName("❗모순을 판정하지 못함 → YELLOW (R-02b, 결정 10.9) — GREEN 으로 새지 않는다")
    void mismatchUnknownIsYellowNotGreen() {
        // 계약이 스스로 경고한 지점이다 — status=insufficient_input 이면 mismatch 는 항상
        // false 인데, 그 false 를 적합으로 읽으면 판정 실패가 통과가 된다.
        GateResult r = engine.judge(List.of(judgment(Grade.U1)), false, true, 0);
        assertThat(r.signal())
                .as("판정하지 못한 것을 통과로 읽으면 안 된다")
                .isEqualTo(Signal.YELLOW);
        assertThat(r.ruleTrace()).contains("R-02b");
    }

    @Test
    @DisplayName("모순 확인이 판정 못 함보다 먼저 잡힌다 — 확인된 RED > 확인 못 한 YELLOW")
    void confirmedMismatchWinsOverUnknown() {
        GateResult r = engine.judge(List.of(judgment(Grade.U1)), true, true, 0);
        assertThat(r.signal()).isEqualTo(Signal.RED);
    }

    @Test
    @DisplayName("모순 없음이 확인되면 통과 — 전 항목 U1 이면 GREEN")
    void noMismatchIsGreen() {
        GateResult r = engine.judge(List.of(judgment(Grade.U1)), false, false, 0);
        assertThat(r.signal()).isEqualTo(Signal.GREEN);
    }

    @Test
    @DisplayName("R-05 경계 — 임계값과 정확히 같으면 발동하지 않는다 (BigDecimal 전환 후에도)")
    void confidenceExactlyAtThresholdDoesNotFire() {
        // gate_rules.yaml 의 anyConfidenceBelow 0.7 은 "미만"이다. double 시절에도 0.7 < 0.7 은
        // 거짓이었고 BigDecimal 로 바꾼 뒤에도 같아야 한다 — 임계값을 문자열에서 만들지 않고
        // Double.parseDouble 을 거치면 이 경계가 표현 오차에 걸릴 수 있다.
        GateResult atThreshold = engine.judge(List.of(judgment(Grade.U1, "0.7")), false, false, 0);
        assertThat(atThreshold.signal()).isEqualTo(Signal.GREEN);

        GateResult justBelow = engine.judge(List.of(judgment(Grade.U1, "0.69")), false, false, 0);
        assertThat(justBelow.signal()).isEqualTo(Signal.YELLOW);
    }

    @Test
    @DisplayName("자릿수만 다른 같은 신뢰도는 같게 판정한다 — BigDecimal.equals 는 스케일을 본다")
    void sameConfidenceDifferentScaleJudgesSame() {
        // ai-service 가 0.70 을 보내도 0.7 과 같아야 한다. equals 로 비교하면 갈린다.
        assertThat(engine.judge(List.of(judgment(Grade.U1, "0.70")), false, false, 0).signal())
                .isEqualTo(engine.judge(List.of(judgment(Grade.U1, "0.7")), false, false, 0).signal());
    }

    private static Judgment judgment(Grade grade) {
        return judgment(grade, "0.9");
    }

    /** 신뢰도는 문자열로 받는다 — double 을 거치면 BigDecimal 로 바꾼 의미가 없어진다. */
    private static Judgment judgment(Grade grade, String confidence) {
        return new Judgment(
                "ITEM-" + grade,
                grade,
                new BigDecimal(confidence),
                new Judgment.Evidence("고객 발화 인용", "루브릭 조항"),
                "판정 사유",
                null);
    }

    private GateResult judge(List<Grade> grades) {
        return engine.judge(grades.stream().map(GateEngineTest::judgment).toList(), false, false, 0);
    }

    // ── 명세 5케이스 ──────────────────────────────────────────────────

    @Test
    @DisplayName("R-01: U4(오해)가 1건이라도 있으면 → RED (틀리게 아는 게 가장 위험)")
    void anyU4_isRed() {
        GateResult r = judge(List.of(Grade.U1, Grade.U4, Grade.U1));
        assertThat(r.signal()).isEqualTo(Signal.RED);
        assertThat(r.ruleTrace()).containsExactly("R-01");
    }

    @Test
    @DisplayName("R-04: U2(부분이해)·U3(미이해)만 있으면 → YELLOW (재설명 트리거)")
    void onlyU2U3_isYellow() {
        GateResult r = judge(List.of(Grade.U1, Grade.U2, Grade.U3));
        assertThat(r.signal()).isEqualTo(Signal.YELLOW);
        assertThat(r.ruleTrace()).containsExactly("R-04");
    }

    @Test
    @DisplayName("R-06: 전부 U1(이해) + 신뢰도 충분이면 → GREEN (판매 진행)")
    void allU1_isGreen() {
        GateResult r = judge(List.of(Grade.U1, Grade.U1));
        assertThat(r.signal()).isEqualTo(Signal.GREEN);
        assertThat(r.ruleTrace()).containsExactly("R-06");
    }

    @Test
    @DisplayName("R-05: 전부 U1이어도 신뢰도 낮으면 → YELLOW (통과 안 시킴)")
    void lowConfidence_isYellow() {
        GateResult r = engine.judge(
                List.of(judgment(Grade.U1, "0.6"), judgment(Grade.U1, "0.9")), false, false, 0);
        assertThat(r.signal()).isEqualTo(Signal.YELLOW);
        assertThat(r.ruleTrace()).containsExactly("R-05");
    }

    @Test
    @DisplayName("트레이스: 같은 신호(YELLOW)를 낸 발화 룰을 전부 기록 (부분이해 + 저신뢰 → R-04·R-05)")
    void multipleRulesSameSignal_allTraced() {
        // U2(R-04 YELLOW) + 저신뢰(R-05 YELLOW) 동시 → 신호는 YELLOW, 트레이스엔 둘 다
        GateResult r = engine.judge(List.of(judgment(Grade.U2, "0.6")), false, false, 0);
        assertThat(r.signal()).isEqualTo(Signal.YELLOW);
        assertThat(r.ruleTrace()).containsExactly("R-04", "R-05");
    }

    @Test
    @DisplayName("U4 예외: 신뢰도 낮아도 U4는 RED (R-01 우선)")
    void lowConfidenceU4_stillRed() {
        GateResult r = engine.judge(List.of(judgment(Grade.U4, "0.5")), false, false, 0);
        assertThat(r.signal()).isEqualTo(Signal.RED);
        assertThat(r.ruleTrace()).containsExactly("R-01");
    }

    @Test
    @DisplayName("R-02: 적합성 설문 vs 발화 모순이면 → RED (등급 무관)")
    void suitabilityMismatch_isRed() {
        GateResult r = engine.judge(List.of(judgment(Grade.U1)), true, false, 0);
        assertThat(r.signal()).isEqualTo(Signal.RED);
        assertThat(r.ruleTrace()).containsExactly("R-02");
    }

    @Test
    @DisplayName("R-03: 재검증 2회 실패면 → RED (항목당 최대 2회, 끝내 이해 못 하면 보류)")
    void reverifyFailedTwice_isRed() {
        GateResult r = engine.judge(List.of(judgment(Grade.U1)), false, false, 2);
        assertThat(r.signal()).isEqualTo(Signal.RED);
        assertThat(r.ruleTrace()).containsExactly("R-03");
    }

    // ── 우선순위·엣지케이스 ────────────────────────────────────────────

    @Test
    @DisplayName("우선순위: U4(RED)와 U2(YELLOW)가 동시면 → 파일 순서상 RED가 먼저 발화")
    void red_takesPriorityOverYellow() {
        GateResult r = judge(List.of(Grade.U2, Grade.U4));
        assertThat(r.signal()).isEqualTo(Signal.RED);
        assertThat(r.ruleTrace()).containsExactly("R-01");
    }

    @Test
    @DisplayName("fail-closed: 판정이 하나도 없으면 → RED (판매 게이트는 보수적으로)")
    void emptyJudgments_failsClosedToRed() {
        GateResult r = engine.judge(List.of(), false, false, 0);
        assertThat(r.signal()).isEqualTo(Signal.RED);
        assertThat(r.ruleTrace()).containsExactly(GateEngine.DEFAULT_TRACE);
    }

    @Test
    @DisplayName("fail-fast: 알 수 없는 룰 문법은 로드 시점에 예외 (오타를 런타임까지 숨기지 않음)")
    void unknownRuleCondition_failsFastAtLoad() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> GateEngine.compile("nonsense >> 3"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
