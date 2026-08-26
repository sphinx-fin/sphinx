/**
 * 시뮬레이터 시나리오 표시 규칙. 소유: 오준서.
 *
 * 기획서 4절 — "최악과 중간과 최선을 나란히 놓는다. 최선만 강조하는 관행의 정반대다."
 * 그래서 화면은 **최악 → 중간 → 최선** 으로 세운다.
 *
 * 정렬 키는 계약이 주는 `severity`(worst·mid·best)다. 예전에는 `name` 문자열에서 "최악"·
 * "중간"·"최선"을 찾아 순위를 매겼는데, 그건 **서버가 문면을 다듬는 순간 조용히 깨지는**
 * 방식이었다 — 못 알아본 이름은 뒤로 밀리므로 에러 없이 순서만 틀어지고, 3열 동일 비중
 * 요건(명세 8절 S-04)이 무너진 것을 아무도 모른다.
 *
 * 계약이 `severity` 를 "표시 라벨이 아니라 배치·정렬 키"라고 못박은 이유도 같다. 기획서 7-2
 * 표는 평가어를 버리고 금액 순으로 갔는데, 스텝다운은 조기상환 시점과 무관하게 연 수익률이
 * 같고 총액만 달라서 **가장 자주 일어나는 전개가 금액으로는 가장 작다.** 그래서 사람에게
 * 보일 문면은 `name` 이고, 자리를 정하는 것은 `severity` 다.
 */
import type { SimScenario } from "../api/types";

/** 화면 배치 순서. 계약 `severity` enum 과 1:1. */
const SEVERITY_ORDER: readonly SimScenario["severity"][] = ["worst", "mid", "best"];

/** 표시 순서로 정렬한 사본. 동순위는 서버 순서를 유지한다(Array#sort 는 안정 정렬). */
export function orderScenarios(scenarios: readonly SimScenario[]): SimScenario[] {
  return [...scenarios].sort(
    (a, b) => SEVERITY_ORDER.indexOf(a.severity) - SEVERITY_ORDER.indexOf(b.severity),
  );
}

/**
 * 시나리오 카드의 DOM id — 열람 완료 관측(F-SIM-001 연동)의 키로도 쓴다.
 *
 * `severity` 를 쓴다. `name` 으로 만들면 금액 슬라이더를 움직여 문면이 바뀔 때 id 가 바뀌고,
 * 그러면 이미 본 카드가 "안 본 카드"로 되돌아가 CTA 가 다시 잠긴다.
 */
export function cardId(s: SimScenario): string {
  return `sc-${s.severity}`;
}
