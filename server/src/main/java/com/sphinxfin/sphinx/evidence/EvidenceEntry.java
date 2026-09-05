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

    /**
     * {@link CanonicalJson#serialize}의 출력. 저장 후 다시 파싱해도 같은 바이트가 나온다.
     *
     * <p>❗<b>{@code length}를 지운 채로 MySQL 에 뜨면 이 테이블이 못 쓰게 된다.</b>
     * {@code @Lob} 에 길이를 안 주면 Hibernate 가 {@code @Column} 기본값 <b>255</b> 를
     * 그대로 타입 선택에 쓰고, MySQL 에서 그건 {@code tinytext} = <b>255바이트</b>다.
     * 정규화 JSON 한 건도 안 들어간다.
     *
     * <p>H2 에서는 {@code @Lob} 이 길이와 무관하게 CLOB 이라 <b>이 결함이 안 보인다</b> —
     * 배포를 MySQL 로 옮기기 전까지 아무 신호도 없었던 이유다. 그리고 MySQL 은 strict
     * 모드라 조용히 자르지 않고 {@code Data too long for column 'payload_json'} 으로
     * 거절하는데, 그 자리가 하필 <b>해시 체인 적재</b>다 — ADR-004 대로 세션 저장과 같은
     * 트랜잭션이라 판정이 통째로 롤백된다. 즉 첫 채점에서 500 이 난다.
     *
     * <p>{@code Integer.MAX_VALUE} 를 적는 것은 특정 DB 를 겨냥한 값이 아니라
     * <i>"상한을 두지 않는다"</i> 는 {@code @Lob} 의 뜻을 길이로 말한 것이다. Hibernate 가
     * 방언별로 옮긴다 — MySQL 은 {@code longtext}, H2 는 그대로 CLOB.
     *
     * <p>{@code columnDefinition = "longtext"} 로 박지 않는 이유가 그것이다. 그러면 H2 에도
     * {@code longtext} 가 나가서 로컬·테스트가 죽는다.
     */
    @Lob
    @Column(nullable = false, length = Integer.MAX_VALUE)
    private String payloadJson;

    /**
     * 이 행이 쌓인 <b>정규화 규약 세대</b>. 값과 뜻은 {@link CanonicalJson#CANONICAL_VERSION}
     * 에 있다 — 여기서 다시 적지 않는다(두 벌이 되면 갈린다).
     *
     * <p>❗<b>append-only 라 나중에 못 채운다.</b> 이 컬럼이 없는 채로 행이 쌓이면 그 행들은
     * 영원히 "모르는 세대" 이고, 채우려면 {@code UPDATE} 가 필요한데 그건 ADR-004 가 금지한다.
     * 그래서 값이 늘 {@code "0"} 인 지금도 컬럼이 있어야 한다 — <b>세대가 하나뿐인 것과
     * 세대를 모르는 것은 다르다.</b>
     *
     * <p>{@code payload_json} 밖이라 해시에 안 들어간다. 그 한계도 위 상수 주석에 있다.
     */
    @Column(nullable = false, length = 16)
    private String canonicalVersion;
}
