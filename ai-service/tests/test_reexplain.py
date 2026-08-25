"""F-INT-004 재설명 — 수치 대조가 본체. 소유: 윤지석

기획서 5절 통제: "상품 조건값은 원문 인용만 허용하고, AI가 수치를 생성하지 못하게 막는다."
환각 수치가 고객에게 노출되면 오해를 잡겠다면서 새 오해를 만드는 것이다.

F-INT-002 와 검사 방향이 반대다 — 질문은 수치를 **금지**하고, 재설명은 **원문에서 온 수치만**
허용한다. API 키 없이 돈다.
"""
from __future__ import annotations

from typing import Any

import pytest

from app import reexplain as rx
from app.llm_client import LlmClient, LlmError
from app.schemas import (
    Condition, Evidence, Grade, Judgment, ReexplainResponse, RiskItem, SourceSpan,
)

SPAN = SourceSpan(page=10, start=72, end=120)
RISK_ITEM = RiskItem(
    item_id="ELS-KNOCKIN-BARRIER", product_id="p", name="낙인 배리어",
    importance="required", status="extracted",
    condition=Condition(
        value_text="낙인구간(각각 최초기준가격의 45%인 45/ 45/ 45) 미만으로 하락한 적이 있고",
        source_span=SPAN,
    ),
)
JUDGMENT = Judgment(
    item_id="ELS-KNOCKIN-BARRIER", grade=Grade.U3, confidence=0.9,
    evidence=Evidence(utterance_quote="잘 모르겠어요", rubric_clause="원금이 보장된다"),
    reason="조건을 설명하지 못함",
)


class FakeLlm(LlmClient):
    def __init__(self, *contents: str, fail: bool = False) -> None:
        self.contents = list(contents)
        self.fail = fail
        self.calls: list[dict[str, Any]] = []

    def complete_json(self, **kwargs: Any) -> ReexplainResponse:  # type: ignore[override]
        self.calls.append(kwargs)
        if self.fail:
            raise LlmError("호출 실패")
        content = self.contents[min(len(self.calls) - 1, len(self.contents) - 1)]
        return ReexplainResponse(item_id="from-llm", content=content, cited_spans=[])


def _reexplain(*contents: str, fail=False, age=None, exper=None):
    llm = FakeLlm(*contents, fail=fail)
    return rx.reexplain(RISK_ITEM, JUDGMENT, age, exper, llm=llm), llm


# ── P6: 수치는 원문에서만 ─────────────────────────────────────────────────────
def test_source_numerics_are_collected():
    assert rx.source_numerics(RISK_ITEM) >= {"45%", "45"}


def test_number_absent_from_source_is_fabricated():
    allowed = rx.source_numerics(RISK_ITEM)
    assert rx.fabricated_numerics("30% 떨어지면 손실입니다", allowed) == ["30%"]


def test_unit_spelling_difference_is_accepted():
    """원문이 "45%" 인데 설명이 "45 퍼센트" 로 쓴 것은 같은 값이다."""
    allowed = rx.source_numerics(RISK_ITEM)
    assert rx.fabricated_numerics("45 퍼센트 아래로 가면", allowed) == []


def test_analogy_without_numbers_passes():
    """기획서 4절 — 비유 중심. 숫자 없는 설명이 막히면 안 된다."""
    allowed = rx.source_numerics(RISK_ITEM)
    assert rx.fabricated_numerics("지수가 절반 아래로 내려가면 원금이 줄어듭니다", allowed) == []


def test_ordinals_are_not_treated_as_values():
    """문장 번호는 조건값이 아니다."""
    allowed = rx.source_numerics(RISK_ITEM)
    assert rx.fabricated_numerics("① 첫째로 기준선을 봅니다", allowed) == []


def test_fabricated_content_is_retried():
    result, llm = _reexplain("30% 떨어지면 손실", "45% 아래로 떨어지면 손실")
    assert "45%" in result.content
    assert len(llm.calls) == 2


def test_persistent_fabrication_falls_back_to_source_quote():
    """환각 수치가 섞인 설명보다 다듬어지지 않은 원문이 낫다."""
    result, llm = _reexplain("30% 떨어지면 손실")
    assert len(llm.calls) == rx.MAX_ATTEMPTS
    assert RISK_ITEM.condition.value_text in result.content
    assert rx.fabricated_numerics(result.content, rx.source_numerics(RISK_ITEM)) == []


def test_llm_failure_falls_back_without_retrying():
    """재설명을 아예 못 내면 강희진의 재검증 루프가 진행할 것이 없다."""
    result, llm = _reexplain("x", fail=True)
    assert len(llm.calls) == 1
    assert RISK_ITEM.condition.value_text in result.content


# ── 근거 스팬 ─────────────────────────────────────────────────────────────────
def test_cited_span_recorded_when_source_is_used():
    result, _ = _reexplain("최초기준가격의 45% 아래로 떨어진 적이 있으면 손실입니다")
    assert result.cited_spans == [SPAN]


def test_no_cited_span_when_nothing_from_source_is_used():
    """근거 없는 설명에 근거 스팬을 붙이면 리포트가 거짓 근거를 갖는다."""
    result, _ = _reexplain("담당자와 함께 다시 확인해 보시면 좋겠습니다")
    assert result.cited_spans == []


def test_fallback_always_cites_the_source():
    result, _ = _reexplain("30% 떨어지면 손실")
    assert result.cited_spans == [SPAN]


def test_item_id_is_pinned_to_caller_value():
    result, _ = _reexplain("45% 아래로 떨어지면 손실")
    assert result.item_id == RISK_ITEM.item_id      # LLM 은 "from-llm" 을 냈다


# ── 청중 맞춤 (기획서 4절 · 7-3) ──────────────────────────────────────────────
def test_default_audience_is_elderly():
    """기획서 3절이 고령·저경험 고객을 1순위 대상으로 지정했다."""
    assert "고령" in rx.audience_note(None, None)


def test_audience_note_uses_given_attributes():
    note = rx.audience_note("70대", "경험 없음")
    assert "70대" in note and "경험 없음" in note


def test_prompt_carries_condition_text_and_judgment():
    """질문 생성과 반대다 — 여기서는 조건 원문이 프롬프트에 있어야 한다.
    고객이 알아야 하는 수치가 거기 있고, 그것만 쓰라고 제한하려면 보여줘야 한다."""
    _, llm = _reexplain("45% 아래로 떨어지면 손실")
    prompt = llm.calls[0]["prompt"]
    assert RISK_ITEM.condition.value_text in prompt
    assert JUDGMENT.evidence.utterance_quote in prompt
    assert "미이해" in prompt          # 등급 라벨


def test_misconception_type_reaches_the_prompt():
    llm = FakeLlm("45% 아래로 떨어지면 손실")
    judgment = JUDGMENT.model_copy(update={"grade": Grade.U4,
                                           "misconception_type": "M01-PRINCIPAL-GUARANTEE"})
    rx.reexplain(RISK_ITEM, judgment, llm=llm)
    assert "M01-PRINCIPAL-GUARANTEE" in llm.calls[0]["prompt"]
