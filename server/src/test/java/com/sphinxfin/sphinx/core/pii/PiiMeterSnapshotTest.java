package com.sphinxfin.sphinx.core.pii;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PiiMeter#snapshot()} — 조회 경로가 내는 값 (이슈 #326 파트1). 소유: 강희진
 *
 * <p>여기서 재는 것은 <b>계량이 아니라 요약의 규약</b>이다: 안 걸린 종류가 0 으로 있는가,
 * 순서가 흔들리지 않는가, 원문이 안 실리는가.
 */
@DisplayName("PiiMeter.snapshot — 조회 경로가 내는 값")
class PiiMeterSnapshotTest {

    @Test
    @DisplayName("❗안 걸린 종류도 0 으로 있다 — 키를 빼면 「0 건」과 「그런 패턴이 없다」가 같아진다")
    void everyKnownKindIsPresentEvenAtZero() {
        PiiMeter meter = new PiiMeter();

        PiiMeter.Summary summary = meter.snapshot();

        assertThat(summary.removedByKind())
                .as("결정 5.40 과 같은 구별이다 — 못 잰 것·0 건·없는 것이 화면에서 갈려야 한다")
                .containsOnlyKeys(PiiGateway.kinds().toArray(String[]::new))
                .containsValue(0L);
        assertThat(summary.calls()).isZero();
        assertThat(summary.callsWithRemovals()).isZero();
        assertThat(summary.removedTotal()).isZero();
        assertThat(summary.since()).isNotNull();
    }

    @Test
    @DisplayName("❗호출 수와 삭제 건수를 가른다 — 한 호출에서 셋이 지워질 수 있다")
    void callsAndRemovalsAreDifferentQuestions() {
        PiiMeter meter = new PiiMeter();

        // 아무것도 안 걸린 호출 하나 — 분모에는 들어가고 분자에는 안 들어간다.
        meter.record(PiiGateway.maskWithHits("원금 손실은 감수하기 어렵습니다"));
        // 한 호출에서 둘이 지워진다.
        meter.record(PiiGateway.maskWithHits("010-1234-5678 · a@b.co.kr"));

        PiiMeter.Summary summary = meter.snapshot();

        assertThat(summary.calls()).isEqualTo(2);
        assertThat(summary.callsWithRemovals())
                .as("«마스킹이 실제로 도는가» 의 답은 이쪽이다 — 삭제 건수만으로는 못 답한다")
                .isEqualTo(1);
        assertThat(summary.removedTotal())
                .as("한 호출에서 둘이 지워졌으므로 호출 수와 다르다")
                .isEqualTo(2);
        assertThat(summary.removedByKind().get("PHONE")).isEqualTo(1L);
        assertThat(summary.removedByKind().get("EMAIL")).isEqualTo(1L);
        assertThat(summary.removedByKind().get("RRN")).isZero();
    }

    /**
     * ★ <b>{@code Map.copyOf} 회귀 가드.</b>
     *
     * <p>처음에 {@code Map.copyOf(byKind)} 로 썼는데 그것이 내는 불변 맵은 <b>삽입 순서를
     * 보장하지 않는다</b>({@code ImmutableCollections.MapN}) — 바로 위에
     * <i>"선언 순서를 유지한다"</i> 라고 적어 둔 주석과 어긋났고, 직렬화 순서가 실행마다
     * 흔들렸다. 주석이 코드를 반박하는 상태였다.
     *
     * <p>❗<b>그 순서는 계약이 아니다</b>(JSON 객체 키 순서는 규격상 무의미하다). 이 단정이
     * 지키는 것은 <i>"소비자가 순서에 의존해도 된다"</i> 가 아니라 <b>그 회귀가 다시
     * 들어오지 않는 것</b>이다 — 읽는 사람에게 {@code ACCOUNT} 가 마지막인 것이 숫자를
     * 설명해 주기 때문이다(카드·전화가 먼저 지워지고 남은 것만 계좌로 센다).
     */
    @Test
    @DisplayName("★ 키 순서가 마스킹 순서다 — Map.copyOf 로 되돌리면 흔들린다")
    void theKeyOrderFollowsTheMaskingOrder() {
        PiiMeter meter = new PiiMeter();

        assertThat(meter.snapshot().removedByKind().keySet())
                .containsExactlyElementsOf(PiiGateway.kinds());
    }

    @Test
    @DisplayName("❗요약에 원문 조각이 없다 — 종류 이름과 개수뿐이다")
    void theSummaryCarriesNoText() {
        PiiMeter meter = new PiiMeter();
        meter.record(PiiGateway.maskWithHits("주민번호 901201-1234567 입니다"));

        PiiMeter.Summary summary = meter.snapshot();

        assertThat(summary.removedByKind().get("RRN")).isEqualTo(1L);
        assertThat(summary.toString())
                .as("무엇이 걸렸는지를 남기면 그게 곧 PII 저장이다 — 지우려고 만든 경로가 "
                        + "새는 자리가 된다")
                .doesNotContain("901201")
                .doesNotContain("1234567");
        for (Map.Entry<String, Long> entry : summary.removedByKind().entrySet()) {
            assertThat(entry.getKey()).isIn(PiiGateway.kinds());
        }
    }
}
