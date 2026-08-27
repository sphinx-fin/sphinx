package com.sphinxfin.sphinx.core.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 가변 JPA 엔티티의 공통 감사 필드. 소유: 강희진
 *
 * 생성/수정 시각을 Spring Data Auditing으로 자동 채운다(persist·update 시점). 엔티티마다
 * 수동으로 Instant.now()를 넣지 않는다. 감사 활성화는 {@code JpaAuditingConfig}.
 *
 * 주의: append-only인 evidence(정세현) 엔티티는 updatedAt이 의미 없어 이 베이스를 쓰지 않는다.
 * 삭제(soft/hard) 플래그도 두지 않는다 — 삭제 의미는 엔티티마다 다르고 팀 결정/ADR 사안이다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Accessors(fluent = true)
public abstract class BaseEntity {

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
