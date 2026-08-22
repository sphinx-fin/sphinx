# 기능 명세서 v1.1

소유: 정세현.

> **전사 미완 — 이 파일을 명세서로 인용하지 말 것.**
> 아래는 레포에서 검증 가능한 조각과 확인된 인용문만 모아둔 것이다. 본문은 원본
> 문서(텍스트 전용 v1.1)에서 옮겨와야 한다. 그때까지 근거가 필요하면 원본을 봐야 한다.

## 확인된 인용

**0.4절** — 역이용 방지 요건

> 이해도 데이터 접근 권한을 부여할 수 있는 역할 자체를 만들지 않는 것이 역이용 방지
> 요건의 구현이다.

구현: [ADR-0001](adr/0001-영업조직-역할-부재-원칙.md), `security/Role.java`,
`resources/rbac_policy.yaml`

## 설계 원칙 (P1~P3)

- **P1** AI는 측정, 룰은 결정. `ai-service`의 출력이 게이트 판정·금액 계산에 직접 쓰이면 안 된다.
- **P2** 시뮬레이터·게이트는 결정론적 순수 함수 + 단위 테스트 필수.
- **P3** 고객 텍스트가 `ai-service`로 나가는 유일한 경로는 `PiiGateway.mask()` → `AiServiceClient`.
  세션 생성 입력에 성명·주민번호 필드는 존재하지 않는다.

## 기능ID 색인 (코드에서 확인된 것만)

| 기능ID | 내용 | 구현 위치 |
|---|---|---|
| F-EXT-001 | 문서 파싱 | `ai-service` (정세현) |
| F-EXT-002 | 위험 항목 추출 | `ai-service` (윤지석) |
| F-INT-001 | 세션 생성·상태머신 | `core/SessionFsm`, `domain/SessionState` |
| F-INT-002 | 질문 생성 | `ai-service` (윤지석) |
| F-INT-003 | 응답 수집(입력 메타 포함) | `api/SessionController` |
| F-GTE-001 | 게이트 룰 엔진 | `core/GateEngine`, `resources/gate_rules.yaml` |
| F-GTE-002 | 적색 오버라이드 | `api/OverrideController` — [ADR-0002](adr/0002-적색-오버라이드-승인-주체.md) |
| F-GTE-004 | 이해 기록 리포트 | `evidence/ReportService` |
| F-SIM-001 | 손익 시뮬레이터 | `simulator/SimulatorService` |
| F-DSH-001 | 오해 지도 | `aggregate/AggregateService` |
| F-DSH-002 | 선행지표 | `aggregate/AggregateService` |
| F-CMN-001 | PII 마스킹 게이트웨이 | `core/PiiGateway` |
| F-CMN-002 | 접근 통제·감사 로그 | `security/`, `evidence/AuditLog` |

화면: S-01 ~ S-08 (`web/`, 오준서). S-05 = 판정 결과.

TODO(정세현): 원본 v1.1 본문 전사. 특히 F-GTE-003이 코드에 없는데 원본에 있는지 확인 필요.
