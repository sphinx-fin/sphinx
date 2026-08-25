package com.sphinxfin.sphinx.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재검증 상한의 단일 논리값을 지킨다(#66). 소유: 강희진
 *
 * 상한 "N"이 두 곳에 있다 — application.yml(sphinx.scoring.max-reverify, 재설명 상한)과
 * gate_rules.yaml(R-03 reverifyFailed>=N, 상한 실패 시 RED). 둘은 같은 논리값이어야 한다:
 * 상한만큼 재검증에 실패하면 게이트가 잡아야 하기 때문이다. 한쪽만 바꾸면 상한과 게이트가
 * 따로 놀아 — 예: 상한 3인데 게이트가 2에서 RED — 3번째 재설명 기회가 무의미해진다.
 * 사람 눈 대신 이 테스트가 대조한다(gate_rules는 선언적 파일이라 GateEngine을 건드리지 않는다).
 */
@DisplayName("재검증 상한 단일 논리값 — application.yml ≡ gate_rules.yaml R-03")
class ReverifyThresholdSyncTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    @DisplayName("sphinx.scoring.max-reverify == gate_rules R-03 reverifyFailed 임계값")
    void maxReverifyMatchesGateThreshold() throws Exception {
        assertThat(maxReverifyFromConfig())
                .as("application.yml max-reverify를 바꿨으면 gate_rules.yaml R-03도 같이 바꿔야 한다")
                .isEqualTo(reverifyThresholdFromGateRules());
    }

    /** application.yml의 sphinx.scoring.max-reverify. */
    private int maxReverifyFromConfig() throws Exception {
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            JsonNode node = YAML.readTree(in).at("/sphinx/scoring/max-reverify");
            assertThat(node.isInt()).as("application.yml에 sphinx.scoring.max-reverify 정수값이 있어야 한다").isTrue();
            return node.asInt();
        }
    }

    /** gate_rules.yaml R-03(reverifyFailed >= N)의 N. */
    private int reverifyThresholdFromGateRules() throws Exception {
        try (var in = new ClassPathResource("gate_rules.yaml").getInputStream()) {
            JsonNode rules = YAML.readTree(in).get("rules");
            assertThat(rules).as("gate_rules.yaml에 rules 배열이 있어야 한다").isNotNull();
            Pattern threshold = Pattern.compile("reverifyFailed\\s*>=\\s*(\\d+)");
            for (JsonNode rule : rules) {
                if ("R-03".equals(rule.path("id").asText())) {
                    Matcher m = threshold.matcher(rule.path("if").asText());
                    assertThat(m.find()).as("R-03 if 가 reverifyFailed >= N 형태여야 한다").isTrue();
                    return Integer.parseInt(m.group(1));
                }
            }
            throw new AssertionError("gate_rules.yaml에 R-03 규칙이 없다");
        }
    }
}
