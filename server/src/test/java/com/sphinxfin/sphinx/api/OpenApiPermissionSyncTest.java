package com.sphinxfin.sphinx.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code openapi.yaml} 의 <b>권한 문면</b>이 실제 {@code @PreAuthorize} 와 같은가. 소유: 강희진
 *
 * <h2>왜 필요한가 — 이슈 #154</h2>
 *
 * <p>{@code POST /sessions/{id}/report} 의 설명이 권한을 {@code report:read} 로 적고 있었다.
 * 실물은 {@code report:issue} 이고 <b>SELLER own_session 뿐</b>이다. 두 action 을 가른 것이
 * 그 엔드포인트의 존재 이유인데(발행과 조회가 감사에서 갈려야 한다, PR #95) <b>계약이 그것을
 * 정반대로 말하고 있었다.</b>
 *
 * <p>계약 문면은 컴파일도 테스트도 안 잡는다. 프론트는 이 문서를 읽고 화면을 짜므로,
 * <i>"MGR 도 발행할 수 있다"</i> 로 읽으면 MGR 화면에 발행 버튼이 생기고 눌러야 403 이 난다.
 *
 * <p>{@code ErrorCodeContractTest} 가 에러 코드에 하는 일을 권한 문면에 한다.
 *
 * <h2>문면 규약</h2>
 *
 * <p>설명에 {@code 권한: `action:name`} 이 있으면 그 경로·메서드의 {@code @PreAuthorize} 가
 * 같은 action 이어야 한다. <b>없으면 검사하지 않는다</b> — 모든 엔드포인트에 문면을 강제하는
 * 것은 이 테스트의 몫이 아니고, 적어 놓고 틀린 것이 안 적은 것보다 나쁘다는 것이 요지다.
 */
@SpringBootTest
@DisplayName("openapi 권한 문면 ≡ @PreAuthorize (이슈 #154)")
class OpenApiPermissionSyncTest {

    private static final Path SPEC = Path.of("../contracts/openapi.yaml");

    /** 설명 안의 첫 {@code 권한: `x:y`}. 백틱 안의 action 만 집는다. */
    private static final Pattern DECLARED = Pattern.compile("권한:\\s*`([a-z:]+)`");
    private static final Pattern ACTION = Pattern.compile("'([a-z:]+)'");

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @Test
    @DisplayName("❗계약에 적힌 권한이 실제 어노테이션과 같다")
    void declaredPermissionsMatchAnnotations() throws Exception {
        Map<String, String> declared = declaredInSpec();
        assertThat(declared)
                .as("openapi.yaml 에서 `권한: ...` 문면을 하나도 못 읽었다 — 문면 모양이 "
                        + "바뀌었으면 이 정규식도 같이 고친다. 안 그러면 0건을 검사하고 "
                        + "조용히 통과한다")
                .isNotEmpty();

        Map<String, String> actual = annotatedActions();
        List<String> drift = new ArrayList<>();
        declared.forEach((key, spec) -> {
            String real = actual.get(key);
            if (real == null) {
                drift.add(key + ": 계약은 " + spec + " 인데 그 엔드포인트에 @PreAuthorize 가 없다");
            } else if (!real.equals(spec)) {
                drift.add(key + ": 계약 " + spec + " ≠ 실물 " + real);
            }
        });

        assertThat(drift)
                .as("프론트는 이 문서를 읽고 화면을 짠다. 계약이 실물보다 넓게 적혀 있으면 "
                        + "없는 버튼이 생기고 눌러야 403 이 난다 — rbac_policy.yaml 이 권한의 "
                        + "유일한 근거이므로 어긋나면 문면 쪽이 틀린 것이다(이슈 #154)")
                .isEmpty();
    }

    @Test
    @DisplayName("❗어노테이션이 붙은 엔드포인트는 계약에도 있다 — 반대 방향도 잰다")
    void everyAnnotatedEndpointIsDeclared() {
        // 위 단정은 **계약 → 실물** 한 방향뿐이었다. 그래서 @PreAuthorize 를 달고 엔드포인트를
        // 새로 만들면서 openapi 에 안 적어도 조용히 통과했다 — 실제로 /report/preview·download
        // 를 붙이고 나서야 알았다(PR #244 부착).
        //
        // 이 방향이 덜 위험해 보이지만(없는 버튼이 생기는 쪽은 반대다) CLAUDE.md 가 *"엔드포인트를
        // 새로 만들면 최소한 목록에는 올린다 — 문서가 전부라고 믿는 사람이 생기지 않게"* 라고
        // 못박은 자리다. 프론트는 이 문서만 읽는다.
        Map<String, String> declared = declaredInSpec2();
        List<String> missing = annotatedActions().keySet().stream()
                .filter(key -> !declared.containsKey(key))
                .sorted()
                .toList();

        assertThat(missing)
                .as("어노테이션은 있는데 openapi 에 없다. 명세를 못 쓰겠으면 요약만이라도 "
                        + "올린다 — 계약이 단일 진실이라고 스스로 선언하고 있어서 더 그렇다")
                .isEmpty();
    }

    /** 권한 문면이 없어도 <b>경로·메서드가 계약에 있기만</b> 하면 담는다. */
    private static Map<String, String> declaredInSpec2() {
        try {
            List<String> lines = Files.readAllLines(SPEC);
            Map<String, String> out = new LinkedHashMap<>();
            String path = null;
            for (String line : lines) {
                if (line.matches("^  /\\S+:\\s*$")) {
                    path = line.trim().replaceFirst(":$", "");
                } else if (path != null && line.matches("^    (get|post|put|delete|patch):\\s*$")) {
                    out.put(line.trim().replaceFirst(":$", "").toUpperCase() + " " + path, path);
                }
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("openapi.yaml 을 못 읽었다", e);
        }
    }

    /** {@code "METHOD /path" → action}. 설명에 권한 문면이 있는 것만. */
    private static Map<String, String> declaredInSpec() throws Exception {
        List<String> lines = Files.readAllLines(SPEC);
        Map<String, String> out = new LinkedHashMap<>();
        String path = null;
        String method = null;
        for (String line : lines) {
            if (line.matches("^  /\\S+:\\s*$")) {
                path = line.trim().replaceFirst(":$", "");
                method = null;
            } else if (line.matches("^    (get|post|put|delete|patch):\\s*$")) {
                method = line.trim().replaceFirst(":$", "").toUpperCase();
            } else if (path != null && method != null) {
                Matcher m = DECLARED.matcher(line);
                if (m.find()) {
                    out.putIfAbsent(method + " " + path, m.group(1));
                }
            }
        }
        return out;
    }

    /** {@code "METHOD /path" → @PreAuthorize 의 action}. */
    private Map<String, String> annotatedActions() {
        Map<String, String> out = new LinkedHashMap<>();
        mapping.getHandlerMethods().forEach((RequestMappingInfo info, HandlerMethod handler) -> {
            PreAuthorize pre = handler.getMethodAnnotation(PreAuthorize.class);
            if (pre == null || info.getPathPatternsCondition() == null) {
                return;
            }
            Matcher m = ACTION.matcher(pre.value());
            if (!m.find()) {
                return;
            }
            String action = m.group(1);
            info.getMethodsCondition().getMethods().forEach(httpMethod ->
                    info.getPathPatternsCondition().getPatterns().forEach(p ->
                            // 계약은 {id}, 컨트롤러는 {sid} 를 쓴다 — 이름이 아니라 자리로 맞춘다.
                            out.put(httpMethod.name() + " "
                                    + p.getPatternString().replaceAll("\\{[^}]+}", "{id}"), action)));
        });
        return out;
    }
}
