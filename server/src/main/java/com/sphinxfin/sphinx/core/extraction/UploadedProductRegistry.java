package com.sphinxfin.sphinx.core.extraction;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업로드된 상품 행을 <b>자기 트랜잭션에서</b> 만들거나 갱신한다 (PR #527 리뷰 ⑥). 소유: 강희진
 *
 * <h2>왜 별도 빈인가 — 같은 파일 동시 업로드가 500 이었다</h2>
 *
 * <p>{@link ProductUploads} 안에서 «찾고 없으면 넣는다» 로 하면 <b>check-then-act</b> 다.
 * 두 요청이 커밋 전에 둘 다 «없다» 를 보면 뒤쪽이 {@code uk_uploaded_products_product} 를
 * 위반해 <b>500</b> 이 된다 — 탭 둘이나 더블클릭으로 닿는다({@code S01_Upload} 의
 * {@code busy} 는 탭 안에서만 막는다). {@code V2__uploaded_products.sql} 머리말이
 * <i>"같은 문서를 두 번 올린 것에 500 을 돌려주지 않는다"</i> 를 설계 근거로 적어 뒀는데,
 * 그건 <b>순차일 때만</b> 참이었다.
 *
 * <p>제약 위반을 그 자리에서 <b>«이미 있다» 로 받아넘기려면</b> 실패한 삽입이 <b>바깥
 * 트랜잭션을 오염시키지 않아야</b> 한다 — 오염되면 그 뒤의 조회·갱신이 전부 죽는다. 그래서
 * {@code REQUIRES_NEW} 로 갈라 둔 별도 빈이다. 같은 클래스의 메서드를 부르면 프록시를
 * 안 지나 전파 설정이 <b>조용히 무시</b>되므로 빈을 나누는 것이 이 구조의 요건이다.
 *
 * <h2>이 연산은 멱등이다</h2>
 *
 * <p>{@code productId} 가 내용 주소라 같은 바이트는 같은 행이다. 그래서 경쟁에서 진 쪽이
 * 할 올바른 일은 <b>실패가 아니라 «상대가 방금 넣은 그 행을 갱신»</b> 이다 — 결과가 같다.
 */
@Component
@RequiredArgsConstructor
public class UploadedProductRegistry {

    private final UploadedProductRepository repository;

    /** 새로 만들 행의 값들 — 이 레지스트리가 알아야 하는 전부다. */
    public record Registration(String productId, String productType, String displayName,
                               String originalFilename, String documentPath,
                               String contentSha256, long sizeBytes,
                               String status, String failureReason) {}

    /**
     * 있으면 갱신, 없으면 삽입. <b>동시 삽입에서 진 쪽도 성공한다.</b>
     *
     * @return 새로 만들었으면 {@code true} (로그 문면을 가르는 데만 쓴다)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean registerOrUpdate(Registration r) {
        UploadedProduct row = repository.findByProductId(r.productId()).orElse(null);
        if (row != null) {
            row.reparsed(r.productType(), r.status(), r.failureReason(),
                    r.documentPath(), r.contentSha256(), r.sizeBytes());
            return false;
        }
        repository.save(UploadedProduct.builder()
                .productId(r.productId())
                .productType(r.productType())
                .displayName(r.displayName())
                .originalFilename(r.originalFilename())
                .documentPath(r.documentPath())
                .contentSha256(r.contentSha256())
                .sizeBytes(r.sizeBytes())
                .status(r.status())
                .failureReason(r.failureReason())
                .build());
        // ❗**여기서 flush 한다.** 안 하면 제약 위반이 커밋 시점에 터지고, 그건 이 메서드
        //   밖이라 호출부의 catch 가 못 잡는다 — 경쟁에서 진 요청이 500 으로 나가는 그 자리다.
        repository.flush();
        return true;
    }
}
