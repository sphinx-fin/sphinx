# 기능 명세서 v1.1

소유: 정세현. 텍스트 전용 — 음성 입력은 범위에서 제외.

> **본문 전사 미완 — 이 파일을 명세서로 인용하지 말 것.**
> 아래는 확인된 인용문과 다른 문서에서 유도한 색인이다. 본문은 원본 v1.1에서 옮겨와야 한다.
> 그때까지 조항 단위 근거가 필요하면 원본을 봐야 한다.

## 확인된 인용

**0.4절** — 역이용 방지 요건

> 이해도 데이터 접근 권한을 부여할 수 있는 역할 자체를 만들지 않는 것이 역이용 방지
> 요건의 구현이다.

구현: [ADR-001](adr/001-no-sales-role.md), `security/Role.java`, `resources/rbac_policy.yaml`

## 설계 원칙 (P1~P3)

- **P1** AI는 측정, 룰은 결정. `ai-service`의 출력이 게이트 판정·금액 계산에 직접 쓰이면 안 된다.
- **P2** 시뮬레이터·게이트는 결정론적 순수 함수 + 단위 테스트 필수.
- **P3** 고객 텍스트가 `ai-service`로 나가는 유일한 경로는 `PiiGateway.mask()` → `AiServiceClient`.
  세션 생성 입력에 성명·주민번호 필드는 존재하지 않는다.

## 기능ID 색인

출처: [역할 분담표 v1.2](role-assignment-v1.2.md). 구현 위치는 현재 레포 기준.

| 기능ID | 기능 | R | 구현 위치 |
|---|---|---|---|
| F-EXT-001 | 상품문서 업로드·파싱 (데모 2종 타임박스) | 정세현 | `ai-service` |
| F-EXT-002 | 필수 이해항목 추출 (LLM) | 윤지석 | `ai-service` |
| F-EXT-003 | 추출 결과 검증 (공시문서 대조) | 정세현 | `eval/` |
| F-INT-001 | 세션 생성·상태머신 | 강희진 | `core/SessionFsm`, `domain/SessionState` |
| F-INT-002 | 되말하기 질문 생성 (LLM) | 윤지석 | `ai-service` |
| F-INT-003 | 응답 수집 (텍스트) | 오준서 | `web/`, `api/SessionController` |
| F-INT-004 | 재설명·재검증 루프 | 강희진 | 미구현 |
| F-SCR-001 | 항목별 이해도 채점 (LLM) | 윤지석 | `ai-service` |
| F-DET-001 | 오해 탐지 (라이브러리+유사도) | 윤지석 | `ai-service`, 데이터는 `data/` |
| F-DET-002 | 적합성 모순 탐지 | 윤지석 | `ai-service` |
| F-SIM-001 | 역사 시나리오 손실 계산 | 정세현 | `simulator/SimulatorService` |
| F-GTE-001 | 게이트 판정 (룰 엔진) | 강희진 | `core/GateEngine`, `resources/gate_rules.yaml` |
| F-GTE-002 | 적색 오버라이드 | 강희진 | `api/OverrideController` — [ADR-002](adr/002-override-approver.md) |
| F-GTE-003 | 불공정영업 신호 알림 | 강희진 | 미구현 (정책: `signal:unfair:read` = COMPL 전용) |
| F-GTE-004 | 이해 기록 리포트 | 정세현 | `evidence/ReportService` — [ADR-003](adr/003-evidence-ownership.md) |
| F-DSH-001 | 오해 지도 히트맵 | 오준서 | `web/`, 집계는 `aggregate/AggregateService` |
| F-DSH-002 | 선행지표 뷰 | 오준서 | `web/`, 집계는 `aggregate/AggregateService` |
| F-DSH-003 | 합성 세션 생성기 | 정세현 | 미구현 |
| F-CMN-001 | PII 마스킹 게이트웨이 | 강희진 | `core/PiiGateway` |
| F-CMN-002 | 접근 통제·감사 로그 | 정세현 | `security/`, `evidence/AuditLog` — [ADR-001](adr/001-no-sales-role.md) |
| F-CMN-003 | 채점 성능 평가 파이프라인 | 정세현 | `eval/` |

화면 S-01 ~ S-08 (`web/`, 오준서). S-03 인터뷰, S-05 판정 결과, S-06 적색 승인.

TODO(정세현): 원본 v1.1 본문 전사.

## 원본과 어긋나는 것 — F-EXT-003 기능명

원본 v1.1 과 기획서는 이 기능을 **"핵심설명서 대조"** 로 부른다. 그 문서는 **확보할 수
없다** — 공시 문서가 아니라 「보험업감독규정」§7-45 에 따라 계약 체결 권유 단계에서
계약자에게 **교부**하는 서류다(게시가 아니다).

  생명보험협회 공시실      "핵심설명서" 검색 → 0건
  삼성생명 보험상품목록    상품요약서 · 사업방법서 · 보험약관
  삼성생명 변액보험공시    변액운용설명서

그래서 이 색인은 **"공시문서 대조"** 로 적는다. 실제 정답지는 상품유형별 문서 집합이다.

  ELS   간이투자설명서
  변액  상품요약서 + 운용설명서 (어느 한쪽만으로는 이해항목 10종을 못 덮는다)

**원본 PDF 를 갱신하면 이 절을 지우고 본문 전사로 대체한다.** 근거는 PR #32.
