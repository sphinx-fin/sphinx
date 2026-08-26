package com.sphinxfin.sphinx.security;

import com.sphinxfin.sphinx.evidence.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F-CMN-002 감사 기록 지점. 등록: 강희진 / 기록 내용: 정세현
 *
 * 감사 로그를 컨트롤러에서 직접 부르지 않기 위한 단일 통로. 여기 한 곳만 api/를 알고,
 * 무엇을 남기는지는 {@link AuditLog.Entry}가 정한다.
 *
 * ── action을 어디서 얻는가 ────────────────────────────────────────────
 * 핸들러 메서드의 {@code @PreAuthorize} 문자열에서 읽는다. action 이름을 애너테이션 두 개에
 * 나눠 적으면 둘이 갈리고, 갈린 쪽이 감사 누락이 된다 — 그러면 "기록이 없다"가 "접근이
 * 없었다"로 읽힌다. 선언은 한 곳뿐이어야 한다.
 *
 * 문자열 형식은 {@code @accessGuard.can('<action>'...)} 로 고정이고
 * AccessControlWiringTest가 그 형식과 action 존재를 함께 고정한다.
 * ────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Component
public class AuditInterceptor implements HandlerInterceptor {

    /** @PreAuthorize("@accessGuard.can('override:approve', #sid)") 에서 action을 뽑는다. */
    private static final Pattern ACTION =
            Pattern.compile("@accessGuard\\.can\\('([a-z][a-z:]*)'");

    private final RbacPolicyFile policyFile;

    public AuditInterceptor(RbacPolicyFile policyFile) {
        this.policyFile = policyFile;
    }

    /**
     * 응답이 나간 뒤 기록한다. 거부된 요청도 남겨야 한다 — 감사에서 의미 있는 것은 성공한
     * 접근만이 아니다. 차단당한 시도가 반복되는 것 자체가 신호다(기획서 7-4 2단계).
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!(handler instanceof HandlerMethod method)) {
            return;
        }
        String action = actionOf(method);
        if (action == null || !policyFile.audited().contains(action)) {
            return;   // rbac_policy.yaml의 audited 목록에 없는 요청은 남기지 않는다
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuditLog.Entry entry = new AuditLog.Entry(
                auth != null ? auth.getName() : null,
                auth != null ? roleOf(auth) : null,
                action,
                request.getRequestURI(),
                String.valueOf(response.getStatus()),
                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());   // ADR-008
        record(entry);
    }

    /**
     * TODO(정세현): ImmutableStore.append("audit", entry) — F-CMN-002.
     *
     * 그때까지는 로그로 남긴다. 비워두면 인터셉터가 등록됐는지조차 확인할 수 없고,
     * "감사 로그가 붙었다"고 착각하기 쉽다. 로그는 불변 기록이 아니라는 점을 분명히 한다.
     */
    private void record(AuditLog.Entry entry) {
        log.info("[감사 로그 — 불변 저장 전 임시] {}", entry);
    }

    private static String actionOf(HandlerMethod method) {
        PreAuthorize annotation = method.getMethodAnnotation(PreAuthorize.class);
        if (annotation == null) {
            return null;
        }
        Matcher m = ACTION.matcher(annotation.value());
        return m.find() ? m.group(1) : null;
    }

    private static String roleOf(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .findFirst()
                .orElse(null);
    }

    /** 등록 대상 경로는 전부다 — 어떤 요청이 감사 대상인지는 위 audited 목록이 정한다. */
    public static Set<String> pathPatterns() {
        return Set.of("/**");
    }
}
