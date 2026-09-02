package com.sphinxfin.sphinx.domain;

import java.util.List;

/**
 * F-GTE-001 출력.
 *
 * @param signal       판정 신호
 * @param ruleTrace    발화한 룰 ID 목록 — 그 신호를 낸 사유 전부
 * @param unmeasured   판정 시점의 미측정 항목 수 (R-00 이 보는 값)
 * @param rulesVersion 판정에 쓴 {@code gate_rules.yaml} 의 {@code version}
 *
 * <h2>왜 입력을 결과에 싣나</h2>
 *
 * <p>신호와 트레이스만 있으면 <b>"R-00 이 물었다"</b> 까지만 남고 <b>몇 개를 못 쟀는지</b>도
 * <b>어느 룰셋으로 잰 건지</b>도 안 남는다. {@code evidence/} 는 append-only 라 그렇게 쌓인
 * 기록은 나중에 못 채운다 — 감사 시점에 "왜 이 신호였나" 를 되짚을 수 없다.
 *
 * <p>두 번째 이유는 테스트다. 값이 결과에 실리지 않으면 <b>그 값을 만드는 계산이 틀려도
 * 신호가 안 바뀌는 구간</b>이 생긴다. 실제로 {@code Session.unmeasuredItemCount()} 를
 * {@code return 0} 으로 바꾸는 변이가 전건 초록으로 통과했다(이슈 {@code #294} ①).
 * 숫자가 결과에 있으면 그 변이가 숫자로 잡힌다.
 *
 * <p>{@code rulesVersion} 은 {@code GateEngine.RulesFile.version} 의 <b>첫 소비처</b>다.
 * 그전까지 파싱만 되고 버려져서 {@code version:} 을 고쳐도 아무 일도 일어나지 않았다 —
 * "바꿔도 안 깨지는 설정값" 이라 언젠가 낡는다.
 */
public record GateResult(Signal signal, List<String> ruleTrace, int unmeasured, int rulesVersion) {}
