package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.domain.Channel;

import java.util.Map;

/**
 * F-INT-001 세션 생성 커맨드. 소유: 강희진
 * 컨트롤러의 요청 DTO(api.dto.CreateSessionRequest)를 서비스가 받는 형태로 변환한 것.
 * 서비스가 web 계층 DTO에 직접 의존하지 않도록 core에 둔다.
 */
public record CreateSessionCommand(
        String productId,
        Channel channel,
        String ageBand,
        String experienceLevel,
        String amountBand,
        String contractRef,
        Map<String, Object> surveyResult) {
}
