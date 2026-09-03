package com.sphinxfin.sphinx.ci;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code server/build.gradle} 의 <b>모듈 밖 입력</b> ≡ {@code ci.yml} 의 server 판별.
 * 소유: 강희진
 *
 * <h2>왜 두 목록이 있나 — 이슈 {@code #285}</h2>
 *
 * <p>{@code server} 테스트 몇 개가 <b>모듈 밖 파일</b>을 읽어 대조한다({@code survey.ts},
 * 개방 모드 지도, {@code scoring.py}). 그 파일들이 두 곳에 적혀 있고 <b>막는 것이 다르다.</b>
 *
 * <pre>
 * build.gradle inputs.file   Gradle 이 UP-TO-DATE 로 테스트를 건너뛰는 것을 막는다
 * ci.yml       server 판별   잡 자체가 안 뜨는 것을 막는다
 * </pre>
 *
 * <p>한쪽만 있으면 <b>대조 테스트가 정작 갈리는 순간에 안 돈다.</b> {@code #270} 이 실측이다 —
 * 개방 모드 지도를 고치는 PR 에서 {@code DemoModeAccountMapTest} 가 CI 에 안 떴다.
 *
 * <p>❗<b>두 목록을 사람이 맞춰 왔다.</b> {@code #285} 가 넷 중 셋이 어긋난 것을 잡았고,
 * 그 뒤에도 넷째({@code docs/functional-spec-v1.1.md}, {@code #300})가 손으로 옮겨졌다.
 * 옮기는 것을 잊으면 아무 일도 안 일어나고 <b>그 사실이 어디에도 안 남는다</b> — 잡이 안 뜬
 * PR 은 초록으로 보인다. 그래서 사람이 아니라 이 단정이 맞춘다.
 *
 * <p>이 테스트 자신은 {@code ci.yml} 이 바뀌면 반드시 돈다 — 그 파일이 바뀌면 판별을 믿을 수
 * 없어서 세 모듈을 전부 돌리기 때문이다({@code ci.yml} 상단). Gradle 쪽은
 * {@code inputs.file} 로 걸어 뒀다.
 */
@DisplayName("ci.yml server 판별 ≡ build.gradle 모듈 밖 입력 (이슈 #285)")
class CiFilterMatchesGradleInputsTest {

    private static final Path GRADLE = Path.of("build.gradle");
    private static final Path CI = Path.of("../.github/workflows/ci.yml");

    /** {@code inputs.file('../X')} 의 X. 모듈 밖만 잡힌다 — 안쪽은 {@code ../} 로 안 적는다. */
    private static final Pattern INPUT_FILE = Pattern.compile("inputs\\.file\\('\\.\\./([^']+)'\\)");
    /** {@code inputs.files(fileTree('../X'))} — 디렉토리 통째. */
    private static final Pattern INPUT_TREE =
            Pattern.compile("inputs\\.files\\(fileTree\\('\\.\\./([^']+)'\\)\\)");

    @Test
    @DisplayName("❗Gradle 이 입력으로 건 모듈 밖 파일은 CI server 판별에도 걸린다")
    void everyGradleInputTriggersTheServerJob() throws IOException {
        Set<String> inputs = outsideInputs();
        Pattern filter = serverFilter();
        // 판별 이전에 **세 모듈을 전부 돌리는 조기 탈출**이 있다(워크플로 자신이 바뀌면
        // 판별을 믿을 수 없다). 그 경로로 덮이는 파일은 server_extra 에 없어도 잡이 뜬다 —
        // 정규식만 보면 이 테스트가 참을 거짓이라고 말한다.
        Pattern alwaysAll = runsEverything();

        Set<String> missed = new TreeSet<>();
        for (String path : inputs) {
            // 디렉토리는 그 안의 아무 파일이나 대표로 세운다.
            String sample = path.endsWith("/") ? path + "sample" : path;
            if (!filter.matcher(sample).find() && !alwaysAll.matcher(sample).find()) {
                missed.add(path);
            }
        }

        assertThat(missed)
                .as("이 파일만 고친 PR 에서는 server 잡이 아예 안 뜬다. 대조 테스트가 정작 "
                        + "갈리는 순간에 안 도는 것이고, 잡이 없으므로 PR 은 초록으로 보인다"
                        + "(#270 실측). ci.yml 의 server_extra 에 같이 올린다.%n"
                        + "  판별 정규식: %s", filter.pattern())
                .isEmpty();
    }

    @Test
    @DisplayName("❗CI 가 server 잡을 띄우는 모듈 밖 파일은 Gradle 입력에도 걸려 있다")
    void everyServerExtraIsAGradleInput() throws IOException {
        Set<String> inputs = outsideInputs();
        Set<String> orphans = new TreeSet<>();

        for (String alt : serverExtra().split("\\|")) {
            String literal = alt.replace("\\.", ".").replaceAll("\\$$", "");
            if (literal.isBlank()) {
                continue;
            }
            if (!inputs.contains(literal)) {
                orphans.add(literal);
            }
        }

        assertThat(orphans)
                .as("잡은 뜨는데 Gradle 이 UP-TO-DATE 로 테스트를 건너뛴다 — 초록인데 "
                        + "아무것도 안 잰 상태다. build.gradle 에 inputs.file 로 같이 건다.%n"
                        + "  현재 Gradle 입력: %s", inputs)
                .isEmpty();
    }

    @Test
    @DisplayName("❗양쪽을 실제로 읽었다 — 하나라도 비면 위 둘이 공회전한다")
    void bothListsWereActuallyRead() throws IOException {
        assertThat(outsideInputs())
                .as("build.gradle 에서 모듈 밖 입력을 하나도 못 뽑았다. inputs.file 표기가 "
                        + "바뀌었으면 정규식도 같이 고친다 — 안 고치면 양쪽이 다 비어서 통과한다")
                .isNotEmpty();
        assertThat(serverExtra())
                .as("ci.yml 에서 server_extra 를 못 뽑았다")
                .isNotBlank();
    }

    private static Set<String> outsideInputs() throws IOException {
        String text = Files.readString(GRADLE);
        Set<String> found = new LinkedHashSet<>();
        for (Pattern p : new Pattern[]{INPUT_FILE, INPUT_TREE}) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String path = m.group(1);
                found.add(p == INPUT_TREE ? path + "/" : path);
            }
        }
        return found;
    }

    /** 판별 전에 세 모듈을 전부 돌리게 하는 조기 탈출의 대상. */
    private static Pattern runsEverything() throws IOException {
        Matcher m = Pattern.compile("grep -qE '(\\^\\\\\\.github[^']*)'")
                .matcher(Files.readString(CI));
        assertThat(m.find())
                .as("ci.yml 의 '워크플로 변경 — 전부 실행' 조기 탈출을 못 찾았다. 그 줄이 "
                        + "없어졌으면 ci.yml 을 server_extra 에 올려야 한다")
                .isTrue();
        return Pattern.compile(m.group(1));
    }

    /** {@code server_extra='...'} 의 값. */
    private static String serverExtra() throws IOException {
        Matcher m = Pattern.compile("server_extra='([^']*)'").matcher(Files.readString(CI));
        return m.find() ? m.group(1) : "";
    }

    /** {@code hit '^(...)' && server=true} 의 정규식 — 셸 치환을 그대로 편다. */
    private static Pattern serverFilter() throws IOException {
        Matcher m = Pattern.compile("server=false;\\s*hit '([^\n]*?)' && server=true")
                .matcher(Files.readString(CI));
        assertThat(m.find())
                .as("ci.yml 에서 server 판별 줄을 못 찾았다 — 줄 모양이 바뀌었으면 "
                        + "이 정규식도 같이 고친다")
                .isTrue();
        // hit '^(a|b|'"$server_extra"')'  →  셸이 이어 붙인 결과를 그대로 만든다
        String raw = m.group(1).replace("'\"$server_extra\"'", serverExtra());
        return Pattern.compile(raw);
    }
}
