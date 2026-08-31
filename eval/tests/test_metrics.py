"""지표 계산 검산. 소유: 정세현 (F-CMN-003)

❗**기대값을 손으로 계산할 수 있는 예제만 쓴다.** 라이브러리 출력을 그대로 기대값에 박으면
"우리 구현이 우리 구현과 같다"만 확인하게 된다 — 이 파일이 지키려는 것은 심사에서 인용할
숫자가 실제로 맞다는 것이므로, 각 단정 위에 **계산 과정을 적어 둔다.**

돌리기: ``cd eval && python -m pytest tests -q`` (CI 미배선 — PR 본문 참조)
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from metrics import (  # noqa: E402
    GRADES,
    EvalError,
    agreement_rate,
    confusion,
    distribution,
    miss_breakdown,
    miss_rate,
    weighted_kappa,
)


class TestGradesMatchTheContract:
    """❗`GRADES` 는 계약을 **옮겨 적은 것**이다 — 대조가 없으면 갈려도 아무도 말하지 않는다.

    갈리면 조용히 벌점이 뒤바뀐다. `metrics.py` 머리말이 스스로 *"틀린 방향이 하필 미탐을
    싸게 만드는 쪽일 수 있다"* 고 적어 둔 그 경우다. (PR #225 리뷰, 오준서)
    """

    def test_grades_match_the_contract_enum_including_order(self):
        schema = json.loads(
            (Path(__file__).resolve().parents[2] / "contracts" / "judgment.schema.json")
            .read_text(encoding="utf-8")
        )
        assert tuple(schema["properties"]["grade"]["enum"]) == GRADES, (
            "계약의 등급 enum 과 갈렸다. **순서까지 같아야 한다** — 순서가 곧 거리이고, "
            "가중 카파가 그 인덱스 차이를 제곱해 벌점으로 쓴다"
        )


class TestWeightedKappa:
    def test_perfect_agreement_is_one(self):
        labels = ["U1", "U2", "U3", "U4"]
        assert weighted_kappa(labels, labels) == pytest.approx(1.0)

    def test_chance_level_is_zero(self):
        # gold [U1,U1,U4,U4] · pred [U1,U4,U1,U4]
        #   관측  [0][0]=1 [0][3]=1 [3][0]=1 [3][3]=1
        #   주변  gold=[2,0,0,2] pred=[2,0,0,2] n=4
        #   분자  w(0,3)·1 + w(3,0)·1 = 1 + 1 = 2          (w = (거리/3)^2, 3칸이면 1.0)
        #   기대  [0][3]=2·2/4=1 [3][0]=1  → 분모 = 1 + 1 = 2
        #   κ = 1 − 2/2 = 0
        assert weighted_kappa(["U1", "U1", "U4", "U4"], ["U1", "U4", "U1", "U4"]) == pytest.approx(0.0)

    def test_hand_computed_half(self):
        # gold [U1,U1,U1,U4] · pred [U1,U1,U4,U4]
        #   관측  [0][0]=2 [0][3]=1 [3][3]=1
        #   주변  gold=[3,0,0,1] pred=[2,0,0,2] n=4
        #   분자  w(0,3)·1 = 1
        #   기대  [0][3]=3·2/4=1.5 [3][0]=1·2/4=0.5 → 분모 = 1·1.5 + 1·0.5 = 2
        #   κ = 1 − 1/2 = 0.5
        assert weighted_kappa(["U1", "U1", "U1", "U4"], ["U1", "U1", "U4", "U4"]) == pytest.approx(0.5)

    def test_quadratic_punishes_distant_errors_harder_than_linear(self):
        """❗가중을 쓰는 이유가 이것이다 — 없으면 제일 위험한 오류가 흔한 오류에 묻힌다."""
        # 3칸 오류 하나 vs 1칸 오류 하나. 같은 "1건 틀림" 인데 벌점이 달라야 한다.
        far = weighted_kappa(["U1", "U1", "U1", "U4"], ["U1", "U1", "U1", "U1"])
        near = weighted_kappa(["U1", "U1", "U1", "U4"], ["U1", "U1", "U1", "U3"])
        assert far < near, "U4 를 U1 으로 읽은 쪽이 U3 로 읽은 쪽보다 나쁘게 나와야 한다"

    def test_single_grade_everywhere_is_refused_not_faked(self):
        """❗0.0 이나 1.0 을 지어내지 않는다 — 1.0 은 '완벽하다'로 읽힌다."""
        with pytest.raises(EvalError, match="한 등급뿐"):
            weighted_kappa(["U1", "U1", "U1"], ["U1", "U1", "U1"])

    def test_empty_sample_is_refused(self):
        with pytest.raises(EvalError, match="0건"):
            weighted_kappa([], [])

    def test_length_mismatch_is_refused(self):
        with pytest.raises(EvalError, match="길이"):
            weighted_kappa(["U1", "U2"], ["U1"])

    def test_grade_outside_enum_is_refused(self):
        with pytest.raises(EvalError, match="enum"):
            weighted_kappa(["U1", "U5"], ["U1", "U1"])

    def test_unobserved_grade_does_not_change_the_value(self):
        """❗등급 하나가 안 쓰여도 카파 값은 안 바뀐다 — GRADES 고정은 값이 아니라 모양 때문이다.

        같은 모양의 오류를 인접 등급(U1↔U2)으로 옮겨도 값이 같다. 거리 정규화가 분자와
        분모에 같이 걸려 약분되기 때문이다. metrics.py 의 주석이 이 사실을 근거로 삼는다.
        """
        far = weighted_kappa(["U1", "U1", "U1", "U4"], ["U1", "U1", "U4", "U4"])
        near = weighted_kappa(["U1", "U1", "U1", "U2"], ["U1", "U1", "U2", "U2"])
        assert far == pytest.approx(near) == pytest.approx(0.5)


class TestMissRate:
    def test_anything_that_is_not_u4_counts_as_missed(self):
        """❗게이트가 가르는 자리에 선을 긋는다 — U2 와 U3 는 같은 분기다(R-04).

        예전에는 `{U1, U2}` 만 셌다. U2 를 세고 U3 를 안 세는 것은 게이트가 구별하지
        않는 자리에 선을 그은 것이라, 미탐율이 실제보다 낮게 나왔다(PR #229 리뷰, 강희진).
        """
        rate, missed, total = miss_rate(["U4", "U4", "U4", "U1"], ["U1", "U2", "U3", "U1"])
        assert (missed, total) == (3, 3), "U3 도 RED 를 못 내므로 미탐이다"
        assert rate == pytest.approx(1.0)

    def test_u4_read_as_u4_is_not_missed(self):
        rate, missed, total = miss_rate(["U4", "U4"], ["U4", "U1"])
        assert (missed, total) == (1, 2) and rate == pytest.approx(0.5)

    def test_breakdown_splits_by_gate_outcome(self):
        """통과(GREEN 가능)와 강등(YELLOW)은 대가가 다르므로 나눠 센다."""
        b = miss_breakdown(["U4"] * 5 + ["U1"], ["U1", "U1", "U2", "U3", "U4", "U1"])
        assert b == {"passes": 2, "downgrades": 2, "caught": 1}

    def test_breakdown_checks_length(self):
        with pytest.raises(EvalError, match="길이"):
            miss_breakdown(["U4", "U4"], ["U1"])

    def test_no_u4_in_gold_is_nan_not_zero(self):
        """❗U4 가 없으면 0.0 이 아니라 NaN 이다 — 0.0 은 '미탐이 없었다'로 읽힌다."""
        rate, missed, total = miss_rate(["U1", "U2"], ["U1", "U2"])
        assert total == 0 and missed == 0
        assert math.isnan(rate)

    def test_high_kappa_can_coexist_with_bad_miss_rate(self):
        """❗**이 조합이 이 시스템에서 제일 나쁜 상태다** — 그래서 카파에 접어 넣지 않는다.

        목표선이 QWK 0.75 인데(eval/README.md), 아래 표본은 **0.73 으로 거의 목표에 닿으면서
        오해의 30% 를 그냥 통과시킨다.** 숫자 하나만 보고 있으면 이 상태가 안 보인다.
        """
        gold = ["U1"] * 10 + ["U2"] * 10 + ["U3"] * 10 + ["U4"] * 10
        pred = ["U1"] * 10 + ["U2"] * 10 + ["U3"] * 10 + ["U1"] * 3 + ["U4"] * 7

        rate, missed, total = miss_rate(gold, pred)
        assert (missed, total) == (3, 10)
        assert rate == pytest.approx(0.3)

        #   관측  대각선 10·10·10·7 + [3][0]=3
        #   주변  gold=[10,10,10,10] pred=[13,10,10,7] n=40
        #   분자  w(3,0)·3 = 1·3 = 3
        #   분모  (10/40)·Σ_j pred[j]·Σ_i w(i,j)
        #         Σ_i w(i,·) = [14/9, 6/9, 6/9, 14/9]
        #         = 0.25/9 · (13·14 + 10·6 + 10·6 + 7·14) = 0.25/9 · 400 = 100/9
        #   κ = 1 − 3/(100/9) = 1 − 0.27 = 0.73
        assert weighted_kappa(gold, pred) == pytest.approx(0.73)


class TestConfusionAndDistribution:
    def test_rows_are_gold_columns_are_prediction(self):
        m = confusion(["U4"], ["U1"])
        assert m[GRADES.index("U4")][GRADES.index("U1")] == 1
        assert m[GRADES.index("U1")][GRADES.index("U4")] == 0, "행/열이 뒤집혔다"

    def test_matrix_is_always_four_by_four(self):
        m = confusion(["U1", "U1"], ["U1", "U1"])
        assert len(m) == 4 and all(len(r) == 4 for r in m)

    def test_distribution_reports_zero_for_unused_grades(self):
        assert distribution(["U1", "U1", "U4"]) == {"U1": 2, "U2": 0, "U3": 0, "U4": 1}


class TestAgreementRate:
    def test_simple_ratio(self):
        assert agreement_rate(["U1", "U2", "U3"], ["U1", "U2", "U4"]) == pytest.approx(2 / 3)

    def test_can_be_high_while_kappa_is_zero(self):
        """분포가 쏠리면 일치율은 높고 카파는 낮다 — 그래서 둘 다 찍는다."""
        gold = ["U1"] * 9 + ["U4"]
        pred = ["U1"] * 9 + ["U1"]
        assert agreement_rate(gold, pred) == pytest.approx(0.9)
