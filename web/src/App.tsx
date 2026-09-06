/** 라우팅 골격. 소유: 오준서. 우선순위: S-03, S-04 (데모의 심장) → S-05 → 나머지 */
import { BrowserRouter, Routes, Route } from "react-router-dom";
import BrandBar from "./components/BrandBar";
import Splash from "./components/Splash";
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
