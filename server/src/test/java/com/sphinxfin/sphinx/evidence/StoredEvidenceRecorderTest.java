package com.sphinxfin.sphinx.evidence;

import com.sphinxfin.sphinx.domain.RuleRef;
import com.sphinxfin.sphinx.domain.SuitabilityMismatch;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.core.EvidenceRecorder;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.Signal;
import com.sphinxfin.sphinx.domain.SuitabilityStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이해 기록 적재. 소유: 정세현
 *
 * <p>여기서 지키는 것은 <b>"세션이 덮어쓴 것이 기록에는 남는다"</b>이다. 세션은 항목별 최신
 * 판정만 들고 있으므로(게이트 입력으로는 그게 맞다), 재설명 이력 — 기획서 174행이 이해 기록의
 * 구성요소로 못박은 것 — 은 이 경로에만 존재한다. 이게 깨지면 리포트가 "최신"만 낼 수 있고
 * "왜 황색이었다가 통과했는가"에 답할 수 없다.
 */
@DataJpaTest
@Import({JpaImmutableStore.class, StoredEvidenceRecorder.class})
@DisplayName("StoredEvidenceRecorder — 이해 기록 적재")
class StoredEvidenceRecorderTest {

    private static final String SID = "S-1";
    private static final Instant T0 = Instant.parse("2026-08-27T01:00:00.000Z");

    @Autowired
    private StoredEvidenceRecorder recorder;
    @Autowired
    private JpaImmutableStore store;
    @Autowired
    private TestEntityManager em;

    private static Judgment judgment(String itemId, Grade grade, String confidence) {
        return new Judgment(itemId, grade, new BigDecimal(confidence),
                new Judgment.Evidence("원금은 지켜지죠", "원금손실 조건"),
                "원금 보장으로 오해", grade == Grade.U4 ? "M01-PRINCIPAL-GUARANTEE" : null);
    }

