# SphinX (스FIN크스)

금융상품 계약 직전 고객 이해도 검증 게이트. (상세 비공개 — 팀 내부 문서는 `docs/` 참고)

## 레포 구조와 소유권

| 디렉토리 | 소유자 | 내용 |
|---|---|---|
| `contracts/` | 강희진 | 인터페이스 계약(JSON Schema, OpenAPI). **소유자 승인 없이 변경 금지** |
| `server/` (Spring Boot) | 강희진(주)·정세현 | API·세션 상태머신·게이트 룰엔진·PII 게이트웨이(강희진), 시뮬레이터·집계·증거계층(정세현) |
| └ `server/.../evidence/` | 정세현 | 정규화 직렬화·해시 체인·append-only 저장 위에 리포트(F-GTE-004)·감사 로그(F-CMN-002) |
| └ `server/.../security/` | 정세현·강희진 | 역할·정책(정세현) / 필터체인·어노테이션 부착(강희진) — 경계는 `AccessPolicy` 주석 |
| `ai-service/` (FastAPI) | 윤지석(주)·정세현 | LLM 파이프라인: 추출·질문생성·채점·오해/모순·재설명(윤지석), PDF 파서(정세현) |
| `web/` | 오준서 | 프론트엔드 전체 (S-01 ~ S-08) |
| `data/` | 정세현 | 지수 시계열, 오해 라이브러리, 수집 문서 — **전부 git 추적**(#30). CI 가 checkout 만으로 테스트를 돌릴 수 있는 근거다 |
| `eval/` | 정세현 | 채점 성능 평가 파이프라인 (라벨링: 강희진+오준서) |
| `docs/` | 정세현 | 기획서·기능명세서·역할분담표·`adr/`(설계 결정 기록) |

## 개발 규칙

1. `main` 브랜치 보호. 작업은 `feat/<기능ID>-설명` 브랜치 → PR → **해당 디렉토리 소유자 리뷰** 후 머지.
2. `contracts/` 변경은 강희진 승인 + 영향받는 사람 전원 멘션.
3. AI는 측정, 룰은 결정: `app/ai`의 출력이 게이트 판정·금액 계산에 직접 쓰이면 안 된다 (명세서 P1).
4. 고객 텍스트가 ai-service로 나가는 유일한 경로는 Spring의 `PiiGateway.mask()` → `AiServiceClient` (P3). ai-service는 내부망 전용 — 브라우저에 직접 노출 금지.
5. 시뮬레이터·게이트는 순수 함수 + 단위 테스트 필수 (P2).
6. 해시·직렬화는 `evidence/`의 `CanonicalJson`·`HashChain`만 쓴다. 리포트와 감사 로그의 해시가 교차 검증돼야 하므로 여기서 갈라지면 안 된다.
7. 권한 규칙은 `rbac_policy.yaml`이 유일한 근거(정세현). 컨트롤러 어노테이션(강희진)은 action 이름만 참조하고 규칙을 하드코딩하지 않는다. 감사 로그는 컨트롤러가 아니라 `AuditInterceptor`가 남긴다.
8. **역이용 방지(기획서 7-4)는 역할 부재 + 범위 분리 두 층이다** (명세서 0.4, [ADR-001](docs/adr/001-no-sales-role.md)). 본부 영업·마케팅 역할을 만들지 않고, 집계(`aggregate:*`)는 COMPL(전체)·MGR(자기 지점)만 접근한다. `Role`에 역할을 추가하거나 `aggregate:*`에 역할을 붙이는 PR은 기획서 7-4 재검토 대상이다.
9. 설계 결정은 `docs/adr/`에 남긴다. 결정이 바뀌면 새 ADR을 추가하고 기존 문서는 상태만 갱신한다 — 삭제·수정 금지.

## 개발 환경

CI 가 고정하는 값이다(`.github/workflows/ci.yml`). 로컬도 같은 버전을 쓰면 "러너에서만
깨진다"가 없어진다.

| 모듈 | 런타임 | 고정 위치 |
|---|---|---|
| `server/` | **JDK 17** | `build.gradle` 의 `toolchain { languageVersion = 17 }` — 여기가 원본이고 CI 는 따라간다. 올리려면 둘을 함께 올린다 |
| `ai-service/` | **Python 3.11** | `requirements.txt` 에 상한이 없어 런타임을 따로 고정한다. pdfplumber·reportlab 이 새 파이썬에서 휠이 늦다 |
| `web/` | **Node 20** | `package.json` 에 `engines` 가 없다. 의존성 설치는 `npm ci`(락파일 준수) |

> **macOS 함정** — Homebrew `openjdk@17` 은 keg-only 라 Gradle 툴체인 자동 탐지가 실패한다.
> `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` 을 걸고 돌린다.

## 테스트

```bash
cd server     && ./gradlew test        # JUnit
cd ai-service && pip install -r requirements-dev.txt && pytest
cd web        && npm ci && npx tsc --noEmit
```

세 명령이 PR 에서 자동으로 돈다. **CI 는 skip 을 실패로 본다** — `data/` 가 전부 커밋돼
있으므로 러너에서 건너뛴 테스트는 "검산이 안 돌았다"는 뜻이고, 그런데도 로그는 초록으로
남기 때문이다(이슈 #73). 로컬에서 skip 이 나오면 데이터 파일이 없는 것이니
`python3 scripts/fetch_timeseries.py` 부터 확인한다.

## 실행

```bash
# backend (Spring Boot, :8000)
cd server && ./gradlew bootRun
# ai-service (FastAPI, :8100 — 내부 전용)
cd ai-service && pip install -r requirements.txt && uvicorn app.main:app --port 8100 --reload
# frontend (:5173 → /api 프록시 → :8000)
cd web && npm install && npm run dev
```

서버는 계약(contracts/) 기준의 목 응답을 반환하도록 초기화돼 있어, 프론트는 첫날부터 실제 엔드포인트로 개발 가능.
