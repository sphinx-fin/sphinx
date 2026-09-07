# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트

SphinX(스FIN크스) — 금융상품 계약 직전에 고객 이해도를 검증하는 판매 게이트. 2026 금융 AI
Challenge 공모전 MVP. 4인 팀(윤지석·강희진·정세현·오준서)이 3주 일정으로 개발한다.

레포 전체가 **스캐폴드 단계**다. 대부분의 클래스는 `// TODO(담당자)` 스텁이고, Spring
컨트롤러는 `api/MockData.java`의 목 응답을 반환한다. 이건 의도된 것으로, 프론트가 첫날부터
실제 엔드포인트로 개발할 수 있게 하려는 구조다. 구현을 붙일 때 목 응답을 지우는 것이 각
모듈의 완료 조건이다.

## 명령어

```bash
# server (Spring Boot, :8000)
cd server && ./gradlew bootRun
cd server && ./gradlew test
cd server && ./gradlew test --tests 'com.sphinxfin.sphinx.core.gate.GateEngineTest'   # 단일 테스트
cd server && ./gradlew compileJava

# ai-service (FastAPI, :8100 — 내부망 전용, 브라우저에 노출 금지)
cd ai-service && pip install -r requirements.txt && uvicorn app.main:app --port 8100 --reload

# web (Vite, :5173 → /api 프록시 → :8000)
cd web && npm install && npm run dev
cd web && npm run build

# eval (F-CMN-003 채점 성능 평가)
python eval/run_eval.py

# 실세션 하나를 S-01~S-07 로 통과시킨다 (교부 문서 시연용 — 이슈 #278 의 1번)
scripts/walk_demo_session.sh
BASE=https://alpha... PYTHON=.venv/bin/python scripts/walk_demo_session.sh
```

합성 세션은 집계용이라 불변 기록(`evidence/`)을 안 쌓는다. 그래서 대시보드에서 합성
세션을 눌러 교부 문서를 열면 **내용이 빈 PDF** 가 나온다 — 시연에 쓸 세션은
`walk_demo_session.sh` 로 만든다. 이 스크립트는 채점이 막히면 **ai-service 가 안 뜬
것인지 LLM 키가 없는 것인지** 갈라서 알려준다(둘 다 같은 502 로 나온다).

