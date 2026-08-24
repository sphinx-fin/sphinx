# ai-service (FastAPI)

LLM 파이프라인 전용 내부 서비스. **외부(브라우저)에 노출하지 않는다** — Spring(server)만 호출.

## 기능 모듈

| 모듈 | 소유 | 기능ID |
|---|---|---|
| `app/parsing.py` | 정세현 | F-EXT-001 (pdfplumber — 한글 PDF 표 추출에 Java보다 유리해 여기 둠) |
| `app/extraction.py` | 윤지석 | F-EXT-002 |
| `app/question_gen.py` | 윤지석 | F-INT-002 |
| `app/scoring.py` | 윤지석 | F-SCR-001 |
| `app/misconception.py` | 윤지석 (데이터: 정세현) | F-DET-001 |
| `app/mismatch.py` | 윤지석 | F-DET-002 |
| `app/reexplain.py` | 윤지석 | F-INT-004 콘텐츠 |

## 공통 모듈 (소유: 윤지석)

| 모듈 | 역할 |
|---|---|
| `app/main.py` | 엔트리포인트 + PII 입구 미들웨어 + `/healthz` |
| `app/routes.py` | `/internal/*` 라우트. 얇게 유지 — 기능 로직은 각 모듈의 순수 함수 |
| `app/schemas.py` | `contracts/*.schema.json`의 pydantic 미러 (계약의 진실은 `contracts/`) |
| `app/pii.py` | P3 입구 재검사 |
| `app/llm_client.py` | LLM 어댑터 (OpenAI 호환 엔드포인트) |
| `app/config.py` | `LLM_API_BASE` / `LLM_API_KEY` / `LLM_MODEL` |
| `app/rubrics/` | 채점 루브릭 YAML (공개 의무) |
| `app/prompts/` | 프롬프트 세트 — 산출물이므로 버전을 올리고 지우지 않는다 |

## 엔드포인트

Spring `core/AiServiceClient`가 호출하는 6개. 미구현 기능은 **501**을 반환한다 —
"아직 없음"과 "터짐"이 구분돼야 연동하는 쪽이 판단할 수 있다.

| 경로 | 기능ID | 상태 |
|---|---|---|
| `POST /internal/parse` | F-EXT-001 (정세현) | 501 |
| `POST /internal/extract` | F-EXT-002 | 501 |
| `POST /internal/question` | F-INT-002 | 501 |
| `POST /internal/score` | F-SCR-001 | 501 |
| `POST /internal/misconception` | F-DET-001 | 501 |
| `POST /internal/reexplain` | F-INT-004 | 501 |

F-DET-002(적합성 모순)는 이 목록에 엔드포인트가 없다. `/internal/score`에 태울지
7번째를 낼지 강희진과 확정한 뒤 추가한다 — 출력 스키마도 `contracts/`에 아직 없다.

## PII 방어선 (P3)

이 서비스에 들어오는 고객 텍스트는 Spring의 `PiiGateway.mask()`를 이미 통과한 상태다.
그래도 방어적으로 **두 겹**을 둔다. 걸리면 마스킹이 아니라 **거부(422)** 한다 —
마스킹하면 상류의 P3 위반이 조용히 덮인다.

1. **입구** — `PiiGuardMiddleware`가 요청 본문의 모든 문자열을 검사한다(중첩 필드 포함).
2. **출구(최종)** — `llm_client.send()`가 외부로 나가는 모든 프롬프트를 다시 검사한다.
   ai-service에서 망 밖으로 텍스트가 나가는 지점은 이 함수 하나뿐이다.

## LLM

OpenAI 호환 엔드포인트로 붙는다(기본값: Gemini). `.env` 3개 변수만 갈아끼우면 다른 모델·
온프레미스로 이동한다 — 호출부는 프로바이더를 모른다.

```
LLM_API_BASE=https://generativelanguage.googleapis.com/v1beta/openai/
LLM_API_KEY=...
LLM_MODEL=gemini-3.7-flash
```

키가 없어도 서비스는 기동한다(LLM 경로만 503). `GET /healthz`로 설정 여부를 확인할 수 있고,
키 값 자체는 노출하지 않는다.

## 실행

```bash
pip install -r requirements.txt
uvicorn app.main:app --port 8100 --reload
```

## 테스트

```bash
pip install -r requirements-dev.txt
pytest
```

`tests/test_skeleton.py`는 기능이 아니라 **골격의 계약**을 검증한다: 6개 엔드포인트 존재,
미구현은 501, PII 거부, 황색 강등이 U4를 건드리지 않음(P5).