    /** 중첩 맵을 꺼낸다. Map<?,?> 로 캐스트하면 반환 타입이 캡처가 되어 단정문이 안 붙는다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> payload, String key) {
        return (Map<String, Object>) payload.get(key);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> payloads() {
        em.flush();
        em.clear();
        return store.replay(StoredEvidenceRecorder.streamOf(SID)).stream()
                .map(e -> (Map<String, Object>) e.payload())
                .toList();
    }

    @Nested
    @DisplayName("세 종류가 한 사슬에 순서대로 쌓인다")
    class OneStream {

        @Test
        @DisplayName("판정 → 게이트 → 오버라이드 순서가 기록에 남는다")
        void keepsCrossKindOrder() {
            recorder.appendJudgment(SID, judgment("A", Grade.U2, "0.8"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, null, T0);
            recorder.appendGate(SID, new GateResult(Signal.YELLOW, List.of(new RuleRef("R-04", "테스트 문면"), new RuleRef("R-05", "테스트 문면")), 0, 3), T0.plusSeconds(1));
            recorder.appendOverride(SID, "고객이 충분히 이해했다고 판단하여 진행합니다", "mgr-01", T0.plusSeconds(2));

            assertThat(payloads()).extracting(p -> p.get("type"))
                    .as("종류별로 스트림을 나누면 각 사슬은 온전한데 사이의 순서가 사라진다")
                    .containsExactly("judgment", "gate", "override");
            assertThat(store.verify(StoredEvidenceRecorder.streamOf(SID)).ok()).isTrue();
        }

        @Test
        @DisplayName("기록 한 건이 스스로 어느 세션·언제인지 말한다")
        void entriesAreSelfDescribing() {
            recorder.appendGate(SID, new GateResult(Signal.RED, List.of(new RuleRef("R-01", "테스트 문면")), 0, 3), T0);

            Map<String, Object> payload = payloads().get(0);
            assertThat(payload.get("sessionId")).isEqualTo(SID);
            assertThat(payload.get("at"))
                    .as("CanonicalJson 이 ADR-008 형식(UTC · 밀리초 3자리)으로 적는다")
                    .isEqualTo("2026-08-27T01:00:00.000Z");
        }

        @Test
        @DisplayName("세션이 다르면 사슬도 다르다")
        void streamsAreSeparatedBySession() {
            recorder.appendGate(SID, new GateResult(Signal.RED, List.of(new RuleRef("R-01", "테스트 문면")), 0, 3), T0);
            recorder.appendGate("S-2", new GateResult(Signal.GREEN, List.of(), 0, 3), T0);

            assertThat(payloads()).hasSize(1);
            assertThat(store.verify(StoredEvidenceRecorder.streamOf("S-2")).ok()).isTrue();
        }
    }

    @Nested
    @DisplayName("세션이 덮어쓴 것이 기록에는 남는다 (ADR-004)")
    class HistoryIsPreserved {

        @Test
        @DisplayName("같은 항목의 재검증이 두 건으로 남는다 — 덮어쓰기가 아니다")
        void reverificationIsAppendedNotOverwritten() {
            recorder.appendJudgment(SID, judgment("A", Grade.U3, "0.7"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, null, T0);
            recorder.appendJudgment(SID, judgment("A", Grade.U1, "0.9"), 1, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, null, T0.plusSeconds(60));

            List<Map<String, Object>> stored = payloads();
            assertThat(stored).hasSize(2);
            assertThat(stored).extracting(p -> child(p, "judgment").get("grade"))
                    .as("세션은 U1 만 들고 있다 — '처음에 U3 였다' 는 여기에만 남는다")
                    .containsExactly("U3", "U1");
            assertThat(stored).extracting(p -> String.valueOf(p.get("reverifyCount")))
                    .containsExactly("0", "1");
        }

        @Test
        @DisplayName("❗판정 기록이 입력 메타데이터를 든다 — 붙여넣기는 발화로는 안 보인다 (이슈 #325)")
        void judgmentEntryCarriesHowItWasTyped() {
            recorder.appendJudgment(SID, judgment("A", Grade.U1, "0.9"), 0, "질문",
                    EvidenceRecorder.QuestionSource.DISPLAYED,
                    new com.sphinxfin.sphinx.domain.InputMeta(120, 400, true, 0, 42, false), T0);

            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) payloads().get(0).get("inputMeta");
            assertThat(meta)
                    .as("붙여넣기로 채운 되말하기는 발화 내용만 보면 완벽한 U1 이다 — "
                            + "이 값이 없으면 그 행동을 구분할 방법이 아예 없다")
                    .isNotNull();
            assertThat(meta.get("pasteDetected")).isEqualTo(true);
            assertThat(meta.get("charCount")).isEqualTo(42);
        }

        @Test
        @DisplayName("❗안 보낸 것을 생략하지 않는다 — 없음과 미기재를 가른다")
        void anAbsentInputMetaIsRecordedAsNull() {
            recorder.appendJudgment(SID, judgment("A", Grade.U1, "0.9"), 0, "질문",
                    EvidenceRecorder.QuestionSource.DISPLAYED, null, T0);

            assertThat(payloads().get(0))
                    .as("키가 없으면 '화면이 안 보냈다' 와 '이 필드가 생기기 전 기록' 이 "
                            + "같아 보인다 — askedQuestion·misconceptionType 과 같은 규약이다(#136)")
                    .containsKey("inputMeta");
            assertThat(payloads().get(0).get("inputMeta")).isNull();
        }

        @Test
        @DisplayName("❗게이트 기록이 판정을 만든 입력을 같이 든다 — 재계산으로 못 되돌린다 (이슈 #295)")
        void gateEntryCarriesItsInputs() {
            recorder.appendGate(SID, new GateResult(Signal.RED, List.of(new RuleRef("R-00", "테스트 문면")), 3, 3), T0);

            Map<String, Object> payload = payloads().get(0);
            assertThat(payload.get("unmeasured"))
                    .as("몇 개를 못 쟀는지가 안 남으면 R-00 이 물었다는 것까지만 남는다 — "
                        + "13항목 중 1개인지 5개인지가 감사에서 다른 이야기다")
                    .isEqualTo(3);
            assertThat(payload.get("rulesVersion"))
                    .as("어느 룰셋으로 잰 건지 — gate_rules.yaml 은 언제든 바뀐다")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("❗입력이 0 이어도 생략하지 않는다 — 없음과 미기재를 가른다. 두 필드 다")
        void zeroInputsAreRecordedNotOmitted() {
            // ❗두 필드를 한 단정으로 같이 본다. `unmeasured` 만 걸었던 판이 있었고,
            // `if (rulesVersion != 0) put(...)` 변이가 그때 전건 통과했다 — 다른 테스트는
            // 전부 rulesVersion 을 3 으로 넣어서 0 인 경로를 아무도 안 지나갔다(PR #299 리뷰).
            // 문면이 두 필드를 같은 규약으로 말하는데 그물이 한쪽만 있으면 규약이 아니다.
            recorder.appendGate(SID, new GateResult(Signal.GREEN, List.of(new RuleRef("R-06", "테스트 문면")), 0, 0), T0);

            assertThat(payloads().get(0))
                    .as("키가 없으면 '전부 쟀다' 와 '이 필드가 생기기 전 기록' 이 같아 보인다 — "
                        + "misconceptionType·promptVersion·escalate 와 같은 규약이다(이슈 #136)")
                    .containsKeys("unmeasured", "rulesVersion");
            assertThat(payloads().get(0).get("unmeasured")).isEqualTo(0);
            // rulesVersion 0 은 뜻이 따로 있어서 생략이 더 나쁘다 — "버전 0 인 룰셋" 이 아니라
            // **파일을 안 지나온 룰**(GateEngine.UNVERSIONED)이다. 생략되면 기록에서
            // "파일을 안 지나온 룰로 판정했다" 와 "이 필드 전 기록" 이 같아진다.
            assertThat(payloads().get(0).get("rulesVersion")).isEqualTo(0);
        }

        @Test
        @DisplayName("게이트 판정도 매 호출 남는다 — 최종 신호가 아니라 신호의 변천")
        void gateSignalsAccumulate() {
            recorder.appendGate(SID, new GateResult(Signal.RED, List.of(new RuleRef("R-01", "테스트 문면")), 0, 3), T0);
            recorder.appendGate(SID, new GateResult(Signal.YELLOW, List.of(new RuleRef("R-04", "테스트 문면")), 0, 3), T0.plusSeconds(60));
            recorder.appendGate(SID, new GateResult(Signal.GREEN, List.of(), 0, 3), T0.plusSeconds(120));

            assertThat(payloads()).extracting(p -> p.get("signal"))
                    .containsExactly("RED", "YELLOW", "GREEN");
        }
    }

    @Nested
    @DisplayName("판정 payload — 무엇이 들어가고 무엇이 빠지는가")
    class JudgmentPayload {

        @Test
        @DisplayName("grade 원값과 근거가 들어간다. 색은 안 들어간다 (ADR-004 §5)")
        void storesGradeAndEvidenceButNoColor() {
            recorder.appendJudgment(SID, judgment("A", Grade.U4, "0.91"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, null, T0);

            Map<String, Object> item = child(payloads().get(0), "judgment");
            assertThat(item.get("grade")).isEqualTo("U4");
            assertThat(child(item, "evidence").get("utteranceQuote")).isEqualTo("원금은 지켜지죠");
            assertThat(item.keySet())
                    .as("표시 관례가 바뀌면 같은 판정의 해시가 달라진다 — 색은 저장하지 않는다")
                    .doesNotContain("signal", "color", "severity");
        }

        @Test
        @DisplayName("misconceptionType 이 null 이어도 키를 남긴다 — 생략하면 '없음' 과 '미기재' 가 같아진다")
        void keepsNullMisconceptionType() {
            recorder.appendJudgment(SID, judgment("A", Grade.U1, "0.95"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, null, T0);

            assertThat(child(payloads().get(0), "judgment").keySet())
                    .contains("misconceptionType");
        }

        @Test
        @DisplayName("❗escalate 가 들어간다 — 안 담으면 상신된 세션에 상신 사유가 없다")
        void storesEscalate() {
            // escalate 가 상신을 결정하게 되면(#160 · #242), 그때 misconceptionType 은
            // **바로 그 경우에 null** 이다 — 루브릭 17종 중 M08-TYING 을
            // related_misconceptions 에 건 것이 0건이라 유형이 안 실린다.
            // 그래서 이 필드를 빼면 담긴 유형은 null, 신호는 부재 — 기록에 근거가 없다.
            Judgment escalating = new Judgment("A", Grade.U4, new BigDecimal("0.91"),
                    new Judgment.Evidence("대출받으려면 이것도 들어야 한다고 해서요", "꺾기 조항"),
                    "꺾기 정황", null, "F-SCR-001_v2", true);

            recorder.appendJudgment(SID, escalating, 0, "질문 문면",
                    EvidenceRecorder.QuestionSource.DISPLAYED, null, T0);

            Map<String, Object> item = child(payloads().get(0), "judgment");
            assertThat(item.get("escalate"))
                    .as("상신 판단의 근거가 불변 기록에 남아야 한다 — 분쟁 시점에 답할 것이 이것뿐이다")
                    .isEqualTo(true);
            assertThat(item.get("misconceptionType"))
                    .as("이 경우 유형은 null 이다 — escalate 가 유일한 근거라는 것이 요지다")
                    .isNull();
        }

        @Test
        @DisplayName("❗Judgment 의 모든 필드가 기록된다 — 필드가 늘면 여기서 정하게 된다")
        void everyJudgmentFieldIsRecorded() {
            // judgmentPayload 의 javadoc 이 *"무엇을 담는지가 이 파일에 보여야 한다"* 고
            // 적어 두었지만 그건 규약일 뿐 강제가 아니었다. 실제로 #246 이 Judgment 에
            // escalate 를 더할 때 적재 쪽은 안 따라왔다 — 그 파일을 건드릴 일이 없었기
            // 때문이고, git 도 텍스트 충돌을 안 낸다.
            //
            // 이 단정이 그 규약을 강제한다. Judgment 에 필드가 늘면 여기서 실패하고,
            // 담을지 뺄지를 **적재 파일에서** 정하게 된다. 일부러 빼는 필드가 생기면
            // 아래 집합에 이유와 함께 적는다 — 지금은 없다.
            Set<String> deliberatelyOmitted = Set.of();

            recorder.appendJudgment(SID, judgment("A", Grade.U4, "0.91"), 0, "질문 문면",
                    EvidenceRecorder.QuestionSource.DISPLAYED, null, T0);
            Set<String> recorded = child(payloads().get(0), "judgment").keySet();

            List<String> components = Arrays.stream(Judgment.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(name -> !deliberatelyOmitted.contains(name))
                    .toList();

            assertThat(recorded)
                    .as("Judgment 에 필드가 늘었는데 적재가 안 따라왔다 — "
                            + "담을 것인지 뺄 것인지를 StoredEvidenceRecorder.judgmentPayload 에서 정한다")
                    .containsAll(components);
        }

        @Test
        @DisplayName("적재한 뒤에도 사슬이 검증된다 — 왕복이 성립한다")
        void chainStaysVerifiableAfterReplay() {
            recorder.appendJudgment(SID, judgment("A", Grade.U4, "0.91"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, null, T0);
            recorder.appendJudgment(SID, judgment("B", Grade.U1, "0.95"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, null, T0.plusSeconds(30));
            em.flush();

            assertThat(store.verify(StoredEvidenceRecorder.streamOf(SID)).ok()).isTrue();
        }
    }

    @Nested
    @DisplayName("신뢰도는 수로 담기고 수로 되읽힌다")
    class Confidence {

        /**
         * <b>scale 을 단정하지 않는 이유</b>: {@link CanonicalJson} 이
         * {@code stripTrailingZeros()} 로 정규화하므로 담는 쪽이 {@code setScale(4)} 로
         * 다시 매겨도 저장 바이트가 같다 — 실측했다. 그러니 여기서 scale 을 지킨다고 적으면
         * <b>지키지 않는 것을 지킨다고 적는 것</b>이 된다. scale 불변은 CanonicalJsonTest 의 몫이다.
         *
         * <p>이 테스트가 실제로 지키는 것은 <b>타입과 값</b>이다. {@code toPlainString()} 으로
         * 문자열이 되거나 {@code doubleValue()} 로 새면 계약({@code judgment.schema.json} —
         * {@code number}, 0~1)과 어긋나고, 되읽는 매퍼의 {@code USE_BIG_DECIMAL_FOR_FLOATS}
         * 설정이 빠지면 {@code Double} 로 돌아온다. 셋 다 여기서 깨진다.
         */
        @Test
        @DisplayName("문자열이나 Double 이 아니라 BigDecimal 값으로 왕복한다")
        void roundTripsAsNumber() {
            recorder.appendJudgment(SID, judgment("A", Grade.U4, "0.91"), 0, "질문 문면", EvidenceRecorder.QuestionSource.DISPLAYED, null, T0);

            Object confidence = child(payloads().get(0), "judgment").get("confidence");
            assertThat(confidence)
                    .as("계약이 number 다 — 문자열로 담기거나 Double 로 되읽히면 안 된다")
                    .isInstanceOf(BigDecimal.class);
            assertThat(confidence)
                    .isEqualTo(new BigDecimal("0.91"));
        }
    }
    @Nested
    @DisplayName("모순 판정이 근거와 함께 남는다 (이슈 #169)")
    class MismatchIsRecordedWithItsBasis {

        private static final Map<String, Object> SURVEY = Map.of(
                "SUIT-RISK-TOLERANCE", "원금 손실은 감수할 수 있다",
                "SUIT-PRODUCT-EXPERIENCE", "있고 이득을 봤다");

        private SuitabilityMismatch detected() {
            return new SuitabilityMismatch(
                    SuitabilityStatus.MISMATCH,
                    "설문은 손실 감수 가능인데 발화는 원금 보장을 전제한다",
                    new BigDecimal("0.82"),
                    List.of(Map.of("axis", "risk_tolerance",
                            "survey", "원금 손실은 감수할 수 있다",
                            "utterance", "원금은 지켜지죠")));
        }

        @Test
        @DisplayName("❗왜 모순인지가 기록에 남는다 — 전에는 게이트의 ruleTrace 뿐이었다")
        void theBasisIsStored() {
            recorder.appendMismatch(SID, detected(), "s02-survey-v2", SURVEY, T0);

            Map<String, Object> p = payloads().get(0);
            assertThat(p).containsEntry("type", "mismatch");
            assertThat(p.get("reason"))
                    .as("이 판정이 R-02 로 게이트를 움직이는데 게이트 기록에는 ruleTrace 밖에 "
                            + "없다. 근거가 여기 없으면 감사 시점에 '왜 모순인가' 에 답할 것이 "
                            + "하나도 없다(#169)")
                    .isEqualTo("설문은 손실 감수 가능인데 발화는 원금 보장을 전제한다");
            assertThat(p.get("confidence")).isEqualTo(new BigDecimal("0.82"));
            assertThat(p).extracting("contradictions").asList().hasSize(1);
        }

        @Test
        @DisplayName("❗판정을 만든 입력도 남는다 — 세션 테이블은 덮인다")
        void theInputIsStored() {
            recorder.appendMismatch(SID, detected(), "s02-survey-v2", SURVEY, T0);

            Map<String, Object> p = payloads().get(0);
            assertThat(p)
                    .as("설문 답변과 그 세트 버전은 세션 필드에만 있었다. 선택지 문면이 바뀌면 "
                            + "같은 세트라고 적힌 두 기록이 서로 다른 문면을 담는다(결정 5.18 과 "
                            + "같은 자리)")
                    .containsEntry("surveySchemaVersion", "s02-survey-v2");
            assertThat(p).extracting("surveyResult").asInstanceOf(
                    org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("SUIT-RISK-TOLERANCE", "원금 손실은 감수할 수 있다");
        }

        @Test
        @DisplayName("❗호출 실패로 근거가 없으면 그 사유가 남는다 — 비어 있는 것과 다르다")
        void unknownCarriesWhyItHasNoBasis() {
            recorder.appendMismatch(SID,
                    SuitabilityMismatch.unknown("ai-service 호출 실패 — 판정하지 못했다"),
                    "s02-survey-v2", SURVEY, T0);

            Map<String, Object> p = payloads().get(0);
            assertThat(p).containsEntry("status", SuitabilityStatus.UNKNOWN.name());
            assertThat(p.get("reason"))
                    .as("근거가 비었다와 못 받았다가 기록에서 같아 보이면 안 된다 — "
                            + "E-EXT-03 과 같은 결이다")
                    .isEqualTo("ai-service 호출 실패 — 판정하지 못했다");
        }

        @Test
        @DisplayName("null 을 생략하지 않는다 — 필드 이전 레코드와 값 없는 판정을 가른다")
        void nullsAreWrittenNotOmitted() {
            recorder.appendMismatch(SID,
                    new SuitabilityMismatch(SuitabilityStatus.NO_MISMATCH, null, null, List.of()),
                    null, Map.of(), T0);

            assertThat(payloads().get(0))
                    .containsKeys("reason", "confidence", "contradictions",
                            "surveySchemaVersion", "surveyResult");
        }

        @Test
        @DisplayName("게이트와 다른 사슬 항목이다 — 재검증마다 도는 게이트와 발생 시점이 다르다")
        void mismatchIsItsOwnKind() {
            recorder.appendMismatch(SID, detected(), "s02-survey-v2", SURVEY, T0);
            recorder.appendGate(SID, new GateResult(Signal.YELLOW, List.of(new RuleRef("R-02", "테스트 문면")), 0, 3), T0.plusSeconds(1));

            assertThat(payloads()).extracting(p -> p.get("type"))
                    .as("게이트에 얹으면 재검증마다 같은 모순 근거가 중복으로 쌓인다")
                    .containsExactly("mismatch", "gate");
        }
    }

}