**주의**: `server/`의 Gradle 래퍼(`gradlew`)는 8.10.2로 고정돼 있다(Boot 3.3.2 플러그인 호환,
PR #6). 린터는 어느 모듈에도 설정돼 있지 않고, `ai-service`·`web`·`eval`에는
테스트 러너도 없다 — 테스트는 현재 `server/src/test`의 JUnit 뿐이다.

## 아키텍처

3개 서비스로 나뉘고, 데이터가 흐르는 방향이 곧 보안 경계다.

```
web(:5173) ──/api 프록시──▶ server(:8000, Spring Boot) ──▶ ai-service(:8100, FastAPI)
                                     │                        └ 내부망 전용
                                     └ H2 (로컬·테스트) / MySQL (배포)
```

`ai-service`는 브라우저에서 직접 호출하지 않는다. Spring만 호출한다.

### 핵심 불변식

명세서의 원칙들로, 코드 여러 곳에 걸쳐 강제된다. 이걸 어기는 변경은 리뷰에서 막힌다.

1. **P1 — AI는 측정, 룰은 결정.** `ai-service`의 LLM 출력은 `domain/Judgment`(등급·근거
   스팬·신뢰도)라는 *측정값*이다. 게이트 판정은 `core/gate/GateEngine`이 선언적
   `resources/gate_rules.yaml`을 적용해서 만든다. LLM 원문이 판정이나 금액 계산에 직접
   들어가는 경로를 만들면 안 된다.
2. **P2 — 시뮬레이터·게이트는 결정론적 순수 함수.** `data/timeseries/VERSION`으로 지수
   스냅샷을 고정해 재현성을 보장한다. 단위 테스트 필수.
3. **P3 — 고객 텍스트가 `ai-service`로 나가는 유일한 경로**는
   `core/pii/PiiGateway.mask()` → `core/aiservice/AiServiceClient`. 다른 경로로 ai-service를 부르면 안 된다.
   세션 생성 입력 스키마에 성명·주민번호 필드는 애초에 존재하지 않는다.
   `ai-service`도 방어적으로 입구에서 PII 패턴을 재검사한다.
4. **P4 — 근거 없는 판정은 무효.** `Judgment.Evidence`(발화 인용 + 루브릭 조항)가 비면 안 된다.

### `core/` — 성격별 하위 패키지

한 디렉토리에 엔티티·서비스·설정·컨버터·게이트·PII·FSM 이 섞여 있어서 갈랐다(이슈 #66).
**루트에는 `EvidenceRecorder` 하나만 남는다** — 그건 core 가 evidence 를 모르게 하려고
core 에 둔 경계 인터페이스라(ADR-003) 어느 하위 패키지에도 속하지 않는다.

| 하위 패키지 | 무엇 |
|---|---|
| `core/session/` | 세션 엔티티·서비스·상태머신·오버라이드 |
| `core/gate/` | `GateEngine`·`GateConfig` (판정) |
| `core/pii/` | `PiiGateway` — P3 경계라 단독 패키지다 |
| `core/aiservice/` | ai-service 호출 경계 |
| `core/extraction/` | 상품 문서 업로드·저장 (F-EXT-001) + 추출 스냅샷 저장·조회 (F-EXT-002). 저장 우선, MockData 폴백 |
| `core/persistence/` | `BaseEntity`·JPA 감사·컨버터 |
| `core/simulator/` | `SimulatorProperties` (설정 주입. 계산 엔진은 최상위 `simulator/`) |

이 두 가지 — 루트에 하나만 남는 것, 위 표가 실물과 같은 것 — 은 `CorePackageBoundaryTest`가
대조한다. 루트에 클래스를 새로 만드는 브랜치는 이 표와 **텍스트 충돌 없이** 합쳐지므로
git이 알려주지 않는다(#142에서 실제로 그랬다).

### `evidence/` — 리포트와 감사 로그의 공통 기반

`ReportService`(F-GTE-004)와 `AuditLog`(F-CMN-002)는 저장 요구가 동일하다: append-only,
해시 체인, 정규화 직렬화. 그래서 한 사람이 소유하고 기반을 한 벌만 만든다([ADR-003](docs/adr/003-evidence-ownership.md)).

**해시·직렬화는 `CanonicalJson`·`HashChain`·`ImmutableStore`만 쓴다.** 두 곳에서 따로
정규화하면 미묘하게 다른 두 정규화가 생기고, 리포트 해시와 감사 로그 해시를 교차 검증할 수
없게 된다. 이 결함은 감사 시점까지 드러나지 않는다.

### `security/` — 역이용 방지가 코드로 구현된 곳

기획서 7-4(역이용 방지)의 실물 근거이므로 **범위 축소 대상이 아니다.**

- **역할 부재 원칙** ([ADR-001](docs/adr/001-no-sales-role.md), 명세서 0.4절):
  `Role` enum은 `CUST`·`SELLER`·`MGR`·`COMPL`·`ADMIN` 5개뿐이고, 본부 영업·마케팅 조직에
  대응하는 역할은 **의도적으로 없다.** 권한을 안 주는 것과 줄 수 있는 대상이 없는 것은
  운영 압박이 들어왔을 때 다르게 작동한다 — 역할이 없으면 부여하려면 코드를 고쳐야 하고
  PR에 남는다. `Role`에 값을 추가하거나 `aggregate:*`에 역할을 붙이는 변경은 ADR-001 재검토 대상.
  참고: `SELLER`(창구 판매 직원)와 영업 조직은 다르다. SELLER는 유지한다.
- **범위 분리**: 역할만으로는 부족하다. `resources/rbac_policy.yaml`이 action마다 데이터
  범위(`own_session`/`branch`/`org`)를 고정한다. SELLER는 집계 접근 불가, 집계는
  COMPL(전체)·MGR(자기 지점)만.
- **`rbac_policy.yaml`이 권한의 유일한 근거다.** Java 상수로 중복 정의하지 않는다.
  컨트롤러 어노테이션은 action 이름만 참조한다.
- **감사 로그는 `AuditInterceptor` 단일 통로로 기록한다.** 컨트롤러마다 `AuditLog`를 부르면
  감사 관심사가 `api/`에 흩어진다.
- 현재 `SecurityConfig`는 `permitAll()`이다(목 개발용). 데모에서는 권한 차단과 로그 기록을
  실제로 시연해야 하므로 이 상태를 그대로 둘 수 없다.

### `contracts/` — 팀 간 단일 진실

`risk_item.schema.json`, `judgment.schema.json`, `openapi.yaml`이 모듈 간 계약이다. Java
`domain/` 레코드는 이 스키마와 1:1로 유지한다. **변경은 강희진 승인 + 수요자 전원 멘션**이
필요하다(오너 승인 없이 변경 금지).

### `api/` — 응답 봉투·예외·세션 영속 규약 (F-INT-001~)

- **모든 응답은 공통 봉투 `api/dto/ApiResponse<T>`** 로 감싼다: 성공 `{success:true, data, error:null}`,
  실패 `{success:false, data:null, error:{code,message,timestamp}}`. 컨트롤러가 raw 객체를
  직접 반환하면 프론트 계약이 깨진다.
- **예외는 컨트롤러에서 처리하지 않고 `api/exception/GlobalExceptionHandler`(전역) 한 곳**에서
  `ApiResponse.fail(...)`로 변환한다. 코드: `NOT_FOUND`(404)·`VALIDATION_ERROR`(400)·
  `MALFORMED_REQUEST`(400)·`REEXPLAIN_NOT_ELIGIBLE`(400)·`REVERIFY_EXHAUSTED`(400)·
  `ILLEGAL_STATE_TRANSITION`(409)·`OVERRIDE_NOT_ELIGIBLE`(409)·`DOCUMENT_UNPROCESSABLE`(400)·`UNAUTHORIZED`(401)·
  `FORBIDDEN`(403)·`EVIDENCE_REQUIRED`(502)·`MEASUREMENT_INVALID`(502)·
  `AI_SERVICE_UNAVAILABLE`(502)·`INTERNAL_ERROR`(500).
  **이 목록은 `contracts/openapi.yaml`의 `ApiError.code` enum과 같아야 한다** — 프론트가
  그대로 유니온 타입으로 들고 분기하므로, 계약에 없는 코드를 내보내면 화면이 조용히 깨진다.
  네 벌(핸들러·openapi·이 문단·`web/src/api/types.ts`의 `ErrorCode` 유니온)이 어긋나지
  않도록 `ErrorCodeContractTest`가 전부 대조한다. **유니온을 뺐더니 실제로 셋 갈렸다**
  (이슈 #316 — `UNAUTHORIZED`·`FORBIDDEN`·`MEASUREMENT_INVALID`가 없었다).
  ❗**다섯 번째 자리가 있다** — `web/src/lib/errorText.ts`의 `Record<ErrorCode, string>`.
  거기는 `ErrorCodeContractTest`가 아니라 **tsc가 잡는다**(코드를 더하고 문면을 안 쓰면
  `npm run build`가 깨진다). 그래서 대조 테스트는 초록인데 웹 빌드만 빨간 상태가 생긴다 —
  코드를 더했으면 `npm run build`까지 돌린다. 문면 규칙은 그 파일 주석에 있다.
  새 코드는 전용 예외 타입으로 만든다. `IllegalArgumentException` 같은 범용 예외를 통째로
  400에 매핑하면 서버 설정 오류(게이트 룰 파싱 실패 등)까지 "잘못된 요청"이 된다.
- **요청 DTO는 `api/dto`에** 두고 `@Valid`로 검증, 서비스에는 `core`의 커맨드로 변환해 넘긴다
  (서비스가 web DTO에 의존하지 않도록).
- **엔티티는 `core/persistence/BaseEntity` 상속** → `createdAt`/`updatedAt` 자동 감사(가변 엔티티 한정.
  append-only인 `evidence/`는 상속하지 않는다). 세션은 JPA로 영속한다.
- **DB 가 환경마다 다르다.** 로컬·테스트는 H2 인메모리(`ddl-auto: update`), 배포는 MySQL 8.4
  컨테이너(`ddl-auto: validate` + Flyway). 그래서 **엔티티를 고치면 마이그레이션도 같이 낸다**
  — `server/src/main/resources/db/migration/`. 안 내면 prod 가 기동을 거부하고(validate),
  로컬에서는 `update` 가 알아서 만들어 주므로 **로컬만 보면 아무 신호가 없다.**
  마이그레이션 SQL 은 손으로 쓰지 않는다: MySQL 에 `ddl-auto: create` 로 만들게 하고
  `mysqldump --no-data` 로 뽑는다. validate 는 JDBC 타입 코드가 아니라 **타입명**을 봐서,
  더 넓은 타입으로 적어도 통과하지 않는다(실측 — V1__init.sql 머리말).

## 소유권과 기여 규칙

이 레포는 디렉토리·파일 단위로 소유자가 정해져 있고, PR은 **해당 소유자 리뷰**를 거친다.
코드를 고칠 때 누구 영역인지 먼저 확인해야 한다. 전체 매핑은
[`docs/role-assignment-v1.2.md`](docs/role-assignment-v1.2.md).

`server/` 안에서 특히 헷갈리는 경계:

| 경로 | 소유 |
|---|---|
| `api/`, `core/` | 강희진 (API·상태머신·게이트·PII) |
| `evidence/`, `security/Role·AccessPolicy`, `resources/rbac_policy.yaml` | 정세현 |
| `security/SecurityConfig`, 컨트롤러 `@PreAuthorize`, `AuditInterceptor` 등록 | 강희진 |
| `simulator/`, `aggregate/` | 정세현 |

`F-CMN-002`는 한 기능이 두 사람에게 갈린 유일한 케이스다 — 정책·역할·감사 로그는 정세현,
필터·어노테이션 부착은 강희진. 파일 단위로 나눠 뒀으니 상대 파일을 건드리지 않는다.

작업 브랜치: `feat/<기능ID>-설명`.

PR 담당자(assignee)는 `.github/workflows/pr-review.yml`이 **작성자로 자동
지정**한다. 이미 담당자가 있으면 손대지 않으므로, 일부러 다른 사람에게 넘긴 PR은
그대로 유지된다. 담당자는 "이 PR을 끝까지 끌고 갈 사람"이고 리뷰어와 다르다.

### PR 리뷰 라벨은 자동이다 — 손으로 붙이지 않는다

`.github/workflows/pr-review.yml`이 리뷰가 지금 **누구 손에 있는지**를 라벨로
붙이고 뗀다. 행동이 필요한 상태에만 라벨이 있고, 끝난 것은 라벨이 없다.

| 라벨 | 뜻 |
|---|---|
| `리뷰어 미배정` (회색) | 배정도 리뷰도 하나도 없다. 리뷰어를 붙이는 게 다음 할 일 |
| `리뷰대기: <이름>` (노랑) | 배정됐고 아직 아무것도 제출하지 않았다 |
| `리뷰중: <이름>` (주황) | 코멘트·변경요청을 냈고 아직 승인하지 않았다 |
| (라벨 없음) | 관련된 사람이 모두 승인했다 |

완료 상태에 라벨을 두지 않는 이유는 두 가지다. `소유자 승인 여부` 초록 체크가 PR
목록에서 이미 같은 사실을 말하고, 종착역 라벨은 떼는 사건이 없어 영원히 눌어붙는다.

**커밋 상태와 라벨은 한 판정의 두 표현이다 — 파일이 하나다.** 예전에는
`pr-review-guard.yml`·`pr-reviewer-label.yml` 두 파일이 승인 유효성 판단(`COMMENTED`는
승인을 취소하지 않는다 · 사용자별 최신 상태만 본다 · 배정 여부와 무관하게 모든 리뷰를
센다)을 **각자 구현**했고, 한쪽만 고치면 *가드는 머지를 막는데 라벨은 비어 있는* 상태가
됐다. 이 어긋남으로 결함이 네 번 났다(PR #90 · #91 · 이슈 #135/PR #140 · 이슈 #180).
다섯 번째는 **트리거 쪽**이었다 — 가드가 `reviewRequests` 로 판정하면서 그 값이 바뀌는
`review_requested`를 안 들어서, 초록인 PR에 리뷰어를 새로 붙이면 **라벨만 바뀌고 가드는
낡은 초록으로 남았다.**

지금은 `pr-review.yml` 한 파일의 「판정」 스텝이 사실을 한 번만 만들고 커밋 상태와 라벨이
그것을 각자 표현한다. **두 표현이 갈릴 자리가 없다** — 위 계열의 결함은 구조적으로 못 난다.
고칠 때도 판정을 두 곳에 복사하지 않는다.

**트리거는 8종이다.** PR이 충돌 상태이면 `pull_request_review` 이벤트가 유실돼 승인이
들어와도 안 돈다. `synchronize`가 있는 이유가 이것이다 — 다음 푸시에 timeline·reviews를
새로 읽어 스스로 낫는다(이슈 #135). **푸시가 없는 채로 승인이 마지막 사건인 PR은 이걸로도
안 낫는다** — 그때는 아무 커밋이나 한 번 밀면 맞춰진다. 트리거를 **줄이지 않는다**:
`review_requested`·`review_request_removed`를 빼면 위의 다섯 번째 결함이 그대로 돌아온다.

로그인→이름 표도 이 워크플로 안에 있다. 팀원이 바뀌면 `.github/CODEOWNERS`와 같이 고친다.

**가드의 빨강은 두 가지다 — 문면으로 가른다(이슈 #180).** `failure` 는 *"승인이 부족하다"*,
`error` 는 *"판정을 못 했다"* 다. API 조회가 실패하면 예전에는 잡이 죽어 **상태 줄이 직전 값
그대로** 섰다 — 갱신 실패가 화면에 아무 흔적도 안 남았고, 방향이 반대였으면 낡은 초록이 머지를
열어 뒀을 것이다. 지금은 집계와 기록이 스텝으로 갈려 있어 집계가 죽어도 줄은 반드시 갱신된다.
`error` 를 보면 승인을 더 받으러 갈 게 아니라 **워크플로를 다시 돌린다.** 라벨 쪽에는 그런 세
번째 값이 없으므로(붙였다 떼는 것으로만 말한다), 판정이 끝까지 못 가면 **라벨은 아예 손대지
않는다** — 라벨을 다 떼는 것은 *"전원 승인했다"* 로 읽혀서 정확히 반대 뜻이 된다. 둘이
어긋나 보이면 참인 쪽은 커밋 상태다.

## 설계 결정 기록

`docs/adr/`에 있다. 코드가 왜 이런지의 근거이므로, 관련 코드를 고치기 전에 해당 ADR을 읽는다.
**결정이 바뀌면 새 ADR을 추가하고 기존 문서는 상태만 갱신한다 — 삭제·수정하지 않는다.**

ADR은 원칙급 결정만 담는다. PR·이슈 스레드에서 합의된 계약·규약·배선 결정은
[`docs/decision-log.md`](docs/decision-log.md)에 전수로 모여 있다 — 자기 영역을 건드리기 전에
해당 절을 먼저 본다. 남은 미결이 누구 몫이고 언제까지인지도 그 문서 10절에 있다.

**두 문서를 갱신하는 절차는 `decision-sweep` 스킬**(`.claude/skills/decision-sweep/`)에 있다.
워터마크 시각 이후에 오간 말을 전부 뽑고(`sweep.sh`), 형식을 검증한다(`check.sh`).
규칙 자체는 스킬에 없다 — `decision-log.md`의 `갱신 규칙`·`ADR 과의 관계`와 이 파일이 근거이고,
스킬은 절차와 함정만 담는다. 두 벌이 되면 갈린다.

## 알려진 문서 불일치

스캐폴드가 Python(FastAPI) 서버에서 Spring으로 전환되며 남은 흔적들이다. 이 값을 신뢰하지 말 것.

- `contracts/README.md`가 `server/app/models/`의 pydantic 모델을 언급한다 — 실제로는 Java
  `domain/` 레코드다.
- `docs/functional-spec-v1.1.md`는 v1.1 위에 「강제 지점」을 붙인 색인이다(테스트가 읽는다 — 구조 유지).
  **구현 완료 시점의 명세는 `docs/functional-spec-v1.2.md`** 이고, v1.1 조항 단위 근거는
  `functional-spec-v1.1-original.md` 를 본다.
