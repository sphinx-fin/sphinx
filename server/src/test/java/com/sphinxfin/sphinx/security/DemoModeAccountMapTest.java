package com.sphinxfin.sphinx.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개방 모드 경로별 계정 지도 ↔ rbac_policy.yaml 대조 (이슈 #263). 소유: 강희진
 *
 * <h2>서버 안에서 끝나는 검사로는 안 잡힌다</h2>
 *
 * <p>{@code AccessControlWiringTest} 가 <i>"모든 엔드포인트가 어떤 action 에 속한다"</i> 를
 * 잠그고 {@code OpenApiPermissionSyncTest} 가 계약과 대조한다. <b>둘 다 서버 안에서 끝난다.</b>
 *
 * <p>alpha 는 개방 모드로 뜬다(결정 10.57). 거기서 nginx 가 <b>경로별로 데모 계정을 주입</b>하고
 * {@code app.conf} 가 {@code proxy_set_header Authorization $sphinx_api_auth} 로 클라이언트가
 * 보낸 것을 덮어쓴다. 그래서 <b>같은 서버 코드가 배포에 따라 다른 역할로 불린다</b> — 그 지도가
 * {@code rbac_policy.yaml} 의 지식을 옮겨 적고 있는데 대조하는 것이 없었다.
 *
 * <p>실제로 났다. {@code #252} 가 {@code GET /signals/unfair}({@code signal:unfair:read},
 * COMPL 전용)를 넣었는데 지도에 없어서 {@code default} 인 seller-01 로 나갔고 <b>alpha 에서
 * 403</b> 이었다. 정책·컨트롤러·계약 세 층이 서로 맞았고 CI 도 초록이었다 — 네 번째 층이
 * 대조 밖이었다.
 *
 * <h2>무엇을 재는가</h2>
 *
 * <p>❗<b>"경로가 지도에 있는가" 로는 부족하다.</b> 경로가 있어도 <b>실리는 계정의 역할이 그
 * action 의 그랜트에 없으면</b> 같은 403 이다. 그래서 끝까지 따라간다.
 *
 * <pre>
 *   엔드포인트 → @PreAuthorize 의 action
 *              → nginx 지도가 이 URI 에 실어 주는 계정
 *              → demo_accounts.yaml 의 그 계정 역할
 *              → rbac_policy.yaml 의 그 action 그랜트에 그 역할이 있는가
 * </pre>
 *
 * <p>지금 {@code ~^/api/dashboard/} → compl-01 이 맞는 것은 {@code aggregate:heatmap:read} 가
 * COMPL 을 갖기 때문인데, COMPL 이 없고 MGR 만 있는 action 이 그 경로 아래 붙으면 경로는
 * 지도에 있는데 계정이 틀려서 막힌다. 이 단정이 그것도 잡는다(PR #263 리뷰, 정세현).
 */
@SpringBootTest
@DisplayName("개방 모드 계정 지도 ≡ rbac_policy.yaml (이슈 #263)")
class DemoModeAccountMapTest {

    private static final Path DEMO_MODE = Path.of("../web/docker-entrypoint.d/15-demo-mode.sh");
    private static final Path ROSTER = Path.of("src/main/resources/demo_accounts.yaml");
    private static final Path POLICY = Path.of("src/main/resources/rbac_policy.yaml");

    /** {@code @PreAuthorize("@accessGuard.can…('action'…")} 에서 action 만. */
    private static final Pattern ACTION = Pattern.compile("@accessGuard\\.can[A-Za-z]*\\('([a-z][a-z:]*)'");
    /** 지도 한 줄: {@code ~^/api/… "Basic $(b64 "$var")";} · {@code default "…$(b64 "$var")";} */
    private static final Pattern MAP_LINE =
            Pattern.compile("^\\s*(default|~\\S+)\\s+\"Basic \\$\\(b64 \"\\$(\\w+)\"\\)\";");
    /** 계정 변수 기본값: {@code seller="${SPHINX_DEMO_ACTOR:-seller-01}"} */
    private static final Pattern VAR_DEFAULT = Pattern.compile("^\\s*(\\w+)=\"\\$\\{\\w+:-([\\w-]+)}\"");

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @Test
    @DisplayName("❗개방 모드에서 주입되는 계정이 그 엔드포인트의 action 을 만족한다")
    void everyEndpointIsReachableUnderTheInjectedAccount() throws Exception {
        List<MapEntry> map = accountMap();
        assertThat(map)
                .as("15-demo-mode.sh 에서 지도를 하나도 못 읽었다 — 문면이 바뀌었으면 이 정규식도 "
                        + "같이 고친다. 안 그러면 0건을 검사하고 조용히 통과한다")
                .isNotEmpty();
        assertThat(map).anyMatch(e -> e.isDefault);

        Map<String, String> roleOf = roster();
        Map<String, Set<String>> grantsOf = grants();
        assertThat(roleOf).isNotEmpty();
        assertThat(grantsOf).isNotEmpty();

        List<String> broken = new ArrayList<>();
        endpoints().forEach((uri, action) -> {
            String account = injectedAccount(map, uri);
            String role = roleOf.get(account);
            Set<String> allowed = grantsOf.getOrDefault(action, Set.of());
            if (role == null) {
                broken.add(uri + " → 지도가 실어 주는 " + account + " 가 명부에 없다");
            } else if (!allowed.contains(role)) {
                broken.add(uri + " (" + action + ") → 지도가 " + account + "(" + role
                        + ") 를 실어 주는데 그랜트는 " + allowed + " 다");
            }
        });

        assertThat(broken)
                .as("개방 모드(alpha)에서 이 엔드포인트들이 403 이다. 서버 테스트는 전부 초록인데 "
                        + "배포된 박스에서만 드러난다 — 지도(web/docker-entrypoint.d/15-demo-mode.sh)에 "
                        + "경로를 더하거나, 그 경로에 맞는 계정으로 바꾼다")
                .isEmpty();
    }

    /** 지도 한 줄. nginx 는 정규식을 <b>등장 순서대로</b> 보고 default 는 맨 끝이다. */
    private record MapEntry(boolean isDefault, Pattern pattern, String account) {}

    private static List<MapEntry> accountMap() throws Exception {
        List<String> lines = Files.readAllLines(DEMO_MODE);
        Map<String, String> vars = new HashMap<>();
        for (String line : lines) {
            Matcher v = VAR_DEFAULT.matcher(line);
            if (v.find()) {
                vars.put(v.group(1), v.group(2));
            }
        }
        List<MapEntry> out = new ArrayList<>();
        for (String line : lines) {
            Matcher m = MAP_LINE.matcher(line);
            if (!m.find()) {
                continue;
            }
            String account = vars.get(m.group(2));
            if ("default".equals(m.group(1))) {
                out.add(new MapEntry(true, null, account));
            } else {
                out.add(new MapEntry(false, Pattern.compile(m.group(1).substring(1)), account));
            }
        }
        return out;
    }

    /** nginx 규칙: 정규식을 등장 순서대로, 하나도 안 맞으면 default. */
    private static String injectedAccount(List<MapEntry> map, String uri) {
        for (MapEntry e : map) {
            if (!e.isDefault && e.pattern.matcher(uri).find()) {
                return e.account;
            }
        }
        return map.stream().filter(e -> e.isDefault).findFirst().orElseThrow().account;
    }

    private static Map<String, String> roster() throws Exception {
        JsonNode y = new ObjectMapper(new YAMLFactory()).readTree(ROSTER.toFile());
        Map<String, String> out = new LinkedHashMap<>();
        y.path("accounts").forEach(a -> out.put(a.path("id").asText(), a.path("role").asText()));
        return out;
    }

    private static Map<String, Set<String>> grants() throws Exception {
        JsonNode y = new ObjectMapper(new YAMLFactory()).readTree(POLICY.toFile());
        Map<String, Set<String>> out = new LinkedHashMap<>();
        y.path("permissions").fields().forEachRemaining(e -> {
            Set<String> roles = new LinkedHashSet<>();
            e.getValue().forEach(g -> g.path("roles").forEach(r -> roles.add(r.asText())));
            out.put(e.getKey(), roles);
        });
        return out;
    }

    /** {@code /api} 를 붙인 요청 URI → action. 경로 변수는 구체 값으로 채운다. */
    private Map<String, String> endpoints() {
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
            info.getPathPatternsCondition().getPatterns().forEach(p ->
                    // nginx 의 $uri 는 프록시 **전** 경로라 /api 가 붙어 있다.
                    // 경로 변수는 지도의 정규식이 구체 값을 기대하므로 채워 넣는다.
                    out.put("/api" + p.getPatternString().replaceAll("\\{[^}]+}", "S-1"), m.group(1)));
        });
        return out;
    }
}
