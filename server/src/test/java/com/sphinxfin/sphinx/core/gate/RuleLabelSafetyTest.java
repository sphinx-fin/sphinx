package com.sphinxfin.sphinx.core.gate;

import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RuleRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 룰 문면이 <b>결과만 말하고 조건은 말하지 않는가</b>. 소유: 강희진 (이슈 #320)
 *
 * <h2>왜 사람이 지키면 안 되나</h2>
 *
 * <p>{@code label} 은 <b>판매자가 읽는 글</b>이다. 룰 조건을 그대로 적으면 판매자가
 * <b>무엇을 피해야 하는지</b> 알게 된다 — 기획서 7-4 역이용 방지이고, {@code #144} 가
 * {@code misconceptionType} 을 판매자 뷰에서 뺀 것과 같은 결이다.
 *
 * <pre>
 * 좋음   R-05  "판정 신뢰도가 낮은 항목이 있습니다"
 * 나쁨   R-05  "신뢰도 0.7 미만인 항목이 있습니다"      ← 그 위를 겨냥할 수 있다
 * </pre>
 *
 * <p>{@code #320} 리뷰에서 <i>"'결과만 말하고 조건은 안 말한다' 를 사람이 지키는 규약으로
 * 두면 다음 룰에서 새는 자리가 된다"</i> 는 지적이 나왔고, 이 파일이 그 답이다.
 * {@code JudgmentViewFieldsTest} 가 판매자 뷰의 필드를 <b>이름으로</b> 잠근 것과 같은 자리다.
 *
 * <p><b>무엇을 재는가.</b> 조건의 <b>숫자</b>가 새는 것을 잡는다 — 임계값이 그 자체로
 * 악용 가능한 값이라 가장 비싸고, 기계로 확실히 볼 수 있다. 문장의 뜻까지는 못 재므로
 * 사람 리뷰를 대체하지 않는다. 다만 <b>가장 흔한 새는 방식</b>은 여기서 막힌다.
 */
@DisplayName("룰 문면 — 결과만 말하고 조건은 말하지 않는다 (이슈 #320)")
class RuleLabelSafetyTest {

    private static final Path RULES = Path.of("src/main/resources/gate_rules.yaml");

    /** {@code - id: R-00} 다음 줄의 {@code label: "…"}. */
    private static final Pattern RULE_WITH_LABEL = Pattern.compile(
            "(?m)^\\s*- id:\\s*(\\S+)\\s*\\n\\s*label:\\s*\"([^\"]*)\"");

    /** 조건 표현식이 쓰는 이름 — 문면에 나오면 룰의 내부 어휘가 샌 것이다. */
    private static final List<String> INTERNALS = List.of(
            "unmeasured", "anyGrade", "allGrade", "suitabilityMismatch",
            "suitabilityUnknown", "reverifyFailed", "anyConfidenceBelow",
            "U1", "U2", "U3", "U4", "GREEN", "YELLOW", "RED");

    @Test
    @DisplayName("❗문면에 숫자가 없다 — 임계값이 새면 그 위를 겨냥할 수 있다")
    void noLabelLeaksAThreshold() throws IOException {
        Map<String, String> offenders = new TreeMap<>();
        labels().forEach((id, label) -> {
            if (label.matches(".*\\d.*")) {
                offenders.put(id, label);
            }
        });

        assertThat(offenders)
                .as("문면은 결과만 말한다. 조건의 숫자를 적으면 판매자가 그 경계를 알게 되고, "
                        + "그건 게이트가 막으려는 것을 게이트가 알려주는 것이다 (기획서 7-4)")
                .isEmpty();
    }

    @Test
    @DisplayName("❗문면에 룰의 내부 어휘가 없다 — 등급·신호·조건 변수명은 판매자의 말이 아니다")
    void noLabelLeaksTheRuleVocabulary() throws IOException {
        Map<String, String> offenders = new TreeMap<>();
        labels().forEach((id, label) -> {
            for (String word : INTERNALS) {
                if (label.contains(word)) {
                    offenders.put(id, label + "  ← " + word);
                }
            }
        });

        assertThat(offenders)
                .as("판매자가 읽는 글이다. 조건 변수명이나 등급 코드가 그대로 나오면 "
                        + "룰을 역산할 재료가 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("❗모든 룰에 문면이 있다 — 없으면 화면이 그 룰에 대해 아무 말도 못 한다")
    void everyRuleHasALabel() throws IOException {
        List<String> declared = ids();
        assertThat(labels().keySet())
                .as("gate_rules.yaml 의 룰 수와 label 수가 다르다 — label 없는 룰이 있으면 "
                        + "로드가 막히지만, 여기서 어느 룰인지 이름으로 말한다")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    @DisplayName("❗fail-closed 판정에도 문면이 있다 — 룰 파일 밖이라 여기서만 잡힌다")
    void theFailClosedVerdictAlsoSpeaks() {
        RuleRef fallback = new GateEngine(List.of()).judge(
                List.of(judgment()), false, false, 0, 0).ruleTrace().get(0);

        assertThat(fallback.id()).isEqualTo(GateEngine.DEFAULT_TRACE);
        assertThat(fallback.label())
                .as("룰이 하나도 안 맞은 판정일수록 왜 막혔는지가 필요하다")
                .isNotBlank()
                .doesNotMatch(".*\\d.*");
    }

    /** {@code gate_rules.yaml} 의 룰 ID → 문면. */
    private static Map<String, String> labels() throws IOException {
        Map<String, String> out = new TreeMap<>();
        Matcher m = RULE_WITH_LABEL.matcher(Files.readString(RULES, StandardCharsets.UTF_8));
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        assertThat(out)
                .as("gate_rules.yaml 에서 문면을 하나도 못 읽었다 — 파일 모양이 바뀌었으면 "
                        + "이 정규식도 같이 고친다. 안 고치면 이 검사가 통째로 공회전한다")
                .isNotEmpty();
        return out;
    }

    /** 선언된 룰 ID 전부 — 문면 유무와 무관하게 센다. */
    private static List<String> ids() throws IOException {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^\\s*- id:\\s*(\\S+)")
                .matcher(Files.readString(RULES, StandardCharsets.UTF_8));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static Judgment judgment() {
        return new Judgment("ITEM", Grade.U1, new BigDecimal("0.9"),
                new Judgment.Evidence("발화 인용", "루브릭 조항"), "사유", null);
    }
}
