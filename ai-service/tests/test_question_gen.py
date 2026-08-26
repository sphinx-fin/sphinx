"""F-INT-002 질문 생성 — 정답 노출 검사가 본체. 소유: 윤지석

기획서 5절 통제: "유도심문이 되지 않도록 질문 유형을 화이트리스트로 제한하고, 질문 안에
정답이 노출되지 않게 한다." 질문이 답을 알려주면 채점이 통과해도 이해도를 잰 것이 아니다.
API 키 없이 돈다.
"""
from __future__ import annotations

from typing import Any

import pytest

from app import question_gen as qg, templates
from app.llm_client import LlmClient, LlmError
from app.schemas import Condition, QuestionDraft, RiskItem, SourceSpan

RISK_ITEM = RiskItem(
    item_id="ELS-PRINCIPAL-LOSS-WARNING", product_id="p", name="원금손실 가능성 고지",
    importance="required", status="extracted",
    condition=Condition(
        value_text="최초기준가격의 45% 미만으로 하락한 적이 있고 만기평가일에 70% 미만인 "
                   "경우 원금 손실이 발생합니다",
        source_span=SourceSpan(page=1, start=0, end=10),
    ),
)


class FakeLlm(LlmClient):
    def __init__(self, *drafts: QuestionDraft, fail: bool = False) -> None:
        self.drafts = list(drafts)
        self.fail = fail
        self.calls: list[dict[str, Any]] = []

    def complete_json(self, **kwargs: Any) -> QuestionDraft:  # type: ignore[override]
        self.calls.append(kwargs)
        if self.fail:
            raise LlmError("호출 실패")
        return self.drafts[min(len(self.calls) - 1, len(self.drafts) - 1)]


def _gen(*drafts: QuestionDraft, asked=None, fail=False, item=None):
    llm = FakeLlm(*drafts, fail=fail)
    return qg.generate(item or RISK_ITEM, asked, "ELS", llm=llm), llm


def _draft(question: str, qtype: str = "situation") -> QuestionDraft:
    return QuestionDraft(question=question, question_type=qtype)


# ── 정답 노출 검사 ────────────────────────────────────────────────────────────
def test_numeric_condition_value_is_a_leak():
    """수치는 amount·condition 유형이 묻는 대상이다. 질문이 먼저 말하면 측정이 무효다.

    **단위를 보지 않는다.** 원문이 "45%" 인데 질문이 "45 아래로" 라고 해도 답을 알려준 것이다.
    재설명(F-INT-004)은 반대로 단위까지 일치해야 한다 — 그쪽은 환각을 막고 이쪽은 노출을 막는다.
    """
    forbidden = qg.answer_fragments(RISK_ITEM)
    assert "45" in forbidden and "70" in forbidden
    assert qg.leaked_fragments("45% 미만으로 떨어지면 어떻게 되나요?", forbidden) == ["45"]


def test_rubric_required_element_is_a_leak():
    forbidden = qg.answer_fragments(RISK_ITEM)
    hits = qg.leaked_fragments("투자원금의 손실이 발생할 수 있다는 점을 아시나요?", forbidden)
    assert hits == ["투자원금의 손실이 발생할 수 있음"]


def test_partial_phrase_is_a_leak():
    """조항을 그대로 쓰지 않아도 핵심 구절만 옮기면 답을 알려준 것이다."""
    forbidden = qg.answer_fragments(RISK_ITEM)
    assert qg.leaked_fragments("손실이 발생할 수 있는 상황을 말씀해 주세요", forbidden)


def test_misconception_condition_is_also_blocked():
    """오답을 질문에 심는 것도 유도심문이다."""
    forbidden = qg.answer_fragments(RISK_ITEM)
    assert qg.leaked_fragments("원금이 보장된다고 들으셨나요?", forbidden)


def test_clean_question_passes():
    forbidden = qg.answer_fragments(RISK_ITEM)
    assert qg.leaked_fragments("어떤 경우에 돈을 잃을 수 있는지 말씀해 주시겠어요?", forbidden) == []


def test_short_common_words_are_not_leaks():
    """'원금'·'손실' 같은 짧은 조각으로 막으면 물을 수 있는 질문이 없어진다."""
    forbidden = qg.answer_fragments(RISK_ITEM)
    assert qg.leaked_fragments("원금이 어떻게 되는지 말씀해 주시겠어요?", forbidden) == []


def test_item_without_rubric_still_checks_numbers():
    """루브릭 커버리지가 7/23 이다(이슈 #26). 루브릭이 없다고 검사를 건너뛰면
    그 항목만 유도심문이 통과한다."""
    item = RISK_ITEM.model_copy(update={"item_id": "ELS-KNOCKIN-BARRIER"})
    forbidden = qg.answer_fragments(item)
    assert "45" in forbidden
    assert not any("낙인" in f for f in forbidden)      # 루브릭 없음


