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
        String surveySchemaVersion,
        Map<String, Object> surveyResult,

        /**
         * 이 세션을 진행하는 창구 직원. <b>요청 본문이 아니라 인증 주체에서 온다</b> —
         * 본문으로 받으면 자기가 아닌 사람을 소유자로 적을 수 있어 own_session 이 견제가
         * 아니게 된다. 계정 분리(10.5) 전에는 null 이다.
         */
        String sellerId,

        /** 그 직원의 소속 지점. 같은 이유로 인증 주체에서 온다. 10.5 전에는 null 이다. */
        String branchId) {

    /**
     * 귀속 없는 세션. <b>운영 경로에서 쓰지 않는다</b> — 테스트 픽스처와, 인증 주체를 읽을 수
     * 없는 경우(dev 프로파일의 익명 요청)를 위한 형태다.
     *
     * <p>이 생성자로 만든 세션은 {@code own_session}·{@code branch} 범위에서 <b>아무도 읽을 수
     * 없다</b>(정책이 "판단할 수 없다" 로 거부한다). 그게 맞는 결과다 — 주인을 모르는 세션을
     * 누군가의 것으로 쳐 주면 own_session 이 견제가 아니게 된다.
     *
     * <p>운영 경로는 {@code CreateSessionRequest.toCommand(sellerId, branchId)} 하나뿐이고,
     * 그쪽은 {@code CurrentActor} 에서만 값을 얻는다.
     */
    public CreateSessionCommand(String productId, Channel channel, String ageBand,
                                String experienceLevel, String amountBand, String contractRef,
                                String surveySchemaVersion, Map<String, Object> surveyResult) {
        this(productId, channel, ageBand, experienceLevel, amountBand, contractRef,
                surveySchemaVersion, surveyResult, null, null);
    }
}
