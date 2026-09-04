"""그림자 매칭이 **판정을 안 바꾸고** 켰을 때를 잰다 (이슈 #284 (b) 근거).

`#284` 가 *"선언한 오해 46개 중 강제되는 건 라이브러리 9종뿐"* 을 열었고 답은 `(b)`
유사도 매칭인데, **그건 채점 동작을 바꾸는 일**이라 정량평가 전에 켜면 등급 분포가
흔들린다. 지금은 켤지를 **근거 없이** 정해야 한다.
"""
from __future__ import annotations

import dataclasses
import logging

import pytest

from app import misconception, rubrics, scoring, shadow
from app.schemas import Grade, Judgment


def test_the_threshold_is_the_matchers_own() -> None:
    """★ 매처와 같은 임계값을 쓴다 — 다르면 여기서 잰 건수가 "켰을 때" 를 안 말한다."""
    assert shadow.THRESHOLD == misconception.NGRAM_THRESHOLD


def test_conditions_the_library_already_covers_are_not_counted(monkeypatch) -> None:
    """❗링크된 유형이 **이미 잡는** 조건은 그림자가 아니다.

    전부 세면 *"켜면 이만큼 잡힌다"* 가 부풀고, 그 숫자로 (b) 를 정하면 과대평가한다.

    ❗실물 데이터로는 못 잰다 — 지금 링크가 조건을 덮는 경우가 거의 없어서(43/46 이
    강제 통로 없음) 단정이 데이터에 걸린다. 여기서는 **덮는 상황을 만들어** 로직을 잰다.
    """
    rubric = rubrics.get("ELS-NO-DEPOSIT-INSURANCE")
    target = rubric.misconception_conditions[0]

    real = misconception.library()[0]
    covering = misconception.MisconceptionType(
        type_id="M99-TEST", label="검사용", products=("ELS",),
        patterns=(target,), escalate=None, source=real.source)
    monkeypatch.setattr(misconception, "library", lambda: (covering,))
    linked = dataclasses.replace(rubric, related_misconceptions=("M99-TEST",))

    assert target not in shadow.unenforced_conditions(linked), (
        "라이브러리가 이미 잡는 조건을 그림자로 세면 (b) 의 값이 부풀어 보인다")


def test_probe_never_returns_a_grade() -> None:
    """★ 등급을 만들지 않는다 — 이 함수가 판정에 닿으면 측정이 판정을 바꾸는 것이다."""
    hits = shadow.probe("예금자보호가 된다고 들었어요", rubrics.get("ELS-NO-DEPOSIT-INSURANCE"), "U1")

    assert all(isinstance(h, shadow.ShadowHit) for h in hits)
    assert not any(hasattr(h, "grade") for h in hits)


def test_it_says_whether_the_grade_would_have_changed() -> None:
    """이미 U4 면 켜도 안 바뀐다 — 그건 "켜서 얻는 게 없는" 건이다."""
    rubric = rubrics.get("ELS-NO-DEPOSIT-INSURANCE")
    text = "예금자보호가 된다"

    assert any(h.would_change_grade for h in shadow.probe(text, rubric, "U1"))
    assert not any(h.would_change_grade for h in shadow.probe(text, rubric, "U4"))


def test_the_meter_never_holds_an_utterance() -> None:
    """❗계량기에 발화가 안 남는다.

    조건 문면은 루브릭에서 오고 그건 공개 의무 대상이라 남겨도 새로 드러나는 것이 없다 —
    **고객이 한 말은 다르다**(P3).
    """
    meter = shadow.ShadowMeter()
    meter.record(shadow.probe("제 주민번호는 900101-1234567 입니다",
                              rubrics.get("ELS-NO-DEPOSIT-INSURANCE"), "U1"))

    everything = meter.summary() + str(meter.by_condition)
    assert "900101" not in everything


