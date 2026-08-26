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
}
