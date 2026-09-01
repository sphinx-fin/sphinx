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
| `app/pii.py` | P3 입구 재검사 (범위: customer / public_document) |
| `app/numerics.py` | 조건값 수치 추출·대조 — F-INT-002·F-INT-004 공용 |
| `app/rubrics.py` | 루브릭 로더 (`status: draft`는 정답지 대조 전) |
| `app/templates.py` | 상품유형 템플릿 로더 — **F-EXT-003 재현율 분모** |
| `app/llm_client.py` | LLM 어댑터 (OpenAI 호환 엔드포인트) |
| `app/config.py` | `LLM_API_BASE` / `LLM_API_KEY` / `LLM_MODEL` |
| `app/rubrics/` | 채점 루브릭 YAML (공개 의무) |
| `app/templates/` | 상품유형별 추출 대상 항목 (ELS 13 · 변액 10) |
| `app/prompts/` | 프롬프트 세트 — 산출물이므로 버전을 올리고 지우지 않는다 |

## 엔드포인트

Spring `core/AiServiceClient`가 호출하는 7개. 미구현 기능은 **501**을 반환한다 —
"아직 없음"과 "터짐"이 구분돼야 연동하는 쪽이 판단할 수 있다.

| 경로 | 기능ID | 상태 |
|---|---|---|
| `POST /internal/parse` | F-EXT-001 (정세현) | 501 |
| `POST /internal/extract` | F-EXT-002 | 구현 — LLM 키 필요 |
| `POST /internal/question` | F-INT-002 | 구현 — LLM 키 필요 |
| `POST /internal/score` | F-SCR-001 | 구현 — LLM 키 필요 |
| `POST /internal/misconception` | F-DET-001 | 구현 (결정론 단계) |
| `POST /internal/mismatch` | F-DET-002 | 구현 — LLM 키 필요 |
| `POST /internal/reexplain` | F-INT-004 | 501 |

F-DET-002는 **7번째 엔드포인트**다(강희진 결정). 모순 판정은 설문 전체 + 세션 발화
전체가 입력이라 항목 단위 `/internal/score`와 분리한다. 게이트 판정 직전에 호출된다.
취약 요인 가중·코칭 스코어는 ai-service가 하지 않는다 — 서버 소유(역할분담표 v1.2 §38).
근거와 결정 이력은 `proposals/F-DET-002-mismatch.md`.

## proposals/ — 계약 초안 대기소

`contracts/`는 강희진 소유이고 변경에 소유자 승인이 필요하다. 내가 공급자인 계약의
초안은 여기서 먼저 만들고 합의된 뒤 옮긴다. **여기 있는 파일은 아직 계약이 아니다.**

## PII 방어선 (P3)

이 서비스에 들어오는 고객 텍스트는 Spring의 `PiiGateway.mask()`를 이미 통과한 상태다.
그래도 방어적으로 **두 겹**을 둔다. 걸리면 마스킹이 아니라 **거부(422)** 한다 —
마스킹하면 상류의 P3 위반이 조용히 덮인다.

1. **입구** — `PiiGuardMiddleware`가 요청 본문의 모든 문자열을 검사한다(중첩 필드 포함).
   검사 범위는 경로별이다. `/internal/parse`·`/internal/extract` 는 본문이 **공시 상품문서**라
   넓은 휴리스틱을 끈다 — 기획서 7-3 이 *"상품설명서(공시 자료이므로 개인정보가 아니다)"* 라고
   명시하고, 발행사 민원부서 번호(`02-785-7424`)가 ACCOUNT 패턴에 걸려 정상 문서가 422 로
   거부된 실측 사례가 있다. **좁은 패턴(RRN·PHONE)은 어느 범위에서도 검사한다.**
2. **출구(최종)** — `llm_client.send()`가 외부로 나가는 모든 프롬프트를 다시 검사한다.
   ai-service에서 망 밖으로 텍스트가 나가는 지점은 이 함수 하나뿐이다.

## LLM

OpenAI 엔드포인트로 붙는다. `.env` 3개 변수만 갈아끼우면 다른 모델·온프레미스로
이동한다 — 호출부는 프로바이더를 모른다. Gemini 로 되돌리는 것도 base_url 한 줄이다
(OpenAI 호환 엔드포인트를 제공한다).

