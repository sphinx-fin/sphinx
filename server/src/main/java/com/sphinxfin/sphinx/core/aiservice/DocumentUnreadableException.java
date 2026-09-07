package com.sphinxfin.sphinx.core.aiservice;

/**
 * 파스가 문서를 <b>열지 못했다</b> — 암호화·손상 PDF 등 (F-EXT-001 예외 규정). 소유: 강희진
 *
 * <p>ai-service {@code /internal/parse} 가 이 경우를 <b>422</b> 로 낸다. 그쪽 라우트 주석이
 * 근거를 적어 뒀다 — <i>"문서가 안 열리는 것은 상류 장애가 아니라 입력 문제다 — 502 로
 * 나가면 Spring 쪽에서 «ai-service 장애» 로 오진된다"</i>.
 *
 * <p>❗<b>{@link AiServiceException} 을 상속하지만 502 로 나가서는 안 된다.</b> 상속은
 * "ai-service 호출에서 나온 실패" 라는 사실을 잃지 않기 위한 것이고, 이걸 502 로 흘리면
 * <b>업로드 화면이 다음 행동을 못 고른다</b> — 계약 {@code UploadResponse.status} 가
 * {@code parsed}/{@code parse_failed} 로 갈라 둔 이유가 그것이다(S-01 설계 판단 ③:
 * 앞은 문서를 다시 넣어야 하고, 뒤는 사람이 채워야 한다). 그래서 <b>부르는 쪽이 잡아서</b>
 * 200 봉투의 {@code parse_failed} 로 바꾼다. 안 잡으면 전역 핸들러가 502 로 내는데,
 * 그건 "우리 서비스가 죽었다" 는 뜻이라 운영자가 문서를 의심하지 않는다.
 */
public class DocumentUnreadableException extends AiServiceException {

    public DocumentUnreadableException(String message) {
        super(message);
    }
}
