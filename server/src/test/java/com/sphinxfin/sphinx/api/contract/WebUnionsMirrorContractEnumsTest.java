package com.sphinxfin.sphinx.api.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계약의 <b>enum 값</b>과 화면이 들고 있는 <b>유니온 값</b>을 대조한다. 소유: 강희진 (이슈 #597)
 *
 * <h2>왜 필요한가 — 한 enum 만 대조되고 나머지는 0 벌이었다</h2>
 *
 * <p>{@code WebTypesMirrorContractTest} 는 <b>필드 이름</b>만 본다. 그래서 계약의 enum 에
 * 값이 하나 늘고 {@code types.ts} 의 유니온이 안 따라와도 아무것도 안 울었다.
 *
 * <pre>
 * ApiError.code       핸들러·openapi·CLAUDE.md·유니온·문면 표·명세서 §9   여섯 벌 (ErrorCodeContractTest)
 * 나머지 enum          openapi · types.ts 유니온                        ❗0 벌
 * </pre>
 *
 * <p>{@code #594}(운영 콘솔)가 유니온을 들고 오고 {@code #596}(추출 카드)이 같은 enum 에
 * 다섯째 값을 더했는데, <b>두 PR 이 서로를 알고 있어서 사람이 맞췄다.</b> 다음 카드가
 * 생기는 날에는 두 변경이 겹치지 않을 수 있고, 그러면 조용히 갈린다.
 *
 * <h2>갈리면 무엇이 나쁜가 — {@code #316} 과 같은 모양이다</h2>
 *
 * <p>화면이 이 목록을 유니온으로 들고 <b>값으로 분기</b>한다. 값이 하나 빠지면 타입 검사에
 * 걸리거나 <b>조용히 기본 갈래로 떨어진다</b> — 운영 콘솔이라면 <b>카드 하나가 이름 없이
 * 그려지고</b>, 하필 그 카드가 지금 문제인 카드일 수 있다.
 *
 * <h2>짝을 손으로 적지 않는다 — 값 집합으로 찾는다</h2>
 *
 * <p>「어느 enum 이 어느 유니온과 짝인가」를 목록으로 두면 <b>그 목록이 새 사본</b>이 되고,
 * 늘릴 때 같이 안 고쳐진다({@code #586} 이 {@code SOURCES} 에서 밟은 자리다). 그래서 짝을
 * <b>값 집합이 같은지</b>로 찾는다 — 집합이 같으면 화면이 그 값들을 안다는 뜻이고, 계약이
 * 값을 하나 더하는 순간 <b>어느 enum 과도 안 맞아서</b> 여기가 빨개진다.
 *
 * <p>❗<b>반대 방향은 안 본다.</b> 계약 enum 28 개 중 유니온으로 온 것은 22 개이고, 나머지
 * ({@code ageBand}·{@code amountBand}·{@code experienceLevel} 등)는 화면이 {@code string}
 * 으로 받는다 — 값으로 분기하지 않으므로 유니온일 이유가 없다. 「전부 유니온이어야 한다」로
 * 두면 <b>그 여섯을 유니온으로 만들라고 요구하는 그물</b>이 되는데, 그건 이 대조가 막으려는
 * 결함과 무관하다.
 *
 * <h2>허용 목록이 없다</h2>
 *
 * <p>지금 22 개가 전부 짝이 있다. 예외 목록을 만들면 어긋난 것을 목록에 넣어 통과시키게
 * 되고, 그러면 그물이 아니라 장식이 된다({@code WebTypesMirrorContractTest} 와 같은 판단).
 * 짝이 없어야 할 유니온이 생기면 <b>그때 이 문단과 함께</b> 이유를 적는다.
 *
 * <p>{@code web/} 에는 테스트 러너가 없으므로(결정 10.59) 여기서 본다. {@code build.gradle}
 * 이 {@code ../web/src/api/types.ts} 와 {@code ../contracts} 를 입력으로 들고 있어, 둘 중
 * 하나만 고쳐도 이 테스트가 다시 돈다.
 */
@DisplayName("계약 enum ≡ web 유니온 (값 · 이슈 #597)")
class WebUnionsMirrorContractEnumsTest {

    private static final Path CONTRACTS = Path.of("../contracts");
    private static final Path TYPES = Path.of("../web/src/api/types.ts");

    /** 블록 주석 — 지운다. javadoc 안의 예시가 유니온으로 잡히면 안 된다. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** 값 사이에 올 수 있는 것 — 공백·줄바꿈·줄 주석. {@code ErrorCode} 가 값마다 주석을 단다. */
    private static final String GAP = "(?:\\s|//[^\\n]*\\n)*";

    /** {@code "a" | "b" | …} — 문자열 리터럴 둘 이상이 이어진 자리. */
    private static final Pattern UNION =
            Pattern.compile("\"[^\"\\n]+\"(?:" + GAP + "\\|" + GAP + "\"[^\"\\n]+\")+");

    private static final Pattern LITERAL = Pattern.compile("\"([^\"]+)\"");

    /**
     * ❗{@code ApiError.code} 가 뽑히는지 먼저 본다 — <b>정규식이 깨지면 0 건이 되고,
     * 그러면 아래 대조가 아무것도 안 재고 통과한다.</b>
     *
     * <p>닻으로 이 집합을 쓰는 이유는 {@code ErrorCodeContractTest} 가 그 한 벌을 이미
     * 지키고 있어서다 — 여기서 따로 숫자를 적어 두면 그 숫자가 낡는다.
     */
    @Test
    @DisplayName("★ 추출이 실제로 유니온을 찾는다 — 정규식이 깨지면 0 건으로 조용히 통과한다")
    void theExtractorActuallyFindsUnions() throws Exception {
        List<Union> unions = unions();
        Set<String> errorCodes = contractEnums().keySet().stream()
                .filter(s -> s.contains("AI_SERVICE_UNAVAILABLE"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "계약에서 ApiError.code 집합을 못 찾았다 — 이 대조의 닻이다"));

        assertThat(unions).extracting(Union::values)
                .as("types.ts 에서 ErrorCode 유니온을 못 뽑았다. 값마다 줄 주석이 달려 있어서 "
                        + "그것을 건너뛰지 못하면 통째로 안 잡힌다 — 그 상태로 아래 대조는 "
                        + "「짝 없는 유니온 0 건」으로 초록이다")
                .contains(errorCodes);
    }

    @Test
    @DisplayName("❗web 유니온마다 같은 값 집합의 계약 enum 이 있다 — 계약이 값을 더하면 여기가 알려준다")
    void everyWebUnionMatchesAContractEnum() throws Exception {
        Map<Set<String>, String> enums = contractEnums();

        List<String> orphans = new ArrayList<>();
        for (Union union : unions()) {
            if (!enums.containsKey(union.values())) {
                orphans.add("types.ts:" + union.line() + " " + union.values());
            }
        }

        assertThat(orphans)
                .as("이 유니온과 값이 같은 enum 이 contracts/ 에 없다. 계약에 값이 늘었는데 "
                        + "화면이 안 따라왔거나(그러면 그 값이 조용히 기본 갈래로 떨어진다), "
                        + "화면이 계약에 없는 값을 지어낸 것이다. 유니온일 이유가 없는 "
                        + "자리라면 유니온을 풀고 %s 처럼 쓴다", "string")
                .isEmpty();
    }

    /** {@code types.ts} 의 문자열 유니온 — 줄 번호를 들고 다닌다(실패 문면이 자리를 말해야 한다). */
    private record Union(int line, Set<String> values) {}

    private static List<Union> unions() throws IOException {
        String src = Files.readString(TYPES);
        // 줄 번호를 지키려고 주석을 줄바꿈으로 바꾼다 — 통째로 지우면 실패 문면이 다른 줄을 가리킨다.
        String stripped = BLOCK_COMMENT.matcher(src)
                .replaceAll(m -> "\n".repeat((int) m.group().chars().filter(c -> c == '\n').count()));

        List<Union> out = new ArrayList<>();
        Matcher m = UNION.matcher(stripped);
        while (m.find()) {
            Set<String> values = new TreeSet<>();
            Matcher lit = LITERAL.matcher(m.group());
            while (lit.find()) {
                values.add(lit.group(1));
            }
            int line = (int) stripped.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
            out.add(new Union(line, values));
        }
        return out;
    }

    /**
     * {@code contracts/} 전체의 문자열 enum — <b>값 집합 → 어디서 왔나</b>.
     *
     * <p>❗{@code openapi.yaml} 만 보지 않는다. {@code RiskItem} 의 {@code importance} ·
     * {@code status} 는 {@code risk_item.schema.json} 에 있고, 화면은 그 둘도 유니온으로
     * 든다 — 한 파일만 보면 그 둘이 짝 없는 유니온으로 잡혀 <b>맞는 코드가 빨개진다.</b>
     */
    private static Map<Set<String>, String> contractEnums() throws IOException {
        ObjectMapper json = new ObjectMapper();
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Map<Set<String>, String> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(CONTRACTS)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                ObjectMapper mapper = name.endsWith(".yaml") || name.endsWith(".yml") ? yaml
                        : name.endsWith(".json") ? json : null;
                if (mapper == null) {
                    continue;
                }
                collect(mapper.readTree(Files.readString(file)), name, out);
            }
        }
        assertThat(out)
                .as("contracts/ 에서 enum 을 하나도 못 읽었다 — 파일 모양이 바뀌었으면 이 "
                        + "대조도 같이 고친다. 안 그러면 모든 유니온이 짝 없음으로 잡힌다")
                .isNotEmpty();
        return out;
    }

    /** {@code enum: [ … ]} 을 재귀로 모은다. 먼저 만난 자리를 실패 문면의 출처로 쓴다. */
    private static void collect(JsonNode node, String where, Map<Set<String>, String> out) {
        if (node.isObject()) {
            JsonNode values = node.get("enum");
            if (values != null && values.isArray() && !values.isEmpty()) {
                Set<String> set = new TreeSet<>();
                boolean allText = true;
                for (JsonNode v : values) {
                    allText &= v.isTextual();
                    set.add(v.asText());
                }
                if (allText) {
                    out.putIfAbsent(set, where);
                }
            }
            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                collect(it.next(), where, out);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collect(child, where, out);
            }
        }
    }
}
