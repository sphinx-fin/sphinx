package com.sphinxfin.sphinx.api.dto;

import com.sphinxfin.sphinx.core.session.Session;
import com.sphinxfin.sphinx.domain.Judgment;

import java.util.Comparator;
import java.util.List;

/**
 * S-05 판정 결과 화면 입력 — 세션에 쌓인 항목별 판정 목록. 소유: 강희진
 *
 * S-03(고객 화면)과 S-05(판매자 화면)는 다른 기기·다른 탭이다. 화면이 메모리에 들고 갈 수
 * 없으므로 서버에서 다시 받아야 한다.
 *
 * 항목별 신호등을 여기에 싣지 않는다 — 게이트 판정은 /judge 의 signal 이 단독으로 소유한다
 * (P1). grade → 색은 표시 관례이며 판정이 아니다.
 */
public record JudgmentsResponse(String sessionId, String state, List<JudgmentView> judgments,
                                List<ReverifyStatus> reverify) {

    /**
     * 항목별 재검증 사용 상태 (이슈 #506). <b>봉투 레벨</b>에 둔다 — 재검증 이력은 판정 자체가
     * 아니라 세션 진행 상태라 {@link JudgmentView}(판매자 노출 허용목록, #144·#147)를 안 늘린다.
     *
     * <p>❗<b>상한 N 은 안 싣는다</b> — 서버가 룰 임계값을 문면에 안 넣기로 한 규약(기획서 7-4
     * 역이용 방지)과 같은 방향이다. {@code exhausted} 불리언까지만 준다: 화면은 "재설명 N회 함"
     * / "재설명 횟수 소진" 까지만 말하고 임계값 숫자는 노출하지 않는다.
     *
     * @param used      이 항목에 쓴 재검증 횟수
     * @param exhausted 상한 도달 여부(도달 시 재설명 불가, 판정으로 진행)
     */
    public record ReverifyStatus(String itemId, int used, boolean exhausted) {}

    /**
     * @param maxReverify 재검증 상한(gate_rules R-03). {@code exhausted} 계산에만 쓰고 응답엔
     *                    안 나간다(임계값 비노출, 7-4). 호출자가 넘긴다 — 서비스가 게이트 룰에서 읽는 값.
     */
    public static JudgmentsResponse of(Session s, int maxReverify) {
        // 항목 ID 순으로 고정한다 — 맵 순회 순서에 화면이 흔들리면 안 된다.
        // JudgmentView 로 내리는 이유는 그 타입의 javadoc 에 있다 (#144) — 목록 경로로
        // 새면 단건을 막은 것이 의미가 없다. 두 엔드포인트가 같은 값을 낸다.
        List<JudgmentView> ordered = s.judgments().stream()
                .sorted(Comparator.comparing(Judgment::itemId))
                .map(JudgmentView::of)
                .toList();
        // 재검증한 적 있는 항목만 실린다(reverifyCounts 에 키가 있는 것) — 화면은 소진·사용
        // 이력을 이 값으로 그린다. sessionStorage/«눌러 본 이력» 대신 서버가 출처(#506).
        List<ReverifyStatus> reverify = s.reverifyCounts().entrySet().stream()
                .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                .map(e -> new ReverifyStatus(e.getKey(), e.getValue(), e.getValue() >= maxReverify))
                .toList();
        return new JudgmentsResponse(s.id(), s.state().name(), ordered, reverify);
    }
}
