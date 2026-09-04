package com.sphinxfin.sphinx.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/functional-spec-v1.1.md} 의 「강제 지점」 표가 <b>실재하는 것</b>을 가리키는가.
 * 소유: 정세현
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>그 표의 칸은 전부 <b>우리가 붙인 것</b>이고(조항이 아니다), 적히는 것이 <b>산문 속
 * 이름</b>이다. 그래서 <b>없는 것을 가리켜도 아무 일이 안 난다</b> — 실제로 {@code #292} 가
 * {@code R-00} 을 아직 없을 때 적었고 사람이 리뷰에서 잡았다({@code #296} 리뷰).
 *
 * <p>이 파일이 스스로 세운 규칙이 그것이라 더 그렇다 — <i>"근거처럼 생겼는데 없는 것을
 * 만들지 않는다"</i> 로 조항 전사를 거절했으므로, <b>강제 지점에서 그 규칙을 어기면 파일이
 * 자기 논거를 깬다.</b>
 *
 * <h2>두 가지를 본다 — 룰 ID 와 <b>심볼·경로</b></h2>
 *
 * <p>처음에는 룰 ID({@code R-\d+})만 봤는데, 표가 부르는 것 중 룰은 둘뿐이고 <b>나머지
 * 열다섯은 클래스·함수·파일·스키마 필드 이름</b>이다({@code #305} 리뷰 실측). 그쪽이 더
 * 조용히 썩는다 — 문서 속 백틱이라 <b>리네임이 따라오지 않고</b>, {@code PiiGateway.mask()}
 * 가 {@code maskAll()} 이 되는 날 이 표는 소리 없이 거짓이 된다.
 *
 * <h2>❗{@code docs/} 를 대조 범위에서 뺀다</h2>
 *
 * <p>토큰이 <b>이 문서 자신에 적혀 있으므로</b>, 레포 전체를 뒤지면 무엇을 적어도 자기 자신이
 * 발견돼 <b>대조가 항상 통과한다.</b> {@code .md} 도 뺀다 — 다른 문서가 그 이름을 언급하는
 * 것은 <b>그것이 실재한다는 근거가 아니다.</b> 코드·계약·데이터만 본다.
 *
 * <p>이것을 변이로 쟀다 — 제외를 없앤 뒤 표의 {@code core/gate/GateEngine} 을
 * {@code core/gate/GateBrain} 으로 바꾸면 <b>초록이다</b>(명세 자신에서 발견된다). 제외를
 * 되살리면 같은 변이가 빨강이다. <b>이 두 줄이 없으면 이 대조는 무엇을 적어도 통과한다.</b>
 *
 * <h2>왜 개수 grep 으로 못 하나</h2>
 *
 * <p>{@code gate_rules.yaml} 은 <b>개수 grep 이 구조적으로 못 쓰이는 파일</b>이다 —
 * {@code ADR-001}·{@code ADR-005} 가 {@code R-00}·{@code R-05} 를 부분문자열로 품는다.
 * 실측: 룰이 없을 때도 {@code grep -c "R-00"} 이 2 였고, 들어온 뒤에는 5 다(진짜는 1).
 * 그래서 여기서도 {@code - id:} 선언만 센다.
 */
class SpecEnforcementIndexTest {

    private static final Path REPO = Path.of("..");
    private static final Path SPEC = REPO.resolve("docs/functional-spec-v1.1.md");
    private static final Path RULES = Path.of("src/main/resources/gate_rules.yaml");

    /**
     * 색인 절의 앵커. <b>절 제목이 아니라 「우리가 붙인 것」 그 자체의 이름</b>이다.
     *
     * <p>전에는 {@code "## 설계 원칙 (P4~P6)"} 이었는데 {@code #305} 가 0.2절을 축자로
     * 전사하면서 그 제목을 지웠다. 그때 표가 P1~P6 을 다 덮게 되었으므로 <b>절 이름이 아니라
     * 강제 지점을 앵커로 잡는 것이 지금 재려는 것에 대응한다.</b>
     */
    private static final String ANCHOR = "### 강제 지점";

    /**
     * 절을 끊는 자리. <b>{@code ##} 만 보면 안 된다</b> — {@code #305} 뒤에는 같은 {@code ##}
     * 절 안에 {@code ### ❗전사하고 보니 우리 요약과 갈리는 것 셋} 이 뒤따르고, 그 산문에
     * {@code R-05} 가 나온다. {@code ##} 로만 끊으면 그것을 <i>"색인이 부르는 룰"</i> 로
     * 세면서 <b>조용히 통과한다</b>(지금은 {@code R-05} 가 실재해서 초록이다).
     *
     * <p>실측(2026-09-03, {@code #305} 브랜치): 앵커·끊기 네 조합에서 절 길이와 뽑히는 룰이
     * {@code 2906/[R-00,R-01,R-05]} · {@code 775/[]} · {@code 2130/[R-00,R-01,R-05]} ·
     * {@code 1167/[R-00,R-01]} 로 갈렸다. <b>마지막 조합만 표만 자른다.</b>
     */
    private static final Pattern SECTION_END = Pattern.compile("\\n#{2,3} ");

    private static final Pattern RULE_ID = Pattern.compile("\\b(R-\\d+[a-z]?)\\b");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    /** 코드로 볼 토큰 — 공백·괄호·연산자가 든 산문을 뺀다. */
    private static final Pattern CODE_LIKE = Pattern.compile("^[A-Za-z0-9_./-]+(\\(\\))?$");

    /** 소스 확장자. 표는 확장자를 생략하고 적는다({@code core/gate/GateEngine}). */
    private static final List<String> EXTENSIONS =
            List.of("", ".java", ".py", ".ts", ".tsx", ".yaml", ".yml", ".json");

    /** 색인 절만 잘라낸다 — 파일의 다른 절(0.2절 전사·0.4절 인용 등)은 이 대조의 대상이 아니다. */
    private String indexSection() throws IOException {
        String text = Files.readString(SPEC);
        int from = text.indexOf(ANCHOR);
        assertThat(from)
                .as("색인 절 앵커('%s')가 없다 — 이 대조가 무엇을 읽는지 다시 정해야 한다. "
                        + "절 이름이 바뀐 것이면 앵커를, 표가 옮겨간 것이면 읽는 파일을 고친다", ANCHOR)
                .isNotNegative();
        Matcher end = SECTION_END.matcher(text);
        return end.find(from + ANCHOR.length())
                ? text.substring(from, end.start())
                : text.substring(from);
    }

    /** 절 안의 <b>표 줄만</b>. 표 아래 산문에는 {@code grep} 예시 같은 백틱이 있어 대조 대상이 아니다. */
    private List<String> tableRows() throws IOException {
        return indexSection().lines().filter(l -> l.startsWith("|")).toList();
    }

    private static List<String> ids(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add(m.group(1));
        }
        return found.stream().distinct().sorted().toList();
    }

    @Test
    @DisplayName("★ 색인이 룰 ID 를 실제로 언급한다 — 없으면 아래 대조가 아무것도 안 잰다")
    void theIndexActuallyNamesRules() throws IOException {
        assertThat(ids(indexSection(), RULE_ID))
                .as("표에서 룰 ID 를 하나도 못 뽑았다. 표 형식이 바뀌었으면 이 정규식도 "
                        + "같이 고친다 — 안 고치면 대조가 공회전한다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("❗색인이 가리키는 룰이 gate_rules.yaml 에 실재한다 (이슈 #290 · PR #296 리뷰)")
    void everyRuleTheIndexNamesExists() throws IOException {
        List<String> named = ids(indexSection(), RULE_ID);
        List<String> declared = ids(Files.readString(RULES),
                Pattern.compile("(?m)^\\s*- id:\\s*(R-\\S+)"));

        assertThat(named)
                .as("색인이 없는 룰을 가리킨다 — 이 파일이 조항 전사를 거절한 이유"
                        + "(\"근거처럼 생겼는데 없는 것을 만들지 않는다\")를 강제 지점에서 "
                        + "어기는 것이다. 룰이 아직 없으면 그 사실을 칸에 적는다(#292 가 한 방식). "
                        + "선언된 룰: %s", declared)
                .isSubsetOf(declared);
    }

    @Test
    @DisplayName("★ 색인에서 심볼·경로를 실제로 뽑는다 — 안 뽑히면 아래 대조가 아무것도 안 잰다")
    void theIndexActuallyNamesSymbols() throws IOException {
        assertThat(codeTokens())
                .as("표에서 코드 토큰을 하나도 못 뽑았다. 표가 백틱을 안 쓰게 바뀌었으면 "
                        + "이 추출도 같이 고친다 — 안 고치면 아래 실재 대조가 공회전한다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("❗색인이 가리키는 심볼·경로가 코드에 실재한다 (PR #305 리뷰)")
    void everySymbolTheIndexNamesExists() throws IOException {
        Map<String, String> unresolved = new LinkedHashMap<>();
        List<Path> haystack = codeFiles();
        for (String token : codeTokens()) {
            String why = whyUnresolved(token, haystack);
            if (why != null) {
                unresolved.put(token, why);
            }
        }
        assertThat(unresolved)
                .as("강제 지점 표가 없는 것을 가리킨다. 이름이 바뀐 것이면 표를 같이 고치고, "
                        + "아직 없는 것이면 그 사실을 칸에 적는다(#292·#296 이 R-00 에 한 방식). "
                        + "❗대조 범위에서 docs/ 와 .md 를 뺐다 — 문서가 자기 자신을, 또는 다른 "
                        + "문서가 그 이름을 언급하는 것은 실재의 근거가 아니다")
                .isEmpty();
    }

    /** 표가 부르는 코드 토큰. 룰 ID 는 위 두 테스트가 따로 본다. */
    private List<String> codeTokens() throws IOException {
        List<String> tokens = new ArrayList<>();
        for (String row : tableRows()) {
            Matcher m = BACKTICKED.matcher(row);
            while (m.find()) {
                String t = m.group(1);
                if (CODE_LIKE.matcher(t).matches() && !RULE_ID.matcher(t).matches()) {
                    tokens.add(t);
                }
            }
        }
        return tokens.stream().distinct().sorted().toList();
    }

    /**
     * 한 파일에서 읽어들일 상한. 이 대조는 후보를 <b>통째로 문자열로 읽으므로</b>
     * ({@code resolveByContent} → {@code Files.readString}) 큰 바이너리 하나가 힙을 넘긴다.
     *
     * <p>❗<b>실측이다.</b> {@code infra/} 에서 {@code tofu init} 을 한 번이라도 한 랩탑에는
     * {@code infra/.terraform/…/terraform-provider-aws} 가 <b>785MB</b> 로 있고, 그 파일에서
     * {@code OutOfMemoryError: Java heap space} 로 <b>server 테스트 전체가 죽는다</b>
     * (`:test` 워커가 통째로 넘어져 다른 297건의 결과도 같이 사라진다). CI 는 그 디렉토리가
     * 없어서 초록이라 — <b>로컬에서만 죽고 아무도 안 본다.</b>
     *
     * <p>디렉토리 목록에 {@code .terraform} 을 넣는 것만으로는 안 된다. 그 목록은
     * <b>오늘 아는 큰 것</b>만 알고, 다음에 생기는 것은 또 같은 방식으로 죽인다. 읽는 쪽에
     * 상한을 두는 것이 실제 그물이고, 목록은 <b>걷는 비용</b>을 줄이는 별개 이유로 둔다.
     *
     * <p>소스·계약·데이터에 이만한 파일은 없다 — 이 레포에서 제일 큰 대조 대상이 폰트
     * {@code Pretendard-Regular.ttf}(2.6MB)이고, 그것도 텍스트가 아니라 읽기에서 걸러진다.
     */
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    /**
     * 대조 대상 파일 — <b>{@code docs/} 와 {@code .md} 를 뺀다.</b> 자기 자신과 다른 문서의
     * 언급이 근거가 되면 이 대조는 무엇을 적어도 통과한다.
     *
     * <p>{@code .terraform} 은 {@code node_modules} 와 같은 자리다 — 내려받은 의존성이지
     * 우리 소스가 아니다. 크기 상한({@link #MAX_BYTES})이 안전을 맡고, 이 목록은 걷지 않을
     * 것을 줄인다.
     */
    private static List<Path> codeFiles() throws IOException {
        List<String> skipDirs = List.of(".git", "node_modules", "build", ".gradle", "dist",
                "docs", ".venv", "__pycache__", ".pytest_cache", "target", ".terraform");
        try (Stream<Path> walk = Files.walk(REPO)) {
            return walk.filter(p -> {
                        for (Path part : p) {
                            if (skipDirs.contains(part.toString())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .filter(Files::isRegularFile)
                    .filter(SpecEnforcementIndexTest::readable)
                    .filter(p -> !p.toString().endsWith(".md"))
                    .toList();
        }
    }

    /** 읽어도 되는 크기인가. 크기를 못 재면 후보에서 뺀다 — 못 재는 것을 통째로 읽지 않는다. */
    private static boolean readable(Path file) {
        try {
            return Files.size(file) <= MAX_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    private static String bare(String token) {
        String t = token.endsWith("()") ? token.substring(0, token.length() - 2) : token;
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    /**
     * 경로로 찾는다. 표는 확장자와 소스 루트를 생략하고 적으므로({@code core/gate/GateEngine})
     * 꼬리 일치에 확장자를 붙여 본다. {@code PiiGateway.mask()} 처럼 멤버가 붙은 것은 멤버를
     * 떼고 타입까지만 본다.
     */
    /**
     * ❗<b>타입이 있다고 멤버가 있는 것은 아니다.</b> 처음 판에서는 {@code X.y} 를 타입까지만
     * 보고 통과시켰는데, 변이로 확인해 보니 {@code PiiGateway.mask()} 를
     * {@code PiiGateway.maskAllTheThings()} 로 바꿔도 <b>초록이었다</b> — 파일이 실재하니
     * 경로가 풀렸고 멤버는 아무도 안 봤다. 이 대조가 막으려던 것이 바로 그 리네임이라
     * 그러면 아무것도 안 재는 것과 같다. 그래서 <b>멤버가 붙은 토큰은 경로와 멤버를 같이</b>
     * 본다.
     *
     * @return 못 찾은 이유, 찾았으면 {@code null}
     */
    private static String whyUnresolved(String token, List<Path> haystack) throws IOException {
        String t = bare(token);
        String last = t.substring(t.lastIndexOf('/') + 1);
        boolean hasMember = last.contains(".")
                && !EXTENSIONS.contains("." + last.substring(last.lastIndexOf('.') + 1));
        if (hasMember) {
            String head = t.substring(0, t.lastIndexOf('.'));
            String member = t.substring(t.lastIndexOf('.') + 1);
            Path owner = resolveByPath(head, haystack);
            if (owner != null) {
                return Files.readString(owner, StandardCharsets.UTF_8).contains(member)
                        ? null
                        : "타입(" + owner + ")은 있는데 멤버 '" + member + "' 가 그 파일에 없다";
            }
            // 타입이 파일이 아닌 경우(스키마 필드 등)는 토큰 전체를 내용에서 찾는다
            return resolveByContent(token, haystack) != null ? null : "경로·내용 어디에도 없다";
        }
        if (resolveByPath(t, haystack) != null || resolveByContent(token, haystack) != null) {
            return null;
        }
        return "경로·내용 어디에도 없다";
    }

    private static Path resolveByPath(String token, List<Path> haystack) {
        String t = bare(token);
        for (Path file : haystack) {
            String path = file.toString().replace('\\', '/');
            for (String ext : EXTENSIONS) {
                String cand = t + ext;
                if (path.endsWith("/" + cand) || path.contains("/" + cand + "/")) {
                    return file;
                }
            }
        }
        return null;
    }

    /** 내용으로 찾는다. 경로가 아닌 이름({@code miss_rate}·{@code condition.value_text})이 이쪽이다. */
    private static Path resolveByContent(String token, List<Path> haystack) throws IOException {
        String needle = bare(token);
        for (Path file : haystack) {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                continue;                                 // 바이너리·읽기 실패는 건너뛴다
            }
            if (text.contains(needle)) {
                return file;
            }
        }
        return null;
    }
}
