# data (소유: 정세현)

| 디렉토리 | 내용 | git |
|---|---|---|
| `timeseries/` | S&P500 · NIKKEI225 · EuroStoxx50 일별 종가 CSV (키움 제4181회 기초자산 3종). `VERSION` 의 sha256 으로 스냅샷 고정 — P2 재현성의 근거 | 추적 |
| `misconception_library/` | 오해 패턴 라이브러리 (분쟁조정례·검사결과 근거) | 추적 |
| `documents/` | 파서 입력 공시 문서 원본 (ELS 간이투자설명서, 변액 상품요약서·운용설명서) | 추적 |
| `dispute_cases/` | 오해 라이브러리 `source` 가 인용하는 금감원 보도자료 | 추적 |
| `synth_sessions/` | F-DSH-003 산출물 | **제외** |

`documents/` 는 private repo 이므로 추적한다(PR #30) — 파싱 재현성의 입력을 코드와 같은
커밋에 고정한다. 제출물에는 5절 가명 처리 요구에 따라 발행사·회차 없이 조건 문면만 인용한다.

## 컨테이너에서의 경로 (배포 시 주의)

이 디렉토리를 읽는 코드가 **레포 루트 기준 상대경로**를 쓴다. 서비스별로 `data/` 없이
이미지를 만들면 조용히 깨지거나 조용히 건너뛴다.

| 읽는 쪽 | 필요한 것 | 경로 해석 | 없으면 |
|---|---|---|---|
| `ai-service/app/misconception.py` | `misconception_library/` | `Path(__file__).parents[2] / "data" / ...` = 레포 루트 | **로딩 시점에 죽는다** — 오해 매칭 전체가 안 돈다 |
| `server` `simulator/SimulatorService.loadSeries` | `timeseries/` | 호출자가 주입 (계산 경로 밖) | 호출자가 정하는 문제 |
| `server` `SimulatorServiceTest` | `timeseries/` | `Path.of("..", "data", "timeseries")` — 작업 디렉토리 `server/` 가정 | `Assumptions` 로 **조용히 skip** — 검산이 안 돌았는지 눈에 안 보인다 |
| `ai-service/tests/conftest.py` | `documents/` | 레포 루트 기준 | skip |

**`timeseries/` 를 이미지에 COPY 하지 말 것.** 18,089 줄이 중복되면서 `VERSION` 의 sha256 으로
고정한 원본과 조용히 갈라질 수 있다(같은 이유로 클래스패스 리소스로도 복사하지 않았다 —
`SimulatorService` 주석 참고). 읽기 전용 볼륨 마운트가 맞다.

배포 형태는 인프라 R(오준서) 소유다. 이 표는 "무엇이 필요한가"까지이고 어떻게 넣을지는
compose 설계에서 정한다.
