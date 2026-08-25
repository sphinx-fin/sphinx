# contracts (디렉토리 소유: 강희진)

레포 전체의 단일 진실. 여기 스키마가 곧 팀 간 계약이다.

| 파일 | 계약 소유 | 공급 → 수요 |
|---|---|---|
| `parsed_document.schema.json` | **정세현** | F-EXT-001 출력 (정세현 → 윤지석) |
| `risk_item.schema.json` | 강희진 | F-EXT-002 출력 (윤지석 → 강희진·정세현·오준서) |
| `judgment.schema.json` | 강희진 | F-SCR-001 출력 (윤지석 → 강희진·오준서) |
| `suitability_mismatch.schema.json` | 강희진 | F-DET-002 출력 (윤지석 → 강희진) |
| `openapi.yaml` | 강희진 | REST API 전체 (강희진 → 오준서) |

디렉토리는 강희진 소유지만 **계약별 소유자는 다르다** (역할 분담표 v1.2 §4). 파일을 고칠 때는
계약 소유자 승인이 필요하고, 디렉토리에 파일을 추가·삭제할 때는 강희진 승인이 필요하다.

변경 절차: PR + 계약 소유자 승인 + 수요자 전원 멘션. Java `domain/` 레코드는 이 스키마와
1:1로 유지한다.

## `suitability_mismatch` — `mismatch=false`를 '적합'으로 읽지 말 것 (주의)

`status`가 `insufficient_input`이면 `mismatch`는 항상 `false`다. **판정하지 못한 것이지
모순이 없는 것이 아니다.** 스키마가 두 필드를 나눠 둔 이유가 그것이다.

게이트는 지금 `mismatch` 불리언 하나만 읽는다(`gate_rules.yaml` R-02의
`suitabilityMismatch`). 즉 **판정 실패가 통과로 읽힌다.** 소비 측이 `status`를 함께 보도록
고치기 전까지 이 격차는 남아 있다 — 게이트 입력을 바꾸는 일이라 룰 변경(감사 대상)을
동반한다.

## `samples/` — 계약이 아니라 픽스처

파서·추출 구현 전에 하위 공정을 착수할 수 있게 만든 수동 샘플이다. 스키마 검증을 통과하며,
`parse_warnings`에 `MANUAL_OVERRIDE`로 사람이 만든 것임을 표시한다. 파서가 완성되면 실제
출력으로 대체한다.

`_expected_risk_items`는 **계약에 포함되지 않는다**(`_` 접두어). F-EXT-002가 이 문서에서
뽑아야 할 정답과 그 원문 스팬을 적어둔 것으로, 프롬프트 개발과 F-EXT-003 정답 세트의
출발점으로 쓴다.

## source_span 규약 (주의)

`RiskItem.condition.source_span`의 `start`/`end`는 **해당 페이지의 `pages[].text`에 대한
페이지 상대 오프셋**이며 반열린 구간 `[start, end)`다. 문서 전역 오프셋이 아니다.

따라서 `pages[page].text[start:end] == condition.value_text`가 항등식으로 성립해야 한다.
F-EXT-002의 원문 스팬 검증 후처리는 이 등식으로 검사한다. 오프셋 기준이 어긋나면 추출은
성공하는데 화면(S-01·S-05)에서 하이라이트가 밀린다.

전제: `pages[].text`는 유니코드 NFC 정규화 상태이고 공백·개행을 임의로 접지 않는다.
표 안의 텍스트도 읽기 순서대로 `pages[].text`에 포함되므로, 모든 스팬은 `tables[]`를 보지
않고 해소된다.
