package com.sphinxfin.sphinx.evidence;

import com.sphinxfin.sphinx.domain.GateResult;
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

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

    private static Judgment judgment(String itemId, Grade grade, double confidence) {
        return new Judgment(itemId, grade, confidence,
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
            recorder.appendJudgment(SID, judgment("A", Grade.U2, 0.8), 0, T0);
            recorder.appendGate(SID, new GateResult(Signal.YELLOW, List.of("R-04", "R-05")), T0.plusSeconds(1));
            recorder.appendOverride(SID, "고객이 충분히 이해했다고 판단하여 진행합니다", "mgr-01", T0.plusSeconds(2));

            assertThat(payloads()).extracting(p -> p.get("type"))
                    .as("종류별로 스트림을 나누면 각 사슬은 온전한데 사이의 순서가 사라진다")
                    .containsExactly("judgment", "gate", "override");
            assertThat(store.verify(StoredEvidenceRecorder.streamOf(SID)).ok()).isTrue();
        }

        @Test
        @DisplayName("기록 한 건이 스스로 어느 세션·언제인지 말한다")
        void entriesAreSelfDescribing() {
            recorder.appendGate(SID, new GateResult(Signal.RED, List.of("R-01")), T0);

            Map<String, Object> payload = payloads().get(0);
            assertThat(payload.get("sessionId")).isEqualTo(SID);
            assertThat(payload.get("at"))
                    .as("CanonicalJson 이 ADR-008 형식(UTC · 밀리초 3자리)으로 적는다")
                    .isEqualTo("2026-08-27T01:00:00.000Z");
        }

        @Test
        @DisplayName("세션이 다르면 사슬도 다르다")
        void streamsAreSeparatedBySession() {
            recorder.appendGate(SID, new GateResult(Signal.RED, List.of("R-01")), T0);
            recorder.appendGate("S-2", new GateResult(Signal.GREEN, List.of()), T0);

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
            recorder.appendJudgment(SID, judgment("A", Grade.U3, 0.7), 0, T0);
            recorder.appendJudgment(SID, judgment("A", Grade.U1, 0.9), 1, T0.plusSeconds(60));

            List<Map<String, Object>> stored = payloads();
            assertThat(stored).hasSize(2);
            assertThat(stored).extracting(p -> child(p, "judgment").get("grade"))
                    .as("세션은 U1 만 들고 있다 — '처음에 U3 였다' 는 여기에만 남는다")
                    .containsExactly("U3", "U1");
            assertThat(stored).extracting(p -> String.valueOf(p.get("reverifyCount")))
                    .containsExactly("0", "1");
        }

        @Test
        @DisplayName("게이트 판정도 매 호출 남는다 — 최종 신호가 아니라 신호의 변천")
        void gateSignalsAccumulate() {
            recorder.appendGate(SID, new GateResult(Signal.RED, List.of("R-01")), T0);
            recorder.appendGate(SID, new GateResult(Signal.YELLOW, List.of("R-04")), T0.plusSeconds(60));
            recorder.appendGate(SID, new GateResult(Signal.GREEN, List.of()), T0.plusSeconds(120));

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
            recorder.appendJudgment(SID, judgment("A", Grade.U4, 0.91), 0, T0);

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
            recorder.appendJudgment(SID, judgment("A", Grade.U1, 0.95), 0, T0);

            assertThat(child(payloads().get(0), "judgment").keySet())
                    .contains("misconceptionType");
        }

        @Test
        @DisplayName("적재한 뒤에도 사슬이 검증된다 — 왕복이 성립한다")
        void chainStaysVerifiableAfterReplay() {
            recorder.appendJudgment(SID, judgment("A", Grade.U4, 0.91), 0, T0);
            recorder.appendJudgment(SID, judgment("B", Grade.U1, 0.95), 0, T0.plusSeconds(30));
            em.flush();

            assertThat(store.verify(StoredEvidenceRecorder.streamOf(SID)).ok()).isTrue();
        }
    }

    @Nested
    @DisplayName("10.32 임시 다리 — 없어져야 하는 코드다")
    class ConfidenceBridge {

        @Test
        @DisplayName("confidence 가 double 인 동안만 payload 변환이 필요하다 — 타입이 바뀌면 이 테스트가 알려준다")
        void bridgeIsNeededOnlyWhileConfidenceIsDouble() {
            RecordComponent confidence = Arrays.stream(Judgment.class.getRecordComponents())
                    .filter(c -> c.getName().equals("confidence"))
                    .findFirst().orElseThrow();

            assertThat(confidence.getType())
                    .as("깨졌다면 10.32 가 (1)번으로 닫힌 것이다. "
                            + "StoredEvidenceRecorder.confidenceOf 를 지우고 Judgment 를 그대로 넘기면 된다")
                    .isEqualTo(double.class);
        }

        @Test
        @DisplayName("BigDecimal.valueOf 라 이진 근사가 안 새어 나온다")
        void doesNotLeakBinaryApproximation() {
            recorder.appendJudgment(SID, judgment("A", Grade.U4, 0.91), 0, T0);

            Object confidence = child(payloads().get(0), "judgment").get("confidence");
            assertThat(confidence)
                    .as("new BigDecimal(0.91) 이었으면 0.9100000000000000355... 가 되고 해시가 흔들린다")
                    .isEqualTo(new BigDecimal("0.91"));
        }
    }
}
