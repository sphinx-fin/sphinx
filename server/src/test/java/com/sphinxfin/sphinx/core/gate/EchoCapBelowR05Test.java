package com.sphinxfin.sphinx.core.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
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
     * ❗<b>캡을 하나씩 적지 않는다</b> — 선언 파일의 {@code *_confidence_cap} 을 <b>전부</b> 훑는다.
     *
     * <p>예전에는 캡마다 정규식을 하나 더 달았다. 그러면 <b>다음 캡을 만드는 사람이 이 파일을
     * 모른 채 지나간다</b> — 실제로 그렇게 캡이 셋이 되는 동안 이 파일은 하나만 보고 있었다.
     * 지금은 이름 규칙만 지키면 새 캡이 자동으로 들어온다.
     *
     * <p>❗<b>필드 순서에 안 걸리게 둔다</b>({@code #368} 리뷰). 규약(파일 머리)은 네 필드를
     * 필수로 두지만 <b>순서는 안 정한다</b> — {@code value} 를 둘째 줄로 옮겨도 읽어야 한다.
     */
    private static final Pattern CAPS = Pattern.compile(
            "^\\s+([a-z0-9_]*confidence_cap):[\\s\\S]*?^\\s+value:\\s*([0-9.]+)",
            Pattern.MULTILINE);

    private static final Pattern R05 = Pattern.compile("anyConfidenceBelow\\s+([0-9.]+)");

    @Test
    @DisplayName("❗확신도 캡이 전부 R-05 아래다 — 위면 깎아도 게이트가 아무 일도 안 한다")
    void everyCapStillTripsR05() throws Exception {
        BigDecimal threshold = read(RULES, R05, "gate_rules.yaml 의 R-05 임계값");
        Map<String, BigDecimal> caps = allCaps();

        Map<String, BigDecimal> above = new TreeMap<>();
        caps.forEach((name, value) -> {
            if (value.compareTo(threshold) >= 0) {
                above.put(name, value);
            }
        });

        assertThat(above)
                .as("캡이 R-05 임계값(%s) 이상이면, 잡아서 깎아도 게이트가 황색으로 안 내린다. "
                        + "모델 자기보고는 이 룰을 발동시킨 적이 없으므로(#268 · #339 실측) "
                        + "그때 R-05 는 **아무것도 안 잡는 룰**이 된다", threshold)
                .isEmpty();
    }

    @Test
    @DisplayName("❗캡 값이 서로 다르다 — 같으면 어느 이유로 깎였는지 숫자로 안 갈린다")
    void theCapsAreDistinguishable() throws Exception {
        Map<String, BigDecimal> caps = allCaps();

        assertThat(caps.values().stream().map(BigDecimal::stripTrailingZeros).distinct().count())
                .as("확신도만 보고 어느 후처리가 깎았는지 알 수 있어야 한다 — 감사 시점에 "
                        + "기록에 남는 것이 그 숫자다. 지금 캡: %s", caps)
                .isEqualTo(caps.size());
    }

    @Test
    @DisplayName("★ 캡을 하나도 못 찾으면 실패한다 — 0건을 검사하고 통과하면 대조가 없는 것이다")
    void theScanItselfIsMeasured() throws Exception {
        assertThat(allCaps())
                .as("선언 파일에서 *_confidence_cap 을 하나도 못 읽었다 — 이름 규칙이나 "
                        + "파일이 바뀌었으면 이 정규식도 같이 고친다. 안 그러면 위 두 단정이 "
                        + "빈 집합을 검사하고 조용히 통과한다")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    /** 선언 파일의 {@code *_confidence_cap} 전부. 이름 → 값. */
    private static Map<String, BigDecimal> allCaps() throws Exception {
        Map<String, BigDecimal> out = new TreeMap<>();
        Matcher m = CAPS.matcher(Files.readString(SCORING));
        while (m.find()) {
            out.put(m.group(1), new BigDecimal(m.group(2)));
        }
        return out;
    }

    private static BigDecimal read(Path file, Pattern pattern, String what) throws Exception {
        Matcher m = pattern.matcher(Files.readString(file));
        assertThat(m.find()).as("%s 를 못 읽었다 — 문면이 바뀌었으면 이 정규식도 같이 고친다. "
                + "안 그러면 0건을 검사하고 조용히 통과한다", what).isTrue();
        return new BigDecimal(m.group(1));
    }
}
