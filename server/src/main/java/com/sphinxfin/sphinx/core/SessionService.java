package com.sphinxfin.sphinx.core;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * F-INT-001 세션 서비스. 소유: 강희진
 *
 * 세션 생명주기 로직을 담는다(생성 시 ID 발급·엔티티 조립, 이후 F-INT-004 재검증 루프·
 * 게이트 판정 오케스트레이션이 여기로 들어온다). 영속은 SessionRepository(JPA)에 위임하되,
 * 컨트롤러가 도메인 엔티티를 직접 조립하지 않도록 이 층을 둔다.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository repository;

    public Session create(CreateSessionCommand cmd) {
        Session session = Session.builder()
                .id(UUID.randomUUID().toString())
                .productId(cmd.productId())
                .channel(cmd.channel())
                .ageBand(cmd.ageBand())
                .experienceLevel(cmd.experienceLevel())
                .amountBand(cmd.amountBand())
                .contractRef(cmd.contractRef())
                .surveyResult(cmd.surveyResult() == null ? Map.of() : Map.copyOf(cmd.surveyResult()))
                .build();
        return repository.save(session);
    }

    /** 세션 조회. 없으면 예외(→ GlobalExceptionHandler에서 404). */
    public Session get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("세션을 찾을 수 없다: " + id));
    }
}
