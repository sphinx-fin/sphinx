package com.sphinxfin.sphinx.core.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 복창 캡(ai-service) ↔ R-05 임계값(게이트) 대조 (이슈 #268). 소유: 강희진
 *
 * <h2>왜 두 숫자를 묶어야 하나</h2>
 *
 * <p>R-05 는 문면상 <i>"모델이 자신 없다고 하면 황색"</i> 인데, <b>모델은 그 말을 하지
 * 않는다.</b> dev set 24건을 두 모델로 채점했을 때 자기보고 {@code < 0.7} 이 <b>0건</b>이었다
 * (프롬프트 v2 의 직교 축으로 바꾼 뒤에도 그렇다 — 이슈 #268).
 *
 * <p>실제로 R-05 를 발동시키는 전건은 우리 후처리다.
 *
 * <pre>
 *   PARROTED-RUBRIC-CLAUSE   모델 raw 1.0  →  cap_confidence_if_echoed  →  0.3  →  R-05
 * </pre>
 *
 * <p>❗그래서 <b>{@code ECHO_CONFIDENCE_CAP} 이 R-05 임계값보다 낮다는 것이 이 룰이 도는
 * 유일한 조건</b>인데, 두 값이 서로를 모른다. 캡을 0.7 이상으로 올리면 R-05 는 아무것도 안
 * 잡는 상태가 되고 <b>테스트도 게이트도 아무 말을 안 한다</b> — 게이트가 조용히 한 겹 얇아진다.
 *
 * <p>{@code ReverifyThresholdSyncTest} 가 {@code sphinx.scoring.max-reverify} 를 R-03 에서
 * 읽게 만든 것과 같은 자리다. 다만 여기서는 값을 <b>한쪽으로 옮길 수 없다</b> — 캡은
 * ai-service 의 측정 상수이고 임계값은 게이트의 결정이라 소유가 다르다(P1 · ADR-005).
 * 옮기는 대신 <b>부등호를 고정한다.</b>
 */
@DisplayName("복창 캡 < R-05 임계값 (이슈 #268)")
class EchoCapBelowR05Test {

    private static final Path SCORING = Path.of("../ai-service/app/scoring.py");
    private static final Path RULES = Path.of("src/main/resources/gate_rules.yaml");

    private static final Pattern CAP = Pattern.compile("^ECHO_CONFIDENCE_CAP\\s*=\\s*([0-9.]+)", Pattern.MULTILINE);
    private static final Pattern R05 = Pattern.compile("anyConfidenceBelow\\s+([0-9.]+)");

    /**
     * 자기일관성 불일치 캡 (F-SCR-001). 같은 발화를 다시 채점해 등급이 갈리면 씌운다.
     *
     * <p>❗복창 캡과 <b>같은 이유로 R-05 아래여야 한다</b> — 위면 숫자만 내려가고 게이트가
     * 아무 일도 안 한다. 그리고 라이브 confidence 가 6/6 전부 1.0 이라({@code #339})
     * <b>지금 R-05 를 실제로 물리는 경로가 이 둘뿐</b>이다.
     */
    private static final Pattern DISAGREE =
            Pattern.compile("^DISAGREEMENT_CONFIDENCE_CAP\\s*=\\s*([0-9.]+)", Pattern.MULTILINE);

    @Test
    @DisplayName("❗복창으로 깎은 값이 R-05 를 발동시킨다 — 캡이 임계값 이상이면 룰이 죽는다")
    void theEchoCapStillTripsR05() throws Exception {
        BigDecimal cap = read(SCORING, CAP, "ai-service 의 ECHO_CONFIDENCE_CAP");
        BigDecimal threshold = read(RULES, R05, "gate_rules.yaml 의 R-05 임계값");

        assertThat(cap)
                .as("복창 캡(%s)이 R-05 임계값(%s) 이상이면, 복창을 잡아 깎아도 게이트가 "
                        + "황색으로 안 내린다. 모델 자기보고는 이 룰을 발동시킨 적이 없으므로"
                        + "(#268 실측) 그때 R-05 는 **아무것도 안 잡는 룰**이 된다 — "
                        + "둘 중 하나를 고칠 때 다른 하나를 같이 본다", cap, threshold)
                .isLessThan(threshold);
    }

    /**
     * 못 읽으면 <b>실패시킨다.</b> 정규식이 낡아 값을 못 뽑으면 위 단정이 무엇을 비교하든
     * 통과하게 되고, 그건 대조가 없는 것과 같다.
     */
    @Test
    @DisplayName("❗자기일관성 캡 < R-05 임계값 — 위면 게이트가 안 받는다 (F-SCR-001)")
    void theDisagreementCapAlsoTripsTheRule() throws Exception {
        BigDecimal cap = read(SCORING, DISAGREE, "ai-service 의 DISAGREEMENT_CONFIDENCE_CAP");
        BigDecimal threshold = read(RULES, R05, "gate_rules.yaml 의 R-05 임계값");

        assertThat(cap)
                .as("두 번 채점이 갈렸는데 확신도가 임계값 위면 R-05 가 안 물고, 그러면 "
                        + "이 검사가 도는데 결과가 없다. 라이브 자기보고가 전부 1.0 이라"
                        + "(#339) 지금 그 룰을 물리는 경로는 캡 둘뿐이다")
                .isLessThan(threshold);
    }

    @Test
    @DisplayName("❗두 캡이 서로 다른 값이다 — 같으면 어느 이유로 깎였는지 숫자로 안 갈린다")
    void theTwoCapsAreDistinguishable() throws Exception {
        assertThat(read(SCORING, DISAGREE, "자기일관성 캡"))
                .isNotEqualByComparingTo(read(SCORING, CAP, "복창 캡"));
    }

    private static BigDecimal read(Path file, Pattern pattern, String what) throws Exception {
        Matcher m = pattern.matcher(Files.readString(file));
        assertThat(m.find()).as("%s 를 못 읽었다 — 문면이 바뀌었으면 이 정규식도 같이 고친다. "
                + "안 그러면 0건을 검사하고 조용히 통과한다", what).isTrue();
        return new BigDecimal(m.group(1));
    }
}
