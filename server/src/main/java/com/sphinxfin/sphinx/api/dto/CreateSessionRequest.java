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
        String surveySchemaVersion,
        Map<String, Object> surveyResult) {

    /**
     * 검증 통과한 요청을 서비스 커맨드로 변환한다.
     *
     * <p>❗<b>진행 주체(sellerId)·지점(branchId)은 이 요청에 없다.</b> 본문으로 받으면
     * 판매자가 자기가 아닌 사람을 소유자로 적을 수 있고, 그러면 {@code own_session} 범위가
     * 견제가 아니라 자기 신고가 된다. 인증 주체에서만 온다 — 그래서 인자로 받는다.
     */
    public CreateSessionCommand toCommand(String sellerId, String branchId) {
        return new CreateSessionCommand(productId, channel, ageBand,
                experienceLevel, amountBand, contractRef, surveySchemaVersion, surveyResult,
                sellerId, branchId);
    }
}
