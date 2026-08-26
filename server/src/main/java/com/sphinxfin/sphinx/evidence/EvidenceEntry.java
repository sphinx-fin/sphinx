package com.sphinxfin.sphinx.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * append-only 기록 한 건. 소유: 정세현
 *
 * <p><b>{@code BaseEntity}를 상속하지 않는다</b>(CLAUDE.md) — {@code updatedAt}이 있다는 것은
 * 고칠 수 있다는 뜻이고, 이 테이블에는 UPDATE 경로가 없다. 기록 시각은 payload 안에 있다
 * (append 계열이 {@code Instant at}을 받는다).
 *
 * <p><b>payload를 정규화된 JSON 문자열로 저장한다.</b> 객체를 직렬화해 두고 검증 때 다시 읽는데,
 * 이게 성립하는 이유는 <b>정규화 JSON이 고정점</b>이기 때문이다 — 정규화된 문자열을 다시 파싱해
 * 정규화하면 같은 바이트가 나온다. 그래서 저장 시점의 hash와 재생 후 재계산한 hash가 같다.
 * ({@code JpaImmutableStore}가 그 왕복을 테스트로 고정한다.)
 *
 * <p>{@code (stream, seq)}에 유니크 제약을 건다. 같은 자리에 두 번 적재되는 것은 코드 결함이고,
 * 그때 조용히 덮이는 대신 DB가 거절해야 한다.
 */
@Entity
@Table(name = "evidence_entries",
        uniqueConstraints = @UniqueConstraint(name = "uk_evidence_stream_seq",
                columnNames = {"stream", "seq"}))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // JPA 전용
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class EvidenceEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 체인 분리 단위. 예: {@code report:{sessionId}} · {@code audit} */
    @Column(nullable = false)
    private String stream;

    /** 스트림 안에서의 순번. {@link HashChain#FIRST_SEQ}부터 1씩. */
    @Column(nullable = false)
    private long seq;

    @Column(nullable = false, length = 64)
    private String prevHash;

    @Column(nullable = false, length = 64)
    private String hash;

    /** {@link CanonicalJson#serialize}의 출력. 저장 후 다시 파싱해도 같은 바이트가 나온다. */
    @Lob
    @Column(nullable = false)
    private String payloadJson;
}
