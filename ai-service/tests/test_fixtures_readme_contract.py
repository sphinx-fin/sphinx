"""`tests/fixtures/README.md` 가 평가 독립성에 대해 말하는 것이 참인가. 소유: 윤지석

## 왜 이 파일이 있나 (이슈 #343 · PR #350 리뷰 ③)

`fixtures/README.md` 머리말이 **내가 F-CMN-003 라벨링에서 제외된 사람**이라는 것과, 그래서
여기 라벨이 평가셋과 섞이면 안 된다는 것을 적는다. 그 문단이 이 디렉토리의 존재 이유이고
심사에서 분리를 방어하는 근거다.

문제는 **그 사실이 레포에 일곱 벌 있다는 것**이다(`#350` 실측).

    docs/role-assignment-v1.2.md          §1 표 — 정본
    README.md · eval/README.md · eval/corpus/README.md
    eval/labeling/guideline.md · eval/run_eval.py
    ai-service/tests/fixtures/README.md   ← 내 것

`#350` 이 라벨러를 `강희진+오준서` → `정세현+강희진` 으로 바꾸면서 일곱 곳을 **손으로**
맞췄다. 다음 변경에서 또 갈린다 — 오늘 `#316` 이 그 실물이었다(`ErrorCodeContract` 에서
유니온을 뺐더니 셋 갈렸다).

**여섯은 `eval/` 쪽이라 정세현 몫이고, 이 파일은 내 것이라 내가 잠근다.**

## 무엇을 재나 — 두 상태를 다 허용하고 어긋남만 잡는다

내 README 가 라벨러 명부를 **들지 않는 쪽이 낫다**(그러면 갈릴 자리가 없다). 그런데 지금은
들고 있고 `#350` 이 그것을 빼는 중이다. 그래서 한쪽만 옳다고 박으면 이 테스트가 `#350`
전후 한쪽에서 깨진다 — 그건 그물이 아니라 방해다.

    명부를 든다   → 정본(role-assignment §1)과 **같아야** 한다
    명부를 안 든다 → 권위 있는 파일을 **가리켜야** 한다

둘 다 참이면 통과다. 어느 쪽으로 가든 *"두 벌이 조용히 갈린 상태"* 만 걸린다.

❗**내 제외는 상태와 무관하게 항상 적혀 있어야 한다.** 라벨러가 누구든 나는 프롬프트
당사자라 빠진다 — 그 문장이 이 디렉토리의 존재 이유다.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURES_README = Path(__file__).resolve().parent / "fixtures" / "README.md"
ROLE_ASSIGNMENT = REPO_ROOT / "docs" / "role-assignment-v1.2.md"

#: 팀원 이름. 명부를 뽑을 때 쓴다 — `.github/CODEOWNERS` 와 같이 바뀐다.
MEMBERS = ("윤지석", "강희진", "정세현", "오준서")

#: 명부를 안 들 때 가리켜야 하는 곳. 하나라도 있으면 된다.
AUTHORITIES = ("eval/labeling/guideline.md", "role-assignment", "eval/README.md")

#: **이름이 `+` 나 `·` 로 이어진 것**만 명부로 센다.
#:
#: 처음에는 *"`라벨링` 이 들어간 줄에서 팀원 이름을 뽑는다"* 로 썼는데 두 곳에서 틀렸다.
#:   - `#350` 이후 정본의 살아 있는 정정 문장에 `라벨링` 이라는 단어가 없다 → 0 건이 나온다
#:   - 정본 행에는 정세현이 **운영자로도** 나오므로 라벨러와 구별이 안 됐다
#: 명부는 언제나 **짝**으로 적힌다(`강희진+오준서` · `정세현+강희진`) — 그 모양을 본다.
_PAIR = re.compile(
    rf"({'|'.join(MEMBERS)})\s*[+·]\s*({'|'.join(MEMBERS)})"
)


def _head(path: Path, lines: int = 12) -> str:
    return "\n".join(path.read_text(encoding="utf-8").splitlines()[:lines])


def _roster(text: str) -> set[str]:
    """`A+B` · `A·B` 로 적힌 명부. 없으면 빈 집합."""
    found: set[str] = set()
    for a, b in _PAIR.findall(text):
        found |= {a, b}
    return found


def _canonical_roster() -> set[str]:
    """정본 §1 의 F-CMN-003 행에서 라벨러 짝을 뽑는다.

    ❗**취소선(`~~…~~`)은 버린다.** `#350` 이 옛 명부를 지우지 않고 취소선 + 정정으로
    남기기로 했다(*"지우면 다음 사람이 배제가 있었던 것을 모른다"*). 그것까지 세면 옛
    이름과 새 이름이 섞여서 대조가 늘 통과한다 — 그물이 찢어진다.
    """
    for line in ROLE_ASSIGNMENT.read_text(encoding="utf-8").splitlines():
        if line.startswith("| F-CMN-003 "):
            live = re.sub(r"~~.*?~~", "", line)
            return _roster(live)
    raise AssertionError("정본에서 F-CMN-003 행을 못 찾았다 — 표 형식이 바뀌었나")


def test_my_exclusion_is_always_stated():
    """★ 라벨러가 누구든 **내 제외는 적혀 있어야 한다.**

    이 문단이 이 디렉토리의 존재 이유다 — 내가 붙인 라벨이 공식 평가셋과 섞이면 평가자와
    피평가자가 같아지고, 그 분리가 심사에서 방어 지점이다.
    """
    head = _head(FIXTURES_README)
    assert "윤지석" in head and "제외" in head, (
        "머리말에서 내 제외가 사라졌다 — 이 픽스처가 왜 평가셋과 무관한지가 근거를 잃는다"
    )
    assert "프롬프트 당사자" in head, (
        "제외의 **이유**가 없으면 다음 사람이 그 제외를 임의로 되돌릴 수 있다"
    )


def test_roster_here_either_matches_the_source_or_defers_to_it():
    """★ 명부를 들면 정본과 같아야 하고, 안 들면 권위를 가리켜야 한다.

    두 상태를 다 허용하는 이유는 docstring 에 있다 — `#350` 전후 어느 쪽에서도
    이 테스트가 방해가 되지 않게 하려는 것이다. 걸리는 것은 **조용히 갈린 상태** 하나다.
    """
    head = _head(FIXTURES_README)
    here = _roster(head) - {"윤지석"}          # 내 제외는 명부가 아니다

    if here:
        canonical = _canonical_roster()
        # ❗예외를 두지 않는다. 처음에는 `| {"정세현"}` 을 허용했는데, 그러자
        # `정세현·오준서` 로 바꾼 변조가 **통과했다** — 내가 만든 구멍이 그물을 찢었다.
        assert here == canonical, (
            f"내 README 의 라벨러 {sorted(here)} ↔ 정본 {sorted(canonical)}. "
            "정본은 docs/role-assignment-v1.2.md §1 F-CMN-003 행이다 — 둘이 갈리면 "
            "심사에서 어느 쪽이 참인지 말할 수 없다. **명부를 여기서 빼고 권위를 가리키는 "
            "쪽이 낫다**(PR #350 이 그렇게 갔다)"
        )
    else:
        assert any(a in head for a in AUTHORITIES), (
            "명부를 안 들면 어디를 봐야 하는지는 적어야 한다 — 안 적으면 읽는 사람이 "
            f"라벨러를 알 방법이 없다. {AUTHORITIES} 중 하나를 가리킨다"
        )


def test_the_two_sets_are_declared_separate():
    """이 디렉토리가 공식 평가셋과 **무관하다**는 선언이 남아 있어야 한다.

    `#204`·`#207` 에서 내가 dev set 라벨의 출처를 문면으로 남긴 것과 같은 층이다 —
    파일 위치만으로는 분리가 안 보인다.
    """
    text = FIXTURES_README.read_text(encoding="utf-8")
    assert "F-CMN-003" in text and "무관하다" in text, (
        "'평가와 무관하다' 선언이 사라지면 이 픽스처가 평가셋으로 흘러 들어가는 것을 "
        "막는 것이 파일 위치뿐이 된다"
    )
    assert "eval/" in text, "공식 평가셋이 어디 있는지 가리켜야 두 세트가 구분된다"


def test_the_canonical_row_is_findable():
    """정본 행을 못 찾으면 위 대조가 조용히 공회전한다 — 그때 여기서 먼저 걸린다."""
    roster = _canonical_roster()
    assert roster, (
        "정본 F-CMN-003 행에서 이름을 하나도 못 뽑았다. 취소선 제거가 너무 넓거나 "
        "'라벨링' 문면이 바뀌었다 — 대조가 아무것도 안 하고 있다"
    )
