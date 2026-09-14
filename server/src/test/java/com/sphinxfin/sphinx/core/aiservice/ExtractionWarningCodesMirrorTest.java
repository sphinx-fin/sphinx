package com.sphinxfin.sphinx.core.aiservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추출 경고 코드셋이 <b>두 곳에 같은 값으로</b> 있는가. 소유: 강희진 (이슈 #620)
 *
 * <h2>왜 server 쪽에도 있나 — 대조가 한 방향으로만 돌았다</h2>
 *
 * <p>같은 대조가 ai-service 에 이미 있다({@code test_the_server_javadoc_lists_the_same_codes}).
 * 집합으로 같음을 요구하고 변이도 걸려 있어 <b>그 자체는 멀쩡하다.</b> 문제는 <b>언제
 * 도는가</b>였다.
 *
 * <pre>
 * ci.yml:181  ai=false; hit '^(ai-service/|contracts/|data/|web/src/lib/survey\.ts$)' && ai=true
 *
 * ai-service/app/schemas.py 를 고친다        ai-service=true   → 그 대조가 돈다   ✅
 * AiServiceClient.java javadoc 만 고친다      ai-service=false  → 안 돈다        ❗
 * </pre>
 *
 * <p>즉 <b>{@code Literal} 을 고치는 쪽은 잡히고 javadoc 을 고치는 쪽은 안 잡혔다.</b>
 * 거기서 코드를 하나 지우거나 이름을 잘못 적으면 초록으로 머지되고, 읽는 사람은 <b>낼 수
 * 없는 코드</b>나 모자란 목록을 믿는다. 두 벌이 갈릴 뻔한 사건이 이미 있었다({@code #444}
 * 리뷰 — 아홉 vs 열).
 *
 * <p>{@code #521} 과 같은 모양이다 — 거기서는 <i>"서버만 고치는 사람은 {@code npm run build}
 * 를 안 돌린다"</i> 였고, 여기서는 <b>CI 판별</b>이 같은 일을 한다.
 *
 * <h2>❗이쪽은 양방향으로 돈다 — 입력 선언이 그 일을 한다</h2>
 *
 * <pre>
 * build.gradle  inputs.file('../ai-service/app/schemas.py')
 * ci.yml        server_extra 에 같은 경로  (CiServerFilterMirrorsGradleInputsTest 가 대조)
 *
 * schemas.py 를 고친다    → server=true  → 이 대조가 돈다
 * javadoc 을 고친다        → server=true  → 이 대조가 돈다
 * </pre>
 *
 * <p>{@code scoring_thresholds.yaml}·{@code app/rubrics/}·{@code survey.ts} 가 같은 방식으로
 * server 스위트에 물려 있다.
 *
 * <h2>python 쪽 대조를 지우지 않는다</h2>
 *
 * <p>그쪽은 {@code server/} 없이 받은 환경에서 조용히 통과하도록 갈래가 있고, 그 조건에서
 * 그쪽이 돈다. 두 대조가 <b>같은 두 파일에서</b> 값을 읽으므로 갈릴 자리가 없다 — 이건
 * 같은 사실을 두 벌로 <b>적는</b> 것이 아니라 같은 사실을 두 번 <b>재는</b> 것이다.
 */
@DisplayName("추출 경고 코드셋 — ai-service Literal ≡ AiServiceClient javadoc (이슈 #620)")
class ExtractionWarningCodesMirrorTest {

    private static final Path SCHEMAS = Path.of("../ai-service/app/schemas.py");
    private static final Path CLIENT = Path.of(
            "src/main/java/com/sphinxfin/sphinx/core/aiservice/AiServiceClient.java");

    /** {@code class ExtractionWarning} 안의 {@code code: Literal[ … ]}. */
    private static final Pattern LITERAL_BLOCK = Pattern.compile(
            "class ExtractionWarning\\b.*?code:\\s*Literal\\[(.*?)]", Pattern.DOTALL);

    /** 그 블록 안의 {@code "CODE"} — 뒤따르는 {@code # 설명} 은 따옴표가 없어 안 걸린다. */
    private static final Pattern QUOTED = Pattern.compile("\"([A-Z_]+)\"");

    /** javadoc 의 {@code {@code ExtractionWarning}(A·B·…)} 목록. */
    private static final Pattern JAVADOC_LIST =
            Pattern.compile("\\{@code ExtractionWarning}\\((.*?)\\)", Pattern.DOTALL);

    @Test
    @DisplayName("❗두 목록의 코드가 같다 — javadoc 만 고치는 PR 에서는 이 대조만 돈다")
    void theJavadocListsExactlyTheLiteralCodes() throws IOException {
        Set<String> literal = literalCodes();
        Set<String> javadoc = javadocCodes();

        assertThat(javadoc)
                .as("ai-service ExtractionWarning 과 서버 javadoc 목록이 갈렸다. 코드를 "
                        + "더하거나 빼거나 **이름을 바꿀 때** 두 곳을 같이 고친다 — 갈린 채로 "
                        + "머지되면 그 사이에 읽는 사람이 낼 수 없는 코드를 믿는다(#444 리뷰)")
                .isEqualTo(literal);
    }

    /**
     * ★ 양쪽에서 실제로 값을 읽었는지 먼저 본다.
     *
     * <p>❗한쪽 정규식이 안 맞으면 <b>빈 집합 두 개가 같아져서 조용히 통과</b>한다. 이
     * 대조가 무는 것이 «같은가» 하나뿐이라, 「읽었는가」를 따로 세우지 않으면 문면이
     * 바뀐 날 그물이 사라진 것을 아무도 모른다.
     *
     * <p>개수를 적어 두지 않는 이유는 <b>그 숫자가 낡기 때문</b>이다 — 코드는 늘어난다.
     * 대신 둘 다 비어 있지 않은 것과, 어느 쪽에도 확실히 있어야 하는 코드 하나를 닻으로 쓴다.
     */
    @Test
    @DisplayName("★ 두 목록을 실제로 읽었다 — 정규식이 어긋나면 빈 집합끼리 같아진다")
    void bothSidesWereActuallyRead() throws IOException {
        assertThat(literalCodes())
                .as("%s 에서 ExtractionWarning 의 Literal 목록을 못 읽었다 — 모양이 바뀌었으면 "
                        + "이 대조도 같이 고친다", SCHEMAS)
                .isNotEmpty()
                .contains("ITEM_NOT_FOUND");
        assertThat(javadocCodes())
                .as("%s 에서 {@code ExtractionWarning}(…) 목록을 못 읽었다 — 문면이 바뀌었으면 "
                        + "이 대조도 같이 고친다", CLIENT)
                .isNotEmpty()
                .contains("ITEM_NOT_FOUND");
    }

    private static Set<String> literalCodes() throws IOException {
        Matcher block = LITERAL_BLOCK.matcher(Files.readString(SCHEMAS));
        Set<String> out = new TreeSet<>();
        if (block.find()) {
            Matcher code = QUOTED.matcher(block.group(1));
            while (code.find()) {
                out.add(code.group(1));
            }
        }
        return out;
    }

    private static Set<String> javadocCodes() throws IOException {
        Matcher listed = JAVADOC_LIST.matcher(Files.readString(CLIENT));
        Set<String> out = new TreeSet<>();
        if (listed.find()) {
            // javadoc 안이라 줄바꿈 · ` * ` 접두 · <b> 강조가 섞여 온다. 이름만 남긴다.
            for (String piece : listed.group(1).replaceAll("</?b>", "").split("[·\\s*]+")) {
                if (!piece.isBlank()) {
                    out.add(piece);
                }
            }
        }
        return out;
    }
}
