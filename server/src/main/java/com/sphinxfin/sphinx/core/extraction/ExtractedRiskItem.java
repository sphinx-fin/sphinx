package com.sphinxfin.sphinx.core.extraction;

import com.sphinxfin.sphinx.core.persistence.BaseEntity;
import com.sphinxfin.sphinx.domain.ParsedDocument;
import com.sphinxfin.sphinx.domain.RiskItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * F-EXT-002 추출 스냅샷 한 항목(JPA 엔티티). 소유: 강희진
 *
 * <p>상품 하나의 추출 결과({@link RiskItem} 목록)를 상품 단위로 통째 저장한다. 재추출하면
 * 그 상품의 기존 행을 전부 지우고 새로 쓴다 — <b>재추출 가능한 스냅샷</b>이지 append-only
 * 증거가 아니므로 {@code evidence/} 가 아니라 여기 있고, {@link BaseEntity} 를 상속한다.
 *
 * <h2>두 상태를 다 왕복해야 한다</h2>
 *
 * <p>{@link RiskItem} 의 컴팩트 생성자가 계약(risk_item.schema.json)의 if/then 을 강제한다:
 * {@code status=extracted} ⇒ condition 필수, {@code extraction_failed} ⇒ condition null +
 * failureReason. 그래서 복원({@link #toDomain()})은 <b>status 로 갈라서</b> condition 을
 * 만든다 — 저장 필드 유무로 가르면 두 불변식 중 한쪽이 재수화에서 터진다.
 *
 * <p>documentId·parserVersion·parsedAt 은 이 항목을 낳은 {@link ParsedDocument} 의 값이다 —
 * 같은 문서·같은 파서면 같은 출력(P2)이므로, 나중에 "이 항목이 어느 파스에서 왔나"를
 * 물을 수 있어야 한다.
 */
@Entity
@Table(name = "extracted_risk_items",
        indexes = @Index(name = "idx_extracted_risk_items_product", columnList = "productId"))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // JPA 전용
@AllArgsConstructor(access = AccessLevel.PRIVATE)    // @Builder 전용
@Builder
public class ExtractedRiskItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String productId;

    /** 추출 응답 안의 순서. 면담(nextQuestion)이 이 순서로 묻는다 — 순서가 곧 진행률이다. */
    @Column(nullable = false)
    private int itemIndex;

    @Column(nullable = false)
    private String itemId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String importance;           // required | recommended

    @Column(nullable = false)
    private String status;               // extracted | extraction_failed

    private String failureReason;        // extraction_failed 일 때만

    /** 원문 인용(P6). 컬럼명에 condition 을 못 쓴다 — SQL 예약어다. */
    @Column(columnDefinition = "TEXT")
    private String conditionValueText;

    private Integer spanPage;            // 페이지 상대 오프셋 [start, end) — ParsedDocument 규약
    private Integer spanStart;
    private Integer spanEnd;

    /** 추출 스냅샷 메타 — 이 항목을 낳은 파스의 정체. */
    @Column(nullable = false)
    private String productType;          // ELS | VARIABLE_INSURANCE

    private String documentId;
    private String parserVersion;
    private String parsedAt;             // ParsedDocument.parsedAt (date-time 문자열, nullable)

    /** 추출 응답의 항목 하나 + 그 파스 메타 → 저장 행. */
    static ExtractedRiskItem of(String productId, int itemIndex, RiskItem item,
                                ParsedDocument parsed) {
        RiskItem.Condition condition = item.condition();
        RiskItem.SourceSpan span = condition == null ? null : condition.sourceSpan();
        return ExtractedRiskItem.builder()
                .productId(productId)
                .itemIndex(itemIndex)
                .itemId(item.itemId())
                .name(item.name())
                .importance(item.importance())
                .status(item.status())
                .failureReason(item.failureReason())
                .conditionValueText(condition == null ? null : condition.valueText())
                .spanPage(span == null ? null : span.page())
                .spanStart(span == null ? null : span.start())
                .spanEnd(span == null ? null : span.end())
                .productType(parsed.productType())
                .documentId(parsed.documentId())
                .parserVersion(parsed.parserVersion())
                .parsedAt(parsed.parsedAt())
                .build();
    }

    /**
     * 저장 행 → 도메인 레코드. <b>status 로 가른다</b> — {@link RiskItem} 생성자의 두 불변식
     * (extracted ⇒ condition 필수, 실패 ⇒ condition 금지)을 저장 필드 유무가 아니라 계약의
     * 판별자(status)로 다시 만족시킨다.
     */
    RiskItem toDomain() {
        RiskItem.Condition condition = null;
        if ("extracted".equals(status)) {
            RiskItem.SourceSpan span = spanPage == null ? null
                    : new RiskItem.SourceSpan(spanPage, spanStart, spanEnd);
            condition = new RiskItem.Condition(conditionValueText, span);
        }
        return new RiskItem(itemId, productId, name, importance, condition, status, failureReason);
    }
}
