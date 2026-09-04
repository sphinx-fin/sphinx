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

    /**
     * ❗<b>읽는 자리가 옮겨졌다</b> — 임계값이 파이썬 상수에서 선언 파일로 나왔다(PR 이 함께 낸 변경).
     * 소스를 정규식으로 긁는 것보다 이쪽이 낡을 여지가 적다: 이 파일은 값과 함께
     * <i>무엇에 반응하는가 · 왜 이 값인가</i>를 들고 있어서, 값을 옮기려는 사람이
     * 여기 적힌 <b>"올리기 전에 그 테스트와 gate_rules.yaml 을 같이 본다"</b>를 먼저 읽는다.
     */
    private static final Path SCORING = Path.of("../ai-service/app/scoring_thresholds.yaml");
    private static final Path RULES = Path.of("src/main/resources/gate_rules.yaml");

    /**
     * ❗<b>필드 순서에 안 걸리게 둔다</b>({@code #368} 리뷰, 강희진). 규약(파일 머리)은 네 필드를
     * 필수로 두지만 <b>순서는 안 정한다</b> — {@code value} 를 둘째 줄로 옮기면 인접 정규식은
     * <i>"못 읽었다"</i> 로 빨개진다. 안전한 쪽으로 깨지긴 하나 <b>실패 문면이 원인을 안 가리킨다.</b>
     * 비탐욕 건너뛰기로 그 결합을 없앤다.
     */
    private static final Pattern CAP = Pattern.compile("^\\s+echo_confidence_cap:[\\s\\S]*?^\\s+value:\\s*([0-9.]+)", Pattern.MULTILINE);
    private static final Pattern R05 = Pattern.compile("anyConfidenceBelow\\s+([0-9.]+)");

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
    private static BigDecimal read(Path file, Pattern pattern, String what) throws Exception {
        Matcher m = pattern.matcher(Files.readString(file));
        assertThat(m.find()).as("%s 를 못 읽었다 — 문면이 바뀌었으면 이 정규식도 같이 고친다. "
                + "안 그러면 0건을 검사하고 조용히 통과한다", what).isTrue();
        return new BigDecimal(m.group(1));
    }
}
