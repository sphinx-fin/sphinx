package com.sphinxfin.sphinx.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * E-INT-03 항목 건너뛰기 요청 (이슈 #518). 소유: 강희진
 *
 * <p>❗<b>{@code text} 가 없다.</b> 그게 이 DTO 의 존재 이유다 — 예전에는 화면이
 * {@code "(응답하지 않음)"} 을 {@link AnswerRequest#text()} 에 실어 보냈고, 서버는 그것을
 * 보통 발화처럼 채점 경로에 넘겼다. 발화 없는 판정을 모델에게 시키는 자리였고 502 가 났다
 * ({@code SkippedItem} javadoc). 문자열은 이제 서버가 소유한다.
 *
 * <p>❗<b>{@code inputMeta} 도 없다.</b> 그 값은 <i>"어떻게 입력했나"</i> 인데 입력이 없다.
 * {@code charCount=0 · pasteDetected=false} 를 남기면 기록에서 <i>"빈 입력을 쟀다"</i> 로
 * 읽히고, 그건 {@code evidence/} 가 append-only 라 나중에 못 뺀다(이슈 #136 과 같은 결).
 */
public record SkipRequest(@NotBlank String itemId) {
}
