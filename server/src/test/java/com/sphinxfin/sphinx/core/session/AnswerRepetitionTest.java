package com.sphinxfin.sphinx.core.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "사실상 같은 말인가" 의 경계. 소유: 강희진 (이슈 #268 (d))
 *
 * <p>이 판정이 무는 자리는 <b>재설명이 효과가 있었나</b>이고, 양쪽으로 다 틀릴 수 있다.
 * 느슨하면 표현을 다듬은 정상적인 이해 향상을 의심해 <b>재설명 기능이 무의미</b>해지고,
 * 빡빡하면 조사만 바꾼 되풀이가 통과한다. 두 방향을 다 잰다.
 */
@DisplayName("발화 되풀이 판정 (이슈 #268 (d))")
class AnswerRepetitionTest {

    @Test
    @DisplayName("❗조사·띄어쓰기만 바뀐 것은 같은 말이다 — 이걸 놓치면 아무것도 안 잡는다")
    void particlesAndSpacingDoNotMakeItANewAnswer() {
        assertThat(AnswerRepetition.essentiallySame(
                "낙인 하회하면 원금 손실이 난다고 들었어요",
                "낙인 하회하면 원금 손실이 난다고 들었어요.")).isTrue();
        assertThat(AnswerRepetition.essentiallySame(
                "낙인 하회하면 원금 손실이 난다고 들었어요",
                "낙인하회하면 원금손실이 난다고 들었어요")).isTrue();
    }

    @Test
    @DisplayName("❗어미만 바꾼 것도 같은 말이다 — 임계값 0.9 에서 여기가 샜다 (실측 0.737)")
    void changingOnlyTheEndingIsStillTheSameAnswer() {
        assertThat(AnswerRepetition.essentiallySame(
                "낙인 하회하면 원금 손실 난다고 들었어요",
                "낙인 하회하면 원금 손실 난다고 들었습니다"))
                .as("문면은 '조사·어미만 바뀐 수준을 잡는다' 인데 실제로는 안 잡고 있었다")
                .isTrue();
    }

    @Test
    @DisplayName("내용을 더한 되풀이는 안 잡는다 (실측 0.440) — 알려진 한계, 낮추면 다듬기까지 문다")
    void aSlightlyExpandedRepetitionIsOutOfReach() {
        assertThat(AnswerRepetition.essentiallySame(
                "낙인 하회하면 원금 손실 난다고 들었어요",
                "낙인 하회하면 원금 손실이 날 수 있다고 이해했어요"))
                .as("여기를 잡으려고 임계값을 내리면 정상적인 다듬기가 매번 황색이 된다 — "
                        + "그러면 재설명 기능을 아무도 안 쓴다")
                .isFalse();
    }

    @Test
    @DisplayName("❗표현을 다듬은 것은 다른 답이다 — 여기까지 의심하면 재설명이 무의미해진다")
    void aRephrasedUnderstandingIsANewAnswer() {
        assertThat(AnswerRepetition.essentiallySame(
                "낙인 하회하면 원금 손실이 난다고 들었어요",
                "기초자산이 처음의 절반 아래로 내려가면 제가 넣은 돈이 줄어든다는 뜻이네요"))
                .as("재설명을 듣고 자기 말로 다시 설명한 것은 이해가 올라간 것이다")
                .isFalse();
    }

    @Test
    @DisplayName("짧은 답도 같으면 같다 — 바이그램이 안 나오는 길이라 따로 답한다")
    void shortAnswersStillCompare() {
        assertThat(AnswerRepetition.essentiallySame("네", "네")).isTrue();
        assertThat(AnswerRepetition.essentiallySame("네", "아니요")).isFalse();
    }

    @Test
    @DisplayName("한쪽이 없으면 판단하지 않는다 — 첫 답변에는 비교 대상이 없다")
    void nothingToCompareMeansNoSignal() {
        assertThat(AnswerRepetition.essentiallySame(null, "답변")).isFalse();
        assertThat(AnswerRepetition.essentiallySame("답변", null)).isFalse();
        assertThat(AnswerRepetition.essentiallySame("   ", "답변")).isFalse();
    }
}
