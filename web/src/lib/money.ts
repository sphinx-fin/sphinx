/**
 * 금액 표기. 소유: 오준서.
 *
 * 기획서 4절 — "확률이 아니라 본인이 넣을 금액으로 환산해서 보여준다. 사람은 확률을 잘
 * 이해하지 못하지만 금액은 이해한다." 그래서 시뮬레이터는 어디서도 %를 주인공으로 쓰지 않고
 * 한국어 만/억 단위로 읽어준다("5,000만 원").
 */

/** 12,345,678 → "1,234만 원" 형태. 억 단위는 "1억 2,345만 원". */
export function formatKrw(won: number): string {
  const sign = won < 0 ? "-" : "";
  const abs = Math.abs(Math.round(won));

  const eok = Math.floor(abs / 100_000_000);
  const man = Math.floor((abs % 100_000_000) / 10_000);
  const rest = abs % 10_000;

  const parts: string[] = [];
  if (eok > 0) parts.push(`${eok.toLocaleString("ko-KR")}억`);
  if (man > 0) parts.push(`${man.toLocaleString("ko-KR")}만`);
  if (rest > 0 || parts.length === 0) parts.push(rest.toLocaleString("ko-KR"));

  return `${sign}${parts.join(" ")} 원`;
}

/** 손익 표기 — 부호를 말로도 드러낸다(색만으로 구분하지 않기 위해). */
export function formatPnl(won: number): string {
  if (won === 0) return "원금 그대로";
  return won > 0 ? `${formatKrw(won)} 이익` : `${formatKrw(Math.abs(won))} 손실`;
}
