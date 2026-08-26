package com.sphinxfin.sphinx.api.dto;

import jakarta.validation.constraints.Positive;

/**
 * F-SIM-001 손실 시뮬레이션 요청. 소유: 강희진(계약)
 * 금액은 화면 슬라이더가 정하는 값이라 서버가 기본값을 갖지 않는다 — 기본값이 있으면
 * 프론트가 금액을 안 실었을 때 그럴듯한 가짜 시나리오가 조용히 나온다(#48 결정).
 */
public record SimulateRequest(@Positive long amount) {
}
