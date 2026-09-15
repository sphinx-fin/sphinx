package com.sphinxfin.sphinx.api.dto;

import com.sphinxfin.sphinx.domain.Rubric;

import java.util.List;

/**
 * {@code GET /products/rubrics} 응답 (이슈 #475). 소유: 강희진
 *
 * <p>❗<b>{@code total} 은 필터 전 전체 개수다.</b> 걸러진 목록만 보이면 화면이 <i>"이게
 * 전부"</i> 로 읽는다 — ai-service 가 같은 이유로 이 값을 내고({@code RubricListResponse})
 * 여기서 접으면 그 뜻이 사라진다. {@code R-00} 이 분모를 지키는 것과 같은 결이다.
 */
public record RubricsResponse(List<Rubric> rubrics, int total) {

    public RubricsResponse {
        rubrics = rubrics == null ? List.of() : List.copyOf(rubrics);
    }
}
