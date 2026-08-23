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
| `app/mismatch.py` | 윤지석 | F-DET-002 (설문 스키마 확정 대기) |
| `app/reexplain.py` | 윤지석 | F-INT-004 콘텐츠 |

## 공통 모듈 (소유: 윤지석)

| 모듈 | 역할 |
|---|---|
| `app/main.py` | 엔트리포인트 + PII 입구 미들웨어 + `/healthz` |
| `app/routes.py` | `/internal/*` 라우트. 얇게 유지 — 기능 로직은 각 모듈의 순수 함수 |
| `app/schemas.py` | `contracts/*.schema.json`의 pydantic 미러 (계약의 진실은 `contracts/`) |
| `app/pii.py` | P3 입구 재검사 |
| `app/rubrics.py` | 루브릭 로더 (`status: draft`는 핵심설명서 대조 전) |
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
| `POST /internal/score` | F-SCR-001 | 구현 — LLM 키 필요 |
| `POST /internal/misconception` | F-DET-001 | 구현 (결정론 단계) |
| `POST /internal/reexplain` | F-INT-004 | 501 |

F-DET-002(적합성 모순)는 이 목록에 엔드포인트가 없다. 세션 단위 판정이라 항목별
`/internal/score`에 얹기 어려워 **7번째 `/internal/mismatch`를 제안**했고, 출력 스키마
초안과 근거는 `proposals/`에 있다. 강희진 확인 후 붙인다.

## proposals/ — 계약 초안 대기소

`contracts/`는 강희진 소유이고 변경에 소유자 승인이 필요하다. 내가 공급자인 계약의
초안은 여기서 먼저 만들고 합의된 뒤 옮긴다. **여기 있는 파일은 아직 계약이 아니다.**

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

**모델 정책: 모든 LLM 호출은 flash-lite 계열을 쓴다.** 기능별로 모델을 나누지 않는다.
`gemini-2.5-flash-lite`는 신규 키에 제공되지 않으므로(404) API가 안내하는
`gemini-3.5-flash-lite`가 기본값이다. 정책에서 벗어난 모델을 넣으면 경고가 남는다 —
성능 수치의 출처를 추적할 수 있어야 한다.

```
LLM_API_BASE=https://generativelanguage.googleapis.com/v1beta/openai/
LLM_API_KEY=...
LLM_MODEL=gemini-3.5-flash-lite   # 생략 가능 — 코드 기본값과 같다
```

### .env 위치

**`ai-service/.env`를 권한다.** LLM 키는 이 서비스만 쓰므로 서비스 디렉토리에 두는 것이
비밀의 범위를 좁힌다. 루트에 둬도 동작한다(루트 `.env.example`이 그 위치를 가정한다).

    프로세스 환경 > ai-service/.env > 레포 루트 .env > 코드 기본값

두 위치 모두 `.gitignore`의 `.env` 규칙에 걸린다 — 슬래시가 없는 패턴이라 모든 깊이에
적용된다. `config.py`가 직접 로드하므로 `uvicorn --env-file` 플래그가 필요 없고 pytest에도
적용된다.

키가 없어도 서비스는 기동한다(LLM 경로만 503). `GET /healthz`가 **어느 `.env`를 읽었는지**
알려주며, 키 값 자체는 노출하지 않는다.

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

- `tests/test_skeleton.py` — 골격의 계약: 6개 엔드포인트 존재, 미구현 501, 키 없으면 503,
  PII 거부.
- `tests/test_misconception.py` — 라이브러리 재현성. 데모 발화가 결정론적으로 잡히는지.
- `tests/test_scoring.py` — 후처리 비대칭. 지어낸 인용 거부, 오해 상향, U4 불완화.

**API 키 없이 전부 돌아간다.** 후처리가 채점 품질의 실질이므로 CI에서 회귀를 잡아야 한다.

## 채점 후처리 순서 (F-SCR-001)

기획서 5절 [채점 성능 목표와 오판 처리]의 비대칭 — 오해→이해 오판 상한 1%,
이해→오해는 관리 지표 10% — 때문에 후처리는 **전부 안전한 방향으로만** 움직인다.

1. `_pin_item_id` — 호출자가 지정한 항목이 진실이다
2. `verify_quote_is_verbatim` — 근거 인용이 실제 발화에 있는지 대조 (P4).
   지어낸 인용은 근거 없는 것보다 나쁘다
3. `apply_misconception_floor` — 루브릭이 관련 유형으로 선언한 오해가 라이브러리에서
   잡히면 U4 아래로 내려가지 않는다. 데모 임계 경로가 LLM 응답에 의존하지 않게 한다
4. `downgrade_low_confidence` — 신뢰도 미달 U1 → U2. **U4는 건드리지 않는다**

## dev set

`tests/fixtures/`는 프롬프트 튜닝용이며 **F-CMN-003 공식 평가셋과 무관하다**
(윤지석은 프롬프트 당사자로 라벨링 제외). 자세한 내용은 그 디렉토리의 README.
