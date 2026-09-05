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
            Pattern.compile("@accessGuard\\.can[A-Za-z]*\\('([a-z][a-z:]*)'");

    // actuator가 같은 타입 빈을 하나 더 등록한다 — 이름으로 MVC 쪽을 집는다.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;
    @Autowired
    private RbacPolicyFile policy;

    /** {@code can*} 의 종류까지 뽑는다 — 어떤 형태로 부르는지가 검사 강도를 바꾼다. */
    private static final Pattern GUARD_CALL =
            Pattern.compile("@accessGuard\\.(can[A-Za-z]*)\\('([a-z][a-z:]*)'");

    @Test
    @DisplayName("❗canCreate 는 :create action 에만 쓴다 — 다른 곳에 쓰면 소유권 검사가 사라진다")
    void canCreateIsOnlyForCreation() {
        // canCreate 가 만드는 Target 은 항상 요청자 자신이 소유자다. 그래서 own_session ·
        // branch · org 가 전부 통과하고, 남는 것은 "이 역할에 그랜트가 있나" 뿐이다.
        // session:create 에는 그게 맞다 — 만들면 내 것이니까. 문제는 다른 action 에 쓰면
        // scope 가 조용히 사라진다는 것이다. can(..., #sid) 를 canCreate 로 잘못 적으면
        // 그 action 이 모든 세션에 열리는데 아무 테스트도 안 깨진다(실측 — PR #110 리뷰).
        //
        // canAggregate 는 반대로 더 좁아서(집계는 own_session 을 거부한다) 오기가 나면
        // 막혀서 드러난다. 완화 방향으로 틀리는 것은 canCreate 뿐이라 여기만 고정한다.
        List<String> misuse = new ArrayList<>();
        mapping.getHandlerMethods().forEach((RequestMappingInfo info, HandlerMethod method) -> {
            if (!method.getBeanType().getPackageName().startsWith("com.sphinxfin.sphinx.api")) {
                return;
            }
            PreAuthorize pre = method.getMethodAnnotation(PreAuthorize.class);
            if (pre == null) {
                return;
            }
            Matcher m = GUARD_CALL.matcher(pre.value());
            while (m.find()) {
                if ("canCreate".equals(m.group(1)) && !m.group(2).endsWith(":create")) {
                    misuse.add(method.getBeanType().getSimpleName() + "#" + method.getMethod().getName()
                            + " → canCreate('" + m.group(2) + "')");
                }
            }
        });
        assertThat(misuse)
                .as("canCreate 는 대상이 아직 없는 생성에만 쓴다. 이미 존재하는 리소스에 쓰면 "
                        + "요청자가 소유자로 간주되어 scope 검사가 통째로 사라진다")
                .isEmpty();
    }

    @Test
    @DisplayName("❗GET /sessions/{sid} 는 session:read 다 — interview 로 되돌리면 승인자가 못 읽는다")
    void sessionReadIsNotInterview() {
        // 읽기와 진행을 가른 이유가 여기 있다(#129 · 이슈 #124). session:interview 는
        // questions/next · re-explain · abort 를 같이 덮으므로, 승인자에게 그 그랜트를 주면
        // 조회뿐 아니라 세션을 몰 수 있게 된다.
        //
        // 정책 층 단정(AccessPolicyTest)만으로는 이 되돌림이 안 잡힌다 — 그쪽은 action 이름을
        // 직접 넣으므로 어노테이션이 무엇을 쓰는지와 무관하다. 배선을 보는 자리는 여기다.
        // findFirst() 를 쓰지 않는다. contains 는 /sessions/{sid}/… 하위 경로에도 걸리므로
        // 그 아래에 get() 이라는 이름의 핸들러가 하나만 더 생기면 매치가 둘이 되고,
        // 어느 쪽을 집을지는 getHandlerMethods() 순회 순서(빈 등록 순서)가 정한다. 그러면
        // 새 엔드포인트가 우연히 session:read 일 때 단정은 통과하는데 정작 GET /sessions/{sid}
        // 는 아무도 안 보는 상태가 된다 — 이 테스트의 존재 이유가 빠져나간다 (#138 리뷰).
        List<String> matched = endpointActions().entrySet().stream()
                .filter(e -> e.getKey().endsWith("→ get"))
                .filter(e -> e.getKey().contains("/sessions/{sid}"))
                .map(Map.Entry::getValue)
                .toList();

        assertThat(matched)
                .as("GET /sessions/{sid} 핸들러가 정확히 하나여야 한다 — 여럿이면 이 단정이 "
                        + "어느 것을 본 것인지 알 수 없다. 키 매칭을 좁혀라")
                .hasSize(1);
        assertThat(matched.get(0))
                .as("GET /sessions/{sid} 의 action 이 바뀌면 승인자 접근이 조용히 닫힌다")
                .isEqualTo("session:read");
    }

    @Test
    @DisplayName("❗세션 이해항목 조회는 session:read 다 — 카탈로그 action 으로 바꾸면 범위가 org 가 된다")
    void sessionRiskItemsIsSessionScopedNotCatalog() {
        // 이 라우트가 있는 이유가 범위다(이슈 #158). 대상이 세션이라 own_session 이 자연히
        // 서는데, action 을 product:read 로 바꾸면 **그 그랜트가 scope: org** 라 세션 대상에도
        // 통과한다 — 라우트는 그대로인데 고객이 전 카탈로그를 볼 수 있던 상태로 되돌아간다.
        //
        // 이 되돌림은 다른 어떤 단정도 안 잡는다. product:read 는 정책에 실재하므로
        // everyDeclaredActionExistsInPolicy 가 통과하고, 라우트 자체 테스트는 200 을 받는다.
        // 실측했다: action 만 바꾸고 전체를 돌리면 BUILD SUCCESSFUL 이다 (#164 리뷰).
        List<String> matched = endpointActions().entrySet().stream()
                .filter(e -> e.getKey().endsWith("→ riskItems"))
                .filter(e -> e.getKey().contains("/sessions/{sid}/risk-items"))
                .map(Map.Entry::getValue)
                .toList();

        assertThat(matched)
                .as("GET /sessions/{sid}/risk-items 핸들러가 정확히 하나여야 한다 — 여럿이면 "
                        + "이 단정이 어느 것을 본 것인지 알 수 없다")
                .hasSize(1);
        assertThat(matched.get(0))
                .as("세션 대상 라우트에 카탈로그 action 을 걸면 own_session 이 아니라 org 로 "
                        + "판정된다. 고객에게 카탈로그를 열지 않으려고 만든 라우트가 그 자체로 "
                        + "카탈로그 권한을 요구하게 된다(이슈 #158)")
                .isEqualTo("session:read");
    }

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
                .as("@accessGuard.can*('<action>'...) 형식이어야 한다. 형식이 깨지면 감사가 조용히 빠진다")
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
        //
        // product:manage 도 뺐다 — POST /products/documents · POST /products/{id}/extract 에
        // 붙었다(이슈 #69 · 결정 10.36). 목록이 줄어드는 것이 진척이라는 위 문장 그대로다.
        //
        // aggregate:indicator:read 는 뺐다 — GET /dashboard/leading-indicators 가 붙었다
        // (이슈 #178). 목록이 줄어드는 것이 진척이라는 위 문장 그대로다.
        //
        // session:simulate 도 뺐다 — POST /sessions/{sid}/simulate 에 붙었다(이슈 #214 · #222).
        // 목록이 줄어드는 것이 진척이라는 위 문장 그대로다.
        //
        // signal:unfair:read 를 뺐다 — GET /signals/unfair 에 붙었다(이슈 #63).
        // 목록이 줄어드는 것이 진척이라는 위 문장 그대로다.
        //
        // audit:read 를 뺐다 — GET /dashboard/audit-summary 에 붙었다(이슈 #326 파트2).
        // 목록이 줄어드는 것이 진척이라는 위 문장 그대로다.
        //
        // ❗남은 둘은 성격이 같다 — **엔드포인트가 아직 없다.** audit:verify 는 체인 검증 화면이,
        // admin:role:assign 은 역할별 계정 설계(결정 10.5)가 서야 붙는다. 기능이 생기면
        // 여기서 빼는 것이 순서다.
        List<String> notYetImplemented = List.of(
                "audit:verify",
                "admin:role:assign");
        assertThat(unreachable)
                .as("감사 대상 action인데 어느 엔드포인트에도 안 붙어 있다 — 로그 0건이 "
                        + "'접근이 없었다'로 읽힌다. 기능이 아직 없으면 예외 목록에 넣고 이유를 적어라")
                .containsExactlyInAnyOrderElementsOf(notYetImplemented);
    }

    @Test
    @DisplayName("❗어노테이션이 안 붙은 엔드포인트가 하나도 없다 — 목록이 비었다")
    void everyEndpointCarriesAnAction() {
        List<String> unannotated = endpointActions().entrySet().stream()
                .filter(e -> e.getValue() == null)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        // 이 단정은 원래 **"몇 개가 남았는지"** 를 셌다(마지막 값은 4 — /products 네 경로).
        // 정책이 생길 때마다 붙이고 숫자를 줄여 왔고(이슈 #178 · #214 · #69), 이제 0 이다.
        //
        // ❗**숫자를 0 으로 두지 않고 "비어 있다" 로 바꾼다.** 남은 개수를 세는 단정은
        // *"아직 덜 됐다"* 를 전제하고, 그 전제가 끝난 지금은 **새 엔드포인트가 어노테이션
        // 없이 들어오는 것**이 유일한 실패 사유다. 그때 나와야 하는 말은 "4개여야 하는데
        // 5개다" 가 아니라 **"이 경로에 action 이 없다"** 다.
        //
        // 미인증으로 열린 경로가 하나도 없다는 뜻이 아니다 — 그건 SecurityConfig 와
        // enforce 스위치가 정한다. 여기서 재는 것은 **모든 경로가 어떤 action 에 속한다**는
        // 것이고, 그래야 AuditInterceptor 가 무엇을 남길지 판단할 근거가 생긴다(#76).
        assertThat(unannotated)
                .as("@PreAuthorize 가 없는 엔드포인트가 생겼다. action 부터 정하고 "
                        + "rbac_policy.yaml 에 넣은 뒤 붙인다 — 정책이 아직 없으면 "
                        + "그 사실을 여기 예외로 적고 이유를 남긴다")
                .isEmpty();
    }
}
