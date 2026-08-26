package com.sphinxfin.sphinx.api.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 에러 코드의 단일 출처를 지킨다. 소유: 강희진
 *
 * PR #28 리뷰 ②의 원인이 "핸들러에 6번째 코드가 생겼는데 openapi는 5개 그대로"였다.
 * 프론트가 이 목록을 유니온 타입으로 들고 있어, 어긋나면 타입 검사에서 걸리거나 조용히
 * default로 떨어진다. 사람 눈 대신 이 테스트가 대조한다.
 *
 * <h2>세 번째 사본까지 본다 — CLAUDE.md</h2>
 *
 * <p>CLAUDE.md {@code api/} 절이 같은 목록을 들고 있고 스스로 <i>"이 목록은
 * contracts/openapi.yaml의 ApiError.code enum과 같아야 한다"</i>고 적어놓았다. 그런데 이 테스트가
 * 핸들러와 openapi만 봐서 <b>그 사본은 테스트 밖이었다</b> — 그래서 <b>같은 방식으로 세 번
 * 낡았다</b>(#67·#68에서 지적해 반영, #105에서 또 빠짐).
 *
 * <p>세 번 같은 방식으로 낡았으면 <b>사람이 기억하는 방식이 안 되는 것</b>이다. 이 파일이 이미
 * 소스와 yaml을 정규식으로 읽으므로 파일 하나 더 읽는 비용은 작다.
 *
 * <p><b>상태 코드까지 본다.</b> CLAUDE.md는 {@code `CODE`(404)}로, openapi는
 * {@code - CODE  # 404}로 각각 상태를 적어둔다. 코드 이름만 맞고 상태가 어긋난 문서는
 * <b>빠진 항목보다 나쁘다</b> — 없는 것은 찾아보게 되는데 틀린 것은 그대로 믿는다.
 */
@DisplayName("에러 코드 계약 — 핸들러 ≡ openapi.yaml ≡ CLAUDE.md")
class ErrorCodeContractTest {

    private static final Path REPO_ROOT = Path.of("..");   // server/ 에서 실행된다
    private static final Pattern EMITTED = Pattern.compile("ApiError\\.of\\(\"([A-Z_]+)\"");

    /** CLAUDE.md의 {@code `CODE`(404)} 형식 — 코드와 상태를 함께 읽는다. */
    private static final Pattern CLAUDE_MD_ENTRY = Pattern.compile("`([A-Z_]+)`\\((\\d{3})\\)");

    /** openapi enum의 {@code - CODE  # 404 설명} 형식. */
    private static final Pattern CONTRACT_ENTRY =
            Pattern.compile("-\\s+([A-Z_]+)\\s+#\\s*(\\d{3})");

    @Test
    @DisplayName("핸들러가 내보내는 코드 집합이 openapi ApiError.code enum과 같다")
    void handlerCodesMatchContract() throws Exception {
        assertThat(handlerCodes())
                .as("핸들러에 코드를 추가했으면 contracts/openapi.yaml의 ApiError.code enum에도 넣어야 한다")
                .isEqualTo(contractCodes());
    }

    @Test
    @DisplayName("CLAUDE.md의 코드 목록이 openapi enum과 같다")
    void claudeMdCodesMatchContract() throws Exception {
        assertThat(claudeMd().keySet())
                .as("CLAUDE.md api/ 절의 코드 목록이 계약과 어긋났다. 그 파일이 스스로 "
                        + "'openapi의 ApiError.code enum과 같아야 한다'고 적어둔 목록이다")
                .isEqualTo(contractStatuses().keySet());
    }

    @Test
    @DisplayName("CLAUDE.md의 상태 코드가 openapi 주석과 같다 — 틀린 상태는 빠진 항목보다 나쁘다")
    void claudeMdStatusesMatchContract() throws Exception {
        Map<String, String> contract = contractStatuses();
        Map<String, String> mismatched = new TreeMap<>();
        claudeMd().forEach((code, status) -> {
            String expected = contract.get(code);
            if (expected != null && !expected.equals(status)) {
                mismatched.put(code, status + " != " + expected);
            }
        });
        assertThat(mismatched)
                .as("CLAUDE.md가 적은 상태 코드가 계약과 다르다. 없는 항목은 찾아보게 되는데 "
                        + "틀린 항목은 그대로 믿는다")
                .isEmpty();
    }

    /** CLAUDE.md api/ 절의 코드 → 상태. */
    private Map<String, String> claudeMd() {
        Map<String, String> out = new TreeMap<>();
        Matcher m = CLAUDE_MD_ENTRY.matcher(read("CLAUDE.md"));
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        assertThat(out).as("CLAUDE.md에서 코드를 하나도 못 읽었다면 이 테스트의 정규식이 낡은 것이다")
                .isNotEmpty();
        return out;
    }

    /** openapi enum의 코드 → 주석에 적힌 상태. */
    private Map<String, String> contractStatuses() throws Exception {
        Map<String, String> out = new TreeMap<>();
        Matcher m = CONTRACT_ENTRY.matcher(read("contracts/openapi.yaml"));
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        assertThat(out.keySet())
                .as("enum 주석에서 읽은 코드 집합이 enum 값과 다르다 — 주석 형식이 바뀐 것이다")
                .isEqualTo(contractCodes());
        return out;
    }

    private String read(String relative) {
        try {
            return Files.readString(REPO_ROOT.resolve(relative));
        } catch (Exception e) {
            throw new IllegalStateException("읽을 수 없다: " + relative, e);
        }
    }

    /** GlobalExceptionHandler가 실제로 내보내는 코드. */
    private Set<String> handlerCodes() throws Exception {
        String src = Files.readString(REPO_ROOT.resolve(
                "server/src/main/java/com/sphinxfin/sphinx/api/exception/GlobalExceptionHandler.java"));
        Set<String> codes = new TreeSet<>();
        Matcher m = EMITTED.matcher(src);
        while (m.find()) {
            codes.add(m.group(1));
        }
        assertThat(codes).as("핸들러에서 코드를 하나도 못 읽었다면 이 테스트의 정규식이 낡은 것이다")
                .isNotEmpty();
        return codes;
    }

    /** 계약이 선언한 코드. */
    private Set<String> contractCodes() throws Exception {
        JsonNode spec = new ObjectMapper(new YAMLFactory())
                .readTree(REPO_ROOT.resolve("contracts/openapi.yaml").toFile());
        JsonNode enumNode = spec.at("/components/schemas/ApiError/properties/code/enum");
        assertThat(enumNode.isArray()).as("openapi ApiError.code에 enum이 있어야 한다").isTrue();
        Set<String> codes = new TreeSet<>();
        enumNode.forEach(n -> codes.add(n.asText()));
        return codes;
    }
}
