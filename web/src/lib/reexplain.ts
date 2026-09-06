/**
 * F-INT-004 재설명 문면의 화면 간 인계. 소유: 오준서.
 *
 * ── 왜 저장소를 거치는가 ────────────────────────────────────────────────────
 *
 * 재설명은 **판매자가 S-05 에서 시작하고 고객이 S-03 에서 읽는다.** 그런데 문면을 만드는
 * `POST /sessions/{id}/re-explain` 은 **판매자 화면이 부르고**(`session:interview` 는
 * SELLER 다), 계약에는 그 문면을 다시 읽는 GET 이 없다 — 응답이 유일한 출처다.
 * 화면이 그 값을 흘리면 서버에서 다시 못 받는다.
 *
 * 라우터 state 로만 넘기지 않는 이유는 **새로고침**이다. 창구 태블릿을 고객에게 넘기는
 * 사이 한 번만 새로고침돼도 문면이 사라지는데, 그때 세션은 이미 `RE_EXPLAIN` 이라
 * 화면이 일반 질문 흐름으로 떨어지면 **재검증 질문이 아닌 다른 항목을 묻는다.**
 * 그건 조용히 틀리는 종류다 — 에러 없이 엉뚱한 항목의 판정이 재검증으로 기록된다.
 *
 * ── `localStorage` 가 아니라 `sessionStorage` 인 이유 ───────────────────────
 *
 * 창을 닫으면 사라져야 한다. 남으면 **다음 고객 세션에서 앞 고객의 재설명 문면이 뜬다.**
 * 세션ID 로 키를 갈라 두지만, 키가 갈려 있는 것과 데이터가 남지 않는 것은 다른 보호다.
 *
 * ❗**고객 발화는 여기 들어가지 않는다.** 담는 것은 서버가 만든 재설명 문면과 재질문
 * 뿐이다. 고객이 입력한 텍스트를 브라우저 저장소에 남기는 경로를 만들지 않는다(P3 정신 —
 * 계약이 세션 입력 스키마에서 식별 필드를 아예 뺀 것과 같은 방향).
 *
 * ── 인계가 «없을 때» 를 화면이 말할 수 있어야 한다 (이슈 #492) ───────────────
 *
 * `sessionStorage` 는 **탭 단위**다. 링크를 새 탭·새 창으로 열면 인계가 통째로 없고, 그때
 * S-03 이 그냥 «평범한 면담» 으로 뜨면 `POST /questions/next` 가 *아직 안 물은 다음 항목*을
 * 준다 — **그 답이 재검증으로 기록된다. 에러 없이 틀리는 종류다.**
 *
 * 저장소를 바꾸는 것은 답이 아니다(위 두 문단의 근거가 그대로 유효하다). 대신 **경로에
 * «지금은 재검증» 이라는 표시를 싣는다.** 표시는 URL 이라 새 탭·새로고침·링크 공유를 전부
 * 따라간다 — 저장소가 못 따라가는 바로 그 축이다. 표시가 있는데 꺼낼 것이 없으면 S-03 이
 * 그 사실을 말하고 **멈춘다**(`S03_Interview` 설계 판단 ④).
 *
 * ❗**표시가 «재검증인가» 를 정하지는 않는다.** 그건 서버(세션 상태)가 정한다. 표시가 없다고
 * 재검증이 아닌 것도 아니고(그 축은 `session:read` 가 CUST 에 없어서 화면이 못 본다 — #166),
 * 표시가 있다고 재검증인 것도 아니다(고객이 URL 을 손으로 고칠 수 있다). 표시는 화면이
 * *"받았어야 하는데 못 받았다"* 를 알아채는 데만 쓴다 — 둘 다 «조용히 다음 항목을 묻는»
 * 것보다 낫다. 완전히 닫히는 것은 계약에 재설명 조회가 생길 때다(#492 ⓑ).
 */
import type { ReExplanation } from "../api/types";

const key = (sessionId: string) => `sphinx.reexplain.${sessionId}`;

/**
 * 재검증 진입 표시(이슈 #492). **붙이는 쪽과 읽는 쪽이 이 상수 하나를 같이 쓴다** — 문자열을
 * 양쪽에 적어 두면 한쪽만 고쳐졌을 때 표시가 조용히 안 걸리고, 그 실패가 곧 «다른 항목을
 * 묻는» 실패다.
 */
export const REVERIFY_PARAM = "reverify";

/** S-05 → S-03 인계 경로. 인계를 넣은 직후에만 쓴다. */
export function reverifyPath(sessionId: string): string {
  return `/interview/${sessionId}?${REVERIFY_PARAM}=1`;
}

/** 저장소에서 나온 값은 형태를 믿지 않는다 — 앞 배포가 남긴 낡은 모양일 수 있다. */
function isReExplanation(v: unknown): v is ReExplanation {
  const r = v as Partial<ReExplanation> | null;
  return !!r
    && typeof r.itemId === "string"
    && typeof r.content === "string"
    && typeof r.vulnerable === "boolean"
    && typeof r.reverifyQuestion === "string";
}

/** S-05 가 받은 재설명을 고객 화면이 꺼내갈 자리에 둔다. */
export function stashReExplanation(sessionId: string, value: ReExplanation): void {
  try {
    sessionStorage.setItem(key(sessionId), JSON.stringify(value));
  } catch {
    /* 사생활 모드·저장소 차단. 인계는 라우터 이동 직후의 첫 렌더에서만 필요하므로
       실패해도 화면은 뜬다 — 다만 새로고침 내성이 없어진다. 막지는 않는다. */
  }
}

/** 진행 중인 재설명. 없으면 null — 그건 정상 인터뷰 흐름이라는 뜻이다. */
export function readReExplanation(sessionId: string): ReExplanation | null {
  try {
    const raw = sessionStorage.getItem(key(sessionId));
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    return isReExplanation(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

/**
 * 재검증 답변을 보낸 뒤 지운다.
 *
 * 지우는 시점이 **제출 성공 직후**인 이유는, 실패했을 때 문면이 남아 있어야 고객이 같은
 * 화면에서 다시 제출할 수 있기 때문이다. 먼저 지우면 실패가 곧 흐름 이탈이 된다.
 */
export function clearReExplanation(sessionId: string): void {
  try {
    sessionStorage.removeItem(key(sessionId));
  } catch {
    /* 못 지워도 다음 재설명이 같은 키를 덮어쓴다. */
  }
}
