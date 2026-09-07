import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/**
 * 소유: 오준서 (인프라 R).
 *
 * ── 이 프록시는 **개발 전용**이다 ────────────────────────────────────────────
 *
 * 배포는 EC2 + docker 3분할(`frontend` · `server` · `ai`)이고, `frontend` 컨테이너는
 * `vite build` 산출물(`dist/`)을 정적으로 서빙한다. **`vite dev` 는 배포에 뜨지 않으므로
 * 이 파일의 `server.proxy` 도 배포에 없다.** `/api` 를 `server` 로 넘기는 일은 배포에서
 * 프론트 컨테이너의 웹서버(nginx) 설정이 한다.
 *
 * 그래서 **prod 자격증명을 여기 넣지 않는다** (decision-log 10.6 · 이슈 #41 강희진 코멘트).
 * 근거는 이 파일이 배포에 없다는 것 하나가 아니다 — 프록시가 `Authorization` 헤더를 고정으로
 * 박으면 **화면에 닿는 사람은 전부 인증된 상태로 API 에 닿는다.** #50 이 세운 인증이 프록시
 * 한 줄로 무력화되고, 감사 로그의 "누가"도 그 고정 계정 하나로 굳어진다(10.5 가 이미 지적한
 * 문제를 더 나쁘게 만든다). 자세한 대안은 이슈 #41 코멘트에 적었다.
 *
 * `SPHINX_API_TARGET` 은 로컬에서 `server` 를 어디에 띄웠는지만 가른다:
 *   - 호스트에서 `./gradlew bootRun`  → 기본값 `http://localhost:8000`
 *   - compose 로 같이 띄움            → `SPHINX_API_TARGET=http://server:8000`
 */
// tsconfig 의 `types` 는 ["vite/client"] 뿐이라 node 전역이 없다. 이 한 줄을 위해
// @types/node 를 넣으면 CI 타입검사가 의존성을 하나 더 지므로 여기서만 좁게 선언한다.
declare const process: { env: Record<string, string | undefined> };

const API_TARGET = process.env.SPHINX_API_TARGET ?? "http://localhost:8000";

/**
 * 데모 답지 캡션(S-03 설계 판단 ⑤ · 이슈 #539)을 빌드에 넣을지.
 *
 * ❗**`import.meta.env` 를 화면에서 직접 읽지 않고 여기서 리터럴로 굳힌다.** 화면 조건이
 * 리터럴이어야 `false && …` 가 접히고, 그래야 캡션 JSX 와 `lib/demoAnswers` 모듈이
 * **번들에서 통째로 빠진다.** `import.meta.env.VITE_…` 는 변수가 없는 빌드에서 객체 속성
 * 접근으로 남아 접히지 않을 수 있고, 그러면 답지 문자열이 꺼진 빌드에도 실린다 —
 * 화면에 안 보일 뿐 페이지 소스에는 있는 상태이고, 그건 이 플래그를 둔 이유를 없앤다.
 *
 * 값은 배포가 준다: `deploy.yml` → compose `build.args` → `web/Dockerfile` 의 ARG.
 * **alpha 에만** 주고 prod 는 빈 값이다(`SPHINX_DEMO_OPEN` 과 같은 배선).
 */
const DEMO_CAPTIONS = process.env.VITE_SPHINX_DEMO_CAPTIONS === "1";

export default defineConfig({
  plugins: [react()],
  define: {
    __DEMO_CAPTIONS__: JSON.stringify(DEMO_CAPTIONS),
  },
  server: {
    proxy: {
      "/api": {
        target: API_TARGET,
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api/, ""),
      },
    },
  },
});
