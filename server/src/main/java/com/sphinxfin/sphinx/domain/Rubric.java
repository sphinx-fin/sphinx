package com.sphinxfin.sphinx.domain;

import java.util.List;

/**
 * 채점 기준 하나 — ai-service {@code app/rubrics/<item_id>.yaml} 의 내용 그대로. 소유: 강희진
 *
 * <h2>왜 서버에 이 타입이 있나 (이슈 #474 ② · #475)</h2>
 *
 * <p>공개 의무(기획서 5절)가 요구하는 것은 <i>"기준이 문서로 존재하고 감사·심사가 볼 수
 * 있다"</i> 다. 그 «볼 수 있다» 를 성립시키는 것이 화면인데, 화면은 {@code ai-service} 를
 * 직접 못 부른다(내부망 전용 · CLAUDE.md). 그래서 서버가 <b>읽기 전용으로 중계</b>하고 이
 * 레코드가 그 왕복의 모양이다.
 *
 * <p>❗<b>판매 직원에게 열지 않는다</b>({@code rbac_policy.yaml} 의 {@code rubric:read} —
 * COMPL·MGR·ADMIN). 채점 정답표라 7-4(역이용 방지)에 걸린다: 기준을 아는 판매자는
 * <i>"이렇게 답하시면 통과합니다"</i> 를 할 수 있다.
 *
 * <h2>❗{@code u1Requires} 를 빼지 않는다</h2>
 *
 * <p>요소 <b>개수</b>가 아니라 이 값이 U1 문턱이다({@code #367}). 화면이 요소 목록만 보이고
 * 이 값을 안 보이면 <i>"이 전부를 말해야 한다"</i> 로 읽힌다 — {@code #450} 이 정확히 그
 * 결함이었고 {@code VAR-PARTIAL-DEPOSIT-INSURANCE} 는 요소 2 · 문턱 <b>1</b> 이다.
 *
 * <p>{@code unlinkedUntil} 은 링크가 <b>비어 있는 이유</b>다 — 빈 목록이 「해당 없음」인지
 * 「아직 못 걸었다」인지 가른다({@code #284}·{@code #396}). {@code null} 이면 그 구분을 안
 * 적은 것이고, 값이 있으면 {@code [근거, 빼는 조건]} 이다.
 *
 * @param status confirmed | draft — draft 는 사람이 아직 확정 안 한 것. ❗<b>채점은 지금 이
 *               값을 안 본다</b>(#609 ① 의 미결) — 화면이 그것을 「확정됨」으로 그리면 안 된다
 */
public record Rubric(String itemId, String productType, String name, String status,
                     List<String> requiredElements, int u1Requires,
                     List<String> misconceptionConditions, List<String> relatedMisconceptions,
                     List<String> unlinkedUntil) {

    public Rubric {
        requiredElements = requiredElements == null ? List.of() : List.copyOf(requiredElements);
        misconceptionConditions = misconceptionConditions == null
                ? List.of() : List.copyOf(misconceptionConditions);
        relatedMisconceptions = relatedMisconceptions == null
                ? List.of() : List.copyOf(relatedMisconceptions);
        // ❗null 을 빈 목록으로 접지 않는다 — 「해당 없음」과 「아직 못 걸었다」가 같아진다.
        unlinkedUntil = unlinkedUntil == null ? null : List.copyOf(unlinkedUntil);
    }
}
