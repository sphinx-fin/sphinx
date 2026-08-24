package com.sphinxfin.sphinx.api.dto;

import com.sphinxfin.sphinx.core.CreateSessionCommand;
import com.sphinxfin.sphinx.domain.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * F-INT-001 세션 생성 요청. 소유: 강희진
 * 비식별 속성만 받는다 — 성명·주민번호 필드는 존재하지 않는다 (P3).
 */
public record CreateSessionRequest(
        @NotBlank String productId,
        @NotNull Channel channel,
        @NotBlank String ageBand,
        String experienceLevel,
        String amountBand,
        String contractRef,
        Map<String, Object> surveyResult) {

    /** 검증 통과한 요청을 서비스 커맨드로 변환한다. */
    public CreateSessionCommand toCommand() {
        return new CreateSessionCommand(productId, channel, ageBand,
                experienceLevel, amountBand, contractRef, surveyResult);
    }
}
