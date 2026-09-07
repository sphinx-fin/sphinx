"""자기일관성 캡(`#370`)이 **얼마나 자주 발동하는지** 잰다.

`#370` 이 `R-05`(`anyConfidenceBelow 0.7`)를 「한 번도 안 도는 룰」에서 도는 룰로 만들었다.
그 PR 본문은 캡 **값**의 근거를 `#327` 실세션 지표로 미뤄 뒀는데, **발동률**은 그보다
먼저 알아야 하는 값이다 — 게이트 `R-06` 이 `allGrade U1` 이므로 **항목 하나만 캡을 맞아도
세션이 GREEN 에서 YELLOW 로 내려간다.**

## 무엇을 재는가 — 모집단을 문면에 적는다

    발화    eval/corpus/els.jsonl 70쌍  (키는 (sample_id, item_id) 짝이다)
    경로    scoring.score() — **프로덕션과 같은 함수**다. 평가용 경로를 따로 만들지 않는다
    분모    그 실행에서 모델이 U1 을 낸 항목 수. 캡은 U1 에만 걸린다(CONSISTENCY_GRADES)
    분자    그중 confidence 가 DISAGREEMENT_CONFIDENCE_CAP 으로 내려온 항목 수

❗**실 LLM 을 호출한다.** 비용을 짐작하지 않는다 — `scoring.METER`(`ConsistencyMeter`)가
던진 횟수·쓴 횟수·**버린 횟수**를 세고 있으므로 그 값을 그대로 찍는다. `#447` 이 재질의를
병렬화하면서 **등급을 보기 전에 투기적으로 던지게** 됐고, 그래서 통과가 아닌 답변에서는
그 호출이 버려진다. 「U1 에만 걸리니 두 배는 아니다」는 그 전의 셈이다.
테스트가 아니다 — `tests/` 에 두지 않는 이유가 그것이다.

❗❗**한 번 돌린 값을 인용하지 않는다. 이 검사 자체가 비결정적이다.**

    2026-09-04   2/19 = 10.5%   캡: els-0018 · els-0014
    2026-09-07 A  0/24 =  0.0%   캡: (없음)
    2026-09-07 B  2/25 =  8.0%   캡: els-0061 · els-0050

같은 코드·같은 코퍼스인데 **A 와 B 가 갈렸고, 걸린 건이 한 건도 안 겹친다.** 당연하다 —
이 검사가 재는 것이 모델의 비결정성이고, 그 비결정성이 *어느 항목에서* 드러날지는
회차마다 다르다. 그러니 한 번 돌려 `0%` 를 보고 *"안 걸린다"* 로 읽으면 안 된다.

**여러 번 돌려 합산하고 구간을 같이 낸다.** 오늘 두 실행 합산은 `2/49 = 4.1%`
(Wilson 95% `[1.1%, 13.7%]`)이고, 13항목 세션이 YELLOW 로 갈 확률로 옮기면
`14% ~ 42% ~ 85%` 다. **점추정만 인용하지 않는다.**

## 이 도구가 대답하지 않는 것

캡이 **옳은지**는 안 잰다. 2026-09-04 실측에서 갈린 두 건은 **둘 다 U1↔U2** 였고, 그건
사람 라벨러 불일치 19건 중 9건(47%)이 몰린 경계다 — 즉 캡이 *없던 노이즈를 만드는 게
아니라 있던 노이즈를 드러낸다*는 정성적 근거다. 그 판단은 `tools/MISCONCEPTION-DETECTION.md`
쪽 논의이고 여기서는 **빈도만** 낸다.
"""

from __future__ import annotations

import collections
import json
import math
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from app import scoring  # noqa: E402
from app.schemas import RiskItem  # noqa: E402

EVAL = pathlib.Path(__file__).resolve().parents[2] / "eval"


def _jsonl(path: pathlib.Path) -> list[dict]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def wilson(hits: int, n: int, z: float = 1.96) -> tuple[float, float]:
    """Wilson 점수 구간. **정규근사(Wald)를 쓰지 않는다** — n 이 작고 비율이 0 에 가까우면
    하한이 음수로 나와 *"발동률 -3%"* 같은 값을 인용하게 된다."""
    if n == 0:
        return (0.0, 0.0)
    p = hits / n
    denom = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return (max(0.0, centre - half), min(1.0, centre + half))


