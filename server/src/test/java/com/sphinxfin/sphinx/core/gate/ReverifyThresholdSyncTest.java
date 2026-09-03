package com.sphinxfin.sphinx.core.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 재검증 상한의 <b>단일 출처</b>를 지킨다 (이슈 #66). 소유: 강희진
 *
 * <h2>무엇이 바뀌었나</h2>
 *
 * <p>전에는 상한 N 이 두 곳에 있었다 — {@code application.yml}({@code sphinx.scoring.max-reverify})
 * 과 {@code gate_rules.yaml}({@code R-03 reverifyFailed >= N}). 이 파일은 <b>둘을 대조</b>하고
 * 있었다.
 *
 * <p>대조는 <b>어긋난 뒤에 잡는 것</b>이다. 고칠 자리가 둘이면 언젠가 한 곳만 바뀌고, 그때
 * 이 테스트가 빨개져도 <b>어느 쪽이 맞는지는 사람이 정해야</b> 했다. ADR-005 가 임계값의
 * 단일 출처를 {@code gate_rules.yaml} 로 정해 뒀으니 그쪽으로 접었다 — 룰이 숫자를 소유하고
 * {@code SessionService} 가 {@link GateEngine#reverifyThreshold()} 로 읽는다.
 *
 * <p>그래서 이 파일이 지키는 것도 바뀐다: <b>"둘이 같은가" 가 아니라 "둘째가 다시 생기지
 * 않는가"</b> 다.
 */
@DisplayName("재검증 상한 단일 출처 — gate_rules.yaml R-03")
class ReverifyThresholdSyncTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    @DisplayName("❗application.yml 에 max-reverify 가 다시 생기지 않는다 — 출처가 둘이 된다")
    void configDoesNotReintroduceTheThreshold() throws Exception {
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            JsonNode node = YAML.readTree(in).at("/sphinx/scoring/max-reverify");
            assertThat(node.isMissingNode())
                    .as("여기 값을 되살리면 gate_rules.yaml R-03 과 두 곳이 된다. 둘이 어긋나면 "
                            + "상한과 게이트가 따로 논다 — 상한 3인데 게이트가 2에서 RED 면 "
                            + "3번째 재설명 기회가 무의미해진다. ADR-005 대로 룰이 소유한다(#66)")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("❗엔진이 내는 상한이 gate_rules.yaml R-03 의 값이다")
    void engineReadsTheThresholdFromTheRule() throws Exception {
        assertThat(new GateEngine().reverifyThreshold())
                .as("엔진이 파일이 아니라 상수를 들고 있으면 룰을 고쳐도 상한이 안 따라온다")
                .isEqualTo(thresholdInFile());
    }

    @Test
    @DisplayName("❗R-03 이 없으면 기동에서 던진다 — 기본값으로 떨어지면 조용히 돈다")
    void missingRuleFailsFast() {
        GateEngine noReverifyRule = new GateEngine(java.util.List.of(
                new GateEngine.Rule("R-06", "모든 항목의 이해가 확인되었습니다", "allGrade == 'U1'",
                        ctx -> true, com.sphinxfin.sphinx.domain.Signal.GREEN)));

        assertThatThrownBy(noReverifyRule::reverifyThreshold)
                .as("기본값으로 떨어뜨리면 R-03 을 지운 파일이 조용히 돌고, 재검증이 영원히 "
                        + "안 끝나거나 게이트가 안 잡는다. 로드 시점 fail-fast 가 이 엔진의 규약이다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reverifyFailed");
    }

    /** gate_rules.yaml R-03 의 N — 엔진과 무관하게 파일에서 직접 읽는다. */
    private int thresholdInFile() throws Exception {
        try (var in = new ClassPathResource("gate_rules.yaml").getInputStream()) {
            JsonNode rules = YAML.readTree(in).get("rules");
            assertThat(rules).as("gate_rules.yaml 에 rules 배열이 있어야 한다").isNotNull();
            for (JsonNode rule : rules) {
                if ("R-03".equals(rule.path("id").asText())) {
                    var m = java.util.regex.Pattern
                            .compile("reverifyFailed\\s*>=\\s*(\\d+)")
                            .matcher(rule.path("if").asText());
                    assertThat(m.find()).as("R-03 if 가 reverifyFailed >= N 형태여야 한다").isTrue();
                    return Integer.parseInt(m.group(1));
                }
            }
            throw new AssertionError("gate_rules.yaml 에 R-03 규칙이 없다");
        }
    }
}
