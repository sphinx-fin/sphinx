"""`reason` 문면 그물을 **전수**로 만든다 — 목록이 아니라 축으로 (이슈 #380).

`#372` 리뷰에서 나왔다. *"어느 캡도 계기를 `reason` 에 안 적는다"* 는 그물이 있었는데
**함수 하나만 부르고 있었다.** 여기서 전수로 만든다.

## 왜 중요한가 — 마지막으로 새는 자리다

서버 `JudgmentView` 가 `reason` 을 **판매자 화면에 그대로 싣는다**(강희진 확인, `#380`).
`misconception_type`·`escalate` 는 이미 `JudgmentView` 에서 빠졌지만 **`reason` 은
통과한다.** 캡이 식별 가능한 계기를 문면에 적으면 판매자가 그걸 읽고 우회한다 —
기획 7-4 가 막으려는 그 지점이고, 구조화된 필드로 막은 방어가 **문자열 조립으로 새는**
모양이다(결정 3.24).

## ❗단어 목록으로 넓히면 합의된 문면을 문다

`banned` 목록을 그대로 두고 함수만 늘리면 `apply_misconception_floor` 의
*"(오해 라이브러리 매칭 [pattern 1.0, 근거 dispute_case] → U4 상향)"* 이 걸린다. 그 문면은
**`#160 ②` 가 남기기로 정한 것**이다 — 유형ID 는 빼되 상향 사실과 강도·근거 등급은 남긴다.
`"U4"` 는 금지어가 **아니다.**

## 축 — 무엇이 허용이고 무엇이 금지인가

    허용   측정값 · 판정 자신이 이미 화면에 싣는 값
           "포함도 1.00 ≥ 0.6" · "→ U4 상향"(U4 가 그 판정의 최종 등급이다)
           → 다음 행동을 지정하지 않는다. 판매자가 읽어도 무엇을 바꿀지 모른다

    금지   그 캡만 본 **숨은 입력**
           두 번째 등급 · 유형ID · 입력 방식
           → 무엇을 바꾸면 신호가 죽는지 그대로 지목한다

`scoring.py:475` 가 이 축을 이미 말로 적어 뒀다 — *"복창 포함도는 **측정값**이고 재현되지
않았다는 **정황**이지만, 붙여넣기는 **다음 행동을 그대로 지정한다**"*. 여기서 기계로 만든다.

**등급 토큰을 판정 자신의 최종 등급으로 재는 것이 이 축의 핵심이다.** `"→ U4 상향"` 은
`grade` 가 이미 U4 라 새는 게 없고, 두 번째 등급은 **화면에 없는 값**이라 샌다. 같은
`"U4"` 라는 글자가 한쪽은 허용이고 한쪽은 금지인 이유가 목록으로는 안 적힌다.

## 모집단은 소스에서 뽑는다 (결정 9.35)

손목록이면 **손이 빠뜨린 것은 영원히 안 걸린다.** `scoring.py` 를 파싱해서 「판정을 받아
판정을 돌려주는 공개 함수」를 전부 뽑고, 태우는 자리(`_EXERCISES`)와 **집합이 같은지**
본다 — 캡을 새로 만들고 여기 안 적으면 빨강이다.
"""
from __future__ import annotations

import ast
import re
from pathlib import Path

import pytest

from app import misconception, rubrics, scoring
from app.schemas import Evidence, Grade, InputMeta, Judgment, RiskItem

SCORING_PY = Path(scoring.__file__)

ITEM = "ELS-PRINCIPAL-LOSS-WARNING"
ANSWER = "제가 낸 돈보다 적게 돌려받을 수도 있다는 뜻으로 이해했습니다"


# ── 모집단: 소스에서 뽑는다 ────────────────────────────────────────────────────
def _post_processors() -> set[str]:
    """`judgment` 를 받아 `Judgment` 를 돌려주는 공개 함수 = 후처리 캡."""
    tree = ast.parse(SCORING_PY.read_text(encoding="utf-8"))
    return {
        fn.name
        for fn in tree.body
        if isinstance(fn, ast.FunctionDef)
        and not fn.name.startswith("_")
        and "judgment" in [a.arg for a in fn.args.args]
        and fn.returns is not None
        and ast.unparse(fn.returns) == "Judgment"
    }


def _writes_reason(name: str) -> bool:
    """그 함수가 `reason` 을 대입하는가 — 소스가 답한다."""
    tree = ast.parse(SCORING_PY.read_text(encoding="utf-8"))
    fn = next(f for f in tree.body if isinstance(f, ast.FunctionDef) and f.name == name)
    return any(isinstance(n, ast.Constant) and n.value == "reason" for n in ast.walk(fn))


