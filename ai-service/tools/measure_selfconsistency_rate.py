"""자기일관성 캡(`#370`)이 **얼마나 자주 발동하는지** 잰다.

`#370` 이 `R-05`(`anyConfidenceBelow 0.7`)를 「한 번도 안 도는 룰」에서 도는 룰로 만들었다.
그 PR 본문은 캡 **값**의 근거를 `#327` 실세션 지표로 미뤄 뒀는데, **발동률**은 그보다
먼저 알아야 하는 값이다 — 게이트 `R-06` 이 `allGrade U1` 이므로 **항목 하나만 캡을 맞아도
세션이 GREEN 에서 YELLOW 로 내려간다.**

## 무엇을 재는가 — 모집단을 문면에 적는다

    발화    eval/corpus/els.jsonl 70쌍  (키는 (sample_id, item_id) 짝이다)
    경로    scoring.score() — **프로덕션과 같은 함수**다. 평가용 경로를 따로 만들지 않는다
    분모    `METER.needed` — **실제로 재질의한 건수.** 최종 U1 을 세면 안 된다(복창 캡이
            이미 걸린 U1 은 두 번째 호출 없이 조기 반환하므로 재확인 대상이 아니다)
    분자    `METER.disagreed` — **캡 사건.** `confidence == CAP` 비교를 쓰면 모델이 그냥
            0.5 를 낸 판정까지 문다
    세션 분모  `interview_items()` = `required` (컨텍스트 전체가 아니다 — 아래)

❗**분모 둘은 계량기가 들고 도구가 유도하지 않는다**(`#533` 리뷰, 오준서). 위 넷 중
셋이 원래는 도구가 계산한 프록시였고 세 개가 다 조금씩 틀렸다.

❗**세션 분모의 출처는 평가 컨텍스트다 — 실세션은 추출 스냅샷이다**(`#533` 리뷰, 강희진).
이 도구는 `eval/data/context/els.json` 을 상품의 대리물로 쓴다. 실세션의 분모는
`ProductRiskItems.requiredItemsOf`(추출 스냅샷)라 **재추출로 항목이 늘면 컨텍스트만 낡는다.**
그때 볼 자리가 여기다.

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
(Wilson 95% `[1.1%, 13.7%]`)이고, **`required` 10항목** 세션이 YELLOW 로 갈 확률로
옮기면 `11% ~ 34% ~ 77%` 다. **점추정만 인용하지 않는다.**

❗**그 셋은 신뢰구간이 아니라 민감도다** — Wilson 상·하한을 `1-(1-q)^n` 에 통과시킨
값이므로 *"q 가 그 값이면 세션 확률이 이렇다"* 를 뜻한다(`#533` 리뷰, 강희진).

❗**세션 확률의 분모는 「세션에 판정이 쌓이는 항목」이다.** `R-06` 이 보는 것은 판정이고
판정은 면담이 물은 항목에만 생기는데, 그 집합이 `required ∧ extracted` 다. 컨텍스트의
13건을 그대로 쓰면 `recommended` 3건까지 세어 확률이 과대해진다(`#533` 리뷰, 강희진).

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
import os
import pathlib
import sys
import time
from datetime import datetime, timezone

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from app import config, scoring  # noqa: E402
from app.config import settings  # noqa: E402
from app.schemas import RiskItem  # noqa: E402

EVAL = pathlib.Path(__file__).resolve().parents[2] / "eval"

#: 행 사이 간격. `eval/tools/run_scoring.py` 의 `PACE_SEC` 와 같은 자리다.
#:
#: ❗**여기는 행마다 투기 프로브까지 붙어 ~140 요청**이 응답 속도만큼 빠르게 나간다.
#: 429·타임아웃이 몰리면 그 행들이 실패로 빠지고 **줄어든 n 위에서 Wilson 구간까지**
#: 계산된 수치가 인쇄된다. 그쪽 주석이 그 상태를 실제로 만들어 본 기록을 적어 뒀다
#: (*"처음에 0.5 로 뒀다가 실제로 그 상태를 만들었다(28/40)"*). `#533` 리뷰, 오준서.
PACE_SEC = float(os.getenv("SPHINX_PACE_SEC", "1.0"))


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
        # ❗**관측이 0 이면 구간은 [0, 1] 이다.** `(0.0, 0.0)` 을 내면 아무것도 못 잰
        #   실행이 «확실히 0%» 를 보증한다 — 이 도구가 막겠다고 쓴 오독을 도구가
        #   스스로 승인한다(`#533` 리뷰, 오준서).
        return (0.0, 1.0)
    p = hits / n
    denom = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return (max(0.0, centre - half), min(1.0, centre + half))


def interview_items(risk_items: dict) -> dict:
    """세션에 **판정이 쌓이는** 항목 — `R-06` 의 분모다.

    `ProductRiskItems.interviewItemsOf` 와 같은 규칙이다(required ∧ extracted). 여기서는
    `status` 를 안 본다 — eval 컨텍스트는 추출이 끝난 스냅샷이라 전부 `extracted` 다.
    실세션에서 추출 실패 항목이 있으면 분모가 더 줄고, 그때는 이 값이 **상한**이 된다.

    ❗함수로 빼 둔 이유는 이 규칙이 조용히 낡기 때문이다. `len(risk_items)` 로 쓰면
    `recommended` 까지 세어 세션 확률이 과대해지는데(42% ↔ 34%) **숫자가 그럴듯해서
    안 보인다**(`#533` 리뷰, 강희진).
    """
    return {k: v for k, v in risk_items.items() if v.get("importance") == "required"}


def preflight() -> str:
    """돌리기 전에 **못 쓸 실행인지** 가른다 (`#533` 리뷰, 오준서).

    형제 도구가 전부 이렇게 막는다 — `eval/tools/run_scoring.py` 는 키가 없거나 모델
    정책 밖이면 `1` 을 돌려준다. 여기서 안 막으면 **정책 모델이 아닌 숫자**나 **전부
    실패한 실행**이 깔끔한 백분율로 인쇄되고 종료코드는 0 이다.
    """
    cfg = settings()
    if not cfg.llm_configured:
        raise SystemExit("❗LLM_API_KEY 가 없다 — 이 실행의 결과는 수치로 못 쓴다")
    if cfg.llm_model != config.DEFAULT_MODEL:
        raise SystemExit(
            f"❗모델 정책 밖이다: {cfg.llm_model} (정책 {config.DEFAULT_MODEL}) — "
            "이 실행의 결과는 수치로 못 쓴다"
        )
    return (f"{cfg.llm_model} · 프롬프트 {scoring.PROMPT_VERSION} · "
            f"병렬재질의 {'on' if settings().parallel_consistency else 'off'} · "
            f"{datetime.now(timezone.utc).isoformat(timespec='seconds')}")


def main() -> int:
    # ⑫ **판을 출력에 싣는다.** 이 숫자를 이슈에 붙여 넣은 뒤 프롬프트가 올라가면,
    #    그 블록만으로는 무엇이 만든 값인지 알 수 없다(`#409` 가 model.jsonl 에 판을
    #    찍는 것과 같은 이유).
    provenance = preflight()
    print(f"판 {provenance}")
    # ⑪ **상품유형으로 컨텍스트를 고른다** — `run_scoring.py:107` 과 같은 규약이다.
    #    ELS 를 박아 두면 `variable.jsonl` 이 생겨도 이 도구만 통째로 무시한다.
    contexts = {path.stem.upper(): json.loads(path.read_text(encoding="utf-8"))
                for path in sorted((EVAL / "data/context").glob("*.json"))}
    if not contexts:
        raise SystemExit(f"컨텍스트가 없다: {EVAL / 'data/context'}")
    corpus = [row for path in sorted((EVAL / "corpus").glob("*.jsonl"))
              for row in _jsonl(path)]

    grades: collections.Counter[str] = collections.Counter()
    capped: list[str] = []
    failures: list[str] = []
    skipped: list[str] = []
    # ★ **어느 상품유형을 실제로 쟀는지 센다.** 세션 분모가 여기서 나온다 — 예전에는
    #   루프 변수 `context` 가 새어 나가 **마지막 행의 컨텍스트**로 분모를 냈다. ELS 와
    #   변액이 섞이면 분모가 **코퍼스 행 순서에 따라** 달라졌고, 코퍼스가 비면
    #   `UnboundLocalError` 로 **리포트를 절반 찍고 죽었다**(`#533` 리뷰가 형제 도구에서
    #   지적한 «절반 찍고 죽는 자리» 와 같은 부류다).
    scored_types: collections.Counter[str] = collections.Counter()

    for row in corpus:
        key = f"{row['sample_id']}/{row['item_id']}"      # ⑦ (sample_id, item_id) 짝이다
        context = contexts.get(row["product_type"])
        if context is None:
            skipped.append(f"{key} ({row['product_type']} 컨텍스트가 없다)")
            continue
        item = context.get("risk_items", {}).get(row["item_id"])
        question = context.get("questions", {}).get(row["item_id"])
        if not (item and question):
            # ❗**조용히 버리지 않는다.** 표본이 왜 줄었는지가 남아야 한다.
            skipped.append(f"{key} (컨텍스트에 {'항목' if not item else '질문'} 없음)")
            continue
        try:
            judgment = scoring.score(
                item_id=row["item_id"], question=question, answer_text=row["utterance"],
                risk_item=RiskItem(**item), product_type=row["product_type"],
            )
        except Exception as exc:  # noqa: BLE001 — 한 건 실패가 측정을 멈추면 안 된다
            # ❗**문면을 버리지 않는다.** `LlmError` 하나가 429·401·타임아웃을 다 덮어서,
            #   이름만 남기면 운영자가 어느 쪽인지 모른다(`#533` 리뷰 · run_scoring 관례).
            failures.append(f"{key}: {type(exc).__name__}: {exc}")
            print(f"  실패 {key}: {type(exc).__name__}: {exc}", file=sys.stderr)
            continue
        grades[judgment.grade.value] += 1
        scored_types[row["product_type"]] += 1
        time.sleep(PACE_SEC)
        # 진행을 stderr 로 낸다 — 90회 호출이라 몇 분 걸리고, 조용하면 죽은 것과
        # 도는 것을 못 가른다. stdout 은 결과만 담아 그대로 인용할 수 있게 둔다.
        print(f"  {sum(grades.values()):3} {row['sample_id']:10} {judgment.grade.value} "
              f"conf={judgment.confidence}", file=sys.stderr, flush=True)
        if (judgment.grade.value in scoring.CONSISTENCY_GRADES
                and judgment.confidence == scoring.DISAGREEMENT_CONFIDENCE_CAP):
            # ❗**이 목록은 되짚기용이지 분자가 아니다.** 분자는 계량기가 센다 —
            #   이 비교는 모델이 그냥 0.5 를 낸 판정까지 문다(`#533` 리뷰, 오준서).
            capped.append(key)

    # ⑥ ❗**프로브가 다 끝난 뒤에 읽는다.** `_discard_probe` 는 기다리지 않고
    #    done-callback 으로 나중에 `discarded_failed` 를 올린다 — 먼저 읽으면 0 을 찍고
    #    그 다음 프로세스가 종료에서 스레드를 조인하느라 멈춘다(숫자 뒤의 멈춤).
    scoring._PROBE_POOL.shutdown(wait=True)
    meter = scoring.METER.snapshot()
    print(f"\n채점 {sum(grades.values())}건 · 등급 {dict(grades)}")
    if skipped:
        print(f"❗건너뜀 {len(skipped)}건 — {skipped}")
    if failures:
        print(f"❗실패 {len(failures)}건 — 표본이 그만큼 줄었다")
        for line in failures:
            print(f"   {line}")

    # ❗**분모·분자를 계량기에서 읽는다. 프록시를 계산하지 않는다** (`#533` 리뷰, 오준서).
    #
    #   분모  최종 U1 이 아니라 `needed`(**실제로 재질의한 건수**)다. 복창 캡이 이미 걸린
    #         U1 은 `cap_confidence_if_inconsistent` 가 두 번째 호출 없이 조기 반환하므로
    #         「재확인 대상」이 아니다.
    #   분자  `confidence == CAP` 비교가 아니라 `disagreed`(**캡 사건**)다. 그 비교는
    #         모델이 그냥 0.5 를 낸 판정까지 문다.
    asked, disagreed = meter["needed"], meter["disagreed"]
    lo, hi = wilson(disagreed, asked)
    rate = disagreed / asked if asked else 0.0
    print(f"\n재질의한 항목 {asked}건 · 등급이 갈린 것 {disagreed}건 = {rate:.1%}"
          f"  Wilson 95% [{lo:.1%}, {hi:.1%}]")
    if asked == 0:
        print("❗재질의가 한 번도 안 돌았다 — 위 구간은 「모른다」이지 「0%」가 아니다.")
    print(f"되짚을 항목: {capped or '없음'}")

    print(f"\n재질의 계량기 {meter}")
    if meter["speculated"]:
        # ❗**「투기 호출 중」이다 — 「던진 것 중」이 아니다** (`#533` 리뷰, 강희진).
        # 분모가 `speculated` 인데 「던진 것」은 순차 재질의(`no_slot`·`disabled` 일 때
        # `_ask_again` 을 그대로 던진다)까지 포함해 읽힌다. 그 둘이 0 이 아닌 회차에서는
        # 실제 쿼터 낭비 비율이 이보다 **낮다** — 옛 이름은 과대 쪽이었다.
        print(f"  투기 호출 중 버린 비율  {meter['discarded']}/{meter['speculated']}"
              f" = {meter['discarded'] / meter['speculated']:.0%}   ← 이만큼이 쿼터 낭비다")
        if meter["no_slot"] or meter["disabled"]:
            print(f"   (순차로 던진 것이 따로 있다 — 자리 없음 {meter['no_slot']} ·"
                  f" 스위치 꺼짐 {meter['disabled']}. 전체 호출 대비 낭비율은 이보다 낮다)")
    else:
        # ❗**0/0 을 0% 로 찍지 않는다.** 투기가 안 돈 것이지 「낭비 0%」를 잰 것이 아니다.
        print("  투기 호출 중 버린 비율  **측정 안 됨** — 투기 재질의가 안 돌았다"
              f" (자리 없음 {meter['no_slot']} · 스위치 꺼짐 {meter['disabled']})")
    if meter["failed"]:
        print(f"  ❗필요했던 재질의가 {meter['failed']}건 죽었다 — 그만큼은 「캡 안 걸림」이"
              " 아니라 「못 쟀음」이다")
    if meter["discarded_failed"]:
        print(f"  버릴 프로브가 {meter['discarded_failed']}건 죽었다 — **판정에 영향 없다**")

    # ❗**세션 단위로 옮겨 적는다.** R-06 이 allGrade U1 이므로 항목 하나만 캡을 맞아도
    # 세션이 GREEN 을 놓친다. 항목당 비율만 보면 이 크기가 안 보인다.
    #
    # ❗**분모는 «세션에 판정이 쌓이는 항목» 이다 — 컨텍스트의 항목 수가 아니다**
    # (`#533` 리뷰, 강희진). R-06 이 보는 것은 판정이고, 판정은 면담이 물은 항목에만
    # 생긴다. 그 집합이 `ProductRiskItems.interviewItemsOf` = required ∧ extracted 라
    # **`recommended` 는 애초에 안 물어지고 판정도 없다.** 컨텍스트 13건을 그대로 쓰면
    # 세션 확률이 과대해진다(42% → 34%). 게이트의 분모와 맞춘다.
    if not scored_types:
        # ❗**분모가 없으면 세션 확률을 안 찍는다.** 예전에는 여기서 죽어 리포트가
        #   절반만 나왔고, 그 절반이 완전한 리포트로 읽혔다.
        print("\n❗세션 확률을 못 낸다 — 채점된 행이 0건이라 분모가 없다.")
    for product_type, n_rows in sorted(scored_types.items()):
        items = contexts[product_type].get("risk_items", {})
        required = interview_items(items)
        items_per_session = len(required)
        print(f"\n[{product_type}] 전부 U1 인 {items_per_session}항목 세션이 YELLOW 로 갈 확률"
              f"  (이 유형에서 {n_rows}행 쟀다)")
        print(f"  (분모는 required {len(required)}건 — 컨텍스트 전체 {len(items)}건이 아니다."
              f" recommended 는 안 물어지므로 판정이 없다)")
        for label, q in (("하한", lo), ("점추정", rate), ("상한", hi)):
            print(f"  {label:4} {1 - (1 - q) ** items_per_session:.0%}")
        # ❗**비율 q 는 유형별로 안 갈랐다.** 위 세 값은 전체 합산 q 를 이 유형의 분모에
        #   얹은 것이다 — 유형마다 q 를 따로 내려면 계량기를 유형별로 나눠야 한다.
        if len(scored_types) > 1:
            print("   (q 는 전체 합산값이다 — 유형별 q 가 필요하면 계량기를 유형별로 나눈다)")
    if scored_types:
        print("❗점추정만 인용하지 않는다 — 위 셋을 같이 적는다.")
    # ❗**구간이 아니라 민감도다** (`#533` 리뷰, 강희진). Wilson 상·하한을
    # `1-(1-q)^n` 에 통과시킨 것은 세션 확률의 신뢰구간이 아니라 *"q 가 그 값이면"* 의
    # 범위다. 문면에 적어 둔다 — 안 적으면 다음 사람이 이 셋을 구간으로 재인용한다.
        print("   (이 셋은 세션 확률의 신뢰구간이 **아니다** — q 가 그 값일 때의 민감도다)")
    # ★ **여기 `return 0` 이 있었다** — 아래 두 줄이 한 번도 출력되지 않았다.
    # 이 도구의 제목이 「한 번 돌린 값은 못 쓴다」인데 그 경고가 리포트에 안 실렸다.
    # `thrown` 과 같은 부류(`#533` 리뷰, 강희진)이고 린터가 없어 아무것도 안 말해 준다.
    print("❗그리고 **한 번 돌린 값을 인용하지 않는다** — 이 검사는 비결정적이라 실행마다")
    print("   걸리는 항목이 다르다(머리말의 세 회차 참조). 여러 번 돌려 합산한다.")
    print(f"❗인용할 때 이 줄을 같이 옮긴다 — 판 {provenance}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
