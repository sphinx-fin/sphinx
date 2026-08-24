package com.sphinxfin.sphinx.core;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * F-INT-001 세션 서비스. 소유: 강희진
 *
 * 세션 생명주기 오케스트레이션(생성·조회, 이후 F-INT-004 재검증 루프·게이트 판정)을 담당한다.
 * 생성 불변식(ID 발급 등)은 도메인 팩토리 Session.create가 소유하고, 서비스는 영속만 조율한다.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository repository;

    public Session create(CreateSessionCommand cmd) {
        return repository.save(Session.create(cmd));
    }

    /** 세션 조회. 없으면 예외(→ GlobalExceptionHandler에서 404). */
    public Session get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("세션을 찾을 수 없다: " + id));
    }
}