# ── 태우는 자리 ────────────────────────────────────────────────────────────────
def _judgment(grade: Grade = Grade.U1, confidence: float = 1.0,
              reason: str = "고객이 원금 손실 가능성을 자기 말로 설명했다") -> Judgment:
    rubric = rubrics.get(ITEM)
    return Judgment(
        item_id=ITEM, grade=grade, confidence=confidence,
        evidence=Evidence(utterance_quote=ANSWER[:12],
                          rubric_clause=rubric.required_elements[0]),
        reason=reason,
    )


def _risk_item() -> RiskItem:
    return RiskItem(
        item_id=ITEM, product_id="p", name="원금 손실",
        importance="required", status="extracted",
        condition={"value_text": "만기평가일에 최초기준가격의 65% 미만",
                   "source_span": {"page": 1, "start": 0, "end": 20}},
    )


class _SecondOpinion:
    """자기일관성 2차 호출 — **첫 판정과 다른 등급**을 돌려준다."""

    def __init__(self, grade: Grade) -> None:
        self._grade = grade

    def complete_json(self, **kwargs) -> Judgment:
        return _judgment(grade=self._grade, reason="2차")


def _fire_echoed() -> tuple[Judgment, Judgment]:
    rubric = rubrics.get(ITEM)
    before = _judgment()
    after = scoring.cap_confidence_if_echoed(
        before, rubric.required_elements[0], rubric, _risk_item())
    return before, after


def _fire_pasted() -> tuple[Judgment, Judgment]:
    before = _judgment()
    meta = InputMeta(first_keystroke_delay_ms=0, total_input_ms=0, paste_detected=True,
                     backspace_count=0, char_count=len(ANSWER), elderly_mode=False)
    return before, scoring.cap_confidence_if_pasted(before, meta)


def _fire_inconsistent() -> tuple[Judgment, Judgment]:
    before = _judgment(grade=Grade.U1)
    after = scoring.cap_confidence_if_inconsistent(
        before, _SecondOpinion(Grade.U3), prompt="(테스트)", attempt=0)
    return before, after


def _fire_floor() -> tuple[Judgment, Judgment]:
    rubric = rubrics.get(ITEM)
    type_id = sorted(rubric.related_misconceptions)[0]
    utterance = _pattern_for(type_id)
    matched = misconception.match(utterance, "ELS")
    assert [m.type_id for m in matched.matches] == [type_id], "매칭이 안 서면 캡이 안 돈다"
    before = _judgment(grade=Grade.U1)
    return before, scoring.apply_misconception_floor(before, matched, rubric)


def _pattern_for(type_id: str) -> str:
    """라이브러리에 등재된 패턴 발화 하나 — 오해 매칭을 결정론 경로로 태운다."""
    for entry in misconception.library():
        if entry.type_id == type_id:
            return entry.patterns[0]
    raise AssertionError(f"{type_id} 가 라이브러리에 없다")


_EXERCISES = {
    "cap_confidence_if_echoed": _fire_echoed,
    "cap_confidence_if_pasted": _fire_pasted,
    "cap_confidence_if_inconsistent": _fire_inconsistent,
    "apply_misconception_floor": _fire_floor,
}


# ── 모집단이 맞는지부터 (양성 대조 · 결정 9.36) ────────────────────────────────
def test_the_population_is_read_from_the_source() -> None:
    """뽑은 자리가 0 건이면 **그물이 아무것도 안 재고 초록이다.**"""
    found = _post_processors()
    assert found, (
        "`scoring.py` 에서 후처리 캡을 하나도 못 뽑았다 — 시그니처 규약이 바뀌었으면 "
        "이 추출을 먼저 고친다. 우회로 예외를 두면 그물이 찢어진다")


def test_every_cap_is_exercised_here() -> None:
    """캡을 새로 만들고 여기 안 적으면 빨강이다 — 손목록의 구멍을 막는 자리다."""
    assert _post_processors() == set(_EXERCISES), (
        "소스의 후처리 캡과 여기서 태우는 자리가 다르다.\n"
        f"  소스에만: {sorted(_post_processors() - set(_EXERCISES))}\n"
        f"  여기에만: {sorted(set(_EXERCISES) - _post_processors())}\n"
        "새 캡이 `reason` 에 무엇을 적어도 되는지는 **축으로** 판단한다 — 모듈 docstring 참조")


# ── 축 ────────────────────────────────────────────────────────────────────────
@pytest.mark.parametrize("name", sorted(_EXERCISES))
def test_the_cap_actually_fires(name: str) -> None:
    """안 걸리는 캡을 재면 **아무것도 안 잰다.**"""
    before, after = _EXERCISES[name]()
    assert (after.confidence, after.grade) != (before.confidence, before.grade), (
        f"{name} 이 안 걸렸다 — 이 태우는 자리가 낡았다")