**모델 정책: 모든 LLM 호출을 한 모델로 한다.** 기능별로 모델을 나누지 않는다.
기본값은 `gpt-5-mini` 다. 정책에서 벗어난 모델을 넣으면 경고가 남는다 —
성능 수치의 출처를 추적할 수 있어야 한다.

```
LLM_API_BASE=https://api.openai.com/v1
LLM_API_KEY=...
LLM_MODEL=gpt-5-mini              # 생략 가능 — 코드 기본값과 같다
LLM_REASONING_EFFORT=minimal      # 생략 가능. 빈 값으로 두면 아예 안 보낸다
```

`LLM_REASONING_EFFORT` 를 비우지 않는 이유: 기본값이면 gpt-5-mini 가 호출당 사고토큰
1,024개를 태운다(20.3초). `minimal` 이면 0개 · 2.4초이고 **등급은 같았다.**

이전 기본값은 `gemini-3.5-flash-lite` 였다. 옮긴 이유는 `app/config.py` 의 모델 정책
주석에 적혀 있다 — 요약하면 무료 티어가 **분당 15회**에서 막혀 데모 중에 폴백 질문이
나가고, 두 모델의 판정 등급이 dev set 24건에서 완전히 같았다.

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
- `tests/test_scoring.py` — 후처리 비대칭. 지어낸 인용·루브릭 밖 조항 거부, 오해 상향.
- `tests/test_parsed_document.py` — `contracts/parsed_document.schema.json` 미러 정합과
  스팬 항등식(`pages[page].text[start:end] == value_text`)을 실파서 출력으로 검증.

**API 키 없이 전부 돌아간다.** 후처리가 채점 품질의 실질이므로 CI에서 회귀를 잡아야 한다.

## 채점 후처리 순서 (F-SCR-001)

기획서 5절 [채점 성능 목표와 오판 처리]의 비대칭 — 오해→이해 오판 상한 1%,
이해→오해는 관리 지표 10% — 때문에 후처리는 **전부 안전한 방향으로만** 움직인다.

1. `_pin_item_id` — 호출자가 지정한 항목이 진실이다
2. `_drop_llm_misconception_type` — 모델이 채운 유형ID를 버린다. 유형ID는 오해 라이브러리에서만
   온다. 실측에서 존재하지 않는 ID를 지어냈고, 그건 오해 지도 집계 키다
3. `verify_quote_is_verbatim` — 근거 인용이 실제 발화에 있는지 대조 (P4).
   지어낸 인용은 근거 없는 것보다 나쁘다
4. `verify_rubric_clause_is_published` — 인용된 조항이 공개 루브릭 안의 것인지 대조 (P4).
   "A 및 B"처럼 합쳐 인용해도 공개 조항으로 환원되면 통과, 잔여에 내용이 남으면 거부
5. `apply_misconception_floor` — 루브릭이 관련 유형으로 선언한 오해가 라이브러리에서
   잡히면 U4 아래로 내려가지 않는다. 데모 임계 경로가 LLM 응답에 의존하지 않게 한다.
   상향 근거의 종류(`dispute_case`/`inspection`/`proposal_example`)를 `reason`에 남긴다

