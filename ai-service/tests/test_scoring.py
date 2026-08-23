"""F-SCR-001 채점 후처리 검증. 소유: 윤지석

기획서 5절 [채점 성능 목표와 오판 처리]의 비대칭을 코드로 고정한 부분을 검증한다.
후처리는 전부 한 방향(안전한 쪽)으로만 움직여야 한다.
"""
from __future__ import annotations

import pytest

from app import rubrics, scoring
from app.llm_client import LlmError
from app.schemas import Condition, Grade, RiskItem, SourceSpan
from tests.conftest import FakeLlm, make_judgment

RISK_ITEM = RiskItem(
    item_id="ELS-PRINCIPAL-LOSS",
    product_id="mock-els-001",
    name="원금손실 조건",
    importance="required",
    condition=Condition(
        value_text="만기평가일에 기초자산 중 하나라도 최초기준가격의 50% 미만인 경우 …(원문 인용)",
        source_span=SourceSpan(page=3, start=120, end=210),
    ),
    status="extracted",
)
QUESTION = "이 상품에서 원금 손실이 나는 상황을 본인 말씀으로 설명해 주시겠어요?"
DEMO_ANSWER = "은행에서 파는 거니까 원금은 지켜지는 거죠?"


def _score(judgment, answer=DEMO_ANSWER, item_id="ELS-PRINCIPAL-LOSS"):
    llm = FakeLlm(judgment)
    return scoring.score(item_id, QUESTION, answer, RISK_ITEM, "ELS", llm=llm), llm


# ── 루브릭 ────────────────────────────────────────────────────────────────────
def test_rubric_clauses_reach_the_prompt():
    """루브릭 공개 의무(기획서 5절)는 프롬프트에 실제로 들어가야 의미가 있다."""
    rubric = rubrics.get("ELS-PRINCIPAL-LOSS")
    prompt = scoring.build_prompt(rubric, RISK_ITEM, QUESTION, DEMO_ANSWER)
    for clause in rubric.required_elements + rubric.misconception_conditions:
        assert clause in prompt
    assert RISK_ITEM.condition.value_text in prompt
    assert DEMO_ANSWER in prompt


def test_system_prompt_states_the_conservative_rule():
    system = scoring.load_system_prompt()
    assert "U2" in system and "U1" in system


def test_unknown_item_id_raises():
    with pytest.raises(rubrics.RubricNotFound):
        scoring.score("NO-SUCH-ITEM", QUESTION, DEMO_ANSWER, RISK_ITEM,
                      llm=FakeLlm(make_judgment()))


# ── P4: 근거 인용 대조 ────────────────────────────────────────────────────────
def test_fabricated_quote_is_rejected():
    """지어낸 인용은 근거 없는 것보다 나쁘다 — 감사 시점에 검증 불가한 기록이 남는다."""
    bogus = make_judgment(quote="고객이 원금 손실을 이해한다고 답변함")
    with pytest.raises(LlmError, match="P4"):
        _score(bogus)


def test_verbatim_quote_with_different_spacing_is_accepted():
    judgment, _ = _score(make_judgment(quote="원금은  지켜지는  거죠"))
    assert judgment.evidence.utterance_quote


# ── 오해 라이브러리 상향 (오해→이해 오판 상한 1%) ──────────────────────────────
def test_library_match_raises_grade_to_u4():
    """LLM이 이해로 봤어도 분쟁조정례 오해 문장이면 U4다."""
    judgment, _ = _score(make_judgment(grade=Grade.U1, confidence=0.95))
    assert judgment.grade is Grade.U4
    assert judgment.misconception_type == "M01-PRINCIPAL-GUARANTEE"
    assert "U4 상향" in judgment.reason  # 감사 추적


def test_floor_only_applies_to_rubric_related_types():
    """다른 항목의 오해가 이 항목 등급을 끌어내리면 안 된다.
    ELS-EARLY-REDEMPTION 루브릭은 M01을 관련 유형으로 선언하지 않았다."""
    judgment, _ = _score(
        make_judgment(grade=Grade.U1, confidence=0.95, item_id="ELS-EARLY-REDEMPTION"),
        item_id="ELS-EARLY-REDEMPTION",
    )
    assert judgment.grade is Grade.U1
    assert judgment.misconception_type is None


def test_already_u4_keeps_its_reason_but_gains_type():
    judgment, _ = _score(make_judgment(grade=Grade.U4, confidence=0.9, reason="원문 사유"))
    assert judgment.grade is Grade.U4
    assert judgment.reason == "원문 사유"          # 상향할 게 없으면 사유를 건드리지 않는다
    assert judgment.misconception_type == "M01-PRINCIPAL-GUARANTEE"


def test_llm_supplied_misconception_type_is_discarded():
    """실측에서 모델이 존재하지 않는 유형ID(M-PRINCIPAL-GUARANTEE)를 지어냈다.
    유형ID는 오해 지도 집계 키이므로 환각이 하나 섞이면 집계가 조용히 오염된다.
    루브릭이 관련 유형을 선언하지 않은 항목에서는 반드시 None이어야 한다."""
    judgment, _ = _score(
        make_judgment(grade=Grade.U4, item_id="ELS-EARLY-REDEMPTION",
                      misconception_type="M-존재하지-않는-유형"),
        item_id="ELS-EARLY-REDEMPTION",
    )
    assert judgment.misconception_type is None


def test_library_type_still_wins_over_discarded_llm_value():
    judgment, _ = _score(make_judgment(grade=Grade.U4, misconception_type="M-엉터리"))
    assert judgment.misconception_type == "M01-PRINCIPAL-GUARANTEE"


# ── 신뢰도 강등 (기획서: 애매하면 부분이해) ────────────────────────────────────
def test_low_confidence_u1_becomes_u2_with_audit_trail():
    judgment = scoring.downgrade_low_confidence(make_judgment(Grade.U1, 0.4))
    assert judgment.grade is Grade.U2
    assert "강등" in judgment.reason


def test_low_confidence_u4_is_never_relaxed():
    """U4→황색은 오해한 고객의 통과. 상한 1%로 관리하는 치명적 오판이다."""
    assert scoring.downgrade_low_confidence(make_judgment(Grade.U4, 0.1)).grade is Grade.U4


def test_low_confidence_u3_is_not_raised():
    """강등은 한 방향이다 — U3(미이해)를 U2로 올리지 않는다."""
    assert scoring.downgrade_low_confidence(make_judgment(Grade.U3, 0.1)).grade is Grade.U3


def test_high_confidence_is_untouched():
    original = make_judgment(Grade.U1, 0.95)
    assert scoring.downgrade_low_confidence(original) == original


# ── item_id 고정 ──────────────────────────────────────────────────────────────
def test_item_id_is_pinned_to_caller_value():
    judgment, _ = _score(make_judgment(item_id="WRONG-ID"))
    assert judgment.item_id == "ELS-PRINCIPAL-LOSS"


# ── 데모 임계 경로 ────────────────────────────────────────────────────────────
def test_demo_main_scenario_is_red_without_relying_on_the_llm():
    """기획서 7-2 ④ 적색 3건 중 원금손실 오해.
    LLM이 최악으로 틀려도(U1, 확신 0.99) 결과는 U4여야 한다."""
    judgment, _ = _score(make_judgment(grade=Grade.U1, confidence=0.99))
    assert judgment.grade is Grade.U4, "데모의 임계 경로가 LLM 응답에 의존하고 있다"