def test_the_log_never_carries_the_utterance(caplog) -> None:
    rubric = rubrics.get("ELS-NO-DEPOSIT-INSURANCE")
    with caplog.at_level(logging.INFO, logger="app.shadow"):
        shadow.observe("예금자보호가 된다고 들었는데 제 번호는 010-1234-5678 이에요", rubric, "U1")

    assert "010-1234-5678" not in caplog.text
    assert "F-DET-001 그림자 매칭" in caplog.text
    assert "판정은 안 바꿨다" in caplog.text


def test_scoring_calls_it_and_the_grade_survives(monkeypatch) -> None:
    """❗채점 경로가 실제로 부르고, **그래도 등급이 그대로다.**

    모듈 둘을 각각 재면 *"부르지만 등급을 바꾼다"* 도 *"안 부른다"* 도 안 잡힌다 —
    그 층을 여기서 지나간다.
    """
    seen: list[str] = []
    monkeypatch.setattr(shadow, "observe",
                        lambda text, rubric, grade: seen.append(grade) or [])

    rubric = rubrics.get("ELS-NO-DEPOSIT-INSURANCE")
    answer = "예금자보호가 된다고 들었어요"
    judgment = Judgment(
        item_id=rubric.item_id, grade=Grade.U1, confidence=0.9,
        evidence={"utterance_quote": answer[:10], "rubric_clause": rubric.required_elements[0]},
        reason="사유",
    )

    class _Stub:
        def complete_json(self, **kwargs):
            return judgment.model_copy()

    result = scoring.score(rubric.item_id, "질문?", answer, _risk_item(), "ELS", llm=_Stub())

    assert seen, "채점이 그림자 매칭을 안 부르면 측정이 아예 안 쌓인다"
    assert seen[0] == result.grade.value, "최종 등급으로 재야 '달라졌을 것' 이 참이 된다"


def test_a_shadow_hit_does_not_change_the_grade() -> None:
    """★ **P1** — 그림자가 걸려도 등급이 그대로다.

    이게 이 모듈의 존재 조건이다. 걸린 것을 등급에 반영하는 순간 `(b)` 를 **재는 게
    아니라 켠 것**이고, 그러면 정량평가 회차의 등급 분포가 흔들린다.

    ❗앞 테스트가 `observe` 를 목으로 갈아끼워서 이 성질을 못 잰다 — 목이 빈 목록을
    돌려주니 등급을 바꾸는 변이가 통과했다(실측). 여기서는 **실물 그림자가 걸리는
    발화**로 지나간다.
    """
    rubric = rubrics.get("ELS-NO-DEPOSIT-INSURANCE")
    answer = "예금자보호는 안 된다고 하셨죠."
    assert shadow.probe(answer, rubric, "U1"), "이 발화가 그림자에 안 걸리면 이 테스트가 공회전"
    assert not misconception.match(answer, "ELS").matches, "라이브러리가 잡으면 원인이 섞인다"

    judgment = Judgment(
        item_id=rubric.item_id, grade=Grade.U1, confidence=0.9,
        evidence={"utterance_quote": answer[:8], "rubric_clause": rubric.required_elements[0]},
        reason="사유",
    )

    class _Stub:
        def complete_json(self, **kwargs):
            return judgment.model_copy()

    result = scoring.score(rubric.item_id, "질문?", answer, _risk_item(), "ELS", llm=_Stub())

    assert result.grade == Grade.U1, (
        "그림자가 등급을 바꾸면 (b) 를 재는 게 아니라 켠 것이다 — 정량평가 회차의 "
        "등급 분포가 흔들린다")


def _risk_item():
    from app.schemas import RiskItem
    return RiskItem(
        item_id="ELS-NO-DEPOSIT-INSURANCE", product_id="p", name="예금자보호",
        importance="required", status="extracted",
        condition={"value_text": "예금자보호법에 따라 보호되지 않습니다",
                   "source_span": {"page": 1, "start": 0, "end": 20}},
    )