**신뢰도 기반 황색 강등은 여기서 하지 않는다.** 게이트 정책이므로 `gate_rules.yaml`이
가진다(강희진 결정, PR #10). 양쪽에서 하면 이중계산이다 —
`proposals/F-SCR-001-yellow-downgrade.md` 참고.

## 추출 (F-EXT-002)

**스팬은 모델에게 묻지 않는다.** 모델은 인용과 페이지만 내고 `[start, end)` 는
`parsing.resolve_span()`(정세현)이 원문에서 찾는다. 모델 숫자를 믿으면 계약 항등식이 깨질 수
있고 **깨진 채로도 추출은 성공한 것처럼 보인다.** 우리가 계산하면 항등식이 구성상 성립한다.
`value_text` 도 모델 인용이 아니라 원문에서 잘라낸 문자열을 쓴다(P6).

실패는 은폐하지 않는다(E-EXT-03) — 못 찾은 항목은 빼지 않고 `status="extraction_failed"` 로
낸다. 스팬 해소 실패·거짓양성 가능(loose)·모호한 스팬·페이지 정정·템플릿 밖 항목·importance
자리표시자를 `ExtractionWarning` 으로 노출한다.

문서 전체를 한 번에 넣는다(ELS 13k자·변액 20k자). `MAX_DOCUMENT_CHARS` 를 넘으면 **거부**한다 —
조용히 잘라내면 뒷부분 항목이 전부 미검출로 잡히고 원인이 보이지 않는다.

## 상품유형 템플릿 — 추출 범위 고정

기획서 5절 통제: *"상품유형 템플릿으로 추출 범위를 고정한다."*
**템플릿에 없는 항목은 F-EXT-002 가 추출하지 않으므로 이 파일들이 F-EXT-003 재현율의
분모다**(이슈 #26). 루브릭 유무는 재현율에 영향을 주지 않는다 — 그쪽은 채점 커버리지다.

두 규칙이 테스트로 고정돼 있다.

- **계약 대조**: 항목 집합이 `contracts/samples/*.json` 의 `_expected_risk_items` 와 정확히
  같아야 한다(ADR-006 정본). 어긋나면 템플릿에만 있는 항목은 오탐이 되고 계약에만 있는
  항목은 재현율이 구조적으로 깎이는데, 둘 다 예외 없이 조용히 지나간다.
- **cue 에 숫자 금지**: 특정 회차의 조건값을 박으면 다른 발행사 문서에 붙지 않는다.
  값은 추출 시점에 원문에서 가져온다(P6).

`importance` 는 미정이다 — 정세현이 23종에 부여하기로 했고(이슈 #26) 그 값이 루브릭 작업
범위를 정한다. `templates.coverage_report()` 가 분모·부여 현황·루브릭 커버리지를 함께 낸다.

## 적합성 모순 판정 (F-DET-002)

**모순은 양쪽이 다 추적 가능해야 한다**(P4). 발화 인용은 실제 발화에서, 설문 참조는 실제
설문에서 와야 한다. 한쪽이라도 지어낸 것이면 그 모순을 버린다 — 예외를 던지지 않고 개별로
걸러낸다. 세션 단위 판정이라 전체를 버리면 실제 모순을 놓친다.

- 입력이 부족하면 `status: insufficient_input`. `mismatch=false` 만 돌려주면 호출자가
  '적합'으로 읽는다 — 판정 못 한 것과 모순 없는 것은 다르다.
- `mismatch`·`confidence`·`session_id` 는 **우리가 다시 계산한다.** LLM 이 낸 값을 믿지 않는다.
- 탐지 자신감 미달(`MISMATCH_CONFIDENCE_FLOOR`) 모순은 **버리지 않고 남긴다.**
  `mismatch=true` 로만 올리지 않는다 — `direction` 이 코칭 문구를 좌우하고(강희진 결정 ⓒ),
  조용히 지우면 왜 통과했는지 추적할 수 없다.
- 취약 요인 가중·코칭 스코어는 하지 않는다 — 서버 소유(ADR-005). `detect()` 시그니처가
  그 입력을 받지 않는 것을 테스트로 고정했다.

약한 고리: 설문 쪽에서 `axis` 를 주지 않으므로(결정 ⓑ) 문항 키·값의 문면을 모델이 해석해야
한다. 문항 문면이 바뀌면 판정이 흔들린다.

## dev set

`tests/fixtures/`는 프롬프트 튜닝용이며 **F-CMN-003 공식 평가셋과 무관하다**
(윤지석은 프롬프트 당사자로 라벨링 제외). 자세한 내용은 그 디렉토리의 README.

RiskItem 은 픽스처에 두지 않고 `contracts/samples/*.json` 에서 만든다 — 조항 문면을
복사하면 낡는다. 러너가 실행 전에 계약 규약 등식
`pages[page].text[start:end] == value_text` 를 검사한다.

```bash
python tools/run_devset.py            # 상품유형 전체 (ELS 5 + 변액 6)
python tools/run_devset.py VAR-       # 케이스 id 부분일치
```

루브릭이 있으면 dev set 케이스도 있어야 한다 — 테스트로 고정했다. 문면만 맞춰둔 루브릭은
실제로 어떻게 채점되는지 모르는 상태이고, 변액 4종이 한동안 그랬다.
