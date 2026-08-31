"""채점 성능 지표 — QWK · 혼동행렬 · 미탐율 · 평가자 간 일치도. 소유: 정세현 (F-CMN-003)

여기에는 **계산만** 있다. 파일 읽기·출력·정책 판단은 ``run_eval.py`` 가 한다. 가른 이유는
이 함수들이 검증 가능한 순수 함수여야 해서다 — 아래 값들은 심사에서 인용될 숫자이고,
"어떻게 나왔는지"에 답하려면 계산이 입출력과 섞여 있으면 안 된다.

의존성이 없다(표준 라이브러리만). numpy·scikit-learn 을 쓰면 한 줄이지만, 이 레포의
``eval/`` 에는 아직 의존성 파일도 테스트 러너도 없어서(CLAUDE.md) 설치 없이 도는 것이
낫다고 봤다. 대신 아래 계산이 맞다는 것은 ``tests/`` 가 **손으로 검산 가능한 예제**로
고정한다.
"""

from __future__ import annotations

from collections import Counter
from typing import Iterable, Sequence

# ── 등급은 순서가 있는 값이다 ──────────────────────────────────────────────────
#
# ❗**항상 이 네 개를 이 순서로 고정한다.** 관측된 등급에서 인덱스를 만들면 안 된다 —
# 이유가 둘인데, 그중 하나만 카파 값에 영향을 준다.
#
#   1. **순서가 곧 거리다.** 가중 카파가 인덱스 차이를 제곱해서 벌점으로 쓰므로,
#      `set()` 순회처럼 순서가 보장되지 않는 것으로 인덱스를 만들면 U4→U1 과 U4→U3 의
#      벌점이 뒤바뀐다. 값이 조용히 틀리고, 틀린 방향이 하필 **미탐을 싸게 만드는 쪽**일
#      수 있다
#   2. **행렬 모양이 회차마다 같아야 한다.** 아무도 U3 를 안 쓴 회차에서 행렬이 3×3 이
#      되면 리포트끼리 겹쳐 볼 수 없고, "U3 가 0건이었다" 는 사실이 **빈 행이 아니라
#      행의 부재**로 나타나서 눈에 안 띈다
#
# ❗카파 **값** 자체는 등급 하나가 관측되지 않아도 안 바뀐다 — 그 행·열의 관측도 기대도
# 0 이라 분자·분모 어디에도 안 들어가고, 거리 정규화(`/span`)는 분자와 분모에 같이 걸려
# 약분된다. 즉 위 2번은 값이 아니라 **읽는 사람**을 위한 것이다. 헷갈리기 쉬운 자리라
# 적어 둔다(`tests/test_metrics.py` 가 이 사실을 단정한다).
#
# contracts/judgment.schema.json 의 enum 과 같은 순서다.
GRADES: tuple[str, ...] = ("U1", "U2", "U3", "U4")  # 이해 · 부분이해 · 미이해 · 오해

#: 미탐으로 세는 조합. 정답이 U4(오해)인데 모델이 "이해했다" 쪽으로 읽은 경우다.
#: U3(미이해)는 포함하지 않는다 — 게이트가 U3 도 통과시키지 않으므로 판매가 막힌다.
MISS_PREDICTIONS: frozenset[str] = frozenset({"U1", "U2"})


class EvalError(ValueError):
    """입력이 지표를 계산할 수 있는 모양이 아니다."""


def _check(labels: Sequence[str], who: str) -> None:
    bad = sorted({g for g in labels if g not in GRADES})
    if bad:
        raise EvalError(f"{who}: 등급 enum 밖의 값이다: {bad} (허용: {list(GRADES)})")


def confusion(gold: Sequence[str], pred: Sequence[str]) -> list[list[int]]:
    """``confusion[i][j]`` = 정답이 ``GRADES[i]`` 인데 예측이 ``GRADES[j]`` 인 건수.

    행이 정답, 열이 예측이다. 뒤집으면 미탐과 과탐이 바뀌므로 방향을 여기 적어 둔다.
    """
    if len(gold) != len(pred):
        raise EvalError(f"길이가 다르다: 정답 {len(gold)} · 예측 {len(pred)}")
    _check(gold, "정답")
    _check(pred, "예측")

    index = {g: i for i, g in enumerate(GRADES)}
    matrix = [[0] * len(GRADES) for _ in GRADES]
    for g, p in zip(gold, pred):
        matrix[index[g]][index[p]] += 1
    return matrix


