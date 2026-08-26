package com.sphinxfin.sphinx.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 접근 통제 배선이 정책 파일과 갈리지 않는지 고정한다. 소유: 강희진
 *
 * F-CMN-002는 두 사람에게 갈려 있다 — 정책(rbac_policy.yaml·AccessPolicy)은 정세현,
 * 컨트롤러 부착과 인터셉터 등록은 강희진. 갈린 지점은 **action 이름 문자열 하나**이고,
 * 그게 어긋나면 아무도 에러를 못 본다:
 *
 *   - 컨트롤러가 정책에 없는 action을 쓰면 → 미정의 action은 기본 거부라 조용히 막힌다
 *   - audited 목록의 action을 아무 컨트롤러도 안 쓰면 → 감사 로그가 0건인데
 *     그 0건이 "접근이 없었다"로 읽힌다
 *
 * 둘 다 런타임에 예외가 안 난다. 그래서 테스트로 대조한다.
 */
@SpringBootTest
@DisplayName("F-CMN-002 접근 통제 배선 ↔ rbac_policy.yaml")
class AccessControlWiringTest {

    /** AuditInterceptor가 쓰는 것과 같은 형식. 여기서 형식 자체를 고정한다. */
    private static final Pattern ACTION =
            Pattern.compile("@accessGuard\\.can\\('([a-z][a-z:]*)'");

    // actuator가 같은 타입 빈을 하나 더 등록한다 — 이름으로 MVC 쪽을 집는다.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;
    @Autowired
    private RbacPolicyFile policy;

    /** 엔드포인트 → @PreAuthorize에 적힌 action(없으면 null). */
    private Map<String, String> endpointActions() {
        Map<String, String> out = new LinkedHashMap<>();
        mapping.getHandlerMethods().forEach((RequestMappingInfo info, HandlerMethod method) -> {
            if (!method.getBeanType().getPackageName().startsWith("com.sphinxfin.sphinx.api")) {
                return;   // actuator·에러 핸들러 등은 대상이 아니다
            }
            PreAuthorize pre = method.getMethodAnnotation(PreAuthorize.class);
            String action = null;
            if (pre != null) {
                Matcher m = ACTION.matcher(pre.value());
                action = m.find() ? m.group(1) : "형식오류:" + pre.value();
            }
            out.put(info.toString() + " → " + method.getMethod().getName(), action);
        });
        return out;
    }

    @Test
    @DisplayName("@PreAuthorize에 적힌 action은 전부 rbac_policy.yaml에 정의돼 있다")
    void everyDeclaredActionExistsInPolicy() {
        List<String> unknown = new ArrayList<>();
        endpointActions().forEach((endpoint, action) -> {
            if (action == null) {
                return;   // 미부착은 아래 테스트가 따로 본다
            }
            if (!policy.actions().contains(action)) {
                unknown.add(endpoint + " → " + action);
            }
        });
        assertThat(unknown)
                .as("정책에 없는 action이다. 미정의 action은 기본 거부라 조용히 막힌다")
                .isEmpty();
    }

    @Test
    @DisplayName("@PreAuthorize 문자열 형식이 고정돼 있다 — AuditInterceptor가 같은 형식을 파싱한다")
    void annotationFormatIsFixed() {
        List<String> malformed = endpointActions().entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().startsWith("형식오류:"))
                .map(e -> e.getKey() + " " + e.getValue())
                .toList();
        assertThat(malformed)
                .as("@accessGuard.can('<action>'...) 형식이어야 한다. 형식이 깨지면 감사가 조용히 빠진다")
                .isEmpty();
    }

    @Test
    @DisplayName("audited 목록의 action은 실제로 어떤 엔드포인트에 붙어 있다")
    void everyAuditedActionIsReachable() {
        Set<String> declared = Set.copyOf(endpointActions().values().stream()
                .filter(a -> a != null && !a.startsWith("형식오류:")).toList());
        List<String> unreachable = policy.audited().stream()
                .filter(a -> !declared.contains(a))
                .sorted()
                .toList();
        // 아직 엔드포인트가 없는 기능(F-GTE-003 신호 큐, 감사 조회, 계정 관리)은 제외한다.
        List<String> notYetImplemented = List.of(
                "audit:read", "audit:verify", "signal:unfair:read",
                "admin:role:assign", "aggregate:indicator:read");
        assertThat(unreachable)
                .as("감사 대상 action인데 어느 엔드포인트에도 안 붙어 있다 — 로그 0건이 "
                        + "'접근이 없었다'로 읽힌다. 기능이 아직 없으면 예외 목록에 넣고 이유를 적어라")
                .containsExactlyInAnyOrderElementsOf(notYetImplemented);
    }

    @Test
    @DisplayName("action이 안 붙은 엔드포인트가 명시적으로 열거돼 있다 — 잊고 빠뜨린 것과 구별한다")
    void unannotatedEndpointsAreEnumerated() {
        List<String> unannotated = endpointActions().entrySet().stream()
                .filter(e -> e.getValue() == null)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        // rbac_policy.yaml에 대응 action이 아직 없는 것들이다(정책 소유자 확정 대기).
        // 정책이 생기면 여기서 빼고 컨트롤러에 붙인다 — 목록이 줄어드는 것이 진척이다.
        assertThat(unannotated)
                .as("action 미부착 엔드포인트가 바뀌었다. 정책이 생겼으면 붙이고, "
                        + "새로 만든 엔드포인트면 action부터 정하라")
                .hasSize(5);
        assertThat(String.join(" | ", unannotated))
                .contains("/products")        // 상품 목록·업로드·추출·조회 4종
                .contains("simulate");        // 손실 시뮬레이터
    }
}
