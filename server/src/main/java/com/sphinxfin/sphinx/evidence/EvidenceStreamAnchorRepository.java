package com.sphinxfin.sphinx.evidence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 스트림별 닻 저장소. 소유: 정세현 */
public interface EvidenceStreamAnchorRepository extends JpaRepository<EvidenceStreamAnchor, String> {

    /**
     * 닻을 <b>잡고</b> 읽는다. 같은 스트림의 append 를 직렬화하는 지점이다.
     *
     * <p>이게 없으면 두 트랜잭션이 같은 {@code count} 를 읽어 <b>같은 seq 를 계산하고</b>,
     * {@code uk_evidence_stream_seq} 에서 하나가 진다. 사슬 무결성은 유니크 제약이 지켜 주므로
     * {@code verify()} 는 통과하는데 <b>진 쪽 기록이 사라진다</b> — 그리고 그 없음이 검증에
     * 안 잡힌다. 20건 동시 적재에서 8건만 남는 것을 실측했다(PR #96 리뷰).
     *
     * <p>"감사 로그가 검증을 통과했다"가 "기록이 다 있다"를 뜻해야 하므로, 사슬이 순서를
     * 요구하는 이상 직렬화 비용은 피할 수 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from EvidenceStreamAnchor a where a.stream = :stream")
    Optional<EvidenceStreamAnchor> findForUpdate(@Param("stream") String stream);
}
