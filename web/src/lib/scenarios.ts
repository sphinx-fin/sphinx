/**
 * 시뮬레이터 시나리오 표시 규칙. 소유: 오준서.
 *
 * 기획서 4절 — "최악과 중간과 최선을 나란히 놓는다. 최선만 강조하는 관행의 정반대다."
 * 서버 응답은 최선부터 오지만, 최선을 맨 앞에 놓는 것 자체가 그 관행이므로 화면은
 * **최악 → 중간 → 최선** 으로 세운다. 손익 크기로 정렬하지 않는 이유는, 데모 파라미터에서
 * 중간(+900만)이 최선(+150만)보다 커서 정렬하면 라벨과 순서가 어긋나기 때문이다.
 */
import type { SimScenario } from "../api/types";

const SEVERITY_ORDER = ["최악", "중간", "최선"] as const;

/**
 * 시나리오 이름에서 심각도 순위를 읽는다. 못 알아본 이름은 뒤로 보내고 서버 순서를 유지한다.
 * TODO(정세현): 응답에 severity(worst|mid|best) 필드가 생기면 이 문자열 매칭을 지운다.
 */
export function severityRank(name: string): number {
  const i = SEVERITY_ORDER.findIndex((k) => name.includes(k));
  return i === -1 ? SEVERITY_ORDER.length : i;
}

/** 표시 순서로 정렬한 사본. 동순위는 서버 순서를 유지한다(Array#sort 는 안정 정렬). */
export function orderScenarios(scenarios: readonly SimScenario[]): SimScenario[] {
  return [...scenarios].sort((a, b) => severityRank(a.name) - severityRank(b.name));
}

/** 시나리오 카드의 DOM id — 열람 완료 관측(F-SIM-001 연동)의 키로도 쓴다. */
export function cardId(s: SimScenario): string {
  return `sc-${s.name.replace(/\s+/g, "-")}`;
}
