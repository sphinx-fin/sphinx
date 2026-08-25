/**
 * 입력 중 개인정보 패턴 감지 (F-INT-003 / F-CMN-001 보조). 소유: 오준서.
 *
 * **여기서 마스킹하지 않는다.** 두 가지 이유다:
 *   1. P3 상 마스킹의 유일한 권위 경로는 서버 `core/PiiGateway.mask()` 다. 화면이 미리
 *      고쳐 쓰면 마스킹 규칙이 두 벌이 되고, 어느 쪽이 실제로 적용됐는지 감사에서 못 가린다.
 *   2. 고객 발화는 판정의 근거(P4, `evidence.utteranceQuote`)로 인용된다. 화면이 조용히
 *      본문을 바꾸면 근거가 원발화와 달라진다.
 *
 * 그래서 화면은 **경고만** 한다 — 애초에 안 적게 만드는 게 목적이고, 실제 제거는 서버가 한다.
 * 제출은 막지 않는다(막으면 고객이 답을 못 내고 세션이 멎는다).
 */

export type PiiKind = "주민등록번호" | "전화번호" | "계좌번호" | "이메일";

const PATTERNS: ReadonlyArray<{ kind: PiiKind; re: RegExp }> = [
  { kind: "주민등록번호", re: /\d{6}\s*[-–]\s*[1-4]\d{6}/ },
  { kind: "전화번호", re: /01[016789][-\s]?\d{3,4}[-\s]?\d{4}/ },
  { kind: "계좌번호", re: /\d{2,6}[-–]\d{2,6}[-–]\d{2,6}/ },
  { kind: "이메일", re: /[\w.+-]+@[\w-]+\.[\w.]+/ },
];

/** 입력 텍스트에서 개인정보로 보이는 패턴 종류를 돌려준다(중복 제거). */
export function detectPii(text: string): PiiKind[] {
  return PATTERNS.filter(({ re }) => re.test(text)).map(({ kind }) => kind);
}
