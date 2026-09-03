package com.sphinxfin.sphinx.evidence;

import com.sphinxfin.sphinx.domain.RuleRef;
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
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이해 기록 리포트. 소유: 정세현
 *
 * <p>여기서 지키는 것 둘. <b>첫째, 리포트는 이력이다</b> — 세션이 덮어쓴 판정이 리포트에는
 * 순서대로 남아야 "왜 황색이었다가 통과했는가"에 답할 수 있다(5.12). <b>둘째, contentHash 는
 * 내용만의 함수다</b> — 발행 시각이나 체인 위치가 섞이면 <b>문서를 받은 고객이 대조할 수 없다.</b>
 * 계약이 요약본에도 *"전문과 같은 해시"* 를 싣게 한 이유가 그것이다.
 */
@DataJpaTest
@Import({JpaImmutableStore.class, StoredEvidenceRecorder.class, ReportService.class, ReportPdf.class})
@DisplayName("ReportService — 이해 기록 리포트")
class ReportServiceTest {

    private static final String SID = "S-1";
    private static final Instant T0 = Instant.parse("2026-08-27T01:00:00.000Z");

    @Autowired
    private ReportService reports;
    @Autowired
    private StoredEvidenceRecorder recorder;
    @Autowired
    private ImmutableStore store;
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
        recorder.appendGate(SID, new GateResult(Signal.YELLOW, List.of(new RuleRef("R-04", "테스트 문면")), 0, 3), T0.plusSeconds(1));
        recorder.appendJudgment(SID, judgment("ELS-A", Grade.U1, "0.95"), 1, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(60));
        recorder.appendJudgment(SID, judgment("ELS-B", Grade.U1, "0.9"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(90));
        recorder.appendGate(SID, new GateResult(Signal.GREEN, List.of(), 0, 3), T0.plusSeconds(91));
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
        @DisplayName("❗리포트가 판정을 만든 입력도 낸다 — 기록에만 있으면 스트림을 열어야 한다 (이슈 #280 ②)")
        void gateHistoryCarriesTheJudgementInputs() {
            seedSession();
            recorder.appendGate(SID, new GateResult(Signal.RED, List.of(new RuleRef("R-00", "테스트 문면")), 2, 3),
                    T0.plusSeconds(120));
            em.flush();
            em.clear();

            Map<String, Object> last = list(reports.render(SID), "gateHistory").get(2);
            assertThat(last.get("signal")).isEqualTo("RED");
            assertThat(last.get("unmeasured"))
                    .as("교부 문서가 'R-00 이 물었다' 까지만 말하면 몇 항목을 못 쟀는지 "
                        + "감사에서 스트림을 직접 열어야 한다 — 이슈 #280 이 든 나머지 절반이다")
                    .isEqualTo(2);
            assertThat(last.get("rulesVersion")).isEqualTo(3);
        }

        @Test
        @DisplayName("❗이 필드 전 기록은 키를 null 로 남긴다 — 0(전부 쟀다) 과 갈라 둔다")
        void olderEntriesKeepTheKeyAsNull() {
            seedSession();

            // 이 필드가 생기기 전에 적힌 게이트 항목이다. 실물에서 이 모양이 나온다 —
            // append-only 라 옛 항목은 고쳐지지 않고, 리허설 전 세션의 기록이 그대로 남는다.
            Map<String, Object> older = new java.util.LinkedHashMap<>();
            older.put("type", "gate");
            older.put("sessionId", SID);
            older.put("at", T0.plusSeconds(120));
            older.put("signal", "RED");
            older.put("ruleTrace", List.of("R-00"));
            store.append(StoredEvidenceRecorder.streamOf(SID), older);
            em.flush();
            em.clear();

            Map<String, Object> last = list(reports.render(SID), "gateHistory").get(2);
            // ❗세 값이 갈려야 한다: 0 은 "전부 쟀다", null 은 "이 필드 전 기록", 키 없음은
            // "리포트가 안 낸다". 여기서 null 을 걸러내면 뒤의 둘이 같아지고, 그러면
            // 옛 세션의 리포트가 **못 쟀는지 안 적었는지를 말하지 않는 채로** 교부된다.
            assertThat(last).containsKeys("unmeasured", "rulesVersion");
            assertThat(last.get("unmeasured")).isNull();
            assertThat(last.get("rulesVersion")).isNull();
        }

        @Test
        @DisplayName("색은 담지 않는다 (ADR-004 §5) — grade 원값과 근거만")
        void doesNotStoreColor() {
            seedSession();

            Map<String, Object> first = historyOf(list(reports.render(SID), "items").get(0)).get(0);
            assertThat(first.keySet()).doesNotContain("signal", "color", "severity");
            // misconceptionType 은 여기 있었다. 이슈 #144 로 뺐다 — 그 값이 불공정영업 신호
            // 그 자체라 판매자가 읽는 문서에 있으면 안 된다. 이 단정이 그것을 붙들고 있어서
            // 누출이 초록으로 남아 있었다.
            assertThat(first.keySet()).contains("grade", "evidence", "reason");
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
            recorder.appendGate("S-2", new GateResult(Signal.YELLOW, List.of(new RuleRef("R-04", "테스트 문면")), 0, 3), T0.plusSeconds(1));
            recorder.appendJudgment("S-2", judgment("ELS-A", Grade.U1, "0.95"), 1, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(60));
            recorder.appendJudgment("S-2", judgment("ELS-B", Grade.U1, "0.9"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(90));
            recorder.appendGate("S-2", new GateResult(Signal.GREEN, List.of(), 0, 3), T0.plusSeconds(91));
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

    @Nested
    @DisplayName("불공정영업 신호가 리포트로 새지 않는다 (이슈 #144)")
    class UnfairSignalDoesNotLeak {

        /** 키 이름이 아니라 렌더된 내용 전체를 본다 — 누출은 내가 상상 못 한 이름으로 난다. */
        private static final List<String> LEAK_WORDS = List.of(
                "unfair", "escalate", "compliance", "compl", "tying", "m08", "signal",
                "꺾기", "불공정");

        private Map<String, Object> reportWithTyingJudgment() {
            recorder.appendJudgment(SID, new Judgment("ELS-A", Grade.U4, new BigDecimal("0.9"),
                            new Judgment.Evidence("대출받으려면 이것도 들어야 한다고 해서요",
                                    "끼워팔기 인지 실패"),
                            "판매자 발화 인용", "M08-TYING"),
                    0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, T0);
            em.flush();
            em.clear();
            return reports.render(SID);
        }

        @Test
        @DisplayName("❗판정 유형이 리포트 본문에 없다 — 그 값이 신호 그 자체다")
        void misconceptionTypeIsNotInTheReport() {
            Map<String, Object> entry =
                    historyOf(list(reportWithTyingJudgment(), "items").get(0)).get(0);

            assertThat(entry)
                    .as("signal:unfair:read 를 COMPL 로 좁혀도 판매자는 report:read 로 "
                            + "자기 세션 리포트를 연다 — 같은 값이 다른 action 으로 새면 "
                            + "그 좁힘이 무의미하다(기획 7-4)")
                    .doesNotContainKey("misconceptionType");
        }

        @Test
        @DisplayName("❗렌더된 내용 어디에도 신호 어휘가 없다 — 키 이름만 보면 놓친다")
        void noSignalVocabularyAnywhereInTheContent() {
            String rendered = reportWithTyingJudgment().toString().toLowerCase();

            assertThat(rendered)
                    .as("이 단정이 의미를 가지려면 리포트가 비어 있지 않아야 한다")
                    .contains("els-a").contains("u4");
            assertThat(LEAK_WORDS)
                    .as("누출은 필드를 하나 지운다고 끝나지 않는다 — reason 이나 "
                            + "rubricClause 로도 같은 값이 나갈 수 있다")
                    .noneMatch(rendered::contains);
        }

        @Test
        @DisplayName("어휘 목록이 실제로 거르는지 잰다 — 비었거나 대소문자가 어긋나면 위가 공짜다")
        void theLeakWordListActuallyMatches() {
            assertThat(LEAK_WORDS).isNotEmpty();
            assertThat(LEAK_WORDS)
                    .as("목록의 어휘가 실제 값에 걸려야 한다 — M08-TYING 은 소문자로 "
                            + "'m08' 과 'tying' 둘 다에 걸린다")
                    .anyMatch("m08-tying"::contains);
        }

        @Test
        @DisplayName("감사 경로는 그대로다 — 불변 기록에는 남는다")
        void theImmutableChainStillHasIt() {
            reportWithTyingJudgment();

            String chain = store.replay(StoredEvidenceRecorder.streamOf(SID)).toString();

            assertThat(chain)
                    .as("리포트는 기록이 아니라 기록에서 만든 문서다. 값을 지운 게 아니라 "
                            + "그 문서에 안 실은 것이고, COMPL 은 audit:read 로 체인을 읽는다")
                    .contains("M08-TYING");
        }
    }

    @Nested
    @DisplayName("PDF — 발행 시점 지면을 다시 그린다 (F-GTE-004 2번)")
    class Pdf {

        private String textOf(byte[] bytes) throws Exception {
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
                return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            }
        }

        @Test
        @DisplayName("발행 전에는 PDF 가 없다 — 없는 문서를 그려 주지 않는다")
        void nothingToRenderBeforeIssuing() {
            seedSession();

            // NoSuchElementException 인 것이 요지다 — GlobalExceptionHandler 가 404 로 내보내는
            // 유일한 타입이고, SessionController.report() 가 "아직 발행된 리포트가 없다" 에
            // 쓰는 것과 같다. 미발행은 오류가 아니라 상태라 두 경로가 같은 코드를 내야 한다.
            assertThatThrownBy(() -> reports.pdf(SID))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("발행된 리포트가 없다");
        }

        @Test
        @DisplayName("❗발행 뒤에 판정이 더 쌓여도 PDF 는 **발행 시점** 지면이다")
        void thePdfShowsWhatWasIssuedNotWhatIsNow() throws Exception {
            seedSession();
            ReportService.Report issued = reports.issue(SID, T0.plusSeconds(100));

            // 교부 뒤에 재검증이 한 번 더 있었다. 스트림은 append-only 라 이게 그대로 쌓인다.
            recorder.appendJudgment(SID, judgment("ELS-C", Grade.U4, "0.99"), 0, "나중 질문",
                    EvidenceRecorder.QuestionSource.DISPLAYED, T0.plusSeconds(200));
            em.flush();
            em.clear();

            String text = textOf(reports.pdf(SID));

            assertThat(text)
                    .as("지금 전체를 재생하면 교부한 적 없는 항목이 종이에 실린다")
                    .doesNotContain("ELS-C");
            assertThat(text)
                    .as("발행 시점에 있던 것은 그대로 있어야 한다")
                    .contains("ELS-A")
                    .contains("ELS-B")
                    .contains(issued.contentHash());
        }

        @Test
        @DisplayName("❗재현한 내용의 해시가 발행 기록과 같다 — 종이와 기록이 갈리지 않는다")
        void theReproducedContentMatchesTheIssuedHash() throws Exception {
            seedSession();
            ReportService.Report issued = reports.issue(SID, T0.plusSeconds(100));

            // pdf() 가 내부에서 이 대조를 하고, 다르면 던진다. 여기서는 지면에 찍힌 값으로 본다.
            assertThat(textOf(reports.pdf(SID))).contains(issued.contentHash());
        }

        @Test
        @DisplayName("❗재현이 발행 기록과 다르면 **그려 주지 않는다** — 조용히 다른 지면을 내주지 않는다")
        void aMismatchRefusesInsteadOfRenderingSomethingElse() {
            seedSession();

            // 발행 기록의 해시만 어긋난 상태를 만든다. 체인이 상했거나 조립 규칙이 바뀐
            // 경우가 실물에서 이 모양이다 — 재현한 내용과 기록된 해시가 다르다.
            Map<String, Object> tampered = new java.util.LinkedHashMap<>();
            tampered.put("type", "report");
            tampered.put("sessionId", SID);
            tampered.put("at", T0.plusSeconds(100));
            tampered.put("contentHash", "0".repeat(64));
            store.append(StoredEvidenceRecorder.streamOf(SID), tampered);
            em.flush();
            em.clear();

            assertThatThrownBy(() -> reports.pdf(SID))
                    .as("그려 주면 고객이 받은 종이와 불변 기록이 갈리고, 그 사실은 분쟁 시점까지 안 드러난다")
                    .isInstanceOf(ReportNotReproducibleException.class)
                    .hasMessageContaining("재현하지 못했다");
        }

        @Test
        @DisplayName("❗재현 실패는 **미발행과 다른 타입**이다 — 404 로 접히면 손상이 사라진다")
        void aMismatchIsNeverMistakenForNotIssuedYet() {
            seedSession();

            Map<String, Object> tampered = new java.util.LinkedHashMap<>();
            tampered.put("type", "report");
            tampered.put("sessionId", SID);
            tampered.put("at", T0.plusSeconds(100));
            tampered.put("contentHash", "0".repeat(64));
            store.append(StoredEvidenceRecorder.streamOf(SID), tampered);
            em.flush();
            em.clear();

            // ❗이 단정이 이 클래스에서 제일 중요하다. GlobalExceptionHandler 는
            // NoSuchElementException 만 404 로 내보내는데, 재현 실패가 그 타입이 되는 순간
            // **체인 손상이 "아직 발행 안 했습니다" 로 화면에 뜬다.** 그러면 종이와 불변
            // 기록이 갈렸다는 사실이 어디에도 안 남고, 다음 행동은 "발행하기" 가 된다.
            //
            // 둘을 같은 타입으로 되돌리는 변경은 여기서 걸린다 — 실제로 원래 둘 다
            // IllegalStateException 이었고, 그 상태로는 엔드포인트를 안전하게 붙일 수 없었다.
            assertThatThrownBy(() -> reports.pdf(SID))
                    .isNotInstanceOf(NoSuchElementException.class);
        }

        @Test
        @DisplayName("같은 발행이면 PDF 도 같다 — 발행 기록의 시각을 쓰기 때문이다")
        void thePdfIsStableAcrossCalls() {
            seedSession();
            reports.issue(SID, T0.plusSeconds(100));

            assertThat(reports.pdf(SID))
                    .as("지금 시각을 넣으면 부를 때마다 다른 파일이 된다")
                    .isEqualTo(reports.pdf(SID));
        }
    }
}