def main() -> None:
    context = json.loads((EVAL / "data/context/els.json").read_text(encoding="utf-8"))
    corpus = _jsonl(EVAL / "corpus/els.jsonl")

    grades: collections.Counter[str] = collections.Counter()
    capped: list[str] = []
    first_u1 = failures = 0

    for row in corpus:
        item = context.get("risk_items", {}).get(row["item_id"])
        question = context.get("questions", {}).get(row["item_id"])
        if not (item and question):
            continue
        try:
            judgment = scoring.score(
                item_id=row["item_id"], question=question, answer_text=row["utterance"],
                risk_item=RiskItem(**item), product_type=row["product_type"],
            )
        except Exception as exc:  # noqa: BLE001 — 한 건 실패가 측정을 멈추면 안 된다
            failures += 1
            print(f"  실패 {row['sample_id']}: {type(exc).__name__}", file=sys.stderr)
            continue
        grades[judgment.grade.value] += 1
        # 진행을 stderr 로 낸다 — 90회 호출이라 몇 분 걸리고, 조용하면 죽은 것과
        # 도는 것을 못 가른다. stdout 은 결과만 담아 그대로 인용할 수 있게 둔다.
        print(f"  {sum(grades.values()):3} {row['sample_id']:10} {judgment.grade.value} "
              f"conf={judgment.confidence}", file=sys.stderr, flush=True)
        if judgment.grade.value in scoring.CONSISTENCY_GRADES:
            first_u1 += 1
            if judgment.confidence == scoring.DISAGREEMENT_CONFIDENCE_CAP:
                capped.append(row["sample_id"])

    print(f"\n채점 {sum(grades.values())}건 · 실패 {failures}건 · 등급 {dict(grades)}")
    lo, hi = wilson(len(capped), first_u1)
    rate = len(capped) / first_u1 if first_u1 else 0.0
    print(f"재확인 대상({'/'.join(scoring.CONSISTENCY_GRADES)}) {first_u1}건 · "
          f"캡 {len(capped)}건 = {rate:.1%}  Wilson 95% [{lo:.1%}, {hi:.1%}]")
    print(f"캡 걸린 것: {capped}")

    # ❗**비용을 짐작하지 않는다.** `#447` 이후 재질의는 등급을 보기 전에 던져지므로
    # 「U1 몇 건」으로 호출 수를 셀 수 없다. `discarded` 가 그 투기의 대가다.
    meter = scoring.METER.snapshot()
    print(f"\n재질의 계량기 {meter}")
    thrown = meter["speculated"] + meter["needed"] - meter["used"] if meter["speculated"] else meter["needed"]
    print(f"  던진 것 중 버린 비율  {meter['discarded']}/{max(meter['speculated'], 1)}"
          f" = {meter['discarded'] / max(meter['speculated'], 1):.0%}   ← 이만큼이 쿼터 낭비다")
    if meter["failed"]:
        print(f"  ❗재질의 호출이 {meter['failed']}건 죽었다 — 그만큼은 «캡 안 걸림»이 아니라 «못 쟀음»이다")
    if meter["no_slot"] or meter["disabled"]:
        print(f"  순차로 떨어진 것: 자리 없음 {meter['no_slot']} · 스위치 꺼짐 {meter['disabled']}")

    # ❗**세션 단위로 옮겨 적는다.** R-06 이 allGrade U1 이므로 항목 하나만 캡을 맞아도
    # 세션이 GREEN 을 놓친다. 항목당 비율만 보면 이 크기가 안 보인다.
    items_per_session = len(context.get("risk_items", {}))
    print(f"\n전부 U1 인 {items_per_session}항목 세션이 YELLOW 로 갈 확률")
    for label, q in (("하한", lo), ("점추정", rate), ("상한", hi)):
        print(f"  {label:4} {1 - (1 - q) ** items_per_session:.0%}")
    print("❗점추정만 인용하지 않는다 — 위 셋을 같이 적는다.")
    print("❗그리고 **한 번 돌린 값을 인용하지 않는다** — 이 검사는 비결정적이라 실행마다")
    print("   걸리는 항목이 다르다(머리말의 세 회차 참조). 여러 번 돌려 합산한다.")


if __name__ == "__main__":
    main()
