"""같은 발화를 다시 채점해 **재현되는지**를 잰다 (F-SCR-001).

## 왜 필요한가 — 자기보고 신뢰도가 죽어 있다

프롬프트 v2 가 `confidence` 를 *"다른 채점자에게도 같게 나올 것인가"* 로 정의해 놓고
**모델에게 그걸 물어본다.**

    ADR-005 (dev 24건)   [0.7, 0.9, 1.0]
    라이브 (#339, 6건)    고유값 1개 — 1.0

즉 `R-05`(`anyConfidenceBelow 0.7`)가 **모델 자기보고로는 한 번도 안 돈다.** 지금 그 룰을
발동시키는 것은 복창 캡뿐이다(`#268` · 결정 10.15).

여기서 하는 것은 그 정의를 **직접 재는 것**이다.
"""
from __future__ import annotations

import logging

import pytest

from app import scoring
from app.schemas import Grade, Judgment, RiskItem


def _risk_item() -> RiskItem:
    return RiskItem(
        item_id="ELS-PRINCIPAL-LOSS-WARNING", product_id="p", name="원금 손실",
        importance="required", status="extracted",
        condition={"value_text": "만기평가일에 최초기준가격의 65% 미만",
                   "source_span": {"page": 1, "start": 0, "end": 20}},
    )


def _judgment(grade: Grade, answer: str, confidence: float = 1.0) -> Judgment:
    from app import rubrics
    rubric = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING")
    return Judgment(
        item_id=rubric.item_id, grade=grade, confidence=confidence,
        evidence={"utterance_quote": answer[:10], "rubric_clause": rubric.required_elements[0]},
        reason="사유",
    )


class _Sequence:
    """호출마다 다음 판정을 돌려준다. seed 도 기록한다."""

    def __init__(self, *judgments: Judgment):
        self._queue = list(judgments)
        self.seeds: list[object] = []

    def complete_json(self, **kwargs):
        self.seeds.append(kwargs.get("seed"))
        return self._queue.pop(0) if len(self._queue) > 1 else self._queue[0]


ANSWER = "제가 낸 돈보다 적게 돌려받을 수도 있다는 뜻으로 이해했습니다"


def test_a_reproduced_grade_keeps_its_confidence() -> None:
    """같게 나오면 안 깎는다 — 재현되는 판정을 깎으면 R-05 가 늘 물린다."""
    llm = _Sequence(_judgment(Grade.U1, ANSWER), _judgment(Grade.U1, ANSWER))

    result = scoring.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", ANSWER,
                           _risk_item(), "ELS", llm=llm)

    assert result.confidence == 1.0
    assert len(llm.seeds) == 2, "통과 판정은 한 번 더 물어야 한다"


def test_a_grade_that_does_not_reproduce_loses_confidence() -> None:
    """★ 두 번이 갈리면 확신도를 깎는다 — 이게 자기보고가 못 하던 일이다."""
    llm = _Sequence(_judgment(Grade.U1, ANSWER), _judgment(Grade.U3, ANSWER))

    result = scoring.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", ANSWER,
                           _risk_item(), "ELS", llm=llm)

    assert result.confidence == scoring.DISAGREEMENT_CONFIDENCE_CAP
    assert "재현되지 않아" in result.reason, (
        "조용히 숫자만 바뀌면 감사 시점에 왜 황색이었는지 설명할 수 없다")


def test_the_grade_itself_never_changes() -> None:
    """★ **P1** — 두 번째가 U4 라도 등급은 첫 판정 그대로다.

    바꾸면 그건 측정이 아니라 판정이고, *"두 번 중 나쁜 쪽"* 이라는 룰을 코드가 몰래
    갖게 된다. 갈렸다는 사실을 **확신도로 보고하고 판정은 게이트가 한다.**
    """
    llm = _Sequence(_judgment(Grade.U1, ANSWER), _judgment(Grade.U4, ANSWER))

    result = scoring.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", ANSWER,
                           _risk_item(), "ELS", llm=llm)

    assert result.grade == Grade.U1


def test_the_cap_is_below_the_gate_threshold() -> None:
    """❗상한이 `R-05`(0.7) **아래**여야 게이트가 받는다.

    위면 숫자만 내려가고 아무 일도 안 일어난다 — 그러면 이 검사가 도는데 결과가 없다.
    `EchoCapBelowR05Test` 가 복창 캡에 세운 것과 같은 자리다.
    """
    assert scoring.DISAGREEMENT_CONFIDENCE_CAP < 0.7
    assert scoring.DISAGREEMENT_CONFIDENCE_CAP != scoring.ECHO_CONFIDENCE_CAP, (
        "복창 캡과 값이 같으면 감사 시점에 어느 이유로 깎였는지 숫자로 안 갈린다")


def test_a_failed_grade_is_not_asked_twice() -> None:
    """통과 쪽만 다시 묻는다 — U4 를 재확인해 봐야 이미 막혀 있고 호출만 두 배다."""
    llm = _Sequence(_judgment(Grade.U4, ANSWER))

    scoring.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", ANSWER, _risk_item(), "ELS", llm=llm)

    assert len(llm.seeds) == 1, (
        "P5 가 미탐을 과탐보다 비싸게 다루므로 다시 물어야 하는 것은 '이해했다' 쪽이다")


def test_the_second_ask_uses_a_different_seed() -> None:
    """같은 seed 로 물으면 같은 답이 올 확률이 올라가 검사가 무뎌진다."""
    llm = _Sequence(_judgment(Grade.U1, ANSWER), _judgment(Grade.U1, ANSWER))

    scoring.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", ANSWER, _risk_item(), "ELS", llm=llm)

    assert llm.seeds[0] != llm.seeds[1] or llm.seeds[0] is None


def test_a_failing_second_call_does_not_stop_the_interview(caplog) -> None:
    """❗재확인이 죽어도 판정은 유효하다 — 여기서 502 를 올리면 인터뷰가 멈춘다."""
    from app.llm_client import LlmError

    class _DiesOnSecond:
        def __init__(self):
            self.calls = 0

        def complete_json(self, **kwargs):
            self.calls += 1
            if self.calls > 1:
                raise LlmError("두 번째 호출 실패")
            return _judgment(Grade.U1, ANSWER)

    with caplog.at_level(logging.INFO, logger="app.scoring"):
        result = scoring.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", ANSWER,
                               _risk_item(), "ELS", llm=_DiesOnSecond())

    assert result.grade == Grade.U1
    assert result.confidence == 1.0
    assert "자기일관성 확인 실패" in caplog.text, (
        "조용히 안 도는 것과 도는데 일치하는 것은 다르다")