@pytest.mark.parametrize("name", sorted(_EXERCISES))
def test_the_reason_is_only_appended_to(name: str) -> None:
    """캡은 앞선 사유를 **접두어로 보존**한다 — 감사에서 순서를 되짚는다.

    이게 서야 아래 두 검사가 **캡이 덧붙인 부분만** 볼 수 있다. 모델이 쓴 원문까지 재면
    *"고객이 U1 수준으로 이해했다"* 같은 정상 문면에 걸린다.
    """
    before, after = _EXERCISES[name]()
    assert after.reason.startswith(before.reason), (
        f"{name} 이 앞선 사유를 지우거나 바꿔 썼다 — 덧붙이기가 아니면 추적이 끊긴다.\n"
        f"  before: {before.reason}\n  after:  {after.reason}")


@pytest.mark.parametrize("name", sorted(_EXERCISES))
def test_no_hidden_grade_reaches_the_reason(name: str) -> None:
    """등급 토큰은 **그 판정의 최종 등급 하나**만 나온다 (`#370`).

    `"→ U4 상향"` 이 허용인 이유가 이것이다 — U4 가 이미 `grade` 라 화면에 있다. 두 번째
    등급은 **화면에 없는 값**이라, 읽으면 *"이 항목은 U1/U3 경계다"* 를 알려주고 그건
    어디를 다시 물어야 게이트가 열리는지를 지목한다(기획 7-4).
    """
    before, after = _EXERCISES[name]()
    added = after.reason[len(before.reason):]
    leaked = {g.value for g in Grade if g.value in added} - {after.grade.value}
    assert not leaked, (
        f"{name} 이 최종 등급이 아닌 등급 {sorted(leaked)} 를 판매자 화면에 싣는다 "
        f"(최종 등급은 {after.grade.value}). 문면: {added!r}")


@pytest.mark.parametrize("name", sorted(_EXERCISES))
def test_no_misconception_id_reaches_the_reason(name: str) -> None:
    """유형ID 는 어느 캡에서도 안 나온다 (`#160 ②`).

    `escalate: compliance` 유형(`M08-TYING`)이 걸렸을 때 `#147`·`#159`·`#145` 가 막아 둔
    비노출 조치를 **문자열로 우회**하는 자리다.
    """
    before, after = _EXERCISES[name]()
    added = after.reason[len(before.reason):]
    assert not re.search(r"M\d{2}\b", added), (
        f"{name} 이 유형ID 를 판매자 화면에 싣는다. 문면: {added!r}")


def test_the_input_method_cap_writes_no_reason_at_all() -> None:
    """입력 방식 캡만 **아무 말도 안 한다** — 다른 셋과 다른 자리다 (`#372`).

    포함도는 측정값이고 "재현되지 않았다" 는 정황이지만, 붙여넣기는 **다음 행동을 그대로
    지정한다**: 손으로 옮겨 적으면 신호만 죽고 행동은 그대로다. 그래서 이 캡은 축의
    허용 쪽에 담을 문면이 아예 없다.

    사유는 `log.info` 에 남고 감사 경로는 불변 기록이다 — `inputMeta` 원본이 통째로
    들어 있다(`#340`).
    """
    assert not _writes_reason("cap_confidence_if_pasted"), (
        "`cap_confidence_if_pasted` 가 `reason` 을 대입한다 — 소스가 이미 갈렸다")
    before, after = _fire_pasted()
    assert after.reason == before.reason, "입력 방식 캡이 문면을 건드렸다"


def test_the_agreed_wording_survives_this_net() -> None:
    """❗**허용 쪽 경로를 같이 태운다** — `#160 ②` 가 남기기로 정한 문면이다.

    금지만 재면 *"오탐이 0"* 과 *"허용 문면이 사라졌다"* 가 구분되지 않는다. 단어 목록으로
    넓히면 정확히 여기가 물리므로, 그 자리를 눈에 보이게 둔다.

        (오해 라이브러리 매칭 [pattern 1.0, 근거 dispute_case] → U4 상향)
         ^^^^^^^^^^^^^^^^                                        ^^^^^^^
         상향 사실 · 강도 · 근거 등급                             최종 등급

    유형ID 만 빠지고 감사에 필요한 것은 다 남는다.
    """
    before, after = _fire_floor()
    added = after.reason[len(before.reason):]
    assert "오해 라이브러리" in added, "상향 사실이 사라졌다 — 감사에서 왜 U4 였는지 못 짚는다"
    assert "U4 상향" in added, "최종 등급 표기가 사라졌다"
    assert after.grade is Grade.U4, "이 문면이 참이려면 최종 등급이 U4 여야 한다"
