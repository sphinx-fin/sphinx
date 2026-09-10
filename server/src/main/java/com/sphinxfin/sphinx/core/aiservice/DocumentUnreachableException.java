package com.sphinxfin.sphinx.core.aiservice;

/**
 * 등록된 문서의 <b>파일에 닿지 못했다</b> — 없거나 읽을 권한이 없다 (이슈 #556). 소유: 강희진
 *
 * <h2>❗{@code DocumentUnreadableException} 과 다르다 — 고칠 자리가 다르다</h2>
 *
 * <pre>
 *   DocumentUnreadableException   그 문서가 문제다      → 파일을 고쳐 다시 올린다
 *   DocumentUnreachableException  배포가 문제다        → 볼륨 마운트·소유권을 고친다
 *   AiServiceException            상류가 문제다        → ai-service 를 본다
 * </pre>
 *
 * <p>이 셋이 한 코드로 뭉쳐 있던 것이 {@code #556} 이다. 증상은 전부
 * <i>"채점 서비스에 연결할 수 없습니다"</i> 였고, 그 문면을 받은 운영자는 <b>ai-service 를
 * 재시작한다.</b> 볼륨 소유권이 틀린 상태에서 그건 아무것도 안 고치고, 같은 502 가 다시 온다.
 *
 * <h2>어디서 나는가</h2>
 *
 * <p>ai-service 가 두 갈래로 알려 주고({@code routes.py} 의 거부 표, {@code #548}·{@code #551})
 * {@link AiServiceClient} 가 그것을 이 타입으로 받는다.
 *
 * <pre>
 *   404 DocumentNotFound                  파일이 없다      업로드본이 사라졌거나 볼륨이 안 붙었다
 *   502 + code DOCUMENT_ACCESS_DENIED     못 읽는다        볼륨이 root:root · server 는 uid 10001
 * </pre>
 *
 * <p>둘을 한 타입으로 두는 이유는 <b>다음 행동이 같기 때문</b>이다 — 배포에서 업로드 볼륨을
 * 본다. 문면이 어느 쪽인지 가르므로 타입을 둘로 늘리지 않는다({@code DOCUMENT_UNPROCESSABLE}
 * 이 «못 열림» 과 «PII 거부» 를 한 코드로 낸 것과 같은 판단).
 *
 * <p>❗<b>{@link AiServiceException} 을 상속한다.</b> 그래서 이 타입을 모르는 옛 호출부는
 * 지금처럼 502 로 다룬다 — 갈래를 늘리는 변경이 조용히 500 을 만들지 않는다.
 */
public class DocumentUnreachableException extends AiServiceException {

    public DocumentUnreachableException(String message) {
        super(message);
    }
}
