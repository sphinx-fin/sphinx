package com.sphinxfin.sphinx.evidence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * append-only 저장소의 JPA 창구. 소유: 정세현
 *
 * <p><b>조회는 스트림 단위 재생뿐이고 UPDATE·DELETE 파생 메서드를 두지 않는다.</b>
 * {@code JpaRepository}가 {@code delete*}를 상속으로 제공하는 것은 어쩔 수 없지만, 이 인터페이스에
 * 그 경로를 <b>늘리지 않는 것</b>이 규약이다(ImmutableStore 주석).
 */
public interface EvidenceEntryRepository extends JpaRepository<EvidenceEntry, Long> {

    /** 검증·교부용 재생. 순서는 seq 오름차순. */
    List<EvidenceEntry> findByStreamOrderBySeqAsc(String stream);
}
