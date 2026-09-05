"""필수 요소 **한 줄에 사실이 둘**이면 셈이 작동하는지 본다 (이슈 #367).

## 무엇이 문제였나

`ELS-LOSS-SIMULATION` 이 이랬다.

    required_elements:
      - 모의실험은 과거 데이터에 기초한 것이고 미래 수익을 보장하지 않는다
                        ^^^^^^^^^^^^^^^^^^      ^^^^^^^^^^^^^^^^^^^^^^^
    u1_requires: 1

**둘 중 하나만 말해도 U1 이었다.** `#366` 이 U1↔U2 를 세어서 정하게 바꿨지만 **셀 것이
하나면 셈이 작동하지 않는다.**

## ❗「형태」가 아니라 「셈이 작동하는가」를 잰다

접속 어미를 금지하면 **멀쩡한 요소까지 문다.** 실제로 `VAR-FEE-DEDUCTION` 의 첫 요소가
그렇다 — 원문이 두 곳(보험료 · 계약자적립액)에서 차감한다고 말하므로 **내용상 한 요소가
맞고**, 요소가 3개에 문턱이 3이라 **그 요소를 못 말하면 U1 이 안 된다.**

    ELS-LOSS-SIMULATION   요소 1 · 문턱 1   ❗하나만 말해도 통과 → 셈이 죽었다
    VAR-FEE-DEDUCTION     요소 3 · 문턱 3    그 요소를 통째로 말해야 한다 → 셈이 산다

그래서 잠그는 것은 **접속 어미가 있으면 문턱이 요소 수와 같아야 한다** 이다. 요소가 하나뿐인
루브릭에서 그 조건은 *"쪼개라"* 와 같은 말이 된다 — `1/1` 은 부분 충족을 표현할 수 없다.

## 왜 그물이 필요한가 — 오늘 내가 하나 더 만들었다

이슈가 *"전수로 셌다 · 1건뿐"* 으로 열렸는데, `#392` 를 고치면서 `VAR-FEE-DEDUCTION` 에
같은 형태를 만들었다. **한 번 센 사실은 다음 PR 에서 깨진다** — 세는 것을 잠가야 한다.
"""
from __future__ import annotations

import re

import pytest

from app import rubrics

#: 사실 둘을 잇는 자리. 나열(`·`)은 한 사실의 구성요소라 안 본다 —
#: `계약체결비용·계약관리비용` 은 둘이 아니라 한 묶음이다.
JOINED = re.compile(r"(이고|하고|이며|하며|,)\s")


def _joined(elements: tuple[str, ...]) -> list[str]:
    return [e for e in elements if JOINED.search(e)]


@pytest.mark.parametrize("item_id", sorted(rubrics.all_rubrics()))
def test_a_joined_element_still_lets_the_count_work(item_id: str) -> None:
    """★ 접속 어미로 이어진 요소가 있으면 **문턱이 요소 수와 같아야 한다.**

    같지 않으면 그 요소를 안 말하고도 U1 이 나온다 — 한 줄에 사실이 둘이므로 **하나만
    말한 고객이 통과한다.** `#366` 의 셈이 그 자리에서 무력해진다.
    """
    rubric = rubrics.get(item_id)
    joined = _joined(rubric.required_elements)
    if not joined:
        # `pytest.skip` 을 안 쓴다 — `ci.yml` 의 `no_skip.py` 가 skip 을 실패로 바꾼다.
        return

    total = len(rubric.required_elements)

    # ❗**요소가 하나뿐이면 문턱이 뭐든 셈이 죽어 있다.** `1/1` 은 부분 충족을 표현할 수
    # 없어서, 한 줄 안의 사실 둘 중 하나만 말한 고객과 둘 다 말한 고객이 구분되지 않는다.
    # 첫 판에서 이 갈래를 안 적었더니 **고치려던 그 모양(요소 1 · 문턱 1)이 통과했다** —
    # `문턱 == 요소 수` 가 거기서도 참이기 때문이다.
    assert total > 1, (
        f"{item_id}: 한 줄에 사실이 둘인데 **요소가 하나뿐**이다.\n"
        f"  {joined}\n"
        "둘 중 하나만 말해도 U1 이 나온다 — 사실 단위로 쪼갠다(이슈 #367)."
    )

    assert rubric.u1_requires == total, (
        f"{item_id}: 한 줄에 사실이 둘인 요소가 있는데 문턱이 "
        f"{rubric.u1_requires}/{total} 다.\n"
        f"  {joined}\n"
        "그 요소를 안 말하고도 U1 이 나온다 — 쪼개거나(요소 수를 늘리고) 문턱을 "
        "요소 수에 맞춘다. 문턱을 낮추는 쪽은 P5(0.2절)와 반대다."
    )


def test_the_detector_actually_fires_on_the_old_shape() -> None:
    """★ 양성 대조 — 이 정규식이 **실제로 잡는다.**

    없으면 `_joined` 가 항상 빈 목록을 돌려줘도 위 검사가 전건 통과한다.
    `#396` 에서 겪은 「빈 모집단이 단정을 참으로 만든다」의 다른 얼굴이다.
    """
    old = ("모의실험은 과거 데이터에 기초한 것이고 미래 수익을 보장하지 않는다",)
    assert _joined(old) == list(old), "이슈 #367 이 지적한 그 문면을 못 잡는다"

    split = ("모의실험은 과거 데이터에 기초한다", "미래 수익을 보장하지 않는다")
    assert _joined(split) == [], "쪼갠 뒤에도 잡으면 오탐이다"


def test_a_bullet_list_inside_one_fact_is_not_joined() -> None:
    """★ 나열(`·`)은 안 문다 — 한 사실의 구성요소다.

    `계약체결비용·계약관리비용` 을 둘로 세면 **원문이 한 묶음으로 말하는 것을 쪼개라고**
    하게 된다. `#392` 에서 원문 용어를 그대로 싣기로 한 판단과 부딪힌다.
    """
    assert _joined(("월공제액(위험보험료·계약체결비용·보증비용)이 차감됨",)) == []


def test_the_split_item_now_counts() -> None:
    """★ `#367` 이 지적한 항목이 실제로 고쳐졌다."""
    rubric = rubrics.get("ELS-LOSS-SIMULATION")
    assert len(rubric.required_elements) == 2, "쪼개지지 않았다"
    assert rubric.u1_requires == 2, (
        "문턱이 2 가 아니면 하나만 말해도 U1 이다 — 쪼갠 의미가 없다")
