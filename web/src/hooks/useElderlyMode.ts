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
  return { elderly, toggle };
}
