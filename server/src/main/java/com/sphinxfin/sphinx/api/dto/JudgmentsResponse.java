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
public record JudgmentsResponse(String sessionId, String state, List<JudgmentView> judgments) {

    public static JudgmentsResponse of(Session s) {
        // 항목 ID 순으로 고정한다 — 맵 순회 순서에 화면이 흔들리면 안 된다.
        // JudgmentView 로 내리는 이유는 그 타입의 javadoc 에 있다 (#144) — 목록 경로로
        // 새면 단건을 막은 것이 의미가 없다. 두 엔드포인트가 같은 값을 낸다.
        List<JudgmentView> ordered = s.judgments().stream()
                .sorted(Comparator.comparing(Judgment::itemId))
                .map(JudgmentView::of)
                .toList();
        return new JudgmentsResponse(s.id(), s.state().name(), ordered);
    }
}
