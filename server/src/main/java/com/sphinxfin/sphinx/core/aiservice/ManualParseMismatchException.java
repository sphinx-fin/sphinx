package com.sphinxfin.sphinx.core.aiservice;

/**
 * 손으로 놓은 파스 출력의 <b>상품유형이 요청과 다르다</b> (이슈 #598). 소유: 강희진
 *
 * <h2>❗왜 갈라야 하나 — 고칠 자리가 「파일 하나」다</h2>
 *
 * <p>{@code #441} 이 {@code data/documents/<id>.json} 을 두면 파스 출력을 사람이 만든 것으로
 * 대체할 수 있게 했다. 그 파일의 상품유형이 요청과 어긋나면 ai-service 가 거부하는데,
 * <b>고칠 자리는 그 JSON 파일</b>이다 — 올린 문서도, 볼륨 마운트도, ai-service 도 아니다.
 *
 * <pre>
 *   DocumentUnreadableException   그 문서가 문제다        → 파일을 고쳐 다시 올린다
 *   DocumentUnreachableException  볼륨이 문제다          → 마운트·소유권을 고친다
 *   ManualParseMismatchException  놓아 둔 파일이 문제다   → 그 JSON 의 상품유형을 고친다
 *   AiServiceException            상류가 문제다          → ai-service 를 본다
 * </pre>
 *
 * <p>이것이 {@code #556} 의 <b>넷 중 마지막</b>이다. 앞 셋은 갈렸는데 이 갈래만
 * {@code AI_SERVICE_UNAVAILABLE} 로 남아 있었다 — 그 문면을 받은 운영자는 <b>ai-service 를
 * 재시작하고</b>, 그러면 같은 502 가 다시 온다. 파일은 그대로이기 때문이다.
 *
 * <h2>어디서 나는가</h2>
 *
 * <p>ai-service 가 본문 코드로 알려 준다({@code routes.py} 의 거부 표 · {@code #603}).
 *
 * <pre>
 *   400 + code MANUAL_PARSE_TYPE_MISMATCH    그 JSON 의 product_type ≠ 요청의 상품유형
 * </pre>
 *
 * <p>❗<b>상태(400)만으로는 못 가른다.</b> 같은 예외 계열의 다른 넷(경로 비었음 · NUL 문자 ·
 * 경로 해소 실패 · 허용된 뿌리 밖)이 전부 400 인데 그것들은 <b>우리가 잘못 보낸 것</b>이라
 * 502 {@code AI_SERVICE_UNAVAILABLE} 이 맞다. 갈라 내는 재료는 <b>본문 코드</b>다
 * ({@code #591} 이 그 배선을 세웠고, {@code #603} 이 이 코드를 실었다).
 *
 * <h2>❗상태는 502 다 — 400 이 아니다</h2>
 *
 * <p>요청은 정상이다. 운영자가 「이 상품을 다시 추출하라」고 부른 것이고, 어긋난 것은
 * <b>배포가 놓아 둔 파일</b>이다. 4xx 로 두면 호출자 잘못으로 읽혀서 요청을 고치려 든다 —
 * 요청을 아무리 고쳐도 그 파일이 그대로면 같은 결과다. {@link DocumentUnreachableException}
 * 이 같은 이유로 502 다(그쪽도 고칠 자리가 배포다).
 *
 * <p>❗<b>{@link AiServiceException} 을 상속한다.</b> 이 타입을 모르는 옛 호출부는 지금처럼
 * 502 로 다룬다 — 갈래를 늘리는 변경이 조용히 500 을 만들지 않는다.
 */
public class ManualParseMismatchException extends AiServiceException {

    public ManualParseMismatchException(String message) {
        super(message);
    }
}
