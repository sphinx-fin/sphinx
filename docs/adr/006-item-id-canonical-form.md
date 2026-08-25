# ADR-006. `item_id` 정본은 `contracts/samples` 의 `_expected_risk_items` 긴 형식이다

- **상태**: Accepted
- **일자**: 2026-08-24
- **결정자**: 정세현·강희진·윤지석 (3자) / 관련: PR #10, PR #16
- **관련**: F-EXT-001, F-EXT-002, F-EXT-003, F-SCR-001

## 맥락

같은 이해항목을 두 이름으로 부르고 있었다.

```
짧은 형식   ELS-PRINCIPAL-LOSS            contracts/risk_item.schema.json 예시,
                                          MockData.java, SessionController.java,
                                          ai-service/app/rubrics/*.yaml
긴 형식     ELS-PRINCIPAL-LOSS-WARNING    contracts/samples/*.json 의 _expected_risk_items
                                          (실파서 출력)
```

**계약 스키마 자신의 예시가 짧은 형식이었다.** 그러니 루브릭이 그 이름을 쓴 것이 근거 없는
선택이 아니었고, 어느 쪽이 정본인지 정하지 않으면 루브릭을 어느 이름으로 고칠지도 정할 수 없었다.

그리고 이 어긋남은 **예외도 로그도 없이 빗나간다.** 루브릭 `item_id` 가 계약 항목과 다르면
매칭이 조용히 실패하고 해당 항목 채점이 아예 안 된다.

## 결정

**긴 형식(`_expected_risk_items`)을 정본으로 한다.**

처음 제시한 근거는 "실문서에서 실제로 추출된 항목이고 F-EXT-003 재현율·정밀도가 이 목록을
기준으로 산출된다"였다. 그런데 윤지석이 더 강한 근거를 댔고 그쪽이 실질이다 —
**계약 쪽 분해가 실제로 더 정확하다.**

`ELS-PRINCIPAL-LOSS` 하나에 세 개념이 뭉쳐 있었다.

```
ELS-PRINCIPAL-LOSS-WARNING     p1 원금손실 고지
ELS-KNOCKIN-BARRIER            낙인 45%
ELS-MATURITY-LOSS-CONDITION    만기 70%
```

뭉쳐두면 **낙인만 말하고 만기 조건을 빼먹은 답변이 U1 로 통과한다.** 항목을 쪼개면 그 문제가
해소된다. 짧은 형식을 정본으로 하면 그 문제가 남는다.

같은 이유로 `VAR-EARLY-SURRENDER` 는 이름 변경이 아니라 **분리**였다. 루브릭의
`required_elements` 두 줄이 계약 항목 둘에 1:1 대응했다.

| `required_elements` | 계약 `item_id` |
|---|---|
| 조기 해지 시 해지환급금이 납입 보험료를 크게 밑돌 수 있음 | `VAR-SURRENDER-BELOW-PREMIUM` |
| 초기에는 월공제액 차감으로 환급률이 특히 낮음 | `VAR-EARLY-SURRENDER-RATIO` |

합쳐두면 "환급률이 낮다"는 일반론만 요구하고 실문서 p12 의 수치(3개월 58.4%, 20년 43.9%)를
못 짚는 답변이 통과한다.

## 이 결정이 실제로 사고를 막았다

루브릭 이름만 바꾸고 조건 원문을 그대로 뒀더니 `UNDERSTOOD-BASELINE` 테스트가 U1 → U2 로
떨어졌다. p1 고지 항목에 낙인/만기 조건 문면을 물려놨기 때문이다. 항목 분해가 왜 필요한지
그대로 보여주는 사례다.

## 고친 곳 (3자 분담)

| 담당 | 파일 |
|---|---|
| 윤지석 | 루브릭 7종을 계약 이름으로 재정렬 (`ELS-PRINCIPAL-LOSS.yaml` → `-WARNING` 등) |
| 강희진 | `contracts/risk_item.schema.json` 예시, `MockData.java`, `SessionController.java` |
| 정세현 | `contracts/parsed_document.schema.json` 의 `page_count` description |

## 결과

- 항목 이름의 유일한 근거가 `contracts/samples/*.json` 이 됐다. 실파서 출력이므로 문서가
  바뀌면 재생성으로 따라간다.
- 새 항목을 만들 때는 **뭉치지 말고 쪼갠다.** 뭉친 항목은 느슨한 답변을 통과시킨다.
- 앞으로 이 종류의 조용한 실패를 막기 위해 로딩 시점 검사를 늘리는 방향이다
  (`assert_products_are_canonical()` 이 같은 패턴의 선례).
