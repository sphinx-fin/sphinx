package com.sphinxfin.sphinx.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;


/**
 * F-INT-003 텍스트 응답 요청. 소유: 강희진
 * text는 서버에서 PiiGateway.mask() 후 ai-service로 나간다. inputMeta는 붙여넣기·지연·수정빈도 등.
 */
public record AnswerRequest(
        @NotBlank String itemId,
        @NotBlank String text,
        @Valid InputMeta inputMeta) {

    /**
     * F-INT-003 입력 메타데이터 (이슈 #325).
     *
     * <p>❗<b>{@code Map<String, Object>} 로 받지 않는다.</b> 그러면 화면이 나중에 실어
     * 보내는 것이 그대로 불변 기록에 박힌다 — {@code evidence/} 는 append-only 라 못
     * 뺀다. 타입으로 받으면 모르는 키는 조용히 버려지고, 그게 이 자리의 화이트리스트다.
     *
     * <p>안 보내도 된다({@code null}). 옛 화면과 스크립트가 이 필드 없이 부른다.
     *
     * <p>❗{@code ignoreUnknown = true} 를 <b>명시</b>한다. 스프링 기본값이 그렇긴 한데,
     * 기본값에 기대면 매퍼 설정이 바뀌는 날 <b>면담이 400 으로 멈춘다.</b> 여기서 모르는
     * 키는 버리는 것이 맞다 — 화면이 필드를 하나 더 실어 보냈다고 고객 응답을 거절하면
     * 그 손해가 실수에 비례하지 않는다. {@code ai-service} 경계가 {@code extra_forbidden}
     * 로 엄격한 것({@code #165})과 방향이 다른 이유는 <b>거기는 우리가 양쪽을 쥐고 여기는
     * 사람이 답을 넣고 있기 때문</b>이다.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record InputMeta(long firstKeystrokeDelayMs, long totalInputMs, boolean pasteDetected,
                            int backspaceCount, int charCount, boolean elderlyMode) {

        com.sphinxfin.sphinx.domain.InputMeta toDomain() {
            return new com.sphinxfin.sphinx.domain.InputMeta(
                    firstKeystrokeDelayMs, totalInputMs, pasteDetected,
                    backspaceCount, charCount, elderlyMode);
        }
    }

    /** 안 보냈으면 {@code null} — 기록에서 "없다" 와 "0" 이 갈린다. */
    public com.sphinxfin.sphinx.domain.InputMeta domainInputMeta() {
        return inputMeta == null ? null : inputMeta.toDomain();
    }
}
