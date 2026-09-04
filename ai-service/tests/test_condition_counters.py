"""반박 표가 루브릭 실물과 갈리지 않는다. 소유: 윤지석

## 왜 필요한가

`tools/condition_counters.py` 는 **지금 판정에 안 쓰인다.** 그래서 루브릭의
`misconception_conditions` 가 바뀌어도 **아무것도 안 물고 조용히 낡는다** — 나중에
학습 재료로 쓸 때 *"이게 어느 조건의 반박인지"* 를 알 수 없게 된다.

`#284` 에서 같은 모양을 봤다: 선언(루브릭 46개)과 강제(라이브러리)가 **어디서도 대조되지
않아서** 공백이 생겼다. 쓰이지 않는 것일수록 대조를 걸어 둔다.

## 무엇을 재나

    (가)  표의 조건이 루브릭에 **실재한다**       오타·낡음
    (나)  표의 항목이 루브릭에 실재한다
    (다)  술어가 맞는다 — 반박이 조건의 부정형     이 표의 존재 이유다

(다)는 문면 대조가 아니라 **부정 표지 유무**로 잰다. 조건이 긍정이면 반박은 부정이어야
하고, 그 반대여야 한다. 문면을 대조하면 다듬을 때마다 빨개진다(`#300` 판단과 같다).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import pytest

from app import rubrics

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from condition_counters import COUNTERS  # noqa: E402

#: 부정 표지. Kiwi 없이 재는 이유는 이 테스트가 **의존성을 늘리지 않아야** 하기 때문이다
#: (`tools/MISCONCEPTION-DETECTION.md`: torch·kiwipiepy 는 레포 의존성이 아니다).
#: ❗`못한` 만 보면 `"받지 **못할** 수 있다"` 를 놓친다 — 이 표를 처음 쓸 때
#: 실제로 그렇게 빗나갔다. `못` 뒤에 조사·어미가 붙는 형태를 다 받는다.
#: ❗두 번 빗나갔다. `못한` 만 보면 `"받지 **못할** 수 있다"` 를 놓치고,
#: `못[하할함해]` 로 좁히면 `"돌려받지 **못한다**"` 를 놓친다(`못` + `한다`).
#: **`못` 뒤를 제한하지 않는다** — 이 표에서 `못` 은 항상 부정 보조다.
_NEG = re.compile(r"(안\s|않|못|없|아니|미달|미보장)")

PAIRS = [(item, cond, counter)
         for item, m in sorted(COUNTERS.items())
         for cond, counter in sorted(m.items())]


def test_the_table_is_not_empty() -> None:
    """★ 공회전 방지."""
    assert PAIRS, "반박 표를 하나도 못 읽었다"


@pytest.mark.parametrize(("item", "cond", "counter"), PAIRS,
                         ids=[f"{i}:{c[:12]}" for i, c, _ in PAIRS])
def test_the_condition_still_exists(item: str, cond: str, counter: str) -> None:
    """★ (가)(나) 표의 조건이 루브릭에 실재한다 — 안 쓰이는 표는 조용히 낡는다."""
    assert item in rubrics.all_rubrics(), f"루브릭에 없는 항목 {item}"
    assert cond in rubrics.get(item).misconception_conditions, (
        f"{item}: 루브릭에 없는 조건 {cond!r} — 조건 문면이 바뀌었으면 반박도 같이 고친다")


@pytest.mark.parametrize(("item", "cond", "counter"), PAIRS,
                         ids=[f"{i}:{c[:12]}" for i, c, _ in PAIRS])
def test_the_counter_flips_the_polarity(item: str, cond: str, counter: str) -> None:
    """★ (다) 반박이 조건의 **극성을 뒤집는다.** 이 표의 존재 이유다.

    술어를 맞추지 않으면 극성 대조가 작동하지 않는다 — 그게 실측으로 드러난 조건이다
    (`tools/MISCONCEPTION-DETECTION.md`). 문면이 아니라 **부정 표지 유무**로 잰다.
    """
    assert bool(_NEG.search(cond)) != bool(_NEG.search(counter)), (
        f"{item}: 조건과 반박의 극성이 같다\n  조건 {cond!r}\n  반박 {counter!r}")


def test_the_table_covers_only_the_aligned_groups() -> None:
    """★ 무리 C 를 넣지 않는다 — 술어를 맞출 수 없는 항목이다.

    ❗`"전액 보호된다"` 와 `"보호 한도가 있다"` 는 부정으로 이어지지 않는다(정도 축).
    억지로 넣으면 (다)를 통과시키려고 **뜻이 다른 문장**을 쓰게 된다.
    """
    C = {"ELS-TOTAL-LOSS-SCENARIO", "ELS-MATURITY-LOSS-CONDITION",
         "VAR-PARTIAL-DEPOSIT-INSURANCE", "ELS-KNOCKIN-BARRIER",
         "ELS-ISSUER-CREDIT-RISK", "VAR-NOT-BANK-SAVINGS",
         "VAR-PERFORMANCE-LINKED", "ELS-LOSS-SIMULATION"}
    assert not (set(COUNTERS) & C), (
        f"축이 다른 항목이 들어왔다: {sorted(set(COUNTERS) & C)} — "
        "정도·수량사·범주 축은 극성으로 안 갈린다")


def test_it_is_not_wired_into_scoring() -> None:
    """★ **판정 경로가 이 표를 안 본다.** 안 쓰는 것이 지금은 옳다.

    양방향 NLI 로 재봤을 때 판정 커버리지가 17% 이고 그 안에 P5 반대 방향 오류가
    1건이었다. 학습 재료로 남기는 것과 판정에 쓰는 것은 다르다 — 그 구분이 지워지면
    그 17% 를 반복한다.
    """
    app = Path(rubrics.__file__).resolve().parent
    users = [p.name for p in app.glob("*.py")
             if "condition_counters" in p.read_text(encoding="utf-8")]
    assert not users, f"채점 경로가 반박 표를 읽는다: {users}"
