package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.evidence.UnfairSignalLog;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * F-GTE-003 불공정영업 신호 조회 (이슈 #63). 소유: 정세현
 *
 * <p><b>`api/` 는 원래 강희진 영역이다.</b> 이 파일만 예외인 것은 `#63` 에서
 * <i>"큐 인프라 + GET 엔드포인트 — 정세현"</i> 으로 명시 위임했기 때문이다. 큐를 만든 쪽이
 * 조회까지 내는 편이 저장 형태와 응답이 갈리지 않는다.
 *
 * <h2>COMPL 전용이다 — SELLER·MGR 이 <b>부재</b>다</h2>
 *
 * <p>{@code rbac_policy.yaml} 의 {@code signal:unfair:read} 가 COMPL 하나뿐이고, 그건 권한을
 * 안 준 것이 아니라 <b>줄 대상이 없는 것</b>이다(ADR-001 과 같은 결). 판매자가 무엇이
 * 탐지되는지 알면 문면만 바꿔 같은 영업을 한다(기획 7-4 역이용 방지).
 *
 * <p>❗<b>{@code canAggregate} 다.</b> 이 조회는 귀속 주체가 없다 — COMPL 이 <i>"어느 세션에
 * 신호가 있었나"</i> 를 물으러 오지 세션을 지정해서 오지 않는다. {@code can(action, id)} 를
 * 쓰면 없는 리소스 ID 로 세션을 조회하게 되고, 그건 {@code AccessGuard.canAggregate} 의
 * javadoc 이 <i>"리소스가 없는 이유는 둘"</i> 이라고 갈라 둔 자리를 다시 뭉개는 것이다.
 *
 * <p>MGR 을 나중에 넣게 되면 {@code scope} 가 {@code branch} 가 되는데, 그때는 신호에
 * 지점이 실려야 한다 — 지금 payload 에 {@code branchId} 가 없으므로 <b>정책만 고치면
 * "지점을 알 수 없다" 로 거부된다.</b> 그 순서를 여기 적어 둔다.
 */
@RestController
@RequestMapping("/signals")
@RequiredArgsConstructor
public class SignalController {

    private final UnfairSignalLog signals;

    /**
     * 쌓인 불공정영업 신호 전부. <b>상태를 바꾸지 않는다</b> — 큐는 append-only 라
     * "처리함" 을 여기서 지우지 않는다. 처리 상태가 필요해지면 <b>지우는 것이 아니라
     * 새 항목을 쌓는</b> 방식이어야 한다(체인이 뒤로 못 간다).
     */
    @PreAuthorize("@accessGuard.canAggregate('signal:unfair:read')")
    @GetMapping("/unfair")
    public ApiResponse<Map<String, Object>> unfairSignals() {
        List<UnfairSignalLog.Signal> all = signals.all();
        return ApiResponse.ok(Map.of("total", all.size(), "signals", all));
    }
}
