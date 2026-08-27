package com.sphinxfin.sphinx.core;

import java.util.Set;

/**
 * 불공정영업으로 볼 오해 유형 (F-GTE-003). 소유: 강희진
 *
 * <h2>왜 여기 목록이 있나 — 그리고 왜 임시인가</h2>
 *
 * <p>판단 근거는 원래 <b>오해 라이브러리</b>에 있다. {@code misconceptions.yaml} 의
 * {@code escalate: compliance} 가 그것이고, ai-service 주석이 <i>"escalate 는 라이브러리
 * 데이터의 필드를 읽어서 판단한다 — M08-TYING 을 코드에 하드코딩하지 않는다"</i> 라고
 * 못박아 뒀다. 그 판단이 옳다.
 *
 * <p>그런데 <b>서버가 그 값을 받을 수 없다.</b> {@code escalate} 는
 * {@code /internal/misconception} 의 {@code MisconceptionResponse} 에 있고, 서버가 부르는
 * {@code /internal/score} 의 {@code Judgment} 에는 없다 —
 * {@code contracts/judgment.schema.json} 에 그 필드가 없다. 그래서 지금은 유형 ID 로 가른다.
 *
 * <p><b>이 클래스는 계약이 열리면 사라진다.</b> 그때까지 조용히 낡지 않도록
 * {@code UnfairSalesTypesSyncTest} 가 라이브러리의 {@code escalate: compliance} 집합과
 * 이 목록을 대조한다 — 유형이 하나 더 승급되면 그 테스트가 실패한다. 목록을 코드에 둔 것이
 * 문제가 아니라, <b>코드에 두고 아무도 안 보는 것</b>이 문제다.
 */
public final class UnfairSalesTypes {

    /** misconceptions.yaml 에서 escalate: compliance 인 유형. 테스트가 대조한다. */
    static final Set<String> ESCALATING = Set.of("M08-TYING");

    private UnfairSalesTypes() {}

    /** 이 판정이 컴플라이언스로 올라갈 신호인가. misconceptionType 은 nullable 이다. */
    public static boolean isSignal(String misconceptionType) {
        return misconceptionType != null && ESCALATING.contains(misconceptionType);
    }
}
