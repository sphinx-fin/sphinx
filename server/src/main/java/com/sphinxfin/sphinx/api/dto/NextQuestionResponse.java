package com.sphinxfin.sphinx.api.dto;

/**
 * F-INT-002 다음 질문 + 진행 상태. 소유: 강희진
 *
 * index/total 을 서버가 준다. 화면이 "추출된 항목 수"로 분모를 보완하면 그건 서버가 물어볼
 * 항목 수와 다를 수 있고, 어긋나면 조용히 틀린 진행률이 보인다.
 * done=true 면 더 물을 항목이 없다 — 인터뷰 종료 판단의 유일한 근거다.
 *
 * <p>❗<b>{@code vulnerable} 은 화면이 취약 판정을 대신 하지 않게 하려고 싣는다</b>(이슈 #319).
 * S-03 이 큰 글씨를 켜는 유일한 경로가 재설명 응답의 같은 이름 필드였는데, 그건
 * <b>고객이 한 번 못 알아들은 뒤</b>에나 온다. 세션 시작에서 이미 「70대」를 받아 놓고
 * 인터뷰를 작은 글씨로 시작하고 있었다.
 *
 * <p>화면이 연령대를 받아 스스로 가르는 안은 <b>기각됐다</b> — 취약 판정의 근거는
 * {@code vulnerability_weights.yaml}(연령·가입금액대·투자경험·채널 네 요인의 합 ≥ 임계값)이고
 * 연령대만 보는 근사가 아니다. 화면이 다시 계산하면 임계값이 web 에 두 벌이 되고, 그 파일이
 * 움직이는 날 <b>조용히</b> 갈린다. 여기서 서버 판단 하나를 실어 화면은 켜기만 한다.
 */
public record NextQuestionResponse(
        String itemId,      // done=true 면 null
        String question,    // done=true 면 null
        int index,          // 1-based. done=true 면 total 과 같다
        int total,
        boolean done,
        boolean vulnerable) {

    public static NextQuestionResponse of(String itemId, String question, int index, int total,
                                          boolean vulnerable) {
        return new NextQuestionResponse(itemId, question, index, total, false, vulnerable);
    }

    /**
     * 물을 항목이 없다. <b>{@code vulnerable} 은 여기서도 채운다</b> — 세션의 성질이지
     * 이번 질문의 성질이 아니다. 비워 두면 마지막 항목에 답한 뒤 오는 응답에서만 힌트가
     * 사라져, 화면이 그 시점에 모드를 되돌릴 근거를 갖게 된다.
     */
    public static NextQuestionResponse done(int total, boolean vulnerable) {
        return new NextQuestionResponse(null, null, total, total, true, vulnerable);
    }
}
