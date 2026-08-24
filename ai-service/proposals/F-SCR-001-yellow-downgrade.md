# 황색 강등을 어디서 할 것인가 (P1 경계)

작성: 윤지석 (F-SCR-001 R) · 결정 필요: 강희진 (F-GTE-001 R, 계약 소유)
상태: **제안.** 현재는 ai-service에서 임시로 적용 중이다.

## 문제

기획서 5절: *"애매하면 '이해'가 아니라 '부분이해'로 내려 재설명을 트리거. 재설명은 차단이
아니므로 보수적 설정의 부작용이 작다."*

이걸 구현하려면 `confidence < 0.7`일 때 판정을 황색으로 내려야 한다. 그런데 **임계값 0.7과
그 적용 지점이 지금 세 곳에 흩어져 있다.**

| 위치 | 상태 |
|---|---|
| `ai-service/app/scoring.py` `CONFIDENCE_FLOOR = 0.7` | **구현됨** (임시) |
| `server/.../application.yml` `sphinx.scoring.confidence-threshold: 0.7` | **선언만 됨** — 읽는 코드 없음 |
| `server/.../gate_rules.yaml` | **없음** — confidence를 보는 룰이 없다 |

`application.yml`의 주석은 `# F-SCR-001: 미만 시 황색 강등 (U4 제외)`다. 즉 강희진도 이
정책을 인지하고 자리를 잡아뒀으나 소비자가 아직 없다. 같은 정책 숫자가 두 곳에 살아 있는
상태이며, 이건 `CanonicalJson`을 두 벌 만들지 말라는 CLAUDE.md의 경고와 같은 종류의 위험이다.

## GateEngine 실측 (a7014e7)

```java
record Context(List<Grade> grades, boolean suitabilityMismatch, int reverifyFailed) {}
//              ^ "평가 컨텍스트: 룰이 참조하는 값의 전부"

public GateResult judge(List<Judgment> judgments, boolean suitabilityMismatch, int reverifyFailed) {
    List<Grade> grades = judgments.stream().map(Judgment::grade).toList();  // confidence 버려짐
```

- `Judgment`는 confidence를 **들고 들어오지만** `grade`만 추출되고 버려진다.
- `compile()`이 허용하는 조건은 4형태뿐이다: `suitabilityMismatch == true`,
  `reverifyFailed >= N`, `anyGrade [in] ...`, `allGrade == ...`.
  알 수 없는 조건은 **로드 시점 예외**(fail-fast).
- 평가는 파일 순서대로 **first-match-wins**, 미매칭 시 fail-closed RED.

즉 **현재 게이트는 신뢰도를 볼 수 없다.** 룰을 추가하려면 `Context`와 `compile()`에 손이 가야 한다.

## 선택지 3개

### A. ai-service에서 grade를 변환 (현재)

`confidence < 0.7`이고 `U1`이면 `U2`를 내보낸다. R-04가 U2를 YELLOW로 받는다.

- ✅ 오늘 동작한다. Spring 변경 0.
- ❌ **P1 위반 소지.** 내 출력은 *측정값*이어야 하는데 정책 결정이 섞인다. 모델은 U1이라
  했는데 구조화 필드에는 U2가 남는다(`reason`에 문구를 남겨 보완하고 있으나 필드는 거짓).
- ❌ **정책 숫자가 감사 표면 밖에 있다.** 0.7을 바꾸면 ai-service PR에만 남는다.
- ❌ **F-CMN-003 지표 해석이 오염된다.** 라벨러가 U1, 모델도 U1인데 출력이 U2면 불일치로
  집계된다. QWK가 모델 품질이 아니라 정책 효과를 반영한다.
- ❌ U4 예외를 `if`문으로 따로 써야 한다.

### B. Spring scoring 계층에서 적용

`/internal/score` 응답을 받아 `GateEngine.judge()`로 넘기기 전에 강등한다.
`sphinx.scoring.confidence-threshold`가 이미 그 자리를 가정하고 있다.

- ✅ 내 출력은 순수 측정값으로 남는다. 감사 로그에 모델이 실제로 낸 값이 보존된다.
- ✅ 임계값이 `application.yml`에 있어 설정 변경으로 추적된다.
- ⚠️ U4 예외를 코드로 명시해야 한다(`application.yml` 주석이 이미 "(U4 제외)"라 적고 있다).
- ⚠️ 정책이 룰 파일이 아니라 서비스 코드에 있다.

