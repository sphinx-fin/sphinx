# docs (소유: 정세현)

- `proposal.md` — 2026 금융 AI Challenge 기획서 **정본**. 조항 단위 근거로 인용한다
  (7-4 역이용 방지, 5절 채점 성능 목표와 오판 처리, 7-3 재위탁 등).
  **제출용 PDF는 이 파일에서 만든다** — 내용이 바뀌면 여기를 고친 뒤 PDF를 다시 뽑는다.
  처음에는 원본 PDF(8쪽)의 전사본이었고, 그 PDF는 이제 이력이며 근거가 아니다.
  아직 다시 뽑지 않아 PDF와 어긋나는 지점은 파일 헤더에 적어 뒀다.
- `functional-spec-v1.1.md` — 기능 명세서 (텍스트 전용, 음성 입력 제외) — **본문 전사 미완**
- `role-assignment-v1.2.md` — 기능ID 단위 역할 분담표 (F-CMN-002 정세현 이관, F-EXT-001 범위 축소 반영)
- `adr/` — 설계 결정 기록. 코드가 왜 이런지의 근거. 결정이 바뀌면 새 ADR을 추가하고
  기존 문서는 상태만 갱신한다(삭제·수정 금지).
- `decision-log.md` — **PR·이슈에서 확정된 것 전수 색인.** ADR 은 원칙급 결정만 담으므로,
  스레드에서 합의됐지만 ADR 급이 아닌 계약·규약·배선 결정은 여기 모은다. 자기 영역에서
  무엇이 확정됐는지 한 곳에서 보고, 남은 미결이 누구 몫인지도 여기에 있다.
- 제출용 기획서 PDF 최신본도 여기에 함께 보관한다(`proposal.md`에서 뽑은 결과물).

## 구현 범위의 기준 문서

`proposal.md`는 공모전 제출 문면이고, **구현 범위의 기준은 기능 명세서 v1.1**이다. 두 문서가
어긋나면 v1.1을 따른다.

| 항목 | 기획서 | 기능 명세서 v1.1 | 적용 |
|---|---|---|---|
| 되말하기 입력 수단 | 음성 + 텍스트 (3절 대상 채널, 4절 되말하기 인터뷰, 7-1 MVP 범위) | 텍스트 전용, 음성 입력 제외 | **v1.1 — 텍스트 전용** |

따라서 **음성 되말하기·실시간 음성인식·통화 기반 채널(TM·GA)은 MVP 범위 밖**이며 구현·데모
대상이 아니다. `.env.example`의 `AUDIO_RETENTION_MONTHS`도 같은 이유로 쓰지 않는다.

`proposal.md`에서도 해당 문면을 v1.1 기준으로 정리했다 — 3절·4절은 텍스트 응답으로 고치고,
통화 기반 TM·GA 채널 항목은 삭제했다. `proposal.md`가 정본이므로 **음성 관련 조항도 이 파일을
근거로 쓴다.** 예전 PDF는 이 지점에서 다르지만 그건 이력이다. 6절 유사 시도 비교의 타사 AI
음성봇 언급과 시장 확대 방향의 TM·GA는 MVP 범위 얘기가 아니므로 건드리지 않았다.

## ADR 목록

| 번호 | 결정 | 상태 |
|---|---|---|
| [001](adr/001-no-sales-role.md) | 역할 정의에 영업·마케팅 조직 역할을 두지 않는다 | Accepted |
| [002](adr/002-override-approver.md) | 적색 오버라이드 승인자는 지점 관리자(MGR)를 유지한다 | Accepted (한계 인지) |
| [003](adr/003-evidence-ownership.md) | F-CMN-002를 정세현이 소유하고 `evidence` 공통 기반을 공유한다 | Accepted |
| [004](adr/004-evidence-append-contract.md) | `evidence` 적재는 세션 트랜잭션에 묶고, 중복을 흡수하지 않는다 | Accepted |
| [005](adr/005-threshold-ownership.md) | 판정을 바꾸는 임계값은 게이트가 소유하고, 측정 자신감은 ai-service 가 소유한다 | Accepted |
| [006](adr/006-item-id-canonical-form.md) | `item_id` 정본은 `_expected_risk_items` 긴 형식이다 | Accepted |
| [007](adr/007-extraction-answer-set.md) | F-EXT-003 정답지는 공시문서 집합이다 — 핵심설명서는 확보 불가 | Accepted |
| [008](adr/008-canonical-json.md) | `CanonicalJson` 은 RFC 8785 를 따르고, 정규화는 직렬화기가 하지 않는다 | Accepted |
