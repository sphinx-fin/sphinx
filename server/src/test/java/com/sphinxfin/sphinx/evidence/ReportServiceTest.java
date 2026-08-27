package com.sphinxfin.sphinx.evidence;

import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.core.EvidenceRecorder;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이해 기록 리포트. 소유: 정세현
 *
 * <p>여기서 지키는 것 둘. <b>첫째, 리포트는 이력이다</b> — 세션이 덮어쓴 판정이 리포트에는
 * 순서대로 남아야 "왜 황색이었다가 통과했는가"에 답할 수 있다(5.12). <b>둘째, contentHash 는
 * 내용만의 함수다</b> — 발행 시각이나 체인 위치가 섞이면 <b>문서를 받은 고객이 대조할 수 없다.</b>
 * 계약이 요약본에도 *"전문과 같은 해시"* 를 싣게 한 이유가 그것이다.
 */
@DataJpaTest
@Import({JpaImmutableStore.class, StoredEvidenceRecorder.class, ReportService.class})
@DisplayName("ReportService — 이해 기록 리포트")
class ReportServiceTest {

    private static final String SID = "S-1";
    private static final Instant T0 = Instant.parse("2026-08-27T01:00:00.000Z");

    @Autowired
    private ReportService reports;
    @Autowired
    private StoredEvidenceRecorder recorder;
    @Autowired
    private TestEntityManager em;

    private static Judgment judgment(String itemId, Grade grade, String confidence) {
        return new Judgment(itemId, grade, new BigDecimal(confidence),
                new Judgment.Evidence("원금은 지켜지죠", "원금손실 조건"), "사유", null);
    }

    /** 프롬프트 버전을 실은 판정 — ai-service 가 `prompt_version` 을 보내는 경우다 (#136). */
    private static Judgment judgment(String itemId, Grade grade, String confidence,
                                     String promptVersion) {
        return new Judgment(itemId, grade, new BigDecimal(confidence),
                new Judgment.Evidence("원금은 지켜지죠", "원금손실 조건"), "사유", null, promptVersion);
    }

