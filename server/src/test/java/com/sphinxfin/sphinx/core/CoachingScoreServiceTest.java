package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.domain.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("F-DET-002 코칭 스코어(취약 가중치)")
class CoachingScoreServiceTest {

    private final CoachingScoreService svc = new CoachingScoreService();   // 실제 vulnerability_weights.yaml 로드

    private static Session session(String ageBand, String amountBand, String exp, Channel ch) {
        return Session.builder()
                .id("s").productId("ELS-001").channel(ch).ageBand(ageBand)
                .amountBand(amountBand).experienceLevel(exp)
                .build();
    }

    @Test
    @DisplayName("고령·무경험·모바일 → 높은 점수·취약")
    void vulnerableCustomer() {
        // 60대(3) + 5천만원대(1) + 없음(3) + MOBILE(1) = 8
        var r = svc.score(session("60대", "5천만원대", "없음", Channel.MOBILE), false);
        assertThat(r.score()).isEqualTo(8);
        assertThat(r.vulnerable()).isTrue();
    }

    @Test
    @DisplayName("젊음·경험있음·대면 → 낮은 점수·비취약")
    void lowRiskCustomer() {
        // 30대(0) + 3년미만(1) + FACE_TO_FACE(0) = 1
        var r = svc.score(session("30대", null, "3년미만", Channel.FACE_TO_FACE), false);
        assertThat(r.score()).isEqualTo(1);
        assertThat(r.vulnerable()).isFalse();
    }

    @Test
    @DisplayName("모순이 있으면 가산되어 취약으로 넘어갈 수 있다")
    void mismatchBonusPushesVulnerable() {
        // 60대(3) + 모순(+2) = 5 ≥ 4
        var r = svc.score(session("60대", null, null, Channel.FACE_TO_FACE), true);
        assertThat(r.score()).isEqualTo(5);
        assertThat(r.vulnerable()).isTrue();
    }

    @Test
    @DisplayName("알 수 없는 값은 0점(오탐 없음)")
    void unknownValuesScoreZero() {
        var r = svc.score(session("알수없음", "이상한값", "몰라", Channel.TM), false);
        assertThat(r.score()).isZero();
        assertThat(r.vulnerable()).isFalse();
    }
}
