/**
 * 첫 진입 스플래시. 소유: 오준서.
 *
 * ── 앱을 가리기만 한다. 늦추지 않는다 ───────────────────────────────────────
 *
 * `App` 안에서 라우터 **위에 얹는 덮개**다. 화면은 아래에서 이미 마운트돼 데이터를
 * 받고 있고, 이건 그 앞을 잠깐 덮을 뿐이다. 스플래시가 끝난 **뒤에** 앱을 그리면
 * 첫 요청이 1초 늦게 나가고, 덮개가 걷힌 자리에 로딩이 또 나온다.
 *
 * ── 사라지는 근거는 CSS 가 아니라 타이머다 ──────────────────────────────────
 *
 * `animationend` 로 벗기면 **애니메이션이 안 걸리는 모든 경우에 덮개가 영원히 남는다** —
 * CSS 가 늦게 오거나, 탭이 백그라운드라 프레임이 안 돌거나, 그 한 줄이 바뀌었을 때.
 * 그 실패는 "화면이 안 뜬다"로 나타나고 원인은 안 보인다. 그래서 **벗기는 것은 타이머가
 * 단독으로 정하고**, CSS 는 보이는 것만 맡는다. 둘이 갈려도 덮개는 반드시 걷힌다.
 *
 * ── 모션 설정을 존중한다 ────────────────────────────────────────────────────
 *
 * `global.css` 의 reduced-motion 블록은 `animation-duration` 만 죽이고 **`animation-delay`
 * 는 안 건드린다.** 그래서 CSS 에만 맡기면 모션을 끈 사람이 빈 화면을 그대로 기다린다.
 * 여기서 질의해서 **아예 안 그린다** — 스플래시는 정보가 없으므로 건너뛰어도 잃는 게 없다.
 *
 * ── 새 창으로 직접 열리는 세션 흐름 화면은 덮지 않는다 (피드백 2번) ─────────────
 *
 * S-02 가 고객 인터뷰·판정 화면을 `window.open` 으로 연다(`S02_SessionStart.tsx` 의
 * `openAside`). 새 탭은 이 컴포넌트가 처음부터 다시 마운트되므로, 화면을 하나 열 때마다
 * `HOLD_MS` 만큼 더 걸린다 — 데모에서 창을 여러 개 넘기면 그 시간이 누적으로 체감된다.
 * `window.opener` 로 "새 창으로 열렸는가" 를 가르는 대신 **경로**로 가른다: 팝업이
 * 차단되면 `openAside` 가 같은 탭으로 `navigate` 하는데, 그때도 목적지는 같은 세션 흐름
 * 화면이라 스플래시를 안 그리는 것이 맞고, `opener` 판정으로는 이 경우를 못 잡는다.
 * 반대로 최초 진입(`/`, `/admin` 등)은 세션 하나짜리 데모에서 실제로 처음 여는 화면이라
 * 그대로 덮는다.
 */
import { useEffect, useState } from "react";
import { useLocation } from "react-router-dom";
import "./Splash.css";
import logo from "../assets/logo-mark.svg?raw";

/** 덮개가 걷히는 시각. `Splash.css` 의 마지막 애니메이션(끝 700ms + 260ms)보다 뒤다. */
const HOLD_MS = 960;

/** S-02 가 `window.open` 으로 여는 세션 흐름 화면들. 새 창마다 다시 덮지 않는다. */
const NO_SPLASH_PREFIXES = ["/interview/", "/judgment/"];

function isSessionFlowEntry(pathname: string): boolean {
  return NO_SPLASH_PREFIXES.some((prefix) => pathname.startsWith(prefix));
}

function prefersReducedMotion(): boolean {
  // 구형 사파리·비브라우저 환경(테스트)에서 matchMedia 가 없을 수 있다. 없으면 모션을 켠다.
  return typeof window !== "undefined" && typeof window.matchMedia === "function"
    ? window.matchMedia("(prefers-reduced-motion: reduce)").matches
    : false;
}

export default function Splash() {
  const location = useLocation();
  const skip = isSessionFlowEntry(location.pathname);
  // 초기값에서 이미 갈린다 — 모션을 끈 사람과 세션 흐름 화면으로 직접 열린 창에는
  // 한 프레임도 안 보인다. `skip` 은 마운트 시점의 경로만 보면 충분하다 — 이 컴포넌트가
  // 같은 탭에서 경로를 바꾸며 다시 덮을 일은 없다(다른 라우트로 가도 재마운트되지 않는다).
  const [covering, setCovering] = useState(() => !skip && !prefersReducedMotion());

  useEffect(() => {
    if (!covering) return;
    const t = window.setTimeout(() => setCovering(false), HOLD_MS);
    return () => window.clearTimeout(t);
  }, [covering]);

  if (!covering) return null;

  return (
    // 뜻이 없는 장식이라 보조기술에서 통째로 뺀다. 아래 화면이 이미 낭독 가능한 상태다.
    <div className="splash" aria-hidden="true">
      <div className="splash__lockup">
        {/* BrandBar 와 같은 이유로 인라인이다 — currentColor 상속이 <img> 로는 안 된다.
            갈라진 두 조각을 따로 움직이려면 path 에 닿아야 하는 것도 여기서만 된다. */}
        <span className="splash__mark" dangerouslySetInnerHTML={{ __html: logo }} />
        <span className="splash__word">
          Sphin<span className="splash__word-x">X</span>
        </span>
      </div>
    </div>
  );
}
