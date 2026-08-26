package com.sphinxfin.sphinx.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * rbac_policy.yaml 읽기 전용 로더. 소유: 강희진 (파일 자체의 소유는 정세현)
 *
 * 여기서 하는 일은 **읽는 것뿐**이다 — 어떤 역할이 무엇을 할 수 있는지는 해석하지 않는다.
 * 그 판단은 {@link AccessPolicy}(정세현)가 한다. 이 클래스가 필요한 이유는 두 가지다:
 *   ① AuditInterceptor가 audited 목록을 알아야 한다(등록은 강희진 몫)
 *   ② 컨트롤러 @PreAuthorize의 action 이름이 실제로 정책에 있는지 테스트가 대조해야 한다
 *
 * 정책을 Java 상수로 중복 정의하지 않는다는 규약(CLAUDE.md)을 지키려면 파일을 읽는 수밖에 없다.
 */
@Component
public class RbacPolicyFile {

    private static final String PATH = "rbac_policy.yaml";

    private final Set<String> actions;
    private final Set<String> audited;
    private final Map<String, List<AccessPolicy.Grant>> grants;

    public RbacPolicyFile() {
        JsonNode root = load();
        this.actions = names(root.path("permissions"));
        this.audited = values(root.path("audited"));
        this.grants = grantsOf(root.path("permissions"));
    }

    /** permissions에 정의된 action 이름 전체. 미정의 action은 정책상 기본 거부다. */
    public Set<String> actions() {
        return actions;
    }

    /** AuditInterceptor가 기록할 action. 여기 없는 요청은 로그를 남기지 않는다. */
    public Set<String> audited() {
        return audited;
    }

    /**
     * action 하나의 그랜트 목록. 정의가 없으면 빈 목록 — 해석은 {@link AccessPolicy}가 한다.
     *
     * <p>여기까지가 "읽는 것"이다. 어떤 역할이 어떤 범위를 갖는지 <b>돌려주기만</b> 하고,
     * 그 범위가 요청에 맞는지는 판단하지 않는다. 정세현 요청으로 추가(결정 10.5) —
     * 파일을 두 곳에서 파싱하면 같은 yaml 의 두 해석이 생긴다.
     */
    public List<AccessPolicy.Grant> grants(String action) {
        return grants.getOrDefault(action, List.of());
    }

    private static JsonNode load() {
        try (InputStream in = new ClassPathResource(PATH).getInputStream()) {
            return new ObjectMapper(new YAMLFactory()).readTree(in);
        } catch (IOException e) {
            // 정책 파일이 없으면 기동을 멈춘다. 없는 채로 뜨면 audited가 비어 감사가
            // 조용히 0건이 되고, 그건 "접근이 없었다"로 읽힌다.
            throw new UncheckedIOException("rbac_policy.yaml을 읽을 수 없다 — 접근 통제·감사의 근거 파일이다", e);
        }
    }

    private static Set<String> names(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(out::add);
        return Set.copyOf(out);
    }

    /**
     * {@code permissions} 를 그랜트 맵으로 읽는다. <b>모르는 값이면 기동을 멈춘다</b> —
     * 오타 난 역할·범위를 조용히 버리면 정책이 의도보다 좁아지고(그래서 막히고), 반대로
     * 넓어지는 오타는 조용히 열린다. 어느 쪽이든 파일을 읽는 시점에 드러나야 한다.
     */
    private static Map<String, List<AccessPolicy.Grant>> grantsOf(JsonNode permissions) {
        Map<String, List<AccessPolicy.Grant>> out = new LinkedHashMap<>();
        permissions.fields().forEachRemaining(entry -> {
            List<AccessPolicy.Grant> parsed = new ArrayList<>();
            for (JsonNode grant : entry.getValue()) {
                Set<Role> roles = new LinkedHashSet<>();
                for (JsonNode role : grant.path("roles")) {
                    roles.add(role(role.asText(), entry.getKey()));
                }
                String rawScope = grant.path("scope").asText(null);
                AccessPolicy.Scope scope = AccessPolicy.Scope.of(rawScope)
                        .orElseThrow(() -> new IllegalStateException(
                                "rbac_policy.yaml: " + entry.getKey() + " 의 scope 가 모르는 값이다: " + rawScope));
                parsed.add(new AccessPolicy.Grant(Set.copyOf(roles), scope));
            }
            out.put(entry.getKey(), List.copyOf(parsed));
        });
        return Map.copyOf(out);
    }

    private static Role role(String name, String action) {
        try {
            return Role.valueOf(name);
        } catch (IllegalArgumentException e) {
            // Role enum에 없는 역할을 정책이 부여하려는 것이다 — ADR-001 재검토 대상이거나 오타다.
            throw new IllegalStateException(
                    "rbac_policy.yaml: " + action + " 이 Role enum 에 없는 역할을 쓴다: " + name, e);
        }
    }

    private static Set<String> values(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        node.forEach(n -> out.add(n.asText()));
        return Set.copyOf(out);
    }
}
