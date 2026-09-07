package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.ApiResponse;
import com.sphinxfin.sphinx.core.ops.OpsStatus;
import com.sphinxfin.sphinx.core.ops.OpsStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영 상태 조회 — 운영 콘솔(S-09)의 서버 몫 (이슈 #522). 소유: 강희진
 *
 * <pre>
 *   GET /ops/status    ops:status:read    ADMIN 만 · <b>audited 아님</b>
 * </pre>
 *
 * <h2>왜 ADMIN 인가 — 응답에 데이터가 없어서다</h2>
 *
 * <p>{@code rbac_policy.yaml} 의 「시스템 운영」절(<i>"데이터가 아니라 계정·설정만"</i>)에
 * 둔다. 그 절의 금지에 안 걸리는 이유는 <i>"운영이라서"</i> 가 아니라 <b>응답에 세션·집계가
 * 없어서</b>다 — ADR-001 데이터 범위 표가 ADMIN 에게 닫아 둔 것이 그 둘이다.
 * {@code product:manage} 가 같은 자리에 있는 근거와 같은 모양이다.
 *
 * <p>❗<b>SELLER·MGR 에 안 준다.</b> <i>"지금 채점이 죽어 있다"</i> 를 아는 것은 <b>게이트가
 * 느슨해지는 시점을 고를 수 있다</b>는 뜻이라, 기획 7-4 역이용 방지가 막는 것과 같은 결이다.
 * COMPL 도 아니다 — 그쪽이 보는 것은 기록({@code audit:*})이지 프로세스 상태가 아니다.
 *
 * <h2>{@code audited:} 에 안 넣는다</h2>
 *
 * <p>근거는 <b>하나</b>다: 고객 데이터를 안 읽으므로 <i>"누가 무엇을 봤는가"</i> 가 성립하지
 * 않는다. {@code audited} 목록의 실제 기준은 세 갈래다 — 남의 세션·집계에 닿는 읽기 ·
 * 상태를 바꾸는 결정 · 「띄운 사실」자체가 요건인 것({@code session:simulate}). 이 action 은
 * 어디에도 안 들어간다.
 *
 * <p>❗<b>"폴링하니까 로그가 는다" 를 근거로 쓰지 않는다</b>(#522 리뷰). 이 레포는 같은
 * 전제에서 <b>반대 결론</b>을 이미 냈다 — {@code session:simulate} 주석이
 * <i>"여기 넣으면 로그가 는다 (…) 그래도 줄이는 자리는 여기가 아니다"</i> 다. 그 근거를
 * 여기 적어 두면 다음 사람이 <b>「폴링하는 action 은 audited 에서 뺀다」를 일반 규칙으로
 * 읽고</b>, 그때 {@code session:simulate} 를 뺄 근거가 생겨 기획 7-2 요건이 조용히 사라진다
 * (이슈 #214 가 정확히 그것을 막으려고 넣은 항목이다).
 *
 * <h2>{@code canAggregate} 를 쓴다 — 집계라서가 아니라 귀속 주체가 없어서</h2>
 *
 * <p>{@code AccessGuard.canAggregate} javadoc 이 가른 두 가지 중 이쪽이다. 운영 상태는
 * 누구의 것도 아니다. {@code can(action, id)} 를 쓰면 그 값을 <b>세션 ID 로 조회</b>하므로
 * 요청마다 헛되이 DB 를 치고, 문면이 <i>"운영 상태는 세션이다"</i> 라고 말하게 된다.
 */
@RestController
@RequestMapping("/ops")
@RequiredArgsConstructor
public class OpsController {

    private final OpsStatusService opsStatusService;

    /**
     * 지금 무엇이 안 되는가. <b>매 호출이 실측이고 캐시하지 않는다</b> — 캐시하면 화면의
     * 시각과 값이 갈려서 "방금 고쳤는데 화면이 안 바뀐다" 가 된다.
     *
     * <p>이 경로가 <b>실패로 응답하지 않는다</b>는 것이 중요하다. ai-service 가 죽어 있어도
     * 200 이고 그 카드가 {@code DOWN} 이다 — 상태를 그리는 엔드포인트가 상태 때문에 죽으면
     * 화면이 아무것도 못 그린다.
     */
    @PreAuthorize("@accessGuard.canAggregate('ops:status:read')")
    @GetMapping("/status")
    public ApiResponse<OpsStatus> status() {
        return ApiResponse.ok(opsStatusService.measure());
    }
}
