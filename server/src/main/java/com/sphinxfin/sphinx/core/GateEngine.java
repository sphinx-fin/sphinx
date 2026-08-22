package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Judgment;
import java.util.List;

/**
 * F-GTE-001 룰 엔진. 소유: 강희진
 * 입력은 구조화된 Judgment만 (P1 — LLM 원문 참조 금지). gate_rules.yaml 로드 → 평가 → ruleTrace 기록.
 */
public class GateEngine {
    public GateResult judge(List<Judgment> judgments, boolean suitabilityMismatch, int reverifyFailed) {
        // TODO(강희진)
        throw new UnsupportedOperationException("not implemented");
    }
}
