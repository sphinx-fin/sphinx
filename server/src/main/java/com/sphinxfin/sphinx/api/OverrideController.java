package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.api.dto.OverrideResponse;
import com.sphinxfin.sphinx.core.session.OverrideService;
import com.sphinxfin.sphinx.core.session.Session;
import com.sphinxfin.sphinx.security.CurrentActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * F-GTE-002 적색 오버라이드. 소유: 강희진
 * 사유 30자 미만 거부는 API에서도 강제한다(UI 검증과 이중, ADR-002 견제 장치).
 */
@RestController
@RequestMapping("/sessions/{sid}/override")
public class OverrideController {

    private final OverrideService overrideService;
    private final boolean enforce;

    public OverrideController(OverrideService overrideService,
                              @Value("${sphinx.security.enforce:false}") boolean enforce) {
        this.overrideService = overrideService;
        this.enforce = enforce;
    }

    // @NotBlank 없이 @Size만 두면 jakarta 규약상 null이 유효로 통과한다("null elements are
    // considered valid") — 사유 없는 오버라이드가 200으로 승인 기록에 남는다(오준서 #68 리뷰).
    // 사유는 ADR-002 견제 장치의 핵심이라 null·공백을 입구에서 막는다.
    public record OverrideRequest(@NotBlank @Size(min = 30) String reason) {}

    /** 판매자의 적색 진행 요청(사유 포함). 적색이 아니면 409 OVERRIDE_NOT_ELIGIBLE. */
    @PreAuthorize("@accessGuard.can('override:request', #sid)")
    @PostMapping
    public ApiResponse<OverrideResponse> request(@PathVariable String sid,
                                                 @Valid @RequestBody OverrideRequest body) {
        Session session = overrideService.request(sid, body.reason());
        return ApiResponse.ok(new OverrideResponse(session.overrideStatus().name()));
    }

    /**
     * 관리자(MGR) 승인 — 사유는 요청 시 이미 기록됐으므로 본문이 필요 없다.
     * 승인자는 인증 주체에서 얻는다. 역할 제약(MGR, ADR-002)은 'override:approve'
     * 어노테이션이 강제한다 — 배포(enforce=true)에서는 rbac_policy.yaml 대로 MGR·branch 로 좁힌다.
     */
    @PreAuthorize("@accessGuard.can('override:approve', #sid)")
    @PostMapping("/approve")
    public ApiResponse<OverrideResponse> approve(@PathVariable String sid, Authentication auth) {
        // 역할별 계정(10.5)이 랜딩됐으므로 폴백을 두 갈래로 가른다:
        // - enforce=true(배포): 여기 도달하는 미식별 요청은 정상 경로가 아니라 **설정 사고**
        //   (어노테이션 누락·PUBLIC_PATHS 누수 등)다. 승인자를 특정할 수 없는 승인이 지워지지
        //   않는 기록에 남으면 ADR-002 의 견제 장치가 무력해지므로, 기록하지 않고 죽는다.
        // - enforce=false(로컬 개발): 미인증이 정상 상태다. 가짜 이름 대신 미인증임을 말하는
        //   문자열을 남기는 게 정직하다(프론트가 계정 없이 개발한다).
        // ❗null 검사로는 부족하다(#407 리뷰 ①) — AnonymousAuthenticationFilter 가 미인증
        //   요청에 익명 토큰(getName()="anonymousUser" · isAuthenticated()=true)을 채우므로,
        //   null 만 보면 그 사고에서 "anonymousUser" 가 승인자로 기록된다. 판정은
        //   CurrentActor.unidentified() 한 곳이 소유한다(null·미인증·익명을 한 판정으로).
        boolean unidentified = CurrentActor.unidentified(auth);
        if (enforce && unidentified) {
            throw new UnidentifiedApproverException(
                    "인가가 켜진 환경에서 승인자를 특정할 수 없는 승인 요청이 컨트롤러까지 도달했다 — "
                    + "@PreAuthorize 배선을 확인하라 (ADR-002: 승인자 불명 승인은 기록하지 않는다)");
        }
        String approver = unidentified ? "MGR(데모-미인증)" : auth.getName();
        Session session = overrideService.approve(sid, approver);
        return ApiResponse.ok(new OverrideResponse(session.overrideStatus().name()));
    }

    /**
     * 승인자 불명 사고 전용 타입(#407 리뷰 ② 곁가지). 범용 IllegalStateException 으로 두면
     * 이 사고가 다른 상태 오류와 같은 타입이 되어 구분·계측할 수 없다 — 전용 타입이라
     * 로그·계량기(#326)가 이 사고만 집을 수 있다. 매핑은 전역 핸들러의 500 그대로다.
     */
    static final class UnidentifiedApproverException extends RuntimeException {
        UnidentifiedApproverException(String message) {
            super(message);
        }
    }
}
