"""F-SCR-001 채점 후처리 검증. 소유: 윤지석

기획서 5절 [채점 성능 목표와 오판 처리]의 비대칭을 코드로 고정한 부분을 검증한다.
후처리는 전부 한 방향(안전한 쪽)으로만 움직여야 한다.
"""
from __future__ import annotations

import pytest

from app import rubrics, scoring
from app.llm_client import LlmError
from app.schemas import Condition, Grade, RiskItem, SourceSpan
from tests.helpers import FakeLlm, make_judgment

RISK_ITEM = RiskItem(
    item_id="ELS-PRINCIPAL-LOSS-WARNING",
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


def _score(judgment, answer=DEMO_ANSWER, item_id="ELS-PRINCIPAL-LOSS-WARNING"):
    llm = FakeLlm(judgment)
    return scoring.score(item_id, QUESTION, answer, RISK_ITEM, "ELS", llm=llm), llm


# ── 루브릭 ────────────────────────────────────────────────────────────────────
def test_no_rubric_references_a_missing_misconception_type():
    """없는 유형을 참조하면 apply_misconception_floor 가 조용히 발동하지 않는다.

    M07-YIELD-OVERCONFIDENCE 가 근거 미확보로 라이브러리에서 빠졌을 때 두 루브릭이
    그것을 계속 참조했고, 채점은 성공한 채로 결정론 상향만 사라졌다. 개수 단정문이
    뒤늦게 잡았을 뿐이다 — 이 검사가 그 실패 양식을 로딩 시점으로 끌어올린다."""
    rubrics.assert_related_misconceptions_exist()
def test_rubric_clauses_reach_the_prompt():
    """루브릭 공개 의무(기획서 5절)는 프롬프트에 실제로 들어가야 의미가 있다."""
    rubric = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING")
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
    ELS-EARLY-REDEMPTION-CONDITION 루브릭은 M01을 관련 유형으로 선언하지 않았다."""
    judgment, _ = _score(
        make_judgment(grade=Grade.U1, confidence=0.95, item_id="ELS-EARLY-REDEMPTION-CONDITION"),
        item_id="ELS-EARLY-REDEMPTION-CONDITION",
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
        make_judgment(grade=Grade.U4, item_id="ELS-EARLY-REDEMPTION-CONDITION",
                      misconception_type="M-존재하지-않는-유형"),
        item_id="ELS-EARLY-REDEMPTION-CONDITION",
    )
    assert judgment.misconception_type is None


def test_library_type_still_wins_over_discarded_llm_value():
    judgment, _ = _score(make_judgment(grade=Grade.U4, misconception_type="M-엉터리"))
    assert judgment.misconception_type == "M01-PRINCIPAL-GUARANTEE"


# ── P4: 루브릭 조항 대조 (강희진 리뷰 반영) ───────────────────────────────────
def test_clause_outside_the_rubric_is_rejected():
    """근거로 적힌 조항이 공개 루브릭에 없으면 근거 표시 의무가 형식만 남는다."""
    bogus = make_judgment()
    bogus = bogus.model_copy(update={
        "evidence": bogus.evidence.model_copy(
            update={"rubric_clause": "고객이 충분히 이해한 것으로 보임"})})
    with pytest.raises(LlmError, match="루브릭 밖"):
        _score(bogus)


def test_published_clause_passes():
    rubric = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING")
    for clause in rubric.required_elements + rubric.misconception_conditions:
        j = make_judgment()
        j = j.model_copy(update={
            "evidence": j.evidence.model_copy(update={"rubric_clause": clause})})
        assert _score(j)[0].evidence.rubric_clause == clause


def test_two_clauses_joined_are_accepted():
    """실측에서 모델이 "A 및 B"로 합쳐 인용했다. 공개 조항으로 환원되므로 추적 가능하다."""
    rubric = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING")
    joined = f"{rubric.required_elements[0]} 및 {rubric.misconception_conditions[0]}"
    j = make_judgment()
    j = j.model_copy(update={
        "evidence": j.evidence.model_copy(update={"rubric_clause": joined})})
    assert _score(j)[0].grade is Grade.U4   # 오해 상향은 그대로 동작


def test_clause_with_extra_content_is_rejected():
    """조항에 없는 내용이 붙으면 거부한다 — 합성 허용이 구멍이 되지 않게."""
    rubric = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING")
    j = make_judgment()
    j = j.model_copy(update={"evidence": j.evidence.model_copy(
        update={"rubric_clause": rubric.required_elements[0] + " 이므로 판매 가능하다"})})
    with pytest.raises(LlmError, match="루브릭 밖"):
        _score(j)


# ── 신뢰도는 그대로 통과시킨다 (게이트가 판단) ────────────────────────────────
def test_low_confidence_grade_is_not_altered():
    """황색 강등은 게이트 정책이다(강희진 결정). ai-service는 측정값만 낸다 —
    양쪽에서 하면 이중계산이 된다."""
    judgment, _ = _score(make_judgment(grade=Grade.U1, confidence=0.3,
                                       item_id="ELS-EARLY-REDEMPTION-CONDITION"),
                         item_id="ELS-EARLY-REDEMPTION-CONDITION")
    assert judgment.grade is Grade.U1
    assert judgment.confidence == 0.3
    assert "강등" not in judgment.reason


# ── item_id 고정 ──────────────────────────────────────────────────────────────
def test_item_id_is_pinned_to_caller_value():
    """LLM이 엉뚱한 item_id를 써 보내도 호출자가 지정한 항목이 진실이다."""
    bogus = make_judgment(item_id="ELS-PRINCIPAL-LOSS-WARNING").model_copy(
        update={"item_id": "WRONG-ID"})
    judgment, _ = _score(bogus)
    assert judgment.item_id == "ELS-PRINCIPAL-LOSS-WARNING"


# ── 데모 임계 경로 ────────────────────────────────────────────────────────────
def test_demo_main_scenario_is_red_without_relying_on_the_llm():
    """기획서 7-2 ④ 적색 3건 중 원금손실 오해.
    LLM이 최악으로 틀려도(U1, 확신 0.99) 결과는 U4여야 한다."""
    judgment, _ = _score(make_judgment(grade=Grade.U1, confidence=0.99))
    assert judgment.grade is Grade.U4, "데모의 임계 경로가 LLM 응답에 의존하고 있다"


# ── 유니코드 정규화 (ADR-008 검토에서 발견) ────────────────────────────────────
def test_decomposed_hangul_quote_is_accepted():
    """한글 조합형(NFD) 인용이 완성형(NFC) 발화와 대조돼야 한다.

    눈으로 같고 바이트가 다르다. 모델이 조합형으로 돌려주면 글자가 같은데도 P4 위반으로
    거부됐다 — 실측으로 재현한 뒤 고쳤다. ADR-008 이 짚은 그 지점이다.
    """
    import unicodedata

    nfd_quote = unicodedata.normalize("NFD", "원금은 지켜지는")
    assert not unicodedata.is_normalized("NFC", nfd_quote)
    judgment, _ = _score(make_judgment(quote=nfd_quote))
    assert judgment.grade is Grade.U4          # 정상 경로로 끝까지 진행


def test_decomposed_rubric_clause_is_accepted():
    """조항 인용도 같다 — 대조 함수를 공유한다."""
    import unicodedata

    rubric = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING")
    nfd_clause = unicodedata.normalize("NFD", rubric.required_elements[0])
    judgment, _ = _score(make_judgment(rubric_clause=nfd_clause))
    assert judgment.evidence.rubric_clause == nfd_clause   # 저장값은 손대지 않는다


def test_fabricated_quote_still_rejected_after_normalization():
    """정규화가 검증을 무르게 하지 않았음을 고정한다."""
    with pytest.raises(LlmError, match="P4"):
        _score(make_judgment(quote="고객이 원금 손실을 이해한다고 답변함"))
