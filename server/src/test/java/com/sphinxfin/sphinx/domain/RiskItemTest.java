package com.sphinxfin.sphinx.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 추출 실패 항목의 표현을 고정한다. 소유: 강희진
 *
 * condition 은 원문에서 뽑은 조건이므로 실패하면 없어야 한다. 사유 문면으로 채우면
 * value_text 가 원문이 아니게 되고(P6), pages[page].text[start:end] == value_text 항등식이
 * 깨지는데 — JSON Schema 는 구조만 보고 예외도 로그도 없어서 아무도 못 잡는다.
 * status 를 안 읽는 소비자에게는 문서에 없는 문장이 원문 인용으로 보인다.
 */
@DisplayName("RiskItem 추출 실패 표현 (P6·E-EXT-03)")
class RiskItemTest {

    private static final RiskItem.Condition COND = new RiskItem.Condition(
            "만기평가일에 기초자산이 최초기준가격의 45% 미만인 경우",
            new RiskItem.SourceSpan(3, 120, 210));

    @Test
    @DisplayName("추출 성공 — condition 필수")
    void extractedCarriesCondition() {
        RiskItem item = RiskItem.extracted("ELS-X", "P", "원금손실 조건", "required", COND);
        assertThat(item.status()).isEqualTo("extracted");
        assertThat(item.condition()).isEqualTo(COND);
        assertThat(item.failureReason()).isNull();
    }

    @Test
    @DisplayName("추출 실패 — condition 은 비고 사유만 남는다")
    void failedCarriesReasonNotCondition() {
        RiskItem item = RiskItem.failed("ELS-X", "P", "조기상환 조건", "required",
                "문서에서 해당 조건을 찾지 못했다");
        assertThat(item.status()).isEqualTo("extraction_failed");
        assertThat(item.condition()).isNull();
        assertThat(item.failureReason()).isNotBlank();
    }

    @Test
    @DisplayName("❗실패 항목에 condition 을 채우면 거부한다 — 지어낸 문장이 원문 인용이 된다")
    void failedWithConditionIsRejected() {
        assertThatThrownBy(() -> new RiskItem("ELS-X", "P", "n", "required",
                new RiskItem.Condition("(추출 실패 — 문서에서 해당 조건을 찾지 못했다)",
                        new RiskItem.SourceSpan(1, 0, 0)),
                "extraction_failed", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("P6");
    }

    @Test
    @DisplayName("성공인데 condition 이 없으면 거부한다")
    void extractedWithoutConditionIsRejected() {
        assertThatThrownBy(() -> new RiskItem("ELS-X", "P", "n", "required",
                null, "extracted", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
