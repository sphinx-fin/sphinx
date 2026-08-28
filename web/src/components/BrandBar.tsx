/**
 * 전 화면 상단 브랜드 바. 소유: 오준서.
 *
 * ── 왜 링크가 아닌가 ────────────────────────────────────────────────────────
 *
 * 로고를 누르면 홈으로 가는 것이 웹의 관습인데 **여기서는 안 건다.** 이 화면들은
 * 창구에서 고객이 직접 잡고, 그 중 S-03 은 답변 도중이다. 습관적으로 로고를 눌렀다가
 * 세션 밖으로 나가면 되말하기를 처음부터 다시 해야 한다 — 관습을 지키는 값이 그
 * 사고보다 크지 않다. 그래서 이건 **표식이지 버튼이 아니다.**
 *
 * ── 왜 App 에 한 번만 두는가 ────────────────────────────────────────────────
 *
 * 화면마다 붙이면 새 화면을 만들 때 빠뜨리고, 그 화면만 브랜드가 없는 상태로 나간다.
 * 라우터 바깥에 한 번 두면 8 개 화면 전부가 자동으로 같은 자리에 같은 크기로 갖는다.
 */
import "./BrandBar.css";
import logo from "../assets/logo-mark.svg?raw";

export default function BrandBar() {
  return (
    <div className="brand" role="presentation">
      {/* 심볼은 currentColor 라 색을 CSS 가 정한다. 인라인으로 넣는 이유는 <img> 로는
          그 색 상속이 안 되기 때문이다(고령자 모드·다른 면 위에서 같이 움직여야 한다). */}
      <span className="brand__mark" aria-hidden="true" dangerouslySetInnerHTML={{ __html: logo }} />
      <span className="brand__word">
        Sphin<span className="brand__word-x">X</span>
      </span>
    </div>
  );
}
