package com.sphinxfin.sphinx.api.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계약 스키마와 <b>화면이 들고 있는 타입</b>의 필드 이름을 대조한다. 소유: 강희진 (이슈 #316 계열)
 *
 * <h2>왜 필요한가 — 타입 검사가 이 어긋남을 못 잡는다</h2>
 *
 * <p>{@code web/src/api/types.ts} 는 <b>손으로 쓴 인터페이스</b>다. 서버 응답을 런타임에
 * 검증하지 않으므로, 서버가 필드 이름을 바꾸면 <b>tsc 는 통과하고 화면만 조용히 틀린다.</b>
 * 없는 필드를 읽으면 {@code undefined} 이고, 그게 비교식에 들어가면 분기가 통째로 뒤집힌다.
 *
 * <p>실제로 났다. {@code OverrideCount.sessions} 를 {@code judged} 로 바꿨는데(#362 리뷰:
 * 비율의 분모와 가림 판단의 표본이 다른 값이었다) 화면 쪽은 옛 이름을 읽고 있었다.
 *
 * <pre>
 * {count.sessions === 0 ? "데이터 없음" : 건수}
 *   → undefined === 0 → false → 판정이 하나도 없는 필터에서 "0건 요청" 이 나간다
 * </pre>
 *
 * <p>이 화면이 스스로 세운 규칙(<i>"0 과 「셀 수 없다」를 가른다"</i>)을 정확히 뒤집는다.
 * {@code ErrorCodeContractTest} 가 에러 코드 네 벌을 대조하는 이유와 같은 자리인데,
 * <b>응답 스키마 쪽에는 그 대조가 없었다.</b>
 *
 * <h2>❗허용 목록을 두지 않는다</h2>
 *
 * <p>이름이 같은 스키마와 인터페이스가 있으면 <b>전부</b> 본다. 예외 목록을 만들면
 * 어긋난 것을 목록에 넣는 것으로 통과시키게 되고, 그러면 그물이 아니라 장식이 된다.
 * 짝을 안 만들 이유가 있으면 <b>이름을 다르게</b> 짓는 것이 그 뜻을 말하는 방법이다.
 *
 * <p>{@code web/} 에는 테스트 러너가 없으므로(결정 10.59) 여기서 본다.
 * {@code build.gradle} 이 {@code ../web/src/api/types.ts} 와 {@code ../contracts} 를
 * 입력으로 들고 있어, 두 파일 중 하나만 고쳐도 이 테스트가 다시 돈다.
 */
@DisplayName("계약 스키마 ≡ web 타입 (필드 이름)")
class WebTypesMirrorContractTest {

    private static final Path OPENAPI = Path.of("../contracts/openapi.yaml");
    private static final Path TYPES = Path.of("../web/src/api/types.ts");

    /** {@code export interface Name {} } 한 벌. 중첩 없는 평평한 인터페이스만 다룬다. */
    private static final Pattern INTERFACE =
            Pattern.compile("export interface (\\w+)\\s*\\{(.*?)\\n\\}", Pattern.DOTALL);

    /** 인터페이스 본문의 {@code name: Type;} · {@code name?: Type;}. */
    private static final Pattern FIELD =
            Pattern.compile("^\\s*(\\w+)\\??\\s*:", Pattern.MULTILINE);

    /**
     * ★ 짝이 지어진 스키마의 필드 이름이 전부 같다.
     *
     * <p>둘 다 카멜케이스이므로 이름을 그대로 비교한다 — 계약이 그렇게 쓰여 있고
     * ({@code rulesVersion}·{@code ruleTrace}) 서버도 그 모양으로 직렬화한다.
     */
    @Test
    @DisplayName("❗같은 이름의 스키마와 인터페이스는 필드 이름이 같다 — tsc 는 이걸 못 잡는다")
    void everyPairedSchemaHasTheSameFieldNames() throws Exception {
        Map<String, Set<String>> schemas = schemaProperties();
        Map<String, Set<String>> interfaces = interfaceFields();

        Map<String, String> mismatched = new TreeMap<>();
        for (var entry : schemas.entrySet()) {
            Set<String> web = interfaces.get(entry.getKey());
            if (web == null) {
                continue;   // 화면이 안 쓰는 스키마는 짝이 없다
            }
            Set<String> onlyContract = new TreeSet<>(entry.getValue());
            onlyContract.removeAll(web);
            Set<String> onlyWeb = new TreeSet<>(web);
            onlyWeb.removeAll(entry.getValue());
            if (!onlyContract.isEmpty() || !onlyWeb.isEmpty()) {
                mismatched.put(entry.getKey(),
                        "계약에만 " + onlyContract + " · 화면에만 " + onlyWeb);
            }
        }

        assertThat(mismatched)
                .as("화면은 서버 응답을 런타임에 검증하지 않는다 — 없는 필드는 undefined 이고 "
                        + "tsc 는 통과한다. 서버가 이름을 바꿨으면 web/src/api/types.ts 도 "
                        + "같이 바꾼다")
                .isEmpty();
    }

    /**
     * ❗<b>짝을 하나도 못 찾으면 실패시킨다.</b>
     *
     * <p>정규식이 낡거나 파일이 옮겨지면 위 단정은 <b>0 개를 대조하고 조용히 통과</b>한다.
     * 그게 이 레포에서 반복된 실패 양식이다 — 그물이 안 도는 것이 그물이 없는 것보다 나쁘다.
     */
    @Test
    @DisplayName("★ 짝이 여러 개 잡힌다 — 0개를 대조하고 통과하면 그물이 없는 것과 같다")
    void thePairingItselfIsMeasured() throws Exception {
        Set<String> paired = new TreeSet<>(schemaProperties().keySet());
        paired.retainAll(interfaceFields().keySet());

        assertThat(paired)
                .as("계약 스키마와 web 인터페이스의 이름이 하나도 안 겹친다 — 정규식이 "
                        + "낡았거나 파일이 옮겨졌다. 고치기 전까지 위 대조는 아무것도 안 잰다")
                .hasSizeGreaterThan(10);
        assertThat(paired)
                .as("화면이 분기에 쓰는 응답들이 대조에서 빠지면 #362·#365 에서 난 어긋남이 "
                        + "다시 조용해진다. 이름을 바꿨으면 여기도 같이 바꾼다")
                .contains("GateResult", "SessionResponse", "HeatmapCell", "ReportResponse",
                        "InputMeta");
    }

    /** openapi {@code components.schemas} 의 이름 → 프로퍼티 이름들(프로퍼티가 있는 것만). */
    private static Map<String, Set<String>> schemaProperties() throws Exception {
        JsonNode root = new ObjectMapper(new YAMLFactory())
                .readTree(Files.readString(OPENAPI, StandardCharsets.UTF_8));
        JsonNode schemas = root.path("components").path("schemas");
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Iterator<String> it = schemas.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            JsonNode props = schemas.path(name).path("properties");
            if (!props.isObject() || props.isEmpty()) {
                continue;
            }
            Set<String> fields = new TreeSet<>();
            props.fieldNames().forEachRemaining(fields::add);
            out.put(name, fields);
        }
        return out;
    }

    /** {@code types.ts} 의 인터페이스 이름 → 필드 이름들. */
    private static Map<String, Set<String>> interfaceFields() throws Exception {
        String src = stripComments(Files.readString(TYPES, StandardCharsets.UTF_8));
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Matcher m = INTERFACE.matcher(src);
        while (m.find()) {
            Set<String> fields = new TreeSet<>();
            Matcher f = FIELD.matcher(m.group(2));
            while (f.find()) {
                fields.add(f.group(1));
            }
            if (!fields.isEmpty()) {
                out.put(m.group(1), fields);
            }
        }
        return out;
    }

    /** 주석 안의 {@code name:} 이 필드로 잡히면 안 된다. */
    private static String stripComments(String src) {
        List<String> kept = new ArrayList<>();
        boolean inBlock = false;
        for (String line : src.split("\n", -1)) {
            String trimmed = line.trim();
            if (inBlock) {
                if (trimmed.contains("*/")) {
                    inBlock = false;
                }
                continue;
            }
            if (trimmed.startsWith("/*")) {
                inBlock = !trimmed.contains("*/");
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }
            kept.add(line);
        }
        return String.join("\n", kept);
    }
}
