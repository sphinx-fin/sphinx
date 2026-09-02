/**
 * 고령자 모드 (명세 10절 접근성). 소유: 오준서.
 *
 * 상태를 `<html data-elderly>` 에 얹어 global.css 의 토큰 배율만 바꾼다. 컴포넌트마다
 * "큰 글씨용 스타일"을 따로 두지 않는 이유는, 그렇게 하면 모드가 화면 단위로 새서
 * 한 화면만 안 커지는 사고가 나기 때문이다.
 *
 * 이 모드는 글자 크기만이 아니다 — S-03 의 무응답 타이머도 여기에 연동해 끈다
 * (명세 10절: 고령자 모드는 "입력 시간 제한 없음").
 */
import { useCallback, useEffect, useState } from "react";

const KEY = "sphinx.elderlyMode";

function readStored(): boolean {
  try {
    return localStorage.getItem(KEY) === "on";
  } catch {
    return false;   // 사생활 모드·저장소 차단 브라우저 — 기본값으로 조용히 동작
  }
}

export function useElderlyMode() {
  const [elderly, setElderly] = useState(readStored);

  useEffect(() => {
    document.documentElement.dataset["elderly"] = elderly ? "on" : "off";
    try {
      localStorage.setItem(KEY, elderly ? "on" : "off");
    } catch {
      /* 저장 실패는 모드 동작에 영향 없음 */
    }
  }, [elderly]);

  const toggle = useCallback(() => setElderly((v) => !v), []);

  /**
   * 켜기만 한다 — 끄지 않는다.
   *
   * F-INT-004 재설명 응답의 `vulnerable` 이 이걸 부른다. 계약이 그 값을 *"렌더링 힌트(큰
   * 글씨·비유)이지 화면에 표시할 라벨이 아니다"* 로 못박았으므로, 화면은 **모드를 켜는
   * 것으로만** 반응하고 이유를 말하지 않는다.
   *
   * 반대 방향(`vulnerable === false` 면 끄기)을 만들지 않는 이유는 그게 **고객이 직접 켠
   * 큰 글씨를 서버 판단으로 되돌리는** 동작이기 때문이다. 접근성 설정은 사람이 마지막
   * 결정권을 갖는다 — 명세 10절이 고령자 모드를 사용자 선택으로 둔 것과 같은 방향이다.
   */
  const enable = useCallback(() => setElderly(true), []);

  return { elderly, toggle, enable };
}
