"""`P5`·`P6` 인용이 **어느 절인지** 파일마다 고정한다 (이슈 #290 · PR #305 · #308 · #341).

## 왜 필요한가

명세의 `P5` 와 `P6` 이 각각 **두 자리**에 있고, 두 자리가 같은 말을 하지 않는다.
`#305` 가 원본을 레포에 넣으면서 드러났다.

    P5  0.2절         "신뢰도가 임계값 미만이면 **무조건** 황색 강등.
                       미탐(오해→이해 오판) 최소화가 오탐 최소화보다 우선"
        3절 F-SCR-001 "단, **U4 판정은 신뢰도가 낮아도 강등하지 않는다**"

    P6  0.2절         "**재설명 생성 시** 상품 조건값은 추출 원문에서 복사만 허용"
        1절 F-EXT-002 "조건값 필드는 반드시 원문 스팬을 동반"

**뒤가 앞에 예외를 두거나(P5), 앞이 뒤보다 좁다(P6).** 그래서 절 번호 없는 인용은
읽는 사람이 어느 쪽으로 읽느냐에 따라 **근거가 되기도 하고 조항 위반의 증거가 되기도
한다** — `#290` 이 없애려던 모양 그대로다(근거처럼 생겼는데 그 조항이 그 말을 안 한다).

## 원칙마다 베끼지 않는다

`#308` 이 P6 만 잠갔고 `#341` 이 P5 를 같은 모양으로 붙였다. 여기서 **원칙을 표로
돌린다** — P5 용 대조를 따로 만들면 두 그물이 갈리고, 갈리는 순간 어느 쪽이 참인지
알 수 없다. `#341` 이 가드를 자기 PR 에 넣지 않고 이리로 넘긴 이유가 이것이다.

    _CLAUSE_TAGS   원칙 → 그 원칙의 절을 가리키는 표기
    (가)           절 표기가 있는가 — 원칙 × `app/**.py` 전수
    (나)           방향이 맞는가 — 방향이 확정된 파일만
    (다)           인용한 **제목**이 실재하는가

## 이 테스트가 잡지 않는 것

**조항 문면을 대조하지 않는다** — 그건 문면을 다듬을 때마다 빨개진다(`#300` 이 룰 ID
만 보기로 한 것과 같은 판단). 절 표기가 있는지, 방향이 맞는지, 제목이 실재하는지만
본다.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

APP = Path(__file__).resolve().parents[1] / "app"
SPEC = Path(__file__).resolve().parents[2] / "docs" / "functional-spec-v1.1.md"

#: 원칙 → 그 원칙의 절을 가리키는 표기. 하나라도 그 줄에 있으면 "절을 적었다" 로 본다.
#:
#: ❗**원칙마다 다른 표를 쓴다.** 두 원칙의 뒷절이 다르기 때문이다(P5 는 3절
#: F-SCR-001, P6 은 1절 F-EXT-002). 표를 합쳐 `("0.2절","1절","3절",…)` 하나로 두면
#: **P5 인용에 「1절」만 적어도 통과**한다 — 그 원칙에 없는 절이다.
_CLAUSE_TAGS = {
    "P5": ("0.2절", "3절", "F-SCR-001"),
    "P6": ("0.2절", "1절", "F-EXT-002"),
}

#: (원칙, 파일) → 그 파일의 인용이 근거로 삼는 절. **방향이 확정된 것만.**
#:
#: ❗**이 목록은 「방향」만 잰다**(아래 (나)). *"절 표기가 있는가"* 는 손목록이 아니라
#: `app/**.py` 전수로 잰다(아래 (가)) — 손으로 적으면 **손이 빠뜨린 것은 영원히 안
#: 걸린다.** `schemas.py` 가 실제로 그랬다(`#308` 리뷰, 정세현): 그 PR 이 그 파일의 P6
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
    ("P6", "extraction.py"): "1절",
    ("P6", "templates.py"): "1절",
    ("P6", "reexplain.py"): "0.2절",
    ("P5", "mismatch.py"): "0.2절",
}

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


def _citations(principle: str, name: str) -> list[str]:
    """그 파일의 `principle` **인용** 줄. 모듈 docstring(해설)은 뺀다."""
    text = (APP / name).read_text(encoding="utf-8")
    start, end = _module_docstring_span(text)
    return [
        ln.strip() for i, ln in enumerate(text.splitlines())
        if re.search(rf"\b{principle}\b", ln) and not (start <= i < end)
    ]


def _files_citing(principle: str) -> list[str]:
    """`app/**.py` 중 그 원칙을 인용하는 파일 전수 — **손목록이 아니다.**"""
    return [
        path.relative_to(APP).as_posix()
        for path in sorted(APP.rglob("*.py"))
        if _citations(principle, path.relative_to(APP).as_posix())
    ]


# ── (가) 절 표기가 있는가 — 원칙 × app/**.py 전수 ────────────────────────────
@pytest.mark.parametrize("principle", sorted(_CLAUSE_TAGS))
def test_the_principle_is_cited_somewhere(principle: str) -> None:
    """★ 공회전 방지 — 모집단이 비면 아래가 아무것도 안 잰다.

    원칙마다 따로 잰다. 뭉쳐서 *"둘 중 하나라도 있으면 통과"* 로 두면 **P5 인용이 전부
    사라져도 P6 이 있는 동안은 초록**이다.
    """
    assert _files_citing(principle), f"app/ 에서 {principle} 인용을 하나도 못 찾았다"


@pytest.mark.parametrize("principle", sorted(_CLAUSE_TAGS))
def test_every_citation_names_its_clause(principle: str) -> None:
    """★ 절 번호 없는 인용이 **어느 파일에도** 없다.

    모집단을 `app/**.py` 전수로 잡는다. 손목록이면 **손이 빠뜨린 것은 영원히 안 걸린다** —
    `schemas.py` 가 그랬다(`#308` 리뷰).
    """
    tags = _CLAUSE_TAGS[principle]
    bare = {
        name: lines
        for name in _files_citing(principle)
        if (lines := [ln for ln in _citations(principle, name)
                      if not any(tag in ln for tag in tags)])
    }
    assert not bare, (
        f"절 번호 없는 {principle} 인용 — 두 자리가 같은 말을 하지 않아서 어느 쪽으로 "
        f"읽느냐에 따라 근거가 뒤집힌다. 어느 절인지 적어라: {bare}"
    )


def test_schemas_is_in_the_population() -> None:
    """★ `schemas.py` 가 P6 모집단에 실제로 든다 — `#308` 이 그 파일을 고쳤다.

    전수로 잰다는 말이 참인지를 **그 파일 이름으로** 잰다. 전수 표현식이 낡으면 (가)가
    조용히 좁아지는데, 그때 제일 먼저 빠질 파일이 이것이다.
    """
    assert "schemas.py" in _files_citing("P6")


# ── (나) 방향이 맞는가 — 방향이 확정된 파일만 ────────────────────────────────
@pytest.mark.parametrize(("principle", "name", "clause"),
                         [(p, n, c) for (p, n), c in sorted(_DIRECTION_BY_FILE.items())])
def test_the_direction_matches(principle: str, name: str, clause: str) -> None:
    """★ 그 파일의 인용이 **자기 절**을 든다."""
    wrong = [ln for ln in _citations(principle, name) if clause not in ln]
    assert not wrong, f"{name} 의 {principle} 은 {clause} 인데 그 표기가 없다: {wrong}"


def test_extraction_does_not_cite_the_reexplain_clause() -> None:
    """★ 추출이 **0.2절을 근거로 들지 않는다** — 그 조항은 재설명에 한정돼 있다.

    (나)가 *"1절이 있다"* 만 보므로 `1절 · 0.2절` 을 같이 적으면 통과한다. 그게 바로
    `#290` 이 없애려던 모양이라 반대 방향도 잰다.
    """
    both = [ln for ln in _citations("P6", "extraction.py") if "0.2절" in ln]
    assert not both, (
        "추출은 0.2절 P6(「재설명 생성 시」) 범위 밖이다. 1절 F-EXT-002 만 든다: "
        f"{both}"
    )


def test_reexplain_does_not_cite_the_extraction_clause() -> None:
    """★ 반대 방향도 — 재설명이 1절 F-EXT-002 를 자기 근거로 들지 않는다."""
    both = [ln for ln in _citations("P6", "reexplain.py") if "F-EXT-002" in ln]
    assert not both, f"재설명의 근거는 0.2절이다: {both}"


def test_mismatch_does_not_cite_the_downgrade_exception_as_its_own() -> None:
    """★ `mismatch.py` 가 **3절 예외를 자기 근거로 들지 않는다**.

    그 파일이 드는 것은 0.2절의 **뒷문장**(*"미탐 최소화가 오탐 최소화보다 우선"*)이고
    **강등 규칙이 아니다**(`#341`, 정세현 확인). 강등 쪽은 채점·게이트의 몫이고 거기서
    3절 예외가 산다.

    ❗머리말이 두 절을 **대조하려고** 같이 싣는 것은 정상이다 — `_citations` 가 모듈
    docstring 을 빼므로 그건 여기 안 들어온다.
    """
    both = [ln for ln in _citations("P5", "mismatch.py") if "F-SCR-001" in ln]
    assert not both, f"이 파일의 P5 는 0.2절이다. 3절 예외는 채점·게이트의 몫이다: {both}"


# ── (다) 인용한 제목이 실재하는가 ────────────────────────────────────────────
#: 명세 제목을 인용하는 줄: 그 파일 이름과 「…」 이 **같은 줄**에 있다.
#:
#: 루브릭·프롬프트의 「…」 은 **상품 문서** 제목이라 모집단이 아니다(`ELS-NO-LISTING.yaml`
#: 의 「환금성 제약의 위험」 등). 명세 파일 이름을 같이 요구해서 가른다.
#:
#: ❗**모집단은 `ai-service/app/**.py` 다 — 지금 한 줄이다**(`mismatch.py:26`).
#: `server/`·`eval/` 도 같은 인용을 할 수 있지만 남의 영역이라 여기서 재지 않는다.
#: 한 줄일 때 넣는 것이 값이 싸고, **이 계열이 이미 두 번 났다**(`#300` 테스트 · `#341`) —
#: 둘 다 제목이 바뀐 것을 인용 쪽이 몰라서 났다. 늘어나기 전에 넣는다(`#341` 리뷰, 오준서).
_SPEC_FILE = "functional-spec-v1.1.md"


def _spec_heading_citations() -> list[tuple[str, str]]:
    return [
        (path.relative_to(APP).as_posix(), m)
        for path in sorted(APP.rglob("*.py"))
        for ln in path.read_text(encoding="utf-8").splitlines()
        if _SPEC_FILE in ln
        for m in re.findall(r"「([^」]*)」", ln)
    ]


def test_spec_headings_are_cited_somewhere() -> None:
    """★ 공회전 방지."""
    assert _spec_heading_citations(), f"app/ 에서 {_SPEC_FILE} 제목 인용을 못 찾았다"


def _heading_exists(title: str, markdown: str) -> bool:
    """`title` 이 `markdown` 의 어느 제목 줄에 **들어 있는가**.

    ❗**exact 가 아니라 부분일치다** (`#341` 리뷰, 오준서가 그 PR 에서 정하고 들어가자고
    한 것). 제목에 장식이 붙기 때문이다.

        docs/functional-spec-v1.1.md:49   ### ❗전사하고 보니 우리 요약과 갈리는 것 셋
        mismatch.py:26                    「전사하고 보니 우리 요약과 갈리는 것 셋」

    exact 로 짜면 **이 줄이 그날 빨개진다** — 그런데 사람이 찾아가는 데는 아무 지장이
    없다. `❗`·이모지·번호 같은 장식을 인용에 베끼게 강요하면, 장식이 바뀔 때마다
    이 그물이 **결함이 아닌 것으로** 빨개진다.

    **막으려는 것은 장식이 아니라 「제목이 통째로 바뀐 것」이다** — 실제로 난 결함이
    그것이었다(「설계 원칙 (P4~P6)」 → 「설계 원칙 (P1~P6) — 0.2절 전사」 + 하위 절 분리).
    부분일치로도 그건 잡힌다(변이 ⓓ·ⓔ).
    """
    return bool(re.search(rf"^#+ .*{re.escape(title)}", markdown, re.MULTILINE))


def test_every_cited_spec_heading_exists() -> None:
    """★ 인용한 제목이 명세에 **실재한다**.

    ❗`#341` 리뷰에서 실제로 걸린 결함이다(정세현). 내가 「설계 원칙 (P4~P6)」 아래
    2번을 가리켰는데 **그 제목이 없었다** — `#305` 가 21행을 「설계 원칙 (P1~P6) — 0.2절
    전사」 로 두고 갈린 것 셋을 49행 하위 절로 뺐다.

    **절 번호 대조로는 안 잡힌다.** 번호는 맞고 제목이 틀렸다. 위 (가)~(나)가 통과하는
    동안 이 결함이 살아 있었던 것이 그 증거다.
    """
    headings = SPEC.read_text(encoding="utf-8")
    missing = [
        (name, title) for name, title in _spec_heading_citations()
        if not _heading_exists(title, headings)
    ]
    assert not missing, (
        f"{_SPEC_FILE} 에 없는 제목을 인용한다 — 제목이 바뀌면 인용은 조용히 끊긴다: "
        f"{missing}"
    )


def test_a_decorated_heading_still_matches() -> None:
    """★ 제목의 **장식**을 인용에 베끼지 않아도 통과한다 — exact 로 바꾸면 여기가 깨진다.

    ❗이 단정이 `#341` 리뷰의 답이다. 실물 데이터가 아니라 **대조 함수에** 직접 건다 —
    그 한 줄이 나중에 지워져도 규약은 남아야 한다(`test_schemas_is_in_the_population` 이
    실물 이름을 잰 것과 역할이 다르다).
    """
    assert _heading_exists("전사하고 보니 우리 요약과 갈리는 것 셋",
                           "### ❗전사하고 보니 우리 요약과 갈리는 것 셋")
    assert _heading_exists("설계 원칙 (P1~P6)",
                           "## 설계 원칙 (P1~P6) — 0.2절 전사")


def test_a_heading_that_changed_is_caught() -> None:
    """★ 부분일치로도 **제목이 통째로 바뀐 것**은 잡힌다 — 실제로 난 결함이 그것이다."""
    assert not _heading_exists("설계 원칙 (P4~P6)",
                               "## 설계 원칙 (P1~P6) — 0.2절 전사")


def test_body_text_is_not_a_heading() -> None:
    """★ 제목 줄이 아닌 **본문**에 그 말이 있는 것으로는 통과하지 않는다.

    `^#+ ` 를 빼면 이게 통과한다 — 그러면 *"그 문서 어디엔가 이 말이 있다"* 를 재는
    것이고, **찾아가는 길이 있는지**를 재는 것이 아니다.
    """
    assert not _heading_exists("설계 원칙 (P4~P6)",
                               "그 조항은 설계 원칙 (P4~P6) 아래 2번에 있다")
