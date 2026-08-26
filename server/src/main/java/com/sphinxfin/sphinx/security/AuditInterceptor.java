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
 * 문자열 형식은 {@code @accessGuard.can*('<action>'...)} 로 고정이고
 * ({@code can} · {@code canCreate} · {@code canAggregate} — 대상의 종류를 호출부가 말한다)
 * AccessControlWiringTest가 그 형식과 action 존재를 함께 고정한다.
 * ────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Component
public class AuditInterceptor implements HandlerInterceptor {

    /** @PreAuthorize("@accessGuard.can('override:approve', #sid)") 에서 action을 뽑는다. */
    private static final Pattern ACTION =
            Pattern.compile("@accessGuard\\.can[A-Za-z]*\\('([a-z][a-z:]*)'");

    private final RbacPolicyFile policyFile;
    private final AuditLog auditLog;

    public AuditInterceptor(RbacPolicyFile policyFile, AuditLog auditLog) {
        this.policyFile = policyFile;
        this.auditLog = auditLog;
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
                // 타입으로 넘긴다 — 포맷은 CanonicalJson 이 ADR-008 대로 한 곳에서 정한다.
                // Instant.toString() 은 밀리초가 0 이면 소수부를 생략해서 자릿수가 흔들린다.
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
        auditLog.record(entry);
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
