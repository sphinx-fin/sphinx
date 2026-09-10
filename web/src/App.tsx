/** 라우팅 골격. 소유: 오준서. 우선순위: S-03, S-04 (데모의 심장) → S-05 → 나머지 */
import { BrowserRouter, Routes, Route } from "react-router-dom";
import BrandBar from "./components/BrandBar";
import Splash from "./components/Splash";
import AuditPage from "./pages/Audit";
import ConsolePage from "./pages/Console";
import GuidePage from "./pages/Guide";
import UploadPage from "./pages/S01_Upload";
import SessionStartPage from "./pages/S02_SessionStart";
import InterviewPage from "./pages/S03_Interview";
import SimulatorPage from "./pages/S04_Simulator";
import JudgmentPage from "./pages/S05_Judgment";
import OverridePage from "./pages/S06_Override";
import ReportPage from "./pages/S07_Report";
import DashboardPage from "./pages/S08_Dashboard";

export default function App() {
  return (
    <BrowserRouter>
      {/* 덮개다. 아래 화면은 이미 마운트돼 요청을 보내고 있고, 이건 그 앞을 1초 덮는다 —
          스플래시가 끝난 뒤에 그리면 첫 요청이 그만큼 늦는다(Splash 주석). */}
      <Splash />
      {/* 라우터 안·Routes 밖 — 화면마다 붙이면 새 화면에서 빠뜨린다(BrandBar 주석). */}
      <BrandBar />
      <Routes>
        {/* 사용 가이드. 제품 흐름 밖이고 **`SCREENS` 에 넣지 않는다** — 그 배열은 명세 8절과
            대조하는 목록이라 명세에 없는 항목이 끼면 대조가 어긋난다. 위 규칙의 유일한
            예외이고, 이유는 `pages/Guide.tsx` 머리말에 적었다. */}
        <Route path="/guide" element={<GuidePage />} />
        {/* 운영 콘솔. 가이드와 **같은 성격**이라 같은 규칙이다 — 제품 흐름 밖이고
            `SCREENS` 에 안 넣는다. 이슈 #522 가 「S-09」로 부르지만 **번호는 안 준다**:
            명세 8절 표는 제품 흐름의 화면 목록이라 거기 얹으면 그 구별이 사라진다
            (#522 질문 3 — 정세현·윤지석 합의. 명세 쪽 「제품 흐름 밖 화면」 절은 정세현).
            `ops:status:read` 가 ADMIN 뿐이라 다른 역할에는 403 이고, 화면이 그것을
            「차단됨」으로 그린다 — 그래서 제품 흐름에서 링크하지 않는다. ❗**지금은 어디서도
            안 걸려 있어 닿는 길이 주소 직접 입력뿐이다**(`/upload` 와 같은 상태 · #406).
            진입점은 이 PR 밖에서 정한다. */}
        <Route path="/console" element={<ConsolePage />} />
        {/* 감사 기록. `/console` 과 같은 자리다 — 제품 흐름 밖이고 `SCREENS` 에 안 넣는다.
            ❗**S-08 안의 뷰로 두지 않는다.** 그쪽은 `aggregate:*`(COMPL org · MGR branch)이고
            여기는 `audit:read`·`audit:verify`(COMPL 뿐)라, 얹으면 **MGR 에게 상시 403 인 탭**이
            생긴다. 성격도 다르다 — S-08 은 «고객이 무엇을 모르는가», 여기는 «그 기록을 믿을 수
            있는가» 이고 무결성 검증은 집계가 아니다. 다른 역할에는 403 이고 화면이 그것을
            「차단됨」으로 그린다. ❗**지금은 어디서도 안 걸려 있어 닿는 길이 주소 직접
            입력뿐이다** — 진입점은 `/upload`·`/console` 과 같이 밖에서 정한다(#406). */}
        <Route path="/audit" element={<AuditPage />} />
        {/* ❗S-01 은 이번 라운드 개발하지 않는다(이슈 #406). 라우트는 남겨 둔다 —
            화면은 목 엔드포인트 상대로 동작하고, 지우면 다음 라운드에 되살리는 값이 생긴다.
            제품 흐름에서는 여전히 어디서도 링크되지 않는다 — 닿는 길은 URL 직접 입력뿐이다. */}
        <Route path="/upload" element={<UploadPage />} />
        <Route path="/" element={<SessionStartPage />} />
        <Route path="/interview/:sid" element={<InterviewPage />} />
        <Route path="/simulator/:sid" element={<SimulatorPage />} />
        <Route path="/judgment/:sid" element={<JudgmentPage />} />
        <Route path="/override/:sid" element={<OverridePage />} />
        <Route path="/report/:sid" element={<ReportPage />} />
        <Route path="/dashboard" element={<DashboardPage />} />
      </Routes>
    </BrowserRouter>
  );
}
