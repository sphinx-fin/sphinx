package com.sphinxfin.sphinx.core.session;

import org.springframework.data.jpa.repository.JpaRepository;

/** F-INT-001 세션 영속 저장소. 소유: 강희진 */
public interface SessionRepository extends JpaRepository<Session, String> {
}