def weighted_kappa(gold: Sequence[str], pred: Sequence[str], *, weights: str = "quadratic") -> float:
    """Cohen 의 가중 카파. 기본은 quadratic(QWK) 이다.

    ❗**가중을 쓰는 이유가 이 지표의 전부다.** 등급이 순서값이라 ``U4`` 를 ``U1`` 로 읽은
    것과 ``U4`` 를 ``U3`` 로 읽은 것은 같은 오류가 아니다. 가중 없는 카파는 둘을 똑같이
    한 건으로 세고, 그러면 **제일 위험한 오류가 제일 흔한 오류에 묻힌다.**

    quadratic 은 거리의 제곱을 벌점으로 쓴다(1칸=1/9, 3칸=9/9). linear 도 받는 이유는
    표본이 작을 때 quadratic 이 극단값 하나에 크게 흔들려서, 둘을 같이 보면 그 흔들림이
    보이기 때문이다.

    :return: -1.0 ~ 1.0. 완전 일치 1.0, 우연 수준 0.0
    """
    if weights not in ("quadratic", "linear"):
        raise EvalError(f"weights 는 quadratic|linear 다: {weights!r}")
    n = len(gold)
    if n == 0:
        raise EvalError("표본이 0건이다 — 카파를 정의할 수 없다")

    observed = confusion(gold, pred)
    k = len(GRADES)
    span = k - 1

    gold_marginal = [sum(row) for row in observed]
    pred_marginal = [sum(observed[i][j] for i in range(k)) for j in range(k)]

    num = den = 0.0
    for i in range(k):
        for j in range(k):
            d = abs(i - j)
            w = (d / span) ** 2 if weights == "quadratic" else d / span
            expected = gold_marginal[i] * pred_marginal[j] / n
            num += w * observed[i][j]
            den += w * expected

    if den == 0:
        # 두 채점자가 **같은 한 등급만** 썼다. 우연 일치도가 100% 라 카파가 정의되지 않는다.
        # 0.0 이나 1.0 을 지어내지 않는다 — 어느 쪽도 사실이 아니고, 특히 1.0 은
        # "완벽하다"로 읽혀서 표본이 쓸모없다는 사실을 감춘다.
        raise EvalError(
            "두 채점 결과가 모두 한 등급뿐이라 카파가 정의되지 않는다 "
            "(우연 일치도 100%). 표본에 다른 등급이 섞여야 한다"
        )
    return 1.0 - num / den


def miss_rate(gold: Sequence[str], pred: Sequence[str]) -> tuple[float, int, int]:
    """미탐율 — 정답이 U4(오해)인데 모델이 U1·U2 로 읽은 비율.

    ❗**카파에 접어 넣지 않고 따로 낸다.** 미탐은 게이트가 위험을 그냥 통과시키는 경우라
    다른 오류와 성격이 다르고, 표본에서 U4 가 소수면 **카파가 높은 채로 미탐율이 나쁠 수
    있다.** 그 조합이 정확히 이 시스템에서 제일 나쁜 상태다 — 숫자 하나로 합치면 안 보인다.

    :return: (비율, 미탐 건수, U4 정답 건수). U4 가 0건이면 비율은 ``float('nan')``
    """
    _check(gold, "정답")
    _check(pred, "예측")
    total = sum(1 for g in gold if g == "U4")
    missed = sum(1 for g, p in zip(gold, pred) if g == "U4" and p in MISS_PREDICTIONS)
    if total == 0:
        return float("nan"), 0, 0
    return missed / total, missed, total


def agreement_rate(a: Sequence[str], b: Sequence[str]) -> float:
    """단순 일치율. 카파와 **같이** 본다 — 한쪽만으로는 오해를 만든다.

    등급 분포가 치우치면 일치율은 높은데 카파는 낮다(우연히 맞을 확률이 높아서다).
    두 값이 벌어지는 것 자체가 "표본이 한 등급에 쏠렸다"는 신호라 둘 다 찍는다.
    """
    if len(a) != len(b):
        raise EvalError(f"길이가 다르다: {len(a)} · {len(b)}")
    if not a:
        raise EvalError("표본이 0건이다")
    return sum(1 for x, y in zip(a, b) if x == y) / len(a)


def distribution(labels: Iterable[str]) -> dict[str, int]:
    """등급 분포. 표본이 한쪽으로 쏠렸는지를 보는 값이라 리포트에 항상 같이 찍는다."""
    counts = Counter(labels)
    return {g: counts.get(g, 0) for g in GRADES}
