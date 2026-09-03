package com.sphinxfin.sphinx.ci;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code server/build.gradle} 이 선언한 <b>모듈 밖 입력</b>이 전부 {@code ci.yml} 의 server
 * 판별에도 있는가. 소유: 오준서.
 *
 * <h2>왜 필요한가 (이슈 #285)</h2>
 *
 * <p>두 목록이 <b>같은 지식의 두 벌</b>이다. 막는 것이 서로 다르다.
 *
 * <pre>
 * inputs.file    Gradle 이 UP-TO-DATE 로 건너뛰는 것을 막는다
 * ci.yml 판별     잡 자체가 안 뜨는 것을 막는다
 * </pre>
 *
 * <p>한쪽만 있으면 대조 테스트가 <b>정작 갈리는 순간에 안 돈다.</b> `#270` 이 그 실측이고
 * (지도를 고치는데 {@code DemoModeAccountMapTest} 가 CI 에서 안 돌았다), `#285` 가 그때
 * 넷 중 셋이 판별 밖이라는 것을 쟀다. `#303` 이 셋을 닫았는데 <b>이슈가 열려 있는 동안
 * 같은 일이 한 번 더 났다</b> — `#300` 이 다섯째 입력을 정본에만 넣고 사본을 반나절 뒤에
 * 옮겼다. 사람이 두 벌을 맞추는 한 계속 난다.
 *
 * <h2>진짜 실패 모양에서 이 대조가 실제로 돈다</h2>
 *
 * <pre>
 * 누가 inputs.file 을 걸고 ci.yml 을 안 고친다
 *   → build.gradle 이 바뀌었으므로 ^server/ 로 server=true
 *   → 이 테스트가 그 PR 에서 돈다          ← 막고 싶던 바로 그 순간이다
 * </pre>
 *
 * <h2>❗반대 방향이 둘이다 — 하나는 조기 종료가 잡고, 하나는 아무도 안 잡았다</h2>
 *
 * <p>누가 {@code ci.yml} 의 필터를 <b>좁히는</b> 쪽은 그 파일 상단의 조기 종료
 * (<i>"워크플로 자신이 바뀌면 3모듈 전부 실행"</i>)가 이미 잡는다.
 *
 * <p>그런데 <b>넓히기만 하고 Gradle 입력을 안 거는</b> 쪽은 다르다.
 *
 * <pre>
 * ci.yml 이 eval/metrics.py 를 server 판별에 넣었다  →  잡이 뜬다        ✅
 * build.gradle 에 그 파일이 입력으로 없다             →  :test 가 UP-TO-DATE
 *                                                      → ❗초록인데 안 돌았다
 * </pre>
 *
 * <p>이론이 아니다. {@code ci.yml} 이 {@code cache: gradle} 을 쓰므로 UP-TO-DATE 건너뛰기가
 * CI 에서 살아 있고, 바로 그 자리가 {@code build.gradle} 주석이 {@code CLAUDE.md} 를 두고
 * <i>"코드를 하나 지워도 4 up-to-date 로 통과한다"</i> 고 실측해 둔 그것이다. `#285` 가 네 번
 * 잡은 것도 절반은 그쪽이었다 — `#303` 은 {@code ci.yml} 이 정본이고 {@code build.gradle} 이
 * 사본이었다.
 *
 * <p>그래서 대조가 <b>양방향</b>이다. {@code everyCrossModuleInputIsInTheCiServerFilter} 가
 * 정본→사본, {@code everyFilterEntryIsAGradleInput} 이 사본→정본이다. 한쪽만 두면 그 축이
 * 통째로 빈다(`#307` 이 그 축만 들고 있다가 닫혔다).
 *
 * <h2>A 방향은 {@code server_extra} 만 잰다 — 판별 전체가 아니다</h2>
 *
 * <p>판별의 앞부분({@code ^(server/|contracts/|data/timeseries/|CLAUDE\.md$})에는 <b>Gradle
 * 입력이 아닌 것이 일부러 있다.</b> {@code server/} 는 모듈 자신이고, {@code data/timeseries/}
 * 는 그 파일 상단 ③ 의 이유로 들어간 것이라 {@code inputs.file} 이 아니다. 판별 전체를 정본과
 * 대조하면 <b>맞는 것을 틀렸다고 한다.</b> 사본으로 관리되는 부분이 {@code server_extra} 라서
 * 배선이 그 값을 변수로 갈라 둔 것이고, A 방향은 그 경계를 그대로 따른다.
 *
 * <h2>❗{@code ci.yml} 자신은 예외다 — 조용히 빼지 않고 여기 적는다</h2>
 *
 * <p>이 테스트가 {@code ci.yml} 을 읽으므로 그 파일도 {@code inputs.file} 로 걸어야 한다
 * (안 걸면 {@code ci.yml} 만 고친 PR 에서 잡은 떠도 Gradle 이 UP-TO-DATE 로 건너뛴다 —
 * `#186` 함정 그대로다). 그런데 그러면 <b>그 입력 자체가 이 규칙의 대상이 되어 자기 자신을
 * 문다.</b>
 *
 * <p>필터에 넣을 필요가 없다 — 조기 종료가 그보다 <b>먼저</b> {@code server=true} 로 끝낸다.
 * 예외를 목록에서 조용히 빼면 다음 사람이 왜 하나만 빠져 있는지 못 읽으므로, 이유와 함께
 * 여기 적어 둔다.
 *
 * <h2>문면이 바뀌면 죽는다 — 그게 맞다</h2>
 *
 * <p>{@code ci.yml} 의 판별 줄 모양을 정규식으로 집는다. 배선이 바뀌면 <b>조용히 통과하지
 * 않고 실패</b>한다 — 이 대조가 무엇을 읽는지 다시 정해야 한다는 뜻이기 때문이다.
 */
class CiServerFilterMirrorsGradleInputsTest {

    private static final Path GRADLE = Path.of("build.gradle");
    private static final Path CI = Path.of("../.github/workflows/ci.yml");

    /**
     * 조기 종료가 대신 지키는 경로. 목록에 없어야 <b>맞는</b> 유일한 값이다.
     * 여기 값을 늘릴 때는 "왜 필터가 필요 없는가"를 같이 적는다.
     */
    private static final List<String> EXEMPT = List.of(".github/workflows/ci.yml");

    /**
     * {@code inputs.file('../x')} · {@code inputs.files(fileTree('../x'))} 의 경로.
     *
     * <p>❗<b>{@code fileTree} 는 디렉토리라 {@code /} 를 붙여 돌려준다.</b> 필터 쪽이
     * {@code contracts/} 로 접두어를 잡으므로 {@code "contracts"} 그대로는 안 맞는데,
     * 그건 <b>필터가 틀린 게 아니라 비교 단위가 다른 것</b>이다 — 파일 하나는 정확히 그
     * 이름으로, 트리는 그 아래 전부로 걸린다. (이 테스트를 처음 돌렸을 때 실제로 여기서
     * 빨개졌고, 문면만 보고 {@code server_extra} 에 {@code contracts} 를 더했으면
     * 필터가 잘못 넓어질 뻔했다.)
     */
    private static List<String> declaredOutsideModule(String gradle) {
        List<String> paths = new ArrayList<>();

        Matcher files = Pattern.compile("inputs\\.file\\s*\\(\\s*'\\.\\./([^']+)'").matcher(gradle);
        while (files.find()) {
            paths.add(files.group(1));
        }

        Matcher trees = Pattern.compile("inputs\\.files\\s*\\(\\s*fileTree\\s*\\(\\s*'\\.\\./([^']+)'").matcher(gradle);
        while (trees.find()) {
            String dir = trees.group(1);
            paths.add(dir.endsWith("/") ? dir : dir + "/");
        }

        return paths.stream().distinct().sorted().toList();
    }

    /**
     * {@code ci.yml} 의 server 판별을 하나의 정규식으로 되살린다.
     *
     * <p>쉘에서 {@code '^(…|'"$server_extra"')'} 는 세 조각의 이어붙이기다. 조각을 그대로
     * 집어 순서대로 잇는다 — 값을 손으로 옮겨 적으면 이 테스트가 <b>세 번째 사본</b>이 된다.
     */
    private static Pattern serverFilter(String ci) {
        Matcher extra = Pattern.compile("server_extra='([^']*)'").matcher(ci);
        assertThat(extra.find())
                .as("ci.yml 에서 server_extra 를 못 찾았다 — 판별 배선이 바뀌었으면 이 대조도 같이 고친다")
                .isTrue();

        Matcher line = Pattern.compile("server=false;\\s*hit\\s*'([^']*)'\"\\$server_extra\"'([^']*)'").matcher(ci);
        assertThat(line.find())
                .as("ci.yml 의 server 판별 줄 모양이 바뀌었다 — 이 대조가 무엇을 읽는지 다시 정해야 한다")
                .isTrue();

        return Pattern.compile(line.group(1) + extra.group(1) + line.group(2));
    }

    /**
     * {@code server_extra} 의 대안(<code>|</code>)을 하나씩 돌려준다.
     *
     * <p>❗<b>문자열로 비교하지 않는다.</b> 각 대안을 {@code ^(?:…)} 로 감싸 <b>정규식으로
     * 평가</b>한다 — 쉘에서 {@code '^(…|'"$server_extra"')'} 이므로 {@code ^} 가 대안 하나
     * 하나에 걸리고, 그 앵커까지 살려야 실제 판별과 같은 것을 재는 것이 된다. 목록 대조로
     * 바꾸면 {@code $} 앵커나 {@code \.} 이스케이프가 빠진 항목을 원리적으로 못 본다.
     *
     * <p>대안 안에 그룹이 생기면 이 단순 분해가 틀린다. <b>조용히 지나가지 않고 죽는다</b> —
     * 그때는 이 대조가 무엇을 읽는지 다시 정해야 한다.
     */
    private static List<String> filterAlternatives(String ci) {
        Matcher extra = Pattern.compile("server_extra='([^']*)'").matcher(ci);
        assertThat(extra.find())
                .as("ci.yml 에서 server_extra 를 못 찾았다 — 판별 배선이 바뀌었으면 이 대조도 같이 고친다")
                .isTrue();

        String value = extra.group(1);
        assertThat(value)
                .as("server_extra 에 그룹이나 이스케이프된 파이프가 생겼다. `|` 로 쪼개는 이 "
                        + "대조가 더는 안 맞으므로, 쪼개는 방법을 먼저 다시 정한다")
                .doesNotContain("(", "\\|");

        return java.util.Arrays.stream(value.split("\\|"))
                .filter(alt -> !alt.isBlank())
                .toList();
    }

    @Test
    @DisplayName("★ 모듈 밖 입력이 실제로 있다 — 없으면 아래 대조가 아무것도 안 잰다")
    void gradleActuallyDeclaresInputsOutsideTheModule() throws IOException {
        assertThat(declaredOutsideModule(Files.readString(GRADLE)))
                .as("build.gradle 에서 '../' 입력을 하나도 못 뽑았다. 선언 문법이 바뀌었으면 "
                        + "이 정규식도 같이 고친다 — 안 고치면 대조가 공회전한다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("❗server 테스트가 읽는 모듈 밖 파일은 ci.yml 판별에도 있다 (이슈 #285)")
    void everyCrossModuleInputIsInTheCiServerFilter() throws IOException {
        Pattern filter = serverFilter(Files.readString(CI));

        List<String> missing = declaredOutsideModule(Files.readString(GRADLE)).stream()
                .filter(path -> !EXEMPT.contains(path))
                .filter(path -> !filter.matcher(path).find())
                .toList();

        assertThat(missing)
                .as("""
                        정본(build.gradle 의 inputs.file)에만 있고 사본(ci.yml 의 server 판별)에 \
                        없는 경로다. 이대로면 그 파일만 고친 PR 에서 **server 잡이 아예 안 떠서** \
                        지키려던 대조가 안 돈다(이슈 #285 · #270 실측). ci.yml 의 server_extra 에 \
                        `<경로>$` 로 더한다. 필터가 필요 없는 경로라면 이 테스트의 EXEMPT 에 \
                        **이유와 함께** 적는다. 지금 판별식: %s""", filter.pattern())
                .isEmpty();
    }

    @Test
    @DisplayName("판별이 `docs/` 처럼 통째로 넓어지지 않았다 — 넓히면 #276 이 도로 는다")
    void theFilterDidNotWidenToWholeDirectories() throws IOException {
        Pattern filter = serverFilter(Files.readString(CI));

        // 이 셋은 server 테스트가 읽지 않는다. 하나라도 걸리면 필터가 통째로 넓어진 것이고,
        // 그러면 화면·문서만 고친 PR 에 JUnit 이 붙어 잡 개수 과금(#276)이 도로 는다.
        List<String> shouldNotMatch = List.of(
                "docs/decision-log.md",
                "web/src/pages/S08_Dashboard.tsx",
                "eval/run_eval.py");

        List<String> widened = shouldNotMatch.stream()
                .filter(path -> filter.matcher(path).find())
                .toList();

        assertThat(widened)
                .as("server 필터가 넓어졌다. `^web/`·`^docs/` 처럼 통째로 넓히지 않는 것이 "
                        + "`#276`(올림 과금은 잡 개수에 비례한다)과의 합의다 — 파일을 이름으로 "
                        + "적는다. 지금 판별식: %s", filter.pattern())
                .isEmpty();
    }

    @Test
    @DisplayName("★ server_extra 에 대안이 실제로 있다 — 없으면 아래 A 방향이 공회전한다")
    void theFilterActuallyCarriesExtraEntries() throws IOException {
        assertThat(filterAlternatives(Files.readString(CI)))
                .as("server_extra 가 비었다. 모듈 밖 파일을 판별에서 다 뺐다면 build.gradle 쪽 "
                        + "inputs.file 도 같이 비어 있어야 한다 — 한쪽만 비면 아래 대조가 "
                        + "아무것도 안 재면서 초록이다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("❗ci.yml 이 server 잡을 띄우는 모듈 밖 파일은 Gradle 입력에도 걸려 있다 (이슈 #285)")
    void everyFilterEntryIsAGradleInput() throws IOException {
        List<String> declared = declaredOutsideModule(Files.readString(GRADLE));

        List<String> orphans = filterAlternatives(Files.readString(CI)).stream()
                .filter(alt -> {
                    Pattern one = Pattern.compile("^(?:" + alt + ")");
                    return declared.stream().noneMatch(path -> one.matcher(path).find());
                })
                .toList();

        assertThat(orphans)
                .as("""
                        ci.yml 의 server 판별에만 있고 정본(build.gradle 의 inputs.file)에 \
                        없는 항목이다. 이대로면 그 파일만 고친 PR 에서 **잡은 뜨는데 Gradle 이 \
                        `:test` 를 UP-TO-DATE 로 건너뛴다** — 초록인데 테스트가 안 돈 것이고, \
                        ci.yml 의 `cache: gradle` 때문에 CI 에서도 그렇게 된다(#186 과 같은 모양). \
                        server/build.gradle 에 `inputs.file('../<경로>')` 로 더한다. 지금 \
                        선언된 모듈 밖 입력: %s""", declared)
                .isEmpty();
    }
}
