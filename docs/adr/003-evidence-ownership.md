# ADR-003. F-CMN-002를 정세현이 소유하고 `evidence` 공통 기반을 공유한다

- **상태**: Accepted
- **일자**: 2026-08-22
- **결정자**: 정세현 / 협의: 강희진
- **관련**: F-CMN-002, F-GTE-004, 역할 분담표 v1.2

## 맥락

F-CMN-002(접근 통제·감사 로그)는 원래 강희진 소유였고 F-GTE-004(이해 기록 리포트)는 정세현
소유였다. 그런데 두 기능의 저장 요구가 동일하다 — **append-only · 해시 체인 · 정규화 직렬화**.

분리 소유하면 정규화와 해시 체인이 두 벌 생긴다. 미묘하게 다른 두 정규화는 리포트 해시와 감사
로그 해시의 교차 검증을 불가능하게 만들고, 이 결함은 감사 시점까지 드러나지 않는다.

## 결정

**F-CMN-002를 정세현이 소유한다.** 두 기능은 `evidence` 패키지의 공통 기반을 공유한다.

```
evidence/
├── CanonicalJson    정규화 직렬화 (RFC 8785 기준)
├── HashChain        prev_hash → hash, GENESIS 상수
├── ImmutableStore   append-only (stream별 체인 분리)
├── ReportService    F-GTE-004
└── AuditLog         F-CMN-002 감사 로그
```

해시·직렬화는 이 세 클래스만 사용한다. `ReportService`나 `AuditLog`가 직접 직렬화하거나 해시를
만들면 결정의 목적이 사라진다.

## F-CMN-002 내부 경계 (강희진과의 분리)

F-CMN-002는 두 덩어리다. 감사 로그는 온전히 정세현이지만, 권한 체크는 필터·컨트롤러
어노테이션으로 붙어 강희진의 API 레이어와 물리적으로 겹친다. 파일 단위로 나눈다.

| 담당 | 파일 |
|---|---|
| 정세현 | `security/Role.java`, `security/AccessPolicy.java`, `resources/rbac_policy.yaml`, `evidence/AuditLog.java` |
| 강희진 | `security/SecurityConfig.java`, 컨트롤러 `@PreAuthorize`, `AuditInterceptor` 등록 |

정책은 `rbac_policy.yaml`에만 두고 Java 상수로 중복 정의하지 않는다. 강희진의 어노테이션은
action 이름만 참조한다. 감사 로그는 컨트롤러가 아니라 `AuditInterceptor` 단일 통로로 기록한다 —
컨트롤러마다 호출하면 감사 관심사가 `api/`에 흩어져 소유권이 다시 겹친다.

## 결과

- 해시 체인과 정규화 직렬화를 한 번만 제대로 만든다.
- 리포트 해시와 감사 로그 해시를 같은 규칙으로 교차 검증할 수 있다.
- 3주차에 같은 파일에서 만나는 일이 없다.

> 이 문서는 역할 분담표 v1.2의 ADR 후보 3번 항목을 근거로 작성했다. 결정자 협의 내용에
> 추가할 맥락이 있으면 보완이 필요하다.
