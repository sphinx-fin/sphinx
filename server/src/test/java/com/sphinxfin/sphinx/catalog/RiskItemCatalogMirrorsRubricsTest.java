package com.sphinxfin.sphinx.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ <b>표시명 사본이 정본과 갈리는 순간을 잡는다.</b>
 *
 * <p>정본은 {@code ai-service/app/rubrics/*.yaml} 의 {@code name}(소유: 윤지석)이고
 * {@code server/src/main/resources/risk_item_catalog.yaml} 은 사본이다. 사본이 필요한 이유는
 * 배포 경계다 — {@code server/Dockerfile} 의 빌드 컨텍스트가 {@code ./server} 하나라 모듈 밖
 * 파일이 이미지에 안 들어간다. 근거는 카탈로그 머리말에 있다.
 *
 * <h2>이 그물이 없으면 무엇이 새나</h2>
 *
 * <pre>
 * 누가 루브릭에 항목을 하나 더한다      → 대시보드 축에 ID 원문이 한 칸 생긴다
 * 누가 루브릭의 name 을 고친다          → 화면과 교부 문서가 옛 이름을 계속 쓴다
 * </pre>
 *
 * <p><b>둘 다 조용하다.</b> 표시명이 없으면 ID 로 되돌아가는 것이 설계라(라벨 때문에 집계를
 * 멈추지 않는다) 어긋남이 화면에서 <i>정상</i>처럼 보인다 — 이슈 #346 이전 상태와 똑같이
 * 생겼다. 그래서 <b>사람이 화면을 봐서는 못 잡고</b> 여기서 잡아야 한다.
 *
 * <p>이 테스트가 루브릭 디렉토리를 읽으므로 {@code server/build.gradle} 이 그 경로를
 * {@code inputs} 로 걸어야 하고(안 걸면 루브릭만 고친 PR 에서 Gradle 이 UP-TO-DATE 로
 * 건너뛴다 — 정확히 이 대조가 필요한 순간이다), 같은 이유로 {@code ci.yml} 의 server 판별에도
 * 그 경로가 있어야 한다. 후자는 {@code CiServerFilterMirrorsGradleInputsTest} 가 지킨다.
 */
class RiskItemCatalogMirrorsRubricsTest {

    private static final Path RUBRICS = Path.of("../ai-service/app/rubrics");

    private static final Pattern ITEM_ID = Pattern.compile("^item_id:\\s*(\\S+)\\s*$", Pattern.MULTILINE);
    private static final Pattern PRODUCT_TYPE = Pattern.compile("^product_type:\\s*(\\S+)\\s*$", Pattern.MULTILINE);
    private static final Pattern NAME = Pattern.compile("^name:\\s*(.+?)\\s*$", Pattern.MULTILINE);

    /** {@code required_elements:} 블록 — 뒤따르는 {@code "  - …"} 줄들만 문다. */
    private static final Pattern REQUIRED_ELEMENTS =
            Pattern.compile("^required_elements:\\s*$\\n((?:[ \\t]*-[ \\t].*(?:\\n|$))+)", Pattern.MULTILINE);
    private static final Pattern LIST_ITEM =
            Pattern.compile("^[ \\t]*-[ \\t]+(.*?)\\s*$", Pattern.MULTILINE);

    private final RiskItemCatalog catalog = new RiskItemCatalog();

    @Test
    @DisplayName("❗항목ID 집합이 루브릭과 같다 — 늘어난 항목은 화면에 ID 원문으로 뜬다")
    void theItemIdsAreExactlyTheRubricsOnes() throws IOException {
        assertThat(new TreeSet<>(catalog.itemNames().keySet()))
                .as("루브릭에 항목이 늘거나 줄었으면 %s 도 같이 고친다. 사본이 뒤처지면 그 "
                        + "항목만 대시보드·교부 문서에 ID 원문으로 뜨는데, 그건 이슈 #346 "
                        + "이전 상태와 구분이 안 된다", "risk_item_catalog.yaml")
                .isEqualTo(new TreeSet<>(rubrics().keySet()));
    }

    @Test
    @DisplayName("★ 표시명이 루브릭 name 과 글자 그대로 같다")
    void everyDisplayNameMatchesTheRubric() throws IOException {
        Map<String, Rubric> rubrics = rubrics();
        Map<String, String> mismatched = new TreeMap<>();

        catalog.itemNames().forEach((itemId, name) -> {
            Rubric rubric = rubrics.get(itemId);
            if (rubric != null && !rubric.name().equals(name)) {
                mismatched.put(itemId, "루브릭 \"" + rubric.name() + "\" · 카탈로그 \"" + name + "\"");
            }
        });

        assertThat(mismatched)
                .as("이름이 갈렸다. **정본은 루브릭 쪽이다** — 채점이 그 파일을 읽는다. "
                        + "카탈로그를 루브릭에 맞춘다")
                .isEmpty();
    }

    @Test
    @DisplayName("상품유형도 같다 — 항목이 어느 유형 축에 서는지가 갈리면 표가 틀린다")
    void everyProductTypeMatchesTheRubric() throws IOException {
        Map<String, Rubric> rubrics = rubrics();
        Map<String, String> mismatched = new TreeMap<>();

        catalog.itemProductTypes().forEach((itemId, productType) -> {
            Rubric rubric = rubrics.get(itemId);
            if (rubric != null && !rubric.productType().equals(productType)) {
                mismatched.put(itemId, "루브릭 " + rubric.productType() + " · 카탈로그 " + productType);
            }
        });

        assertThat(mismatched).isEmpty();
    }

    @Test
    @DisplayName("❗루브릭에 쓰인 상품유형에 전부 표시명이 있다 — 없으면 축 머리에 enum 값이 뜬다")
    void everyProductTypeInUseHasALabel() throws IOException {
        TreeSet<String> inUse = new TreeSet<>();
        rubrics().values().forEach(r -> inUse.add(r.productType()));

        assertThat(catalog.productNames().keySet())
                .as("VARIABLE_INSURANCE 가 그대로 축 머리에 뜨던 것이 이슈 #346 의 절반이다. "
                        + "유형이 늘면 표시명도 같이 넣는다")
                .containsAll(inUse);
    }

    /**
     * ★ <b>「무엇을 재는 항목인가」도 글자 그대로 같다.</b>
     *
     * <p>대시보드 ⓘ 가 이 문장들을 그대로 편다. 요약본을 카탈로그에 적으면 <b>화면이 채점
     * 기준과 다른 말을 하게 되는데</b>, 그 어긋남은 이름이 갈리는 것보다 나쁘다 — 이름은
     * 눈에 띄지만 설명은 그럴듯하게 틀린다. 그래서 리스트를 <b>순서까지</b> 통째로 견준다.
     */
    @Test
    @DisplayName("★ 필수 요소가 루브릭 required_elements 와 순서까지 같다 — 요약본은 그럴듯하게 틀린다")
    void everyRequirementListMatchesTheRubric() throws IOException {
        Map<String, Rubric> rubrics = rubrics();
        Map<String, String> mismatched = new TreeMap<>();

        catalog.itemRequirements().forEach((itemId, requires) -> {
            Rubric rubric = rubrics.get(itemId);
            if (rubric != null && !rubric.requires().equals(requires)) {
                mismatched.put(itemId, "루브릭 " + rubric.requires() + " · 카탈로그 " + requires);
            }
        });

        assertThat(mismatched)
                .as("**정본은 루브릭 쪽이다** — 채점이 그 파일을 읽는다. 카탈로그를 루브릭에 "
                        + "맞춘다. 고쳐 쓰거나 줄이지 않는다")
                .isEmpty();
    }

    /** ❗리스트가 통째로 비면 위 대조는 빈 것끼리 견주고 통과한다. */
    @Test
    @DisplayName("★ 필수 요소가 실제로 읽혔다 — 전부 한 개 이상")
    void everyRubricActuallyHasRequirements() throws IOException {
        assertThat(rubrics())
                .as("required_elements 를 하나도 못 읽었다 — 정규식이나 루브릭 형식을 본다")
                .allSatisfy((itemId, rubric) ->
                        assertThat(rubric.requires()).as(itemId).isNotEmpty());
    }

    /**
     * ❗<b>루브릭을 실제로 읽었는지 잰다.</b>
     *
     * <p>경로가 틀리거나 정규식이 낡으면 위 단정들은 <b>빈 맵과 빈 맵을 견주고 통과</b>한다.
     * 이 레포에서 반복된 실패 양식이라(그물이 안 도는 것이 그물이 없는 것보다 나쁘다) 건수를
     * 직접 잠근다.
     */
    @Test
    @DisplayName("★ 루브릭을 17건 읽었다 — 0건을 대조하고 통과하면 그물이 없는 것과 같다")
    void theMirrorItselfIsMeasured() throws IOException {
        assertThat(rubrics())
                .as("%s 를 못 읽었거나 머리말 형식이 바뀌었다. 대조가 도는지부터 고친다", RUBRICS)
                .hasSizeGreaterThanOrEqualTo(17);
    }

    private record Rubric(String productType, String name, List<String> requires) {}

    private static Map<String, Rubric> rubrics() throws IOException {
        Map<String, Rubric> parsed = new TreeMap<>();
        if (!Files.isDirectory(RUBRICS)) {
            return parsed;
        }
        try (var files = Files.list(RUBRICS)) {
            List<Path> yamls = files.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
            for (Path yaml : yamls) {
                String text = Files.readString(yaml, StandardCharsets.UTF_8);
                Matcher id = ITEM_ID.matcher(text);
                Matcher type = PRODUCT_TYPE.matcher(text);
                Matcher name = NAME.matcher(text);
                if (id.find() && type.find() && name.find()) {
                    parsed.put(id.group(1), new Rubric(type.group(1), unquoted(name.group(1)),
                            requiredElements(text)));
                }
            }
        }
        return parsed;
    }

    /** {@code required_elements} 항목들. 없으면 빈 목록 — 카탈로그 쪽과 같은 규칙이다. */
    private static List<String> requiredElements(String text) {
        Matcher block = REQUIRED_ELEMENTS.matcher(text);
        if (!block.find()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        Matcher item = LIST_ITEM.matcher(block.group(1));
        while (item.find()) {
            out.add(unquoted(item.group(1)));
        }
        return List.copyOf(out);
    }

    /** 루브릭은 이름을 따옴표 없이 쓰지만, 붙었을 때 조용히 갈리지 않게 벗긴다. */
    private static String unquoted(String raw) {
        if (raw.length() >= 2
                && ((raw.startsWith("\"") && raw.endsWith("\"")) || (raw.startsWith("'") && raw.endsWith("'")))) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }
}
