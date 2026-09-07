package com.sphinxfin.sphinx.core.ops;

import java.util.List;

/**
 * 운영 콘솔(S-09)이 그리는 실측 상태 (F-OPS-001 · 이슈 #522). 소유: 강희진
 *
 * <h2>❗이 타입에 고객 데이터가 실리면 권한 설계가 무너진다</h2>
 *
 * <p>{@code ops:status:read} 를 ADMIN 에게 준 근거는 <i>"운영이라서"</i> 가 아니라
 * <b>응답에 데이터가 없어서</b>다 — ADR-001 데이터 범위 표가 ADMIN 에게 닫아 둔 것은
 * <b>개별 세션과 집계</b>이고, 세션 수 하나만 얹어도 이 엔드포인트가 그 우회 경로가 된다.
 * 그때 고칠 자리는 정책이 아니라 <b>여기</b>다: 값을 응답에서 뺀다.
 *
 * <p>그 전제를 리뷰에만 맡기지 않는다 — {@code OpsStatusHasNoCustomerDataTest} 가 이 타입
 * 계열이 세션·판정·집계 타입을 <b>하나도 참조하지 않는지</b> 대조한다(#522 리뷰 제안).
 * 문자열로 우회하는 것까지는 못 막지만 <b>실수로 얹는 경로</b>는 닫힌다.
 *
 * <h2>비밀은 값이 아니라 «설정됐는가» 만</h2>
 *
 * <p>ai-service {@code /healthz} 가 이미 그 규칙으로 만들어져 있다(<i>"키 유무를 노출하지만
 * 값은 절대 노출하지 않는다"</i>) — 그대로 잇는다. JDBC URL 은 {@code ?} 앞까지만 싣는다:
 * 지금 거기 비밀이 없지만, 누가 파라미터로 자격증명을 붙이는 날 화면이 그걸 그린다.
 *
 * @param checkedAt  실측 시각. <b>캐시하지 않는다</b> — 캐시하면 화면의 시각과 값이 갈려서
 *                   "방금 고쳤는데 화면이 안 바뀐다" 가 된다
 * @param components server · database · ai-service · data-volumes 넷
 */
public record OpsStatus(String checkedAt, Deployment deployment, List<Component> components) {

    /**
     * 지금 뜬 것이 무엇인가.
     *
     * @param stack blue 또는 green (compose 의 {@code STACK}). <b>로컬은 빈 문자열</b> —
     *              색이 없는 것이 정상이고, 그걸 «모른다» 로 그리면 로컬이 상시 경고가 된다
     */
    public record Deployment(String profile, String stack, String startedAt, long uptimeSec) {}

    /**
     * 구성요소 하나.
     *
     * <h2>❗{@code DEGRADED} 가 이 엔드포인트의 요점이다</h2>
     *
     * <p><b>떠 있는데 못 하는 상태</b>가 이 스택에서 실제로 자주 나는 실패다 — 키 없이 뜬
     * ai-service, 마운트가 빠진 채 뜬 server. UP/DOWN 둘로만 그리면 그게 <b>전부 정상으로
     * 보인다.</b> 그 셋이 겉으로 같은 502 하나였다는 것이 #522 의 출발점이다.
     *
     * @param latencyMs 왕복 시간. <b>못 잰 자리는 null 이다 — 0 과 다르다.</b> 0 으로 채우면
     *                  «즉시 응답» 과 «재지 않았다» 가 화면에서 같아진다
     * @param note      정상이면 null. 한 줄만 — 스택트레이스는 서버 로그로 간다
     * @param facts     ❗<b>{@code Map} 이 아니라 배열이다.</b> 화면이 읽는 순서가 곧 중요도인데
     *                  {@code Map} 의 직렬화 순서는 구현에 달린다(#522 요청)
     */
    public record Component(String id, String name, Health health,
                            Integer latencyMs, String note, List<Fact> facts) {}

    /** 이름표와 값. 값은 사람이 읽는 문면이고, 비밀은 «설정됐는가» 까지만 적는다. */
    public record Fact(String label, String value) {}

    /**
     * 세 값이다. {@code DEGRADED} 를 넣은 이유는 {@link Component} 주석에 있다.
     *
     * <p>❗<b>H2 를 {@code DEGRADED} 로 두지 않는다</b>(#522 판단). 로컬에서 상시 노랑이면
     * 노랑이 아무 뜻도 없어진다 — {@code note} 로만 적는다.
     */
    public enum Health { UP, DEGRADED, DOWN }
}
