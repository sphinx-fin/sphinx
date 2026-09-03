"""`P6` 인용이 **어느 절인지** 파일마다 고정한다 (이슈 #290 · PR #305).

## 왜 필요한가

명세의 `P6` 이 **두 자리**에 있고 범위가 다르다. `#305` 가 원본을 레포에 넣으면서 드러났다.

    0.2절 P6         "**재설명 생성 시** 상품 조건값은 추출 원문에서 복사만 허용"
    1절 F-EXT-002    "조건값 필드는 반드시 원문 스팬을 동반. 원문에 없는 수치가 출력되면
                      검증 단계에서 해당 항목 반려"

**추출은 0.2절 범위 밖이다.** 그런데 `extraction.py` 의 P6 인용 넷이 절 번호 없이 적혀
있어서, 읽는 사람이 0.2절로 읽으면 **범위 밖 조항을 근거로 든 것**이 된다 — `#290` 이
없애려던 모양 그대로다(근거처럼 생겼는데 그 조항이 그 말을 안 한다).

## 이 테스트가 잡는 것

절 번호 없는 새 `P6` 인용이 생기면 실패한다. **문면을 대조하지 않는다** — 그건 문면을
다듬을 때마다 빨개진다(`#300` 이 룰 ID 만 보기로 한 것과 같은 판단). 여기서는
**절 표기가 있는지만** 본다.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

APP = Path(__file__).resolve().parents[1] / "app"

#: 파일 → 그 파일의 P6 인용이 근거로 삼는 절.
#:
#: `reexplain` 이 0.2절인 이유: 그 조항이 *"재설명 생성 시"* 로 한정돼 있고 재설명이
#: 정확히 그 범위다. 나머지는 추출 경로라 1절 F-EXT-002 다.
_CLAUSE_BY_FILE = {
    "extraction.py": "1절",
    "templates.py": "1절",
    "reexplain.py": "0.2절",
}

#: 절 표기를 요구하지 않는 줄. 파일 머리말이 그 파일 전체의 절을 이미 선언한 경우다.
#:
#: ❗**예외를 목록으로 두는 이유**는 "왜 이 줄만 다른가" 를 다음 사람이 다시 조사하지
#: 않게 하려는 것이다. 늘어나면 머리말 선언이 안 읽히고 있다는 신호다.
_DECLARED_BY_MODULE_DOCSTRING = {
    ("reexplain.py", "그건 정의상 P6 안전하다"),
    ("reexplain.py", "그 문면은 P4·P6 을 어기지 않는다"),
    ("reexplain.py", "P6 — 상품설명서에 없는 문장을"),
}


#: 두 절의 **대비를 설명하는** 줄. 인용이 아니라 해설이라 이 대조의 대상이 아니다.
#:
#: ❗이걸 안 가르면 *"1절이다(0.2절이 아니다)"* 같은 설명 문장이 **0.2절 인용으로 잡힌다** —
#: 실제로 처음 돌렸을 때 그렇게 걸렸다. 대조가 문면을 읽으려 하면 이런 자리가 계속 나온다.
_EXPLAINS_THE_SPLIT = re.compile(r"0\.2절 P6|두 자리|범위 밖|이 파일의|0\.2절이 아니다|쪽은 0\.2절이 맞다")


def _p6_lines(name: str) -> list[str]:
    """그 파일의 P6 **인용** 줄. 두 절의 대비를 설명하는 줄은 뺀다."""
    text = (APP / name).read_text(encoding="utf-8")
    return [
        ln.strip() for ln in text.splitlines()
        if re.search(r"\bP6\b", ln) and not _EXPLAINS_THE_SPLIT.search(ln)
    ]


@pytest.mark.parametrize("name,clause", sorted(_CLAUSE_BY_FILE.items()))
def test_every_p6_citation_names_its_clause(name: str, clause: str) -> None:
    """★ 절 번호 없는 P6 인용이 없다.

    새로 하나 넣으면 여기서 걸린다 — 그때 **어느 절인지 정해서 쓰게** 만드는 것이 요지다.
    """
    lines = _p6_lines(name)
    assert lines, f"{name} 에 P6 인용이 하나도 없다 — 이 대조가 아무것도 안 잰다"

    bare = [
        ln for ln in lines
        if clause not in ln
        and "F-EXT-002" not in ln
        and not any(frag in ln for f, frag in _DECLARED_BY_MODULE_DOCSTRING if f == name)
    ]
    assert not bare, (
        f"{name}: 절 번호 없는 P6 인용 — 0.2절은 「재설명 생성 시」로 한정돼 있어 "
        f"추출에는 안 걸린다. 어느 절인지 적어라: {bare}"
    )


def test_extraction_does_not_cite_the_reexplain_clause() -> None:
    """★ 추출이 0.2절을 근거로 들면 **범위 밖 인용**이다.

    0.2절 P6 은 *"재설명 생성 시"* 로 시작한다. 추출은 그 범위가 아니다.
    """
    for name in ("extraction.py", "templates.py"):
        for line in _p6_lines(name):
            assert "0.2절" not in line, (
                f"{name}: 추출 경로가 0.2절을 인용한다 — 그 조항은 재설명으로 한정돼 있다. "
                f"1절 F-EXT-002 통제가 맞다: {line}"
            )


def test_reexplain_does_not_cite_the_extraction_clause() -> None:
    """반대 방향도 막는다 — F-EXT-002 는 추출 항목 반려 조항이라 재설명 근거가 아니다."""
    for line in _p6_lines("reexplain.py"):
        assert "F-EXT-002" not in line, (
            f"재설명이 추출 조항을 인용한다 — 0.2절이 맞다: {line}"
        )
