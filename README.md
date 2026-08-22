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
| `data/` | 정세현 | 지수 시계열, 오해 라이브러리, 수집 문서(git 제외) |
| `eval/` | 정세현 | 채점 성능 평가 파이프라인 (라벨링: 강희진+오준서) |
| `docs/` | 정세현 | 기획서·기능명세서·역할분담표 |

## 개발 규칙

1. `main` 브랜치 보호. 작업은 `feat/<기능ID>-설명` 브랜치 → PR → **해당 디렉토리 소유자 리뷰** 후 머지.
2. `contracts/` 변경은 강희진 승인 + 영향받는 사람 전원 멘션.
3. AI는 측정, 룰은 결정: `app/ai`의 출력이 게이트 판정·금액 계산에 직접 쓰이면 안 된다 (명세서 P1).
4. 고객 텍스트가 ai-service로 나가는 유일한 경로는 Spring의 `PiiGateway.mask()` → `AiServiceClient` (P3). ai-service는 내부망 전용 — 브라우저에 직접 노출 금지.
5. 시뮬레이터·게이트는 순수 함수 + 단위 테스트 필수 (P2).
6. 해시·직렬화는 `evidence/`의 `CanonicalJson`·`HashChain`만 쓴다. 리포트와 감사 로그의 해시가 교차 검증돼야 하므로 여기서 갈라지면 안 된다.
7. 권한 규칙은 `rbac_policy.yaml`이 유일한 근거(정세현). 컨트롤러 어노테이션(강희진)은 역할 이름만 참조하고 규칙을 하드코딩하지 않는다. 감사 로그는 컨트롤러가 아니라 `AuditInterceptor`가 남긴다.

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
