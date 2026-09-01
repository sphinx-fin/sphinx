package com.sphinxfin.sphinx.evidence;

/**
 * 발행 기록에서 그 지면을 <b>다시 만들어내지 못했다.</b> 소유: 정세현
 *
 * <p>체인이 상했거나 조립 규칙이 바뀌어서, 지금 재현한 내용의 해시가 발행 시점에 기록해 둔
 * 해시와 다르다는 뜻이다. {@link ReportService#pdf(String)} 이 그 대조에 실패하면 던진다.
 *
 * <h2>왜 별도 타입인가 — {@code NoSuchElementException} 과 절대 같아지면 안 된다</h2>
 *
 * <p>{@code pdf()} 가 실패하는 방식은 둘인데 <b>성격이 정반대</b>다.
 *
 * <pre>
 *   아직 발행하지 않았다   정상 상태다. 화면은 "발행하기" 를 띄우면 된다 → 404
 *   재현하지 못했다        무결성 실패다. 조용히 넘어가면 안 된다      → 500
 * </pre>
 *
 * <p>❗원래 둘 다 {@code IllegalStateException} 이었다. 그러면 엔드포인트를 붙이는 사람에게
 * 나쁜 선택지 둘밖에 없다 — 그대로 두면 <b>정상 상태가 500</b> 이 되고,
 * {@code IllegalStateException → 404} 로 매핑하면 <b>체인 손상이 "아직 발행 안 했습니다" 로
 * 둔갑한다.</b> 뒤쪽이 훨씬 나쁘다: 종이와 불변 기록이 갈렸다는 사실이 화면에도 로그에도
 * 안 남고, 분쟁 시점까지 아무도 모른다. 타입을 갈라 <b>그 매핑을 애초에 쓸 수 없게</b> 한다.
 *
 * <p>전용 에러 코드를 만들지 않는 이유는 이것이 <b>클라이언트가 고칠 수 있는 상태가 아니기</b>
 * 때문이다. 핸들러가 없으므로 {@code GlobalExceptionHandler} 의 마지막 {@code Exception} 갈래로
 * 떨어져 {@code INTERNAL_ERROR}(500) 가 된다 — 그게 맞는 자리다. 계약({@code ApiError.code})
 * 도 안 늘어난다.
 */
public class ReportNotReproducibleException extends RuntimeException {

    public ReportNotReproducibleException(String message) {
        super(message);
    }
}
