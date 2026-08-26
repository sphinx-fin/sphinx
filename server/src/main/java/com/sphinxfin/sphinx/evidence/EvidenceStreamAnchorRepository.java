package com.sphinxfin.sphinx.evidence;

import org.springframework.data.jpa.repository.JpaRepository;

/** 스트림별 닻 저장소. 소유: 정세현 */
public interface EvidenceStreamAnchorRepository extends JpaRepository<EvidenceStreamAnchor, String> {
}
