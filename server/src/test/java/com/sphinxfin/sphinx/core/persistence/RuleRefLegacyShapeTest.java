package com.sphinxfin.sphinx.core.persistence;

import com.sphinxfin.sphinx.domain.RuleRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 옛 저장 모양 수용 (결정 10.74 의 2번). 소유: 강희진
 *
 * gateRuleTrace 는 #320 전까지 List&lt;String&gt; 이라 DB 에 ["R-01"] 로 저장됐다.
 * 영속 DB(#410) 뒤에는 그 행이 프로세스보다 오래 살므로, 옛 모양을 못 읽으면
 * **옛 세션 조회가 전부 500** 이다. 이 테스트가 잠그는 것 셋:
 * 새 모양 왕복 · 옛 모양 읽기 · 옛 것을 읽어 다시 쓰면 새 모양으로 나간다(전방 이행).
 */
@DisplayName("RuleRef 옛 모양(맨 문자열) 수용 — 10.74 ②")
class RuleRefLegacyShapeTest {

    private final RuleRefListConverter converter = new RuleRefListConverter();

    @Test
    @DisplayName("새 모양({id,label} 객체 배열)은 그대로 왕복한다 — 위임 생성자가 객체 파싱을 깨지 않는다")
    void newShapeRoundTrips() {
        List<RuleRef> trace = List.of(new RuleRef("R-01", "오해로 판정된 항목이 있습니다"));

        String column = converter.convertToDatabaseColumn(trace);
        List<RuleRef> back = converter.convertToEntityAttribute(column);

        assertThat(back).containsExactly(new RuleRef("R-01", "오해로 판정된 항목이 있습니다"));
    }

    @Test
    @DisplayName("옛 모양([\"R-01\",\"R-05\"])을 읽는다 — 문면은 재계산하지 않고 '기록 없음'으로")
    void legacyStringArrayReads() {
        List<RuleRef> back = converter.convertToEntityAttribute("[\"R-01\",\"R-05\"]");

        assertThat(back).containsExactly(
                new RuleRef("R-01", RuleRef.LEGACY_LABEL),
                new RuleRef("R-05", RuleRef.LEGACY_LABEL));
    }

    @Test
    @DisplayName("섞인 배열(옛 문자열 + 새 객체)도 읽힌다 — 이행기 행을 버리지 않는다")
    void mixedArrayReads() {
        String mixed = "[\"R-01\", {\"id\":\"R-05\",\"label\":\"판정 신뢰도가 낮은 항목이 있습니다\"}]";

        List<RuleRef> back = converter.convertToEntityAttribute(mixed);

        assertThat(back).containsExactly(
                new RuleRef("R-01", RuleRef.LEGACY_LABEL),
                new RuleRef("R-05", "판정 신뢰도가 낮은 항목이 있습니다"));
    }

    @Test
    @DisplayName("옛 것을 읽어 다시 쓰면 새 모양(객체)으로 나간다 — 저장이 앞으로만 이행한다")
    void legacyReadWritesForwardAsNewShape() {
        List<RuleRef> read = converter.convertToEntityAttribute("[\"R-01\"]");

        String rewritten = converter.convertToDatabaseColumn(read);

        assertThat(rewritten).contains("\"id\":\"R-01\"").contains(RuleRef.LEGACY_LABEL)
                .doesNotStartWith("[\"");   // 맨 문자열 배열로 되돌아가지 않는다
    }
}
