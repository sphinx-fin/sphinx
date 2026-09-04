"""dev set 픽스처가 규약을 지킨다. 소유: 윤지석

## 왜 생겼나

이 픽스처를 **읽는 테스트가 하나도 없었다.** `tools/run_devset.py` 만 읽고, 그건 실제
LLM 을 부르는 도구라 CI 에서 안 돈다. 즉 오타·빠진 키·없는 `item_id` 가 **dev set 을
돌릴 때까지** 안 드러난다.

## ❗화법이 한 종류로 되돌아가는 것을 막는다

35건 중 26건이 존댓말이다. 원래는 **24건 중 23건(96%)** 이었고, 그게 위험한 이유가
`#377` 에서 실측으로 나왔다 — **화법 폭이 좁으면 형태가 라벨과 붙는다.**

가장 곧은 증거는 정확도가 아니라 **서술 사실**이다.

    eval 합의 U1 7건이 예외 없이 「거네요」·「라는/다는 거죠」 두 형태에 있고,
    그 형태의 U4 는 0건이다.

크기는 이렇다(끝 6자 · leave-one-out · 짧은 쪽 백오프 · 동률은 등급 이름 순).

    정세현 라벨 70쌍   54%  (기준선 34%)
    강희진 라벨 70쌍   51%  (기준선 31%)
    이 dev set 34행    65%  (기준선 56%)   ← 넓힌 뒤에도 1.2배다

❗**`#377` 최초의 「95% · 기준선 63%」는 무효다** — 규칙을 라벨을 보고 골라 같은 행에서
채점했고(leave-one-out 으로도 안 고쳐진다), 기준선은 이진인데 지표는 4단계였다.
재현은 `eval/tools/measure_suffix_leak.py` 하나이고 **인용은 그 출력을 옮겨 적는다**
(`#385`·`#387` 리뷰).

❗**넓혀도 누설이 남는다는 것을 같이 적는다.** 이 파일이 지키는 비율은 누설을 *없애는*
장치가 아니라 *키우지 않는* 장치다. 반말이 동작하는 것은 확인했고(라이브러리
`pattern`·`ngram`·LLM 전부) 문제는 **표본에 한 건도 없었다**는 것이었다.

## 등급 폭도 같이 잠근다

U2 1건 · U3 0건이었다. 그런데 F-CMN-003 실측에서 **사람 불일치 19건 중 9건(47%)이
U1↔U2** — 제일 갈리는 경계에 표본이 없었다.

## ❗이 파일이 하지 않는 것

**성능을 재지 않는다.** 이 dev set 은 프롬프트 튜닝용이고 F-CMN-003 공식 평가셋과
무관하다(`tests/fixtures/README.md`). 라벨도 내가 붙였다 — 나는 공식 라벨링에서
프롬프트 당사자로 배제된 사람이다. 여기서 재는 것은 **표본의 모양**뿐이다.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest
import yaml

from app import misconception, rubrics

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "utterances"

#: 케이스의 **신원**. 없으면 무엇을 재는 케이스인지 알 수 없다.
REQUIRED_KEYS = frozenset({"id", "source", "item_id", "question", "answer", "deterministic"})

#: 기대값 키. **하나 이상** 있어야 한다 — 아무것도 단정하지 않는 케이스는 dev set 을
#: 돌릴 때 그냥 지나가고, 건수만 늘린다.
#:
#: ❗처음에 이 셋을 `REQUIRED_KEYS` 에 넣었더니 **기존 케이스 둘이 빨개졌다.** 둘 다
#: 정당한 변형이었다 — `TYING-SIGNAL` 은 상신 경로를 재서 등급이 `None` 이고
#: (`expected_escalate`), `PARROTED-RUBRIC-CLAUSE` 는 복창 캡을 잰다
#: (`expected_confidence_max`). **그물이 실물을 물면 실물을 먼저 본다.**
EXPECTATION_KEYS = frozenset({
    "expected_grade", "expected_misconception",
    "expected_escalate", "expected_confidence_max",
})

GRADES = frozenset({"U1", "U2", "U3", "U4"})

#: 존댓말 종결. 넉넉하게 잡는다 — 여기서 재는 것은 **비율**이라 경계 몇 건은 안 중요하다.
_POLITE = re.compile(r"(요|습니다|십니다|세요|어요|아요|지요|죠)\s*[.?!]?\s*$")

#: 존댓말 상한.
#:
#: ❗**처음에 0.90 으로 뒀더니 안 물었다.** 반말 4건을 존댓말로 되돌리는 변이가
#: 86% 로 끝나 상한 아래였다 — 즉 되돌아가는 것을 못 잡는다. 변이가 통과한 이유가
#: 그물이 아니라 **숫자**였다.
#:
#: 지금 74% 다. 여기서 **세 건만 되돌아가도 물게** 둔다(0.80 → 29/35 = 82.9% 에서
#: 빨강). 96% 였던 원래 상태로 갈 때까지 기다리면 그 사이에 형태가 라벨과 붙는다.
MAX_POLITE_RATIO = 0.80


def _cases() -> list[tuple[str, dict]]:
    out = []
    for path in sorted(FIXTURES.glob("*.yaml")):
        doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        for case in doc.get("cases") or []:
            out.append((path.name, case))
    return out


CASES = _cases()


# ── 스키마 ───────────────────────────────────────────────────────────────────
def test_the_fixture_is_not_empty() -> None:
    """★ 공회전 방지 — 못 읽으면 아래가 아무것도 안 잰다."""
    assert CASES, f"{FIXTURES} 에서 케이스를 하나도 못 읽었다"


@pytest.mark.parametrize(("f", "case"), CASES, ids=[c["id"] for _, c in CASES])
def test_every_case_has_the_required_keys(f: str, case: dict) -> None:
    missing = sorted(REQUIRED_KEYS - set(case))
    assert not missing, f"{f}:{case.get('id')}: 신원 키 누락 {missing}"
    extra = sorted(set(case) - REQUIRED_KEYS - EXPECTATION_KEYS)
    assert not extra, f"{f}:{case['id']}: 모르는 키 {extra} — 오타이면 조용히 무시된다"
    asserts = sorted(EXPECTATION_KEYS & set(case))
    assert asserts, (
        f"{f}:{case['id']}: 기대값이 하나도 없다 — 아무것도 단정하지 않는 케이스는 "
        f"건수만 늘린다. {sorted(EXPECTATION_KEYS)} 중 하나 이상")


@pytest.mark.parametrize(("f", "case"), CASES, ids=[c["id"] for _, c in CASES])
def test_the_item_id_exists(f: str, case: dict) -> None:
    """★ 없는 `item_id` 는 dev set 을 돌릴 때 터진다 — 여기서 먼저 잡는다."""
    assert case["item_id"] in rubrics.all_rubrics(), (
        f"{f}:{case['id']}: 루브릭에 없는 item_id {case['item_id']!r}")


@pytest.mark.parametrize(("f", "case"), CASES, ids=[c["id"] for _, c in CASES])
def test_the_expected_values_are_in_range(f: str, case: dict) -> None:
    g = case.get("expected_grade")
    assert g is None or g in GRADES, f"{f}:{case['id']}: 등급 {g!r}"
    m = case.get("expected_misconception")
    if m is not None:
        known = {t.type_id for t in misconception.library()}
        assert m in known, (
            f"{f}:{case['id']}: 라이브러리에 없는 유형 {m!r} — "
            "유형ID 는 라이브러리에서만 온다")
    assert isinstance(case["deterministic"], bool), (
        f"{f}:{case['id']}: deterministic 은 bool 이어야 한다")


# ── 표본의 모양 ──────────────────────────────────────────────────────────────
def test_the_speech_levels_are_not_one_kind() -> None:
    """★ 화법이 존댓말 하나로 되돌아가지 않는다 (`#377`).

    ❗eval 표본에서 실측한 것: **합의 U1 7건이 예외 없이 두 종결 형태에 있고 그 형태의
    U4 는 0건이다.** 화법 폭이 좁으면 형태가 라벨과 붙고, 그러면 모델이 형태로 지름길을
    낸다. 크기는 끝 6자 프로토콜로 54%/51%(기준선 34%/31%)이고 이 dev set 은
    65%(기준선 56%)다 — **넓힌 뒤에도 1.2배**라 이 그물이 계속 필요하다.
    (수치와 프로토콜: `tools/MISCONCEPTION-DETECTION.md` · 재현:
    `eval/tools/measure_suffix_leak.py`. `#377` 최초의 `95%` 는 무효다.)

    비율로 재는 이유는 **건수로 재면 표본이 늘 때마다 고쳐야** 하기 때문이다.
    """
    answers = [c["answer"].strip() for _, c in CASES if c.get("answer")]
    polite = sum(bool(_POLITE.search(a)) for a in answers)
    ratio = polite / len(answers)
    assert ratio <= MAX_POLITE_RATIO, (
        f"존댓말 종결이 {polite}/{len(answers)} = {ratio:.0%} — 상한 {MAX_POLITE_RATIO:.0%}. "
        "반말·평서체를 섞는다. 화법이 한 종류면 종결어미가 라벨 표지가 된다(#377)")


@pytest.mark.parametrize("grade", sorted(GRADES))
def test_every_grade_appears(grade: str) -> None:
    """★ 네 등급이 다 있다.

    U3 가 0건이었다. **없는 등급은 프롬프트가 그 등급을 어떻게 내는지 아무도 안 본
    상태**를 뜻한다 — U3↔U4 구분(*"모른다"* vs *"틀리게 안다"*)이 프롬프트의 명시
    규칙인데 그걸 재는 표본이 없었다.
    """
    n = sum(1 for _, c in CASES if c["expected_grade"] == grade)
    assert n >= 2, (
        f"{grade} 가 {n}건 — 두 건 이상 둔다. 없는 등급은 그 경로를 아무도 안 본 것이다")


def test_the_readme_still_separates_this_from_the_official_set() -> None:
    """★ 이 디렉토리가 F-CMN-003 평가셋과 **무관하다**는 선언이 남아 있다.

    케이스를 늘릴 때 제일 먼저 낡는 문면이다 — 내가 붙인 라벨이 공식 평가셋과 섞이면
    평가 독립성이 깨진다(나는 프롬프트 당사자로 라벨링에서 배제돼 있다).
    """
    text = (FIXTURES.parent / "README.md").read_text(encoding="utf-8")
    assert "F-CMN-003" in text and "무관" in text
