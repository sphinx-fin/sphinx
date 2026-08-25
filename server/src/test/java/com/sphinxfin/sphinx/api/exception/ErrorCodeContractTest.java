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
