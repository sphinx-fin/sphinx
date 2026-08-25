/**
 * F-INT-003 입력 메타데이터 수집. 소유: 오준서.
 *
 * 명세 F-INT-003 출력: {입력 시작까지 지연시간, 총 입력 시간, 응답 길이, 붙여넣기 여부,
 * 수정(백스페이스) 빈도}. 이 값은 F-DET-002 **코칭 정황 스코어**의 입력이 된다.
 *
 * 명세 F-DET-002 단서 — 이 스코어는 **판정에 직접 반영되지 않고** 세션 메타데이터로만
 * 기록되어 지점 단위 통계 이상치 탐지에 쓰인다. 따라서 화면은 이 값으로 고객을 막거나
 * 경고하지 않는다. 수집만 하고 그대로 서버에 넘긴다.
 *
 * 항목이 바뀌면 `reset(questionShownAt)` 으로 다시 시작한다 — 항목별로 따로 재는 값이라
 * 세션 전체에 걸쳐 누적하면 의미가 없다.
 */
import { useCallback, useRef } from "react";
import type { InputMeta } from "../api/types";

export function useInputMeta() {
  const shownAt = useRef<number>(Date.now());
  const firstKeystrokeAt = useRef<number | null>(null);
  const pasted = useRef(false);
  const backspaces = useRef(0);

  /** 새 질문이 표시될 때 호출 — 항목 단위로 초기화한다. */
  const reset = useCallback(() => {
    shownAt.current = Date.now();
    firstKeystrokeAt.current = null;
    pasted.current = false;
    backspaces.current = 0;
  }, []);

  /** textarea 의 onKeyDown 에 연결. */
  const onKeyDown = useCallback((e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (firstKeystrokeAt.current === null) firstKeystrokeAt.current = Date.now();
    if (e.key === "Backspace" || e.key === "Delete") backspaces.current += 1;
  }, []);

  /** textarea 의 onPaste 에 연결 — 붙여넣기는 대필·메모 제시 정황이다. */
  const onPaste = useCallback(() => {
    if (firstKeystrokeAt.current === null) firstKeystrokeAt.current = Date.now();
    pasted.current = true;
  }, []);

  /** 제출 시점에 확정. 첫 입력이 없었으면(붙여넣기도 없이 제출) 지연=경과시간, 입력시간=0. */
  const snapshot = useCallback((text: string, elderlyMode: boolean): InputMeta => {
    const now = Date.now();
    const first = firstKeystrokeAt.current;
    return {
      firstKeystrokeDelayMs: (first ?? now) - shownAt.current,
      totalInputMs: first === null ? 0 : now - first,
      pasteDetected: pasted.current,
      backspaceCount: backspaces.current,
      charCount: text.replace(/\s/g, "").length,
      elderlyMode,
    };
  }, []);

  return { reset, onKeyDown, onPaste, snapshot };
}
