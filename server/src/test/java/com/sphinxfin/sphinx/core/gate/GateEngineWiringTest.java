package com.sphinxfin.sphinx.core.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로덕션 배선이 <b>파일을 읽는 생성자</b>만 쓴다. 소유: 강희진
 *
 * <h2>왜 이 단정이 있나 — 이슈 {@code #294} ⑥</h2>
 *
 * <p>{@link GateEngine} 에는 생성자가 둘이다.
 *
 * <pre>
 * new GateEngine()              classpath 의 gate_rules.yaml 을 읽는다. 버전이 있다
 * new GateEngine(List&lt;Rule&gt;)    룰을 직접 주입한다. 파일을 안 지나왔으므로 버전이 없다
 * </pre>
 *
 * <p>후자로 만든 엔진의 판정은 룰셋 버전을 남길 수 없다. 그리고 감사 기록을 쓰는 자리
 * ({@code evidence/StoredEvidenceRecorder})가 <b>"프로덕션 경로는 파일을 읽는 생성자뿐이라
 * 그 값이 기록에 나오지 않는다"</b> 를 근거로 주석을 달고 있다.
 *
 * <p>❗<b>그건 오늘의 사실이지 규약이 아니었다.</b> {@code GateConfig} 가 지금 무인자
 * 생성자를 쓸 뿐이고, 주입 생성자로 바꾸는 것을 막는 것이 없었다. 바뀌는 순간 그 주석은
 * 거짓이 되는데 <b>깨지는 테스트가 없다</b> — 판정도 기록도 정상으로 보이고, 룰셋 버전만
 * 조용히 "모름"이 된다. {@code evidence/} 는 append-only 라 그렇게 쌓인 기록은 못 고친다.
 *
 * <p>이 단정이 서면 그 주석이 <b>사실 기술에서 계약</b>이 된다. 주입 생성자를 배선에
 * 쓰려는 사람은 여기서 멈추고, 그 판단이 PR 에 남는다.
 *
 * <p>패키지-프라이빗이라 {@code core.gate} 밖에서는 애초에 못 부르지만, 그 패키지 안에
 * {@code GateConfig}(스프링 빈 조립)가 있어서 <b>사각이 실재한다.</b>
 */
@DisplayName("게이트 엔진 배선 (이슈 #294 ⑥)")
class GateEngineWiringTest {

    /** {@code new GateEngine(} 뒤에 인자가 오는 것. 무인자 {@code new GateEngine()} 는 안 걸린다. */
    private static final Pattern INJECTED =
            Pattern.compile("new\\s+GateEngine\\s*\\(\\s*[^)\\s]");

    /** 스캔이 여기 닿지 않았으면 아래 단정은 아무것도 안 잰다. */
    private static final String WIRING = "core/gate/GateConfig.java";

    @Test
    @DisplayName("❗프로덕션 코드는 룰 주입 생성자를 쓰지 않는다 — 쓰면 기록의 룰셋 버전이 사라진다")
    void productionNeverInjectsRules() throws IOException {
        List<String> offenders = new ArrayList<>();
        String wiringSource = null;

        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (file.toString().replace('\\', '/').endsWith(WIRING)) {
                    wiringSource = text;
                }
                Matcher m = INJECTED.matcher(text);
                while (m.find()) {
                    offenders.add(file + " — " + line(text, m.start()));
                }
            }
        }

        // ❗양성 대조. offenders 가 비는 이유는 둘인데 관측이 같다 — "위반이 없다" 와
        // "아무것도 안 읽었다". 소스 루트가 움직이면(Files.walk 는 .java 없는 실재
        // 디렉토리에서 예외를 안 낸다) **배선이 깨진 채로 초록**이 된다. 개수 단정은
        // "뭔가 읽었다" 까지인데 사각은 하필 배선 파일을 못 읽는 것이라 그 자리를 짚는다.
        assertThat(wiringSource)
                .as("배선 파일(%s)을 안 읽었다. 스캔 뿌리가 움직였으면 아래 단정은 "
                        + "위반이 있어도 초록이다.", WIRING)
                .isNotNull();
        assertThat(wiringSource)
                .as("배선이 무인자 생성자를 쓰지 않는다 — 엔진을 다른 데서 만들게 됐으면 "
                        + "이 테스트가 지키는 자리도 거기로 옮긴다.")
                .contains("new GateEngine()");

        assertThat(offenders)
                .as("룰을 주입한 엔진은 gate_rules.yaml 의 version 을 모른다. 그 엔진이 낸 판정이 "
                        + "감사 기록에 들어가면 '어느 룰셋으로 쟀는지' 가 영구히 비고, evidence 는 "
                        + "append-only 라 나중에 못 채운다. 테스트/DI 용으로만 쓴다.%n"
                        + "  · 버전을 인자로 받는 형태(new GateEngine(new Ruleset(...)))도 배선에는 "
                        + "쓰지 않는다 — 버전의 근거가 파일이어야 한다.%n"
                        + "  · 주석·문자열도 소스로 센다. 이 형태를 문서에 적으려면 new 를 빼고 "
                        + "GateEngine(List) 처럼 쓴다.")
                .isEmpty();
    }

    @Test
    @DisplayName("❗정규식이 실제로 무언가를 걸러낸다 — 안 그러면 위 단정이 늘 초록이다")
    void thePatternActuallyMatches() {
        assertThat(INJECTED.matcher("var e = new GateEngine(rules);").find())
                .as("주입 형태를 못 잡으면 위 테스트는 아무것도 안 잰다")
                .isTrue();
        assertThat(INJECTED.matcher("var e = new GateEngine();").find())
                .as("무인자 생성자를 잡으면 프로덕션 배선이 늘 실패한다")
                .isFalse();
    }

    private static String line(String text, int at) {
        int start = text.lastIndexOf('\n', at) + 1;
        int end = text.indexOf('\n', at);
        return text.substring(start, end < 0 ? text.length() : end).trim();
    }
}
