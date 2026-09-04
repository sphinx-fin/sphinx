package com.sphinxfin.sphinx.core.pii;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 경계가 <b>몇 번 작동했는지</b> 세되 <b>원문은 안 남긴다</b>. 소유: 강희진 (이슈 #326)
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>보안·개인정보 주장이 전부 코드 주석과 정책 파일로만 있었다. 심사에서
 * <i>"개인정보는 어떻게 처리하나요"</i> 가 나오면 답이 주석이었다.
 *
 * <p>그리고 이 기능은 <b>잘못 만들면 그 자체가 유출</b>이다 — 무엇이 걸렸는지를 남기는
 * 순간 지우려고 만든 경로가 새는 자리가 된다. 그래서 <b>안 남기는 것</b>을 먼저 잰다.
 */
@DisplayName("P3 마스킹 계량기 (이슈 #326)")
class PiiMeterTest {

    /** 실제 값이 아니다 — 패턴만 맞춘 합성 문자열이다. */
    private static final String UTTERANCE =
            "제 번호는 010-1234-5678 이고 메일은 hong@example.com 입니다. "
            + "카드 1234-5678-9012-3456 도 적어 둘게요.";

    @Test
    @DisplayName("❗종류별로 센다 — 마스킹이 동작했다는 증거가 코드 읽기 말고 없었다")
    void itCountsWhatWasRemoved() {
        PiiGateway.Masked masked = PiiGateway.maskWithHits(UTTERANCE);

        assertThat(masked.hits())
                .containsEntry("PHONE", 1)
                .containsEntry("EMAIL", 1)
                .containsEntry("CARD", 1);
        assertThat(masked.total()).isEqualTo(3);
    }

    @Test
    @DisplayName("❗안 걸린 종류는 키가 없다 — 0 을 채우면 '안 걸렸다' 와 '이 종류가 생기기 전' 이 같아진다")
    void kindsThatDidNotMatchAreAbsent() {
        assertThat(PiiGateway.maskWithHits("원금이 줄 수 있다고 이해했습니다").hits())
                .isEmpty();
    }

    @Test
    @DisplayName("❗계량기에 원문이 없다 — 무엇이 걸렸는지를 남기면 그게 PII 저장이다")
    void theMeterNeverHoldsTheText() {
        PiiMeter meter = new PiiMeter();
        meter.record(PiiGateway.maskWithHits(UTTERANCE));

        String everything = meter.summary() + meter.removed() + meter.calls();

        assertThat(everything)
                .as("계량기가 들고 있는 것은 종류 이름과 개수뿐이어야 한다 — 조각 하나라도 "
                        + "남으면 지우려고 만든 경로가 새는 자리가 된다")
                .doesNotContain("010")
                .doesNotContain("1234")
                .doesNotContain("hong")
                .doesNotContain("example.com");

        assertThat(Arrays.stream(PiiGateway.Masked.class.getRecordComponents())
                .map(RecordComponent::getName))
                .as("Masked 에 원문 조각을 담는 필드가 생기면 여기서 걸린다")
                .containsExactlyInAnyOrder("text", "hits");
    }

    @Test
    @DisplayName("❗세션·행위자 축이 없다 — 붙는 순간 '이 고객이 주민번호를 적었다' 가 된다")
    void theMeterHasNoPerSubjectAxis() {
        assertThat(Arrays.stream(PiiMeter.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .as("우리가 지운 사실 자체를 다시 만드는 축이 생기면 안 된다")
                .doesNotContain("bySession", "byActor", "forSession");
        assertThat(Arrays.stream(PiiMeter.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("record"))
                .allMatch(m -> m.getParameterCount() == 1))
                .as("record 에 세션·행위자 인자가 붙으면 축이 생긴 것이다")
                .isTrue();
    }

    @Test
    @DisplayName("한 건도 안 걸린 호출도 센다 — 분모가 있어야 비율이 선다")
    void callsWithNoHitsStillCount() {
        PiiMeter meter = new PiiMeter();
        meter.record(PiiGateway.maskWithHits("깨끗한 발화"));
        meter.record(PiiGateway.maskWithHits(UTTERANCE));

        assertThat(meter.calls()).isEqualTo(2);
        assertThat(meter.removed()).containsEntry("PHONE", 1L);
    }

    @Test
    @DisplayName("❗두 번 마스킹하면 두 번째는 0 건이다 — 멱등이라던 주석이 참인지 본다")
    void maskingTwiceFindsNothingTheSecondTime() {
        // AiServiceClient:137 이 "이미 마스킹된 값이지만 mask() 는 멱등이라 한 번 더 태운다"
        // 고 적어 두고 있다. 그 문장이 참이 아니면 같은 발화가 두 번 계상된다.
        PiiGateway.Masked once = PiiGateway.maskWithHits(UTTERANCE);
        PiiGateway.Masked twice = PiiGateway.maskWithHits(once.text());

        assertThat(twice.text()).isEqualTo(once.text());
        assertThat(twice.hits())
                .as("두 번째 통과가 또 세면 경계를 두 번 지나는 발화가 두 배로 잡힌다")
                .isEmpty();
    }

    @Test
    @DisplayName("❗같은 숫자가 두 종류로 중복 계상되지 않는다 — 뒤 패턴은 앞이 지운 뒤를 본다")
    void oneStringIsCountedOnce() {
        // 카드번호(4-4-4-4)는 계좌번호 패턴(구분자 3구획)에도 맞는다. 순서대로 세지 않고
        // 각 패턴을 원문에 따로 돌리면 CARD 와 ACCOUNT 로 두 번 잡힌다.
        Map<String, Integer> hits = PiiGateway.maskWithHits("카드 1234-5678-9012-3456").hits();

        assertThat(hits).containsOnlyKeys("CARD");
    }
}
