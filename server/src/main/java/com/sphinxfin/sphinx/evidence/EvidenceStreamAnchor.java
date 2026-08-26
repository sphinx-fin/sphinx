package com.sphinxfin.sphinx.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 스트림별 닻 — 머리 hash와 항목 수. 소유: 정세현
 *
 * <p><b>왜 필요한가.</b> 해시 체인은 <b>꼬리 절단을 탐지하지 못한다</b>(HashChain 주석의 한계 절).
 * 끝에서부터 지우면 남은 부분이 그 자체로 완전한 체인이라 검증이 통과하고, 전부 지우면 빈 체인이
 * 된다. 그런데 감사에서 실제로 지우고 싶은 것은 대개 최근 기록이다 — 방금 승인한 적색 오버라이드,
 * 방금 차단당한 접근 시도. 그래서 <b>체인 밖에</b> 기대값을 둔다(PR #79 리뷰).
 *
 * <p><b>이것은 append-only가 아니라 갱신되는 행이다.</b> 기록이 아니라 기록의 요약이므로 그게 맞다.
 * 대신 {@link EvidenceEntry}와 같은 트랜잭션에서만 움직인다 — 둘이 갈리면 검증이 거짓 경보를 낸다.
 *
 * <p><b>한계는 정직하게 적어둔다.</b> DB에 쓸 수 있는 사람은 이 행도 고칠 수 있다. 닻이 막는 것은
 * "기록만 지우고 끝내는 것"이고, 두 곳을 아귀 맞게 고쳐야 하도록 비용을 올릴 뿐이다. 암호학적
 * 보장이 되려면 닻이 <b>이 DB 밖</b>에 있어야 한다(외부 공증·별도 저장소). MVP 범위 밖이고,
 * 그 사실을 모른 채 "체인이 있으니 안전하다"고 하지 않으려고 여기 적는다.
 */
@Entity
@Table(name = "evidence_stream_anchors")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class EvidenceStreamAnchor {

    @Id
    private String stream;

    /** 마지막 항목의 hash. 비어 있으면 {@link HashChain#GENESIS}. */
    @Column(nullable = false, length = 64)
    private String headHash;

    /** 적재된 항목 수. 재생 결과가 이보다 적으면 꼬리가 잘린 것이다. */
    @Column(nullable = false)
    private long count;

    static EvidenceStreamAnchor start(String stream) {
        return new EvidenceStreamAnchor(stream, HashChain.GENESIS, 0L);
    }

    /** 새 항목 하나를 반영한다. 되돌리는 경로는 두지 않는다. */
    void advance(String newHead) {
        this.headHash = newHead;
        this.count++;
    }
}
