package com.sphinxfin.sphinx.api.dto;

/**
 * F-INT-002 다음 질문 + 진행 상태. 소유: 강희진
 *
 * index/total 을 서버가 준다. 화면이 "추출된 항목 수"로 분모를 보완하면 그건 서버가 물어볼
 * 항목 수와 다를 수 있고, 어긋나면 조용히 틀린 진행률이 보인다.
 * done=true 면 더 물을 항목이 없다 — 인터뷰 종료 판단의 유일한 근거다.
 */
public record NextQuestionResponse(
        String itemId,      // done=true 면 null
        String question,    // done=true 면 null
        int index,          // 1-based. done=true 면 total 과 같다
        int total,
        boolean done) {

    public static NextQuestionResponse of(String itemId, String question, int index, int total) {
        return new NextQuestionResponse(itemId, question, index, total, false);
    }

    public static NextQuestionResponse done(int total) {
        return new NextQuestionResponse(null, null, total, total, true);
    }
}
