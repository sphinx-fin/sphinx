/** 라우팅 골격. 소유: 오준서. 우선순위: S-03, S-04 (데모의 심장) → S-05 → 나머지 */
import { BrowserRouter, Routes, Route } from "react-router-dom";
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
      <Routes>
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
