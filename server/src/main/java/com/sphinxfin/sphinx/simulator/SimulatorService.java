package com.sphinxfin.sphinx.simulator;

import java.util.Map;

/**
 * F-SIM-001 손실 시뮬레이터. 소유: 정세현
 * 순수 함수만. LLM 미개입 (P2). 동일 입력 = 동일 출력 (SimulatorServiceTest).
 * 시나리오: 2008 금융위기 / 2020 코로나 — 이 둘은 제거 불가(시스템 불변 요건) / 중간 / 조기상환.
 * 시계열: data/timeseries/ CSV를 클래스패스 리소스로 복사해 버전 고정.
 */
public class SimulatorService {

    public record Scenario(String name, long payout, long pnl, Map<String, Object> pathMeta) {}

    public Scenario simulate(long amount, Map<String, Object> productTerms, String scenarioId) {
        // TODO(정세현): 낙인·조기상환·쿠폰 로직. 기획서 7-2 표 수치(5000만→3200만 등) 재검산 필수.
        throw new UnsupportedOperationException("not implemented");
    }
}
