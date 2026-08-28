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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code demo_accounts.yaml} 읽기 전용 로더. 소유: 정세현 (결정 10.5)
 *
 * <p>{@link RbacPolicyFile} 과 같은 모양이다 — <b>읽는 것뿐</b>이고 해석하지 않는다.
 * 이 계정이 무엇을 할 수 있는지는 {@code rbac_policy.yaml} 이 유일한 근거이고, 여기는
 * "누가 있고 그 사람의 역할·지점이 무엇인가" 까지만 말한다.
 *
 * <h2>왜 명부가 파일이어야 하는가</h2>
 *
 * <p>지금까지 basic auth 단일 계정이라 <b>감사 로그의 "누가" 가 한 명</b>이었다. SELLER 가
 * 한 것과 COMPL 이 한 것이 같은 사람으로 남고, ADR-001 시연(SELLER 로 집계 접근 → 차단)이
 * 성립하지 않는다. 계정을 Java 상수로 두면 <b>역할을 하나 얹는 변경이 코드 리뷰 없이 지나갈
 * 수 없다</b>는 성질을 잃는다 — 정책 파일을 쓰는 이유와 같다.
 *
 * <h2>비밀번호는 여기 없다</h2>
 *
 * <p>명부는 "누가 있고 무엇인가" 이고 자격증명은 환경변수다. 레포에 비밀번호가 들어가면
 * 그 값이 곧 배포 자격증명이 되고, 파일을 지워도 git 이력에 남는다.
 *
 * <h2>모르는 값이면 기동을 멈춘다</h2>
 *
 * <p>{@link RbacPolicyFile} 과 같은 이유다. 오타 난 역할을 조용히 버리면 그 계정이 권한
 * 없이 뜨고, 원인이 정책 위반처럼 보인다. 지점 오타는 더 나쁘다 — {@code scope: branch} 가
 * 비교할 값이 어긋나서 <b>그 사람만 자기 지점 세션을 못 읽는다.</b>
 */
@Component
public class DemoAccountsFile {

    private static final String PATH = "demo_accounts.yaml";

    /**
     * 계정 한 줄. {@code branchId} 는 지점이 없는 역할(COMPL·ADMIN·CUST)에서 null 이다 —
     * 없는 것이지 미기재가 아니다.
     */
    public record Account(String actorId, Role role, String branchId, String displayName) {

        /** 정책 평가에 넘길 주체. 지점은 <b>계정에서만</b> 온다(요청에서 받지 않는다). */
        public AccessPolicy.Actor toActor() {
            return new AccessPolicy.Actor(actorId, role, branchId);
        }
    }

    private final List<Account> accounts;
    private final Map<String, String> branches;

    public DemoAccountsFile() {
        JsonNode root = load();
        this.branches = branchesOf(root.path("branches"));
        this.accounts = accountsOf(root.path("accounts"), branches.keySet());
    }

    /** 명부 전체. 순서는 파일 순서다. */
    public List<Account> accounts() {
        return accounts;
    }

    /** 지점 코드 → 이름. 세션의 {@code branchId} 와 같은 코드여야 비교가 성립한다. */
    public Map<String, String> branches() {
        return branches;
    }

    public Optional<Account> byId(String actorId) {
        return accounts.stream().filter(a -> a.actorId().equals(actorId)).findFirst();
    }

    /** 역할이 같은 계정들. 시연 시나리오가 "SELLER 둘" 같은 모양을 요구한다. */
    public List<Account> withRole(Role role) {
        return accounts.stream().filter(a -> a.role() == role).toList();
    }

    private static JsonNode load() {
        try (InputStream in = new ClassPathResource(PATH).getInputStream()) {
            return new ObjectMapper(new YAMLFactory()).readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "demo_accounts.yaml 을 읽을 수 없다 — 감사 로그의 행위자가 누구인지의 근거 파일이다", e);
        }
    }

    private static Map<String, String> branchesOf(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return Map.copyOf(out);
    }

    private static List<Account> accountsOf(JsonNode node, java.util.Set<String> knownBranches) {
        List<Account> out = new ArrayList<>();
        for (JsonNode entry : node) {
            String id = required(entry, "id");
            String roleName = required(entry, "role");
            Role role;
            try {
                role = Role.valueOf(roleName);
            } catch (IllegalArgumentException e) {
                // Role enum 에 없는 역할이다 — ADR-001 재검토 대상이거나 오타다.
                throw new IllegalStateException(
                        "demo_accounts.yaml: " + id + " 이 Role enum 에 없는 역할을 쓴다: " + roleName, e);
            }
            String branch = entry.path("branch").asText(null);
            if (branch != null && !knownBranches.contains(branch)) {
                // 오타난 지점은 조용히 두면 그 사람만 자기 지점 세션을 못 읽는다 —
                // scope: branch 가 비교할 값이 어긋나기 때문이다. 기동 시점에 드러낸다.
                throw new IllegalStateException(
                        "demo_accounts.yaml: " + id + " 의 지점이 branches 에 없다: " + branch);
            }
            out.add(new Account(id, role, branch, entry.path("name").asText(null)));
        }
        return List.copyOf(out);
    }

    private static String required(JsonNode entry, String field) {
        String value = entry.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("demo_accounts.yaml: 계정에 " + field + " 가 없다: " + entry);
        }
        return value;
    }
}
