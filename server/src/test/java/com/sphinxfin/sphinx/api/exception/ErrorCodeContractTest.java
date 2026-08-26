package com.sphinxfin.sphinx.api.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
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
 */
@DisplayName("에러 코드 계약 — 핸들러 ≡ openapi.yaml")
class ErrorCodeContractTest {

    private static final Path REPO_ROOT = Path.of("..");   // server/ 에서 실행된다
    private static final Pattern EMITTED = Pattern.compile("ApiError\\.of\\(\"([A-Z_]+)\"");

    @Test
    @DisplayName("핸들러가 내보내는 코드 집합이 openapi ApiError.code enum과 같다")
    void handlerCodesMatchContract() throws Exception {
        assertThat(handlerCodes())
                .as("핸들러에 코드를 추가했으면 contracts/openapi.yaml의 ApiError.code enum에도 넣어야 한다")
                .isEqualTo(contractCodes());
    }

    @Test
    @DisplayName("❗CLAUDE.md 의 코드 목록도 같다 — 테스트가 안 보던 세 번째 사본이다")
    void claudeMdListMatchesContract() throws Exception {
        // 이 목록이 세 번 낡았다(#67 · #68 · #105). 두 번은 리뷰에서 잡혔고 한 번은 놓쳤다.
        // 같은 방식으로 세 번 낡았으면 사람이 기억하는 방식이 안 되는 것이다. CLAUDE.md 가
        // 스스로 "openapi 의 enum 과 같아야 한다" 고 적어놓았으므로 그 문장을 테스트로 만든다.
        assertThat(claudeMdCodes())
                .as("CLAUDE.md 「api/」 절의 코드 목록이 핸들러·openapi 와 어긋난다. "
                        + "규약을 적어둔 문서가 낡으면 다음 사람이 낡은 규약을 따른다")
                .isEqualTo(contractCodes());
    }

    /**
     * CLAUDE.md 「api/」 절이 나열하는 코드. {@code `CODE`(상태)} 형태만 센다.
     *
     * <p>백틱 안의 대문자만 보므로 산문에 코드 이름이 등장해도 걸리지 않는다 — 상태 코드가
     * 괄호로 붙은 것이 목록 항목의 형태다.
     */
    private Set<String> claudeMdCodes() throws Exception {
        String doc = Files.readString(REPO_ROOT.resolve("CLAUDE.md"));
        Set<String> codes = new TreeSet<>();
        Matcher m = Pattern.compile("`([A-Z_]{4,})`\\(\\d{3}\\)").matcher(doc);
        while (m.find()) {
            codes.add(m.group(1));
        }
        assertThat(codes)
                .as("CLAUDE.md 에서 코드를 하나도 못 읽었다 — 목록 형태가 바뀌었으면 "
                        + "이 정규식도 같이 고쳐야 한다")
                .isNotEmpty();
        return codes;
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