    /** 재검증 한 번을 포함한 전형적인 세션을 만든다. */
    private void seedSession() {
        recorder.appendJudgment(SID, judgment("ELS-A", Grade.U3, "0.7"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0);
        recorder.appendGate(SID, new GateResult(Signal.YELLOW, List.of("R-04")), T0.plusSeconds(1));
        recorder.appendJudgment(SID, judgment("ELS-A", Grade.U1, "0.95"), 1, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(60));
        recorder.appendJudgment(SID, judgment("ELS-B", Grade.U1, "0.9"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(90));
        recorder.appendGate(SID, new GateResult(Signal.GREEN, List.of()), T0.plusSeconds(91));
        em.flush();
        em.clear();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> content, String key) {
        return (List<Map<String, Object>>) content.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> historyOf(Map<String, Object> item) {
        return (List<Map<String, Object>>) item.get("history");
    }

    @Nested
    @DisplayName("render — 최신이 아니라 이력이다 (5.12)")
    class Render {

        @Test
        @DisplayName("같은 항목의 재검증이 순서대로 남는다")
        void keepsPerItemHistory() {
            seedSession();

            List<Map<String, Object>> items = list(reports.render(SID), "items");

            assertThat(items).extracting(i -> i.get("itemId")).containsExactly("ELS-A", "ELS-B");
            assertThat(historyOf(items.get(0))).extracting(h -> h.get("grade"))
                    .as("세션은 U1 만 들고 있다 — 리포트만 'U3 였다' 를 말할 수 있다")
                    .containsExactly("U3", "U1");
            assertThat(historyOf(items.get(0))).extracting(h -> String.valueOf(h.get("reverifyCount")))
                    .containsExactly("0", "1");
        }

        @Test
        @DisplayName("게이트 신호의 변천과 오버라이드도 시간순으로 담는다")
        void keepsGateAndOverrideHistory() {
            seedSession();
            recorder.appendOverride(SID, "고객이 충분히 이해했다고 판단합니다", "mgr-01", T0.plusSeconds(120));
            em.flush();
            em.clear();

            Map<String, Object> content = reports.render(SID);
            assertThat(list(content, "gateHistory")).extracting(g -> g.get("signal"))
                    .containsExactly("YELLOW", "GREEN");
            assertThat(list(content, "overrides")).singleElement()
                    .extracting(o -> o.get("approver")).isEqualTo("mgr-01");
        }

        @Test
        @DisplayName("색은 담지 않는다 (ADR-004 §5) — grade 원값과 근거만")
        void doesNotStoreColor() {
            seedSession();

            Map<String, Object> first = historyOf(list(reports.render(SID), "items").get(0)).get(0);
            assertThat(first.keySet()).doesNotContain("signal", "color", "severity");
            assertThat(first.keySet()).contains("grade", "evidence", "misconceptionType");
        }

        @Test
        @DisplayName("발행 기록은 조립 대상이 아니다 — 자기를 포함하면 순환이다")
        void excludesItsOwnIssuanceRecords() {
            seedSession();
            Map<String, Object> before = reports.render(SID);

            reports.issue(SID, T0.plusSeconds(200));
            em.flush();
            em.clear();

            assertThat(reports.render(SID))
                    .as("리포트를 발행해도 리포트 내용은 안 바뀐다")
                    .isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("contentHash — 문서만 가진 사람도 재계산할 수 있어야 한다")
    class ContentHash {

        @Test
        @DisplayName("발행 시각이 달라도 내용이 같으면 같은 해시")
        void doesNotDependOnIssueTime() {
            seedSession();
            Map<String, Object> content = reports.render(SID);

            ReportService.Report first = reports.issue(SID, T0.plusSeconds(200));
            em.flush();

            assertThat(first.contentHash())
                    .as("발행 시각이 섞이면 '이 문서가 그 문서인가' 를 대조할 수 없다")
                    .isEqualTo(reports.contentHash(content));
        }

        @Test
        @DisplayName("체인 위치가 안 섞인다 — 앞에 다른 세션이 있어도 같은 해시")
        void doesNotDependOnChainPosition() {
            seedSession();
            String hashHere = reports.contentHash(reports.render(SID));

            // 같은 내용을 다른 세션에 쌓으면 체인 위치는 다르지만 내용은 같다.
            recorder.appendJudgment("S-2", judgment("ELS-A", Grade.U3, "0.7"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0);
            recorder.appendGate("S-2", new GateResult(Signal.YELLOW, List.of("R-04")), T0.plusSeconds(1));
            recorder.appendJudgment("S-2", judgment("ELS-A", Grade.U1, "0.95"), 1, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(60));
            recorder.appendJudgment("S-2", judgment("ELS-B", Grade.U1, "0.9"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(90));
            recorder.appendGate("S-2", new GateResult(Signal.GREEN, List.of()), T0.plusSeconds(91));
            em.flush();
            em.clear();

            Map<String, Object> other = reports.render("S-2");
            assertThat(other.get("sessionId")).isEqualTo("S-2");
            assertThat(reports.contentHash(other))
                    .as("sessionId 가 내용에 들어가므로 다른 세션은 다른 해시다 — 위치가 아니라 내용의 차이다")
                    .isNotEqualTo(hashHere);
        }

        @Test
        @DisplayName("판정이 하나 더 쌓이면 해시가 달라진다")
        void changesWhenEvidenceGrows() {
            seedSession();
            String before = reports.contentHash(reports.render(SID));

            recorder.appendJudgment(SID, judgment("ELS-C", Grade.U2, "0.6"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(150));
            em.flush();
            em.clear();

            assertThat(reports.contentHash(reports.render(SID))).isNotEqualTo(before);
        }
    }

    @Nested
    @DisplayName("issue — 내용이 같으면 다시 발행하지 않는다")
    class Issue {

        @Test
        @DisplayName("두 번 불러도 발행 기록은 하나 — 같은 reportId·같은 해시")
        void isIdempotentWhileContentIsUnchanged() {
            seedSession();

            ReportService.Report first = reports.issue(SID, T0.plusSeconds(200));
            em.flush();
            em.clear();
            ReportService.Report second = reports.issue(SID, T0.plusSeconds(300));
            em.flush();
            em.clear();

            assertThat(second.reportId()).isEqualTo(first.reportId());
            assertThat(second.contentHash()).isEqualTo(first.contentHash());
            assertThat(second.generatedAt())
                    .as("발행 시각도 처음 것이다 — 새로 발행한 게 아니다")
                    .isEqualTo(first.generatedAt());
        }

        @Test
        @DisplayName("내용이 달라지면 새로 발행하고 이전 기록은 남는다")
        void reissuesWhenContentChanged() {
            seedSession();
            ReportService.Report first = reports.issue(SID, T0.plusSeconds(200));
            em.flush();

            recorder.appendJudgment(SID, judgment("ELS-C", Grade.U2, "0.6"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(250));
            em.flush();
            em.clear();

            ReportService.Report second = reports.issue(SID, T0.plusSeconds(300));
            em.flush();
            em.clear();

            assertThat(second.contentHash()).isNotEqualTo(first.contentHash());
            assertThat(reports.latest(SID).orElseThrow().contentHash()).isEqualTo(second.contentHash());
            assertThat(second.reportId())
                    .as("이전 발행 기록을 지우지 않는다 — 교부 시점에 무엇이 적혀 있었는지가 남아야 한다")
                    .isNotEqualTo(first.reportId());
        }

        @Test
        @DisplayName("발행해도 사슬은 검증된다")
        void chainStaysVerifiable() {
            seedSession();
            reports.issue(SID, T0.plusSeconds(200));
            em.flush();

            assertThat(reports.latest(SID)).isPresent();
        }

        @Test
        @DisplayName("발행한 적 없으면 latest 는 비어 있다")
        void latestIsEmptyBeforeFirstIssue() {
            seedSession();
            assertThat(reports.latest(SID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("측정을 결정한 값이 리포트에 남는다 (이슈 #136)")
    class MeasurementProvenance {

        @Test
        @DisplayName("❗판정마다 그때 물은 질문이 따로 남는다 — 세션 테이블은 마지막 것만 갖는다")
        void eachJudgmentKeepsItsOwnQuestion() {
            recorder.appendJudgment(SID, judgment("ELS-A", Grade.U3, "0.7"), 0, "첫 질문", EvidenceRecorder.QuestionSource.DISPLAYED, T0);
            recorder.appendJudgment(SID, judgment("ELS-A", Grade.U1, "0.95"), 1,
                    "다시 여쭙는 질문", EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(60));
            em.flush();
            em.clear();

            List<Map<String, Object>> history = historyOf(list(reports.render(SID), "items").get(0));

            assertThat(history).extracting(h -> h.get("askedQuestion"))
                    .as("재질문하면 세션 맵은 덮어쓴다. 리포트가 이력이라는 것은 "
                            + "각 판정에 그때의 질문이 붙어 있다는 뜻이다 (#136)")
                    .containsExactly("첫 질문", "다시 여쭙는 질문");
        }

        @Test
        @DisplayName("❗confidence 옆에 그 정의(promptVersion)가 같이 온다")
        void confidenceCarriesItsDefinition() {
            recorder.appendJudgment(SID, judgment("ELS-A", Grade.U1, "0.65", "F-SCR-001_v2"),
                    0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0);
            em.flush();
            em.clear();

            Map<String, Object> entry = historyOf(list(reports.render(SID), "items").get(0)).get(0);

            assertThat(entry.get("promptVersion"))
                    .as("v1 은 등급 확신도, v2 는 재현 가능성이다(#114). 값만 남기면 "
                            + "감사 시점에 0.65 가 두 가지 뜻일 수 있다(결정 10.38)")
                    .isEqualTo("F-SCR-001_v2");
            assertThat(entry.get("confidence")).isEqualTo(new BigDecimal("0.65"));
        }

        @Test
        @DisplayName("❗고객이 못 본 문면은 SERVER_FALLBACK 으로 구별된다 — 문면은 똑같이 생겼다")
        void questionSourceSeparatesFallbackFromDisplayed() {
            recorder.appendJudgment(SID, judgment("ELS-A", Grade.U1, "0.9"), 0,
                    "같은 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0);
            recorder.appendJudgment(SID, judgment("ELS-A", Grade.U1, "0.9"), 1,
                    "같은 문면", EvidenceRecorder.QuestionSource.SERVER_FALLBACK, T0.plusSeconds(60));
            em.flush();
            em.clear();

            List<Map<String, Object>> history = historyOf(list(reports.render(SID), "items").get(0));

            assertThat(history).extracting(h -> h.get("askedQuestion"))
                    .as("문면이 같으니 문면만으로는 두 판정이 구별되지 않는다 — 그래서 출처가 필요하다")
                    .containsExactly("같은 문면", "같은 문면");
            assertThat(history).extracting(h -> h.get("questionSource"))
                    .as("고객이 보고 답한 질문으로 잰 판정과, 서버가 지어낸 맥락으로 잰 판정을 "
                            + "감사 시점에 갈라야 한다 (#136 3항)")
                    .containsExactly("DISPLAYED", "SERVER_FALLBACK");
        }

        @Test
        @DisplayName("❗출처가 바뀌면 contentHash 가 바뀐다")
        void questionSourceIsPartOfTheHashedContent() {
            recorder.appendJudgment(SID, judgment("ELS-A", Grade.U1, "0.9"), 0,
                    "물은 질문 A", EvidenceRecorder.QuestionSource.DISPLAYED, T0);
            em.flush();
            em.clear();

            Map<String, Object> content = reports.render(SID);
            Map<String, Object> entry = historyOf(list(content, "items").get(0)).get(0);
            assertThat(entry).containsEntry("questionSource", "DISPLAYED");

            String before = reports.contentHash(content);
            entry.put("questionSource", "SERVER_FALLBACK");

            assertThat(reports.contentHash(content))
                    .as("해시에 안 실리면 문서를 받은 사람이 폴백 판정으로 바뀐 것을 대조로 못 잡는다")
                    .isNotEqualTo(before);
        }

        @Test
        @DisplayName("❗질문이 바뀌면 contentHash 가 바뀐다 — 안 그러면 대조로 못 잡는다")
        void questionIsPartOfTheHashedContent() {
            recorder.appendJudgment(SID, judgment("ELS-A", Grade.U1, "0.9"), 0, "물은 질문 A", EvidenceRecorder.QuestionSource.DISPLAYED, T0);
            em.flush();
            em.clear();

            // 세션 둘을 비교하지 않는다 — sessionId 가 내용에 들어가므로 질문을 빼도 해시가
            // 갈려서 이 단정이 공짜로 통과한다(역검증에서 잡혔다). 같은 내용에서 질문 하나만
            // 바꿔야 그 필드가 해시에 실린다는 것을 잰다.
            Map<String, Object> content = reports.render(SID);
            Map<String, Object> entry = historyOf(list(content, "items").get(0)).get(0);
            assertThat(entry)
                    .as("질문이 내용에 들어 있어야 그다음 단정이 의미를 갖는다")
                    .containsEntry("askedQuestion", "물은 질문 A");

            String before = reports.contentHash(content);
            entry.put("askedQuestion", "물은 질문 B");

            assertThat(reports.contentHash(content))
                    .as("질문만 바뀌었는데 해시가 같으면, 문서를 받은 사람이 질문이 바뀐 것을 "
                            + "대조로 못 잡는다 — 요약본에 전문과 같은 해시를 싣는 이유가 그것이다")
                    .isNotEqualTo(before);
        }

        @Test
        @DisplayName("null 을 생략하지 않는다 — 없음과 미기재를 가른다")
        void nullsAreWrittenNotOmitted() {
            recorder.appendJudgment(SID, judgment("ELS-A", Grade.U1, "0.9"), 0, null, EvidenceRecorder.QuestionSource.DISPLAYED, T0);
            em.flush();
            em.clear();

            Map<String, Object> entry = historyOf(list(reports.render(SID), "items").get(0)).get(0);

            assertThat(entry)
                    .as("misconceptionType 과 같은 규약이다. 생략하면 '필드가 생기기 전 "
                            + "레코드' 와 '값이 없는 판정' 이 같아지고, append-only 라 "
                            + "섞인 뒤에는 못 가른다")
                    .containsKeys("askedQuestion", "promptVersion");
            assertThat(entry.get("askedQuestion")).isNull();
            assertThat(entry.get("promptVersion")).isNull();
        }
    }
}
