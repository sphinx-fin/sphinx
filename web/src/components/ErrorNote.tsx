/**
 * 에러 한 건을 그리는 자리. 소유: 오준서.
 *
 * 네 화면이 같은 모양을 각자 갖고 있었고(이슈 #316), 그중 셋이 시연 경로다. 문면 표는
 * `lib/errorText` 가 들고, 이 컴포넌트는 **접는 방식**만 한 벌로 둔다 — 서버 원문을 어디에
 * 어떻게 두느냐가 화면마다 다르면 어떤 화면에서는 원문이 그대로 노출된다.
 *
 * `<p>` 가 아니라 `<div>` 인 이유: `<details>` 는 문단 안에 들어갈 수 없다(phrasing content
 * 가 아니다). 클래스는 화면별로 받아서 기존 스타일을 그대로 쓴다.
 */
import type { ShownError } from "../lib/errorText";
import "./ErrorNote.css";

interface Props {
  error: ShownError;
  /** 화면별 에러 박스 클래스(`s05__error` 등). */
  className: string;
  /** 굵게 앞세울 한 줄. 화면이 맥락을 아는 자리라 밖에서 받는다. */
  title?: string;
}

export default function ErrorNote({ error, className, title }: Props) {
  return (
    <div className={className} role="alert">
      {title && <b>{title}</b>}
      {error.text}
      {/* 원문은 버리지 않는다 — 리허설·디버깅에 그 문장이 필요하다. 다만 접어 둔다:
          서버 메시지는 개발자에게 쓴 반말 로그체이고 내부 상태를 담을 수 있다. */}
      {error.detail && (
        <details className="errnote__detail">
          <summary>기술 정보</summary>
          <code>{error.detail}</code>
        </details>
      )}
    </div>
  );
}
