"""`measure_suffix_leak.py` 가 **재현되는 숫자**를 내는지. 소유: 정세현 (이슈 #377)

이 도구가 생긴 이유가 *"같은 값을 세 사람이 세 번 다르게 냈다"* 이므로, 테스트가 지킬
것은 **특정 퍼센트가 아니라 프로토콜의 성질**이다. 퍼센트를 박으면 표본이 바뀔 때마다
고쳐야 하고(`#354` 가 코퍼스를 다시 뽑았다) 그러면 이 파일이 낡는다.

넷을 잠근다.

    1. 행 순서를 바꿔도 같은 값     ← 세 번 갈린 원인이 동률 처리의 순서 의존이었다
    2. LOO 없는 값이 항상 더 크다   ← 그 차이가 곧 「표가 표본을 재현하는 정도」다
    3. 만장일치 규칙은 100%        ← 구조적이라 누설의 크기가 아니다
    4. 기준선이 4단계 최빈이다      ← `#377` 이 이진 기준선(63%)과 비교해 과대평가했다
"""
from __future__ import annotations

import importlib.util
import random
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "eval" / "tools" / "measure_suffix_leak.py"


def _load():
    spec = importlib.util.spec_from_file_location("measure_suffix_leak", TOOL)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


leak = _load()


@pytest.fixture(scope="module")
def tables():
    got = leak._rows_from_corpus()
    if not got:
        pytest.skip("라벨 파일이 없다 — 이 회차에서는 대조할 실물이 없다")
    return got


def test_the_sample_is_actually_read(tables):
    """★ 양성 대조. 표본을 못 읽으면 아래 단정들이 **아무것도 안 잰다.**

    라벨러를 갈라 내는 것도 여기서 확인한다 — 한 벌로 접히면 *"어느 라벨로 잰 건가"* 가
    다시 빠진다(`#385` 리뷰).
    """
    assert len(tables) >= 2, f"라벨러가 둘 이상이어야 한다 — 지금 {list(tables)}"
    for name, rows in tables.items():
        assert len(rows) >= 30, f"{name}: {len(rows)}행 — 표본이 이렇게 작으면 비율이 무의미하다"


def test_the_number_does_not_depend_on_row_order(tables):
    """❗**행 순서를 바꿔도 같은 값이다.**

    이 도구가 생긴 직접적인 이유다. `collections.Counter.most_common` 은 동률에서 삽입
    순서를 따르므로, 같은 프로토콜을 적어 두고도 사람마다 다른 값이 나왔다
    (`#385`: 51/57 ↔ 53/54). 등급 이름 순으로 깨면 순서에 안 걸린다.
    """
    rng = random.Random(20260904)
    for name, rows in tables.items():
        base = leak.suffix_accuracy(rows, 6)
        for _ in range(3):
            shuffled = rows[:]
            rng.shuffle(shuffled)
            assert leak.suffix_accuracy(shuffled, 6) == pytest.approx(base), (
                f"{name}: 행 순서를 바꾸니 값이 변한다 — 동률 처리가 순서에 의존한다")


def test_dropping_leave_one_out_inflates_it(tables):
    """❗LOO 를 빼면 **항상 더 커진다.** 그 차이가 `#377` 의 95% 였다.

    자기 자신을 표에 넣으면 그 종결이 1건뿐인 행은 구조적으로 자기를 맞힌다. 즉 그 값은
    *"이 표본으로 만든 표가 이 표본을 재현하는가"* 이고 **누설의 크기가 아니다.**
    """
    for name, rows in tables.items():
        honest = leak.suffix_accuracy(rows, 6)
        naive = leak.suffix_accuracy(rows, 6, loo=False)
        assert naive > honest, (
            f"{name}: LOO 없는 값({naive:.0%})이 LOO 값({honest:.0%})보다 크지 않다 — "
            "이 도구가 보이려는 차이가 사라졌다")


def test_the_unanimous_rule_is_structurally_perfect(tables):
    """❗**만장일치 규칙은 100% 다.** 그래서 그 값으로 누설을 말할 수 없다.

    `#377` 의 95% 가 이 계열이었다 — 형태를 라벨을 보고 골라 화이트리스트로 만들고 같은
    행에서 채점하면, 그 규칙은 정의상 틀릴 수 없다(`#385` 리뷰에서 저자가 찾아냈다).
    """
    for name, rows in tables.items():
        decided, hit, singleton = leak.unanimous_only(rows, 6)
        assert decided > 0, f"{name}: 만장일치 그룹이 없다 — 이 대조가 공회전한다"
        assert hit == decided, (
            f"{name}: 만장일치만 예측했는데 {hit}/{decided} 다 — 구조적으로 전부 맞아야 한다")
        assert singleton > 0, (
            f"{name}: 싱글턴이 0 이다 — 그러면 위 100% 가 왜 구조적인지 안 보인다")


def test_the_baseline_is_the_same_task_as_the_metric(tables):
    """❗기준선이 **4단계 최빈**이다 — 이진이 아니다.

    `#377` 이 4단계 예측 정확도를 *"항상 비U4"* 이진 기준선(63%)과 비교해 누설을 두 배
    가까이 과대평가했다. 지표와 기준선은 같은 과제여야 한다.
    """
    for name, rows in tables.items():
        grade, base = leak.baseline(rows)
        grades = {g for _, g in rows}
        assert len(grades) >= 3, f"{name}: 등급이 {grades} 뿐이면 4단계 기준선이 아니다"
        assert base <= 0.5, (
            f"{name}: 기준선 {base:.0%} — 4단계 최빈이 절반을 넘으면 이진에 가깝다. "
            "표본 편중을 먼저 본다")
        assert grade in grades
