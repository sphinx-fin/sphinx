package com.sphinxfin.sphinx.core.aiservice;

/**
 * 업로드된 문서가 <b>PII 입구 재검사에 걸려</b> 거부됐다 (P3 · PR #534). 소유: 강희진
 *
 * <p>ai-service 의 {@code PiiGuardMiddleware} 가 라우트에 닿기도 전에 막은 경우다.
 * PR #534 가 {@code public_document} 완화를 «측정된 오탐만큼» 으로 좁혀 {@code CARD} 를
 * 이 범위에서도 검사하게 했으므로, <b>올린 파일에 카드번호 같은 값이 있으면</b> 여기로 온다.
 *
 * <h2>❗{@link DocumentUnreadableException} 과 반드시 갈라야 한다</h2>
 *
 * <p>둘 다 ai-service 에서 <b>422</b> 로 온다. 그쪽이 실패를 코드로 갈라 둔 것이 여기까지
 * 이어지지 않으면 같은 코드가 두 뜻을 갖는다 — 그리고 사람에게 가는 문면이 정확히 뒤집힌다.
 *
 * <pre>
 *   DocumentUnreadable   문서를 못 열었다        → 다른 PDF 를 넣는다
 *   DocumentRejected     문서는 열렸다           → 그 문서의 그 값을 확인한다
 * </pre>
 *
 * <p><i>"암호화·손상 PDF 인지 확인하라"</i> 를 받은 운영자는 다른 파일을 넣어 보고, 같은
 * 결과를 받고, 원인을 못 찾는다 — <b>문서는 멀쩡히 열리기 때문</b>이다.
 *
 * <p>{@link DocumentUnreadableException} 과 마찬가지로 <b>부르는 쪽이 잡아서</b> 200 봉투의
 * {@code parse_failed} 로 바꾼다. 계약의 그 값이 <i>"문서를 다시 넣어야 한다"</i> 는 뜻이고
 * 이쪽도 그 부류다 — 운영자가 고칠 수 있는 문제이지 서비스 장애가 아니다.
 *
 * <p>❗<b>패턴 이름만 문면에 싣는다</b>({@code CARD}·{@code ACCOUNT}). 걸린 <b>값</b>은
 * ai-service 가 애초에 안 보낸다(그쪽 {@code PiiDetected} 가 <i>"원문은 절대 담지 않는다"</i>
 * 로 못박아 뒀다) — 그 규약을 이쪽에서 깨지 않는다.
 */
public class DocumentRejectedException extends AiServiceException {

    public DocumentRejectedException(String message) {
        super(message);
    }
}