# ── 재시도와 폴백 ─────────────────────────────────────────────────────────────
def test_leaking_draft_is_retried():
    leaky = _draft("45% 아래로 떨어지면 어떻게 되나요?")
    clean = _draft("어떤 경우에 돈을 잃을 수 있는지 말씀해 주시겠어요?")
    result, llm = _gen(leaky, clean)
    assert result.question == clean.question
    assert result.fallback_used is False
    assert len(llm.calls) == 2


def test_persistent_leak_falls_back():
    """인터뷰가 멈추면 세션이 진행되지 않는다 — 질문을 못 만드는 것이 더 나쁘다."""
    result, llm = _gen(_draft("45% 아래로 떨어지면요?"))
    assert result.fallback_used is True
    assert len(llm.calls) == qg.MAX_ATTEMPTS
    expected = next(i for i in templates.get("ELS").items
                    if i.item_id == RISK_ITEM.item_id).fallback_question
    assert result.question == expected


def test_llm_failure_falls_back_without_retrying():
    result, llm = _gen(_draft("x"), fail=True)
    assert result.fallback_used is True
    assert len(llm.calls) == 1


def test_fallback_question_itself_does_not_leak():
    """폴백이 정답을 노출하면 폴백이 문제를 옮기는 것뿐이다."""
    for product_type in ("ELS", "VARIABLE_INSURANCE"):
        for item in templates.get(product_type).items:
            assert item.fallback_question, item.item_id
            probe = RISK_ITEM.model_copy(update={"item_id": item.item_id})
            hits = qg.leaked_fragments(item.fallback_question, qg.answer_fragments(probe))
            assert not hits, f"{item.item_id}: {hits}"


# ── 유형 화이트리스트 ─────────────────────────────────────────────────────────
def test_type_outside_whitelist_is_retried():
    """pydantic 이 enum 을 막지만, 이미 물은 유형을 다시 내는 것도 걸러야 한다."""
    repeated = _draft("어떤 경우인지 말씀해 주시겠어요?", "situation")
    fresh = _draft("넣으신 돈이 어떻게 되는지 말씀해 주시겠어요?", "amount")
    result, _ = _gen(repeated, fresh, asked=["situation"])
    assert result.question_type == "amount"


def test_all_types_asked_resets_the_whitelist():
    """세 유형을 다 물은 뒤에도 질문은 나와야 한다 — 재검증 루프가 돌 수 있다."""
    result, _ = _gen(_draft("어떤 경우인지 말씀해 주시겠어요?", "situation"),
                     asked=list(qg.QUESTION_TYPES))
    assert result.question_type in qg.QUESTION_TYPES


# ── 프롬프트 ──────────────────────────────────────────────────────────────────
def test_prompt_does_not_contain_the_condition_text():
    """원문에는 정답 수치가 그대로 있다. 모델에게 보여주면 질문에 옮겨 쓸 가능성이 올라간다 —
    모델이 알아야 하는 것은 무엇을 묻는지이고 답이 무엇인지가 아니다."""
    _, llm = _gen(_draft("어떤 경우인지 말씀해 주시겠어요?"))
    prompt = llm.calls[0]["prompt"]
    assert RISK_ITEM.condition.value_text not in prompt
    assert "45" in prompt         # 금지 목록으로는 들어간다
    assert "45% 미만으로 하락한 적이 있고" not in prompt


def test_unknown_item_raises():
    item = RISK_ITEM.model_copy(update={"item_id": "ELS-MADE-UP"})
    with pytest.raises(templates.TemplateNotFound):
        _gen(_draft("x"), item=item)


# ── 공용 수치 추출기 (PR #60 리뷰 후속) ────────────────────────────────────────
def test_bare_number_in_question_is_a_leak():
    """`_NUMERIC` 이 단위를 필수로 요구해서 맨숫자를 놓쳤다 —
    "45 아래로 떨어지면" 이 누출 검사를 통과했다. reexplain 과 같은 추출기를 쓴다."""
    forbidden = qg.answer_fragments(RISK_ITEM)
    assert "45" in forbidden or any(f.startswith("45") for f in forbidden)
    assert qg.leaked_fragments("45 아래로 떨어지면 어떻게 되나요?", forbidden)


def test_fallback_question_type_is_stable():
    """`allowed[0]` 을 쓰면 같은 문장이 1회차엔 situation, 2회차엔 amount 로 보고돼
    유형 커버리지가 조용히 틀린다(PR #60 리뷰)."""
    first, _ = _gen(_draft("45 아래로 떨어지면요?"))
    second, _ = _gen(_draft("45 아래로 떨어지면요?"), asked=["situation"])
    third, _ = _gen(_draft("45 아래로 떨어지면요?"), asked=["situation", "amount"])
    assert first.fallback_used and second.fallback_used and third.fallback_used
    assert first.question_type == second.question_type == third.question_type
    assert first.question_type == qg.FALLBACK_QUESTION_TYPE
