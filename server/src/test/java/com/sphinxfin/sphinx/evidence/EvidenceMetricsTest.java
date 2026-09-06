package com.sphinxfin.sphinx.evidence;

import com.sphinxfin.sphinx.core.EvidenceRecorder;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 불변 기록에 이미 있는 값 넷을 센다 (이슈 #327). 소유: 정세현
 *
 * <p>여기서 지키는 것은 <b>세는 값이 실제로 움직이는가</b>다. 계량 테스트가 전부 0 이면
 * 단정이 아무것도 안 잰다 — `#396` 에 `{} == set(())` 로 물린 전례가 있다. 그래서 등급·회차·
 * 시각을 갈아 가며 넣고 칸마다 값이 서는 것을 본다.
 */
@DataJpaTest
@Import({JpaImmutableStore.class, StoredEvidenceRecorder.class, EvidenceMetrics.class})
@DisplayName("EvidenceMetrics — 이미 쌓인 것을 센다")
class EvidenceMetricsTest {

    private static final Instant T0 = Instant.parse("2026-09-06T01:00:00.000Z");

    @Autowired
    private StoredEvidenceRecorder recorder;
    @Autowired
    private EvidenceMetrics metrics;
    @Autowired
    private EvidenceEntryRepository entries;
    @Autowired
    private EvidenceStreamAnchorRepository anchors;

    @BeforeEach
    void clear() {
        entries.deleteAll();
        anchors.deleteAll();
    }

    private static Judgment judgment(String itemId, Grade grade, String confidence) {
        return new Judgment(itemId, grade, new BigDecimal(confidence),
                new Judgment.Evidence("원금은 지켜지죠", "원금손실 조건"),
                "사유", null);
    }

    private void judge(String sessionId, String itemId, Grade grade, String confidence,
                       int reverifyCount, EvidenceRecorder.QuestionSource source, Instant at) {
        recorder.appendJudgment(sessionId, judgment(itemId, grade, confidence),
                reverifyCount, "물은 질문", source, null, at);
    }

    @Test
    @DisplayName("① 질문 폴백률 — 표시된 것과 템플릿 폴백을 갈라 센다 (\"데모가 될 상태인가\")")
    void countsQuestionSource() {
        judge("S-1", "A", Grade.U1, "1.0", 0, EvidenceRecorder.QuestionSource.DISPLAYED, T0);
        judge("S-1", "B", Grade.U1, "1.0", 0,
                EvidenceRecorder.QuestionSource.TEMPLATE_FALLBACK, T0.plusSeconds(30));

        assertThat(metrics.summary().questionSource())
                .as("문면만으로는 폴백을 못 가른다 — 목 문면도 질문처럼 생겼다(#136)")
                .containsEntry("DISPLAYED", 1L)
                .containsEntry("TEMPLATE_FALLBACK", 1L);
    }

    @Test
    @DisplayName("② 확신도 분포 — 1.0 과 1 이 같은 칸에 선다")
    void countsConfidenceDistribution() {
        judge("S-1", "A", Grade.U1, "1.0", 0, EvidenceRecorder.QuestionSource.DISPLAYED, T0);
        judge("S-1", "B", Grade.U1, "1.00", 0, EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(1));
        judge("S-1", "C", Grade.U3, "0.70", 0, EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(2));

        assertThat(metrics.summary().confidence())
                .as("문면이 갈리면 같은 값이 두 칸으로 세인다 — CanonicalJson 이 쓴 문면으로 맞춘다")
                .containsEntry("1", 2L)
                .containsEntry("0.7", 1L);
    }

    @Test
    @DisplayName("③ 소요시간 — 세션 첫 기록부터 게이트까지. 끝나지 않은 세션은 안 센다")
    void measuresElapsedToGate() {
        judge("S-1", "A", Grade.U1, "1.0", 0, EvidenceRecorder.QuestionSource.DISPLAYED, T0);
        recorder.appendGate("S-1", new com.sphinxfin.sphinx.domain.GateResult(
                        com.sphinxfin.sphinx.domain.Signal.GREEN, java.util.List.of(), 0, 5),
                T0.plusSeconds(180));
        // 판정이 없는 세션 — 0 초로 세면 가운데 값이 아래로 끌린다
        judge("S-2", "A", Grade.U1, "1.0", 0, EvidenceRecorder.QuestionSource.DISPLAYED, T0);

        EvidenceMetrics.Durations d = metrics.summary().sessionDuration();
        assertThat(d.sessions()).as("끝난 세션만 센다").isEqualTo(1);
        assertThat(d.medianSeconds()).isEqualTo(180);
        assertThat(d.maxSeconds()).isEqualTo(180);
    }

    @Test
    @DisplayName("★④ 재설명 전후 등급 전환 — 이 제품의 가치 증명이 이 숫자다")
    void countsReexplainTransitions() {
        judge("S-1", "A", Grade.U3, "0.9", 0, EvidenceRecorder.QuestionSource.DISPLAYED, T0);
        judge("S-1", "A", Grade.U1, "1.0", 1, EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(120));

        assertThat(metrics.summary().reexplainTransitions())
                .as("「오해가 29% 였다」는 문제 제기이지 성과가 아니다 — 전환이 성과다")
                .containsEntry("U3→U1", 1L);
    }

    @Test
    @DisplayName("❗재판정은 재설명이 아니다 — 회차가 안 늘면 전환으로 안 센다")
    void doesNotCountRejudgementAsReexplain() {
        // 같은 회차에 판정이 두 번 적재되는 것은 재판정이다(측정 무효 → 다시 물음).
        judge("S-1", "A", Grade.U3, "0.9", 0, EvidenceRecorder.QuestionSource.DISPLAYED, T0);
        judge("S-1", "A", Grade.U1, "1.0", 0, EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(3));

        assertThat(metrics.summary().reexplainTransitions())
                .as("재설명을 안 했는데 전환으로 세면 이 제품의 효과가 부풀려진다")
                .isEmpty();
    }

    @Test
    @DisplayName("❗못 읽은 기록을 따로 센다 — 0 으로 뭉개면 judgments 가 「읽을 수 있었던 것」이 된다")
    void countsUnreadableSeparately() {
        judge("S-1", "A", Grade.U1, "1.0", 0, EvidenceRecorder.QuestionSource.DISPLAYED, T0);
        // 사슬은 무엇이든 담을 수 있다. 집계가 이걸 만나 죽어도, 조용히 버려도 안 된다.
        store.append("report:S-1", java.util.List.of("판정이 아니다"));

        EvidenceMetrics.Summary summary = metrics.summary();
        assertThat(summary.judgments()).isEqualTo(1);
        assertThat(summary.unreadable()).isEqualTo(1);
    }

    @Test
    @DisplayName("감사 스트림은 안 센다 — 세션 스트림만 본다")
    void ignoresNonSessionStreams() {
        store.openStream("audit");
        store.append("audit", Map.of("type", "audit", "at", T0));
        judge("S-1", "A", Grade.U1, "1.0", 0, EvidenceRecorder.QuestionSource.DISPLAYED, T0);

        assertThat(metrics.summary().sessions())
                .as("감사와 세션이 섞이면 세션 수가 거짓이 된다")
                .isEqualTo(1);
    }

    @Autowired
    private ImmutableStore store;
}
