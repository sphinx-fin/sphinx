package com.sphinxfin.sphinx.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Judgment P4 강제")
class JudgmentTest {

    @Test
    @DisplayName("근거 있으면 생성됨")
    void withEvidence_ok() {
        assertThatCode(() -> new Judgment("A", Grade.U4, new BigDecimal("0.9"),
                new Judgment.Evidence("발화 인용", "루브릭 조항"), "사유", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("evidence가 null이면 예외 (P4)")
    void nullEvidence_rejected() {
        assertThatThrownBy(() -> new Judgment("A", Grade.U4, new BigDecimal("0.9"), null, "사유", null))
                .isInstanceOf(EvidenceRequiredException.class);
    }

    @Test
    @DisplayName("발화 인용·루브릭 조항이 비면 예외 (P4)")
    void blankEvidence_rejected() {
        assertThatThrownBy(() -> new Judgment("A", Grade.U4, new BigDecimal("0.9"),
                new Judgment.Evidence("", "루브릭"), "사유", null))
                .isInstanceOf(EvidenceRequiredException.class);
        assertThatThrownBy(() -> new Judgment("A", Grade.U4, new BigDecimal("0.9"),
                new Judgment.Evidence("발화", "  "), "사유", null))
                .isInstanceOf(EvidenceRequiredException.class);
    }

    private static final Judgment.Evidence OK =
            new Judgment.Evidence("낙인 하회하면 손실 난다고 들었어요", "원금손실 조건 인지");

    @Test
    @DisplayName("❗confidence 가 없으면 막는다 — double 이던 시절엔 0.0 이라 막을 게 없었다")
    void nullConfidenceRejected() {
        assertThatThrownBy(() -> new Judgment("I-1", Grade.U1, null, OK, "이유", null))
                .isInstanceOf(MeasurementInvalidException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    @DisplayName("근거 누락과 신뢰도 누락은 다른 예외다 — 상류에 고칠 자리를 알려야 한다")
    void evidenceAndConfidenceFailDifferently() {
        assertThatThrownBy(() -> new Judgment("I-1", Grade.U1, new BigDecimal("0.9"), null, "이유", null))
                .isInstanceOf(EvidenceRequiredException.class);
        assertThatThrownBy(() -> new Judgment("I-1", Grade.U1, null, OK, "이유", null))
                .isInstanceOf(MeasurementInvalidException.class);
    }

    @Test
    @DisplayName("계약 범위(0~1)를 벗어난 신뢰도는 막는다 — R-05 비교가 의미를 잃는다")
    void outOfRangeConfidenceRejected() {
        assertThatThrownBy(() -> new Judgment("I-1", Grade.U1, new BigDecimal("1.5"), OK, "이유", null))
                .isInstanceOf(MeasurementInvalidException.class)
                .hasMessageContaining("0~1");
        assertThatThrownBy(() -> new Judgment("I-1", Grade.U1, new BigDecimal("-0.1"), OK, "이유", null))
                .isInstanceOf(MeasurementInvalidException.class);
    }

    @Test
    @DisplayName("경계값 0 과 1 은 통과한다 — 범위는 닫힌 구간이다")
    void boundaryConfidenceAccepted() {
        assertThatCode(() -> new Judgment("I-1", Grade.U1, BigDecimal.ZERO, OK, "이유", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> new Judgment("I-1", Grade.U1, BigDecimal.ONE, OK, "이유", null))
                .doesNotThrowAnyException();
    }
}
