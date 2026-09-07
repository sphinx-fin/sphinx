package com.sphinxfin.sphinx.core.extraction;

import com.sphinxfin.sphinx.core.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * F-EXT-001 업로드된 상품 문서 한 건(JPA 엔티티). 소유: 강희진
 *
 * <p>{@code POST /products/documents} 가 받은 파일이 어디에 살고 어느 상품이 되었는지를
 * 적는다. 예전에는 이 행이 없어서 업로드가 <b>파일을 버리고 {@code mock-els-001} 을
 * 냈고</b>, 그런데도 감사 로그에는 <i>"누가 언제 상품을 등록했다"</i> 가 남았다(이슈 #521).
 * 그 기록이 가리킬 실물이 이 행이다.
 *
 * <h2>바이트는 여기 없다 — 경로만 있다</h2>
 *
 * <p>파일은 {@code sphinx.documents.data-dir} 아래 {@code uploads/} 에 살고 이 행은 그
 * <b>상대경로</b>만 든다({@link #documentPath}). 바이트를 DB 에 넣지 않은 이유는
 * {@code /internal/parse} 가 <b>경로를 받는</b> 계약이기 때문이다 — DB 에 넣으면 추출마다
 * 임시파일로 다시 떨어뜨려야 하고, 그러면 <i>"어디에 쓰나"</i> 가 임시 디렉토리 문제로
 * 옮겨갈 뿐이다. 대신 그 디렉토리는 배포를 넘어 사는 이름 있는 도커 볼륨이다
 * ({@code sphinx_uploads} — {@code docker-compose.yml}).
 *
 * <h2>{@link #productId} 는 내용에서 나온다 (P2)</h2>
 *
 * <p>{@code doc-<파일명 슬러그>-<sha256 앞 8자>} 다({@link UploadedDocumentStore#issueProductId}).
 * 같은 파일을 두 번 올리면 같은 상품이고(재업로드가 상품을 늘리지 않는다), 내용이 한 바이트
 * 달라지면 <b>다른 상품</b>이다 — 게이트가 물을 항목이 문서에서 나오므로, 문서가 바뀌면
 * 판정의 근거가 바뀐 것이라 같은 상품으로 뭉치면 안 된다.
 *
 * <p>❗<b>{@code parse_failed} 도 행을 남긴다.</b> 지우면 감사 로그의 "등록했다" 가 가리킬
 * 것이 없어지고, 운영자는 자기가 올린 문서가 왜 안 보이는지 알 길이 없다(E-EXT-03
 * 은폐 금지와 같은 방향). 그 행은 {@code GET /products} 에 {@code parse_failed} 로 뜬다.
 */
@Entity
@Table(name = "uploaded_products",
        uniqueConstraints = @UniqueConstraint(name = "uk_uploaded_products_product",
                columnNames = "productId"))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // JPA 전용
@AllArgsConstructor(access = AccessLevel.PRIVATE)    // @Builder 전용
@Builder
public class UploadedProduct extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 발급된 상품ID. 재업로드가 같은 값을 내므로 unique 다 — 두 행이면 조회가 갈린다. */
    @Column(nullable = false)
    private String productId;

    /** ELS | VARIABLE_INSURANCE. 파스가 판별한 값이 있으면 그것, 없으면 요청값. */
    @Column(nullable = false)
    private String productType;

    /** {@code GET /products} 가 내는 표시명. 업로더가 준 파일명에서 만든다. */
    @Column(nullable = false)
    private String displayName;

    /** 업로더가 올린 원래 파일명. 저장 파일명은 이것을 정제한 값이다. */
    @Column(nullable = false)
    private String originalFilename;

    /**
     * {@code sphinx.documents.data-dir} 상대경로(= ai-service {@code SPHINX_DATA_DIR} 규약).
     * {@link ProductRiskItems#documentPathOf} 가 이 값을 내고 파스와 원문 조회가 같이 읽는다.
     */
    @Column(nullable = false)
    private String documentPath;

    /** 업로드 바이트의 sha256. productId 의 재료이자 같은 파일인지의 판단 근거다(P2). */
    @Column(nullable = false, length = 64)
    private String contentSha256;

    @Column(nullable = false)
    private long sizeBytes;

    /** parsed | parse_failed — 계약 {@code UploadResponse.status} 와 같은 어휘다. */
    @Column(nullable = false)
    private String status;

    /** {@code parse_failed} 일 때 왜 못 읽었는지. 운영자가 문서를 다시 넣을 판단 근거다. */
    private String failureReason;

    /**
     * 같은 문서를 다시 올렸을 때 파스 결과만 갱신한다.
     *
     * <p>❗<b>새 행을 넣지 않는다.</b> {@link #productId} 가 내용 주소라 같은 값이 나오고
     * unique 제약에 걸린다 — 같은 문서를 두 번 올린 것에 대한 답으로 500 을 주게 된다.
     * 경로·sha256·크기는 내용이 같으므로 바뀔 것이 없고, 바뀔 수 있는 것은 파스 결과뿐이다
     * (예: LLM 키가 없어 실패했다가 나중에 성공). {@code updatedAt} 이 그 시각을 잡는다.
     */
    public void reparsed(String productType, String status, String failureReason) {
        this.productType = productType;
        this.status = status;
        this.failureReason = failureReason;
    }
}
