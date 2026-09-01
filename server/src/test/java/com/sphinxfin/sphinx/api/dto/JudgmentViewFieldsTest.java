package com.sphinxfin.sphinx.api.dto;

import com.sphinxfin.sphinx.domain.Judgment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판매자 응답이 담는 필드를 <b>이름으로</b> 잠근다. 소유: 강희진 (이슈 #160 · #147)
 *
 * <h2>어휘 검사만으로는 부족하다</h2>
 *
 * <p>{@code UnfairSignalNotExposedTest} 는 응답 본문에 <b>신호 어휘</b>가 없는지 본다. 강한
 * 검사지만 <b>이름을 아는 것만</b> 잡는다 — 고정 어휘와 오해 라이브러리에서 만들기 때문이다.
 * {@code escalate} 는 마침 고정 어휘에 있어서 덮이지만 <b>다음에 붙는 필드는 그렇지 않을 수
 * 있다.</b>
 *
 * <p>여기서는 반대로 잰다: <b>허용 목록에 없는 것이 늘면 깨진다.</b> 무엇이 위험한지 미리
 * 알아야 하는 검사와, 무엇이 안전한지만 알면 되는 검사는 다르다. 뒤엣것이 새 필드에 강하다.
 *
 * <p>깨졌다면 답할 것은 하나다 — <b>이 값을 판매자가 봐도 되는가.</b> 봐도 되면 목록에
 * 더하고, 아니면 {@link JudgmentView#of} 가 안 담으면 된다.
 */
@DisplayName("판매자 응답 필드 잠금 (이슈 #160 · #147)")
class JudgmentViewFieldsTest {

    /** 판매자가 봐도 되는 것. <b>더하기 전에 {@link JudgmentView} 의 javadoc 을 읽는다.</b> */
    private static final Set<String> SELLER_MAY_SEE =
            Set.of("itemId", "grade", "confidence", "evidence", "reason");

    /** 판매자에게 가면 안 되는 것 — 어느 발화가 탐지됐는지를 알려준다(기획 7-4). */
    private static final Set<String> NEVER =
            Set.of("misconceptionType", "escalate");

    @Test
    @DisplayName("❗JudgmentView 는 허용 목록 그대로다 — 늘면 깨진다")
    void theViewCarriesExactlyTheAllowedFields() {
        assertThat(componentsOf(JudgmentView.class))
                .as("판매자 응답에 필드가 늘었다. 이 값을 판매자가 봐도 되는지 먼저 답한다 — "
                        + "봐도 되면 SELLER_MAY_SEE 에 더하고, 아니면 JudgmentView.of 가 안 담는다")
                .containsExactlyInAnyOrderElementsOf(SELLER_MAY_SEE);
    }

    @Test
    @DisplayName("❗도메인 판정에는 있는데 판매자 뷰에는 없다 — 안 담은 것이지 버린 게 아니다")
    void theDomainKeepsWhatTheViewDrops() {
        List<String> domain = componentsOf(Judgment.class);

        // 먼저 도메인 쪽에 실제로 있는지 고정한다. 없으면 아래 단정이 공짜로 통과한다 —
        // 필드 이름이 바뀌었을 때 "안 샌다" 가 아니라 "안 잰다" 가 되는 자리다.
        assertThat(domain).containsAll(NEVER);

        assertThat(componentsOf(JudgmentView.class))
                .as("서버는 값을 들고 있고 판매자에게만 안 보낸다")
                .doesNotContainAnyElementsOf(NEVER);
    }

    private static List<String> componentsOf(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
