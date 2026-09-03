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

#: **방향이 확정된** 파일 → 그 파일의 P6 인용이 근거로 삼는 절.
#:
#: ❗**이 목록은 「방향」만 잰다**(아래 (나)). *"절 표기가 있는가"* 는 손목록이 아니라
#: `app/**.py` 전수로 잰다(아래 (가)) — 손으로 적으면 **손이 빠뜨린 것은 영원히 안
#: 걸린다.** `schemas.py` 가 실제로 그랬다(`#308` 리뷰, 정세현): 이 PR 이 그 파일의 P6
#: 인용 셋을 고쳤는데 목록에 없어서 **되돌려도 초록**이었다.
#:
#: `schemas.py` 를 목록에 못 넣은 이유는 **한 파일이 두 절을 다 인용**하기 때문이다.
#:
#:     value_text · NARROWING_REFUSED   추출     → 1절 F-EXT-002
#:     cited_spans                      재설명   → 0.2절
#:
#: 파일 → 절 하나로는 표현할 칸이 없다. 갈라 두면 (가)가 그 파일을 받고 (나)는 안 받으므로
#: 두 절이 섞여도 문제가 없다.
_DIRECTION_BY_FILE = {
    "extraction.py": "1절",
    "templates.py": "1절",
    "reexplain.py": "0.2절",
}

#: 절을 가리키는 표기. 하나라도 그 줄에 있으면 "절을 적었다" 로 본다.
_NAMES_A_CLAUSE = ("0.2절", "1절", "F-EXT-002")

_TRIPLE = ('"' * 3, "'" * 3)


def _module_docstring_span(text: str) -> tuple[int, int]:
    """모듈 docstring 의 줄 범위 (0-based, [시작, 끝)).

    ❗**해설을 문면이 아니라 위치로 가른다** (`#308` 리뷰, 정세현). 처음에는
    `0.2절 P6|두 자리|범위 밖|…` 처럼 **문면으로** 지웠는데, 그러면 그 낱말이 우연히 든
    맨 인용이 조용히 삼켜진다 — `# 범위 밖 값은 쓰지 않는다 (P6).` 이 실제로 통과했다.

    해설은 모듈 docstring 안에 있고 인용은 코드·필드 옆에 있다. 그 경계로 가른다.
    """
    lines = text.splitlines()
    if not lines:
        return (0, 0)
    head = lines[0].lstrip()
    quote = next((q for q in _TRIPLE if head.startswith(q)), None)
    if quote is None:
        return (0, 0)
    if lines[0].count(quote) >= 2:
        return (0, 1)
    for i, line in enumerate(lines[1:], start=1):
        if quote in line:
            return (0, i + 1)
    return (0, len(lines))


def _p6_citations(name: str) -> list[str]:
    """그 파일의 P6 **인용** 줄. 모듈 docstring(해설)은 뺀다."""
    text = (APP / name).read_text(encoding="utf-8")
    start, end = _module_docstring_span(text)
    return [
        ln.strip() for i, ln in enumerate(text.splitlines())
        if re.search(r"\bP6\b", ln) and not (start <= i < end)
    ]


def _files_citing_p6() -> list[str]:
    """`app/**.py` 중 P6 를 인용하는 파일 전수 — **손목록이 아니다.**"""
    return [
        path.relative_to(APP).as_posix()
        for path in sorted(APP.rglob("*.py"))
        if _p6_citations(path.relative_to(APP).as_posix())
    ]


# ── (가) 절 표기가 있는가 — 전수 ─────────────────────────────────────────────
def test_p6_is_cited_somewhere() -> None:
    """★ 공회전 방지 — 모집단이 비면 아래가 아무것도 안 잰다."""
    assert _files_citing_p6(), "app/ 에서 P6 인용을 하나도 못 찾았다"


def test_every_p6_citation_names_its_clause() -> None:
    """★ 절 번호 없는 P6 인용이 **어느 파일에도** 없다.

    모집단을 `app/**.py` 전수로 잡는다. 손목록이면 **손이 빠뜨린 것은 영원히 안 걸린다** —
    `schemas.py` 가 그랬다(`#308` 리뷰).
    """
    bare = {
        name: [ln for ln in _p6_citations(name)
               if not any(tag in ln for tag in _NAMES_A_CLAUSE)]
        for name in _files_citing_p6()
    }
    bare = {k: v for k, v in bare.items() if v}
    assert not bare, (
        "절 번호 없는 P6 인용 — 0.2절은 「재설명 생성 시」로 한정돼 있어 추출에는 안 "
        f"걸린다. 어느 절인지 적어라: {bare}"
    )


def test_schemas_is_in_the_population() -> None:
    """★ `schemas.py` 가 모집단에 실제로 든다 — 이 PR 이 그 파일을 고쳤다.

    전수로 바꿨다는 말이 참인지를 **그 파일 이름으로** 잰다. 전수 표현식이 낡으면 (가)가
    조용히 좁아지는데, 그때 제일 먼저 빠질 파일이 이것이다.
    """
    assert "schemas.py" in _files_citing_p6()


# ── (나) 방향이 맞는가 — 방향이 확정된 파일만 ────────────────────────────────
@pytest.mark.parametrize("name,clause", sorted(_DIRECTION_BY_FILE.items()))
def test_the_direction_matches(name: str, clause: str) -> None:
    """그 파일의 P6 인용이 **자기 절**을 가리킨다."""
    wrong = "0.2절" if clause == "1절" else "1절"
    for line in _p6_citations(name):
        assert wrong not in line, f"{name} 은 {clause} 인데 {wrong} 을 인용한다: {line}"


def test_extraction_does_not_cite_the_reexplain_clause() -> None:
    """★ 추출이 0.2절을 근거로 들면 **범위 밖 인용**이다.

    0.2절 P6 은 *"재설명 생성 시"* 로 시작한다. 추출은 그 범위가 아니다.
    """
    for name in ("extraction.py", "templates.py"):
        for line in _p6_citations(name):
            assert "0.2절" not in line, (
                f"{name}: 추출 경로가 0.2절을 인용한다 — 1절 F-EXT-002 가 맞다: {line}"
            )


def test_reexplain_does_not_cite_the_extraction_clause() -> None:
    """반대 방향도 막는다 — F-EXT-002 는 추출 항목 반려 조항이라 재설명 근거가 아니다."""
    for line in _p6_citations("reexplain.py"):
        assert "F-EXT-002" not in line, f"재설명이 추출 조항을 인용한다 — 0.2절이 맞다: {line}"
