package com.sphinxfin.sphinx.domain;

import java.util.List;

/** F-GTE-001 출력. ruleTrace = 발화한 룰 ID 목록 */
public record GateResult(Signal signal, List<String> ruleTrace) {}
