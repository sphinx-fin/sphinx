package com.sphinxfin.sphinx.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
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

    public RbacPolicyFile() {
        JsonNode root = load();
        this.actions = names(root.path("permissions"));
        this.audited = values(root.path("audited"));
    }

    /** permissions에 정의된 action 이름 전체. 미정의 action은 정책상 기본 거부다. */
    public Set<String> actions() {
        return actions;
    }

    /** AuditInterceptor가 기록할 action. 여기 없는 요청은 로그를 남기지 않는다. */
    public Set<String> audited() {
        return audited;
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

    private static Set<String> values(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        node.forEach(n -> out.add(n.asText()));
        return Set.copyOf(out);
    }
}