### C. gate_rules.yaml에 룰 추가 ← **권고**

`Context`에 최소 신뢰도를 넣고 룰 하나를 추가한다.

```java
// GateEngine.judge()
double minConfidence = judgments.stream()
        .mapToDouble(Judgment::confidence).min().orElse(1.0);
Context ctx = new Context(grades, suitabilityMismatch, reverifyFailed, minConfidence);

// compile()에 분기 하나
Matcher conf = Pattern.compile("minConfidence\\s*<\\s*([0-9.]+)").matcher(e);
if (conf.matches()) {
    double t = Double.parseDouble(conf.group(1));
    return ctx -> ctx.minConfidence() < t;
}
```

```yaml
# gate_rules.yaml — R-01~R-03(RED) 뒤, R-05(GREEN) 앞
  - id: R-06
    if: "minConfidence < 0.7"
    then: YELLOW
```

- ✅ **P1을 정확히 지킨다.** 나는 측정(grade + confidence), 룰이 결정.
- ✅ **정책이 팀이 지정한 감사 표면에 있다.** GateEngine docstring: *"룰 변경은 그 파일의
  PR로만 이뤄지고 감사 대상이 된다."* 0.7은 정책 숫자이므로 여기 있어야 한다.
- ✅ **U4 예외가 공짜다.** first-match-wins라 R-01(`anyGrade == 'U4'` → RED)이 먼저 발화하고
  R-06에 도달하지 않는다. A·B가 특수 케이스로 처리하는 것이 룰 순서의 자연스러운 결과가 된다.
  → 기획서 5절의 오해→이해 오판 상한 1%가 룰 순서로 보장된다.
- ✅ F-CMN-003이 모델의 원본 grade를 평가한다. 정책 효과는 게이트 트레이스(`R-06`)로 분리 관측된다.
- ⚠️ `Context`·`compile()` 변경 필요(약 10줄) + 테스트 2케이스.

### 판정표 (C 채택 시)

| 상황 | 발화 룰 | 신호 |
|---|---|---|
| U4 있음, 신뢰도 낮음 | R-01 | RED (완화되지 않음 — P5) |
| 전부 U1, 신뢰도 0.4 | R-06 | YELLOW |
| 전부 U1, 신뢰도 0.95 | R-05 | GREEN |
| U2 있음 | R-04 | YELLOW |

## 요청

- [ ] **A / B / C 중 택일.** C를 권고한다. 근거는 감사 표면과 U4 예외 소거다.
- [ ] C를 택하면 `Context`·`compile()`·`gate_rules.yaml` 변경은 강희진 영역이다.
      내 쪽에서는 `DOWNGRADE_IN_AI_SERVICE = False` 한 줄로 끝난다.
- [ ] 어느 쪽이든 **0.7이 두 곳에 있는 상태를 끝내야 한다.** 지금 `scoring.py`와
      `application.yml`에 같은 숫자가 산다.

## 그 전까지

`scoring.DOWNGRADE_IN_AI_SERVICE = True`로 A를 유지한다. 끄면 강등이 아예 사라져 기능
후퇴가 되므로, 결정이 날 때까지는 켜 둔다. 강등 시 `reason`에 사실을 기록한다.

## 참고: 실측에서 이 장치는 거의 발동하지 않는다

`gemini-3.5-flash-lite`로 애매한 발화를 넣어도 confidence가 0.80 미만으로 내려오지 않았다
("잘 모르겠어요" → U3 conf 1.00, "뭐 떨어지면 좀 손해가 나는 거 아닌가요" → U2 conf 0.80).
모델이 *등급 배정에 대한 확신*을 보고하고, 모호성은 등급 자체를 U2·U3로 낮추는 방식으로
표현한다. 기획서가 원하는 결과는 나오지만 **안전망은 비어 있다.**
→ 프롬프트 v2에서 confidence의 정의를 "발화의 모호성"으로 바꿔 재측정할 후보다.
임계값 자체의 튜닝은 F-CMN-003 지표를 보고 결정해야 하며, 나는 라벨링에서 배제된 사람이다.
