"""F-INT-002 질문 생성 — 정답 노출 검사가 본체. 소유: 윤지석

기획서 5절 통제: "유도심문이 되지 않도록 질문 유형을 화이트리스트로 제한하고, 질문 안에
정답이 노출되지 않게 한다." 질문이 답을 알려주면 채점이 통과해도 이해도를 잰 것이 아니다.
API 키 없이 돈다.
"""
from __future__ import annotations

from typing import Any

import pytest

from app import numerics
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
    """루브릭이 없다고 검사를 건너뛰면 그 항목만 유도심문이 통과한다(이슈 #26).

    대상 item_id 를 박아 두지 않는다 — 루브릭을 채울 때마다 이 테스트가 깨졌다.
    루브릭 없는 항목을 코드에서 고르고, 다 채워지면 가상 ID 로 같은 경로를 검사한다.
    """
    from app import rubrics, templates

    covered = set(rubrics.all_rubrics())
    uncovered = [
        i.item_id
        for pt in ("ELS", "VARIABLE_INSURANCE")
        for i in templates.get(pt).items
        if i.item_id not in covered
    ]
    probe_id = uncovered[0] if uncovered else "ELS-RUBRIC-ABSENT-PROBE"

    item = RISK_ITEM.model_copy(update={"item_id": probe_id})
    forbidden = qg.answer_fragments(item)
    assert "45" in forbidden                      # 조건 원문의 수치는 그대로 막힌다
    assert set(forbidden) == set(numerics.numbers(item.condition.value_text))


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


# ── 이슈 #183 — 문서 표기를 그대로 옮긴 질문 ─────────────────────────────────
def _contract_items():
    """계약 정답 전건을 RiskItem 으로. 금지 목록의 실제 모집단이다."""
    import json

    for product_type, fname in templates.CONTRACT_SAMPLE_BY_PRODUCT.items():
        raw = json.loads((templates.CONTRACT_SAMPLES / fname).read_text(encoding="utf-8"))
        for entry in raw["_expected_risk_items"]:
            yield RISK_ITEM.model_copy(update={
                "item_id": entry["item_id"],
                "condition": Condition(
                    value_text=entry["value_text"],
                    source_span=SourceSpan(**entry["source_span"]),
                ),
            })


def _comma_tokens(text: str) -> set[str]:
    return {t for t in text.replace("\n", " ").split()
            if "," in t and any(c.isdigit() for c in t)}


def test_question_copying_the_document_notation_is_caught():
    """★ 원문 표기 그대로(콤마 포함) 옮긴 질문이 잡혀야 한다 — 계약 정답 전수.

    `numbers()` 는 조각에서 콤마를 지우는데 질문 쪽은 안 지워서 **가장 자연스러운 누출
    방식이 통과했다.** 모델은 원문을 보고 질문을 만드니 콤마째 옮기는 쪽이 오히려 흔하다.

    계약 정답의 콤마 수치는 5건이고 **전건이 뚫려 있었다.** 일부가 잡히는 것처럼 보이던 것도
    우연이다 — 콤마가 세 자리마다 끊어 연속 숫자 런이 최대 3자라, `MIN_LEAK_NGRAM`(6)을
    넘기는 것은 소수점이 붙은 값뿐인데 계약 정답에는 그런 값이 없다.
    """
    checked = 0
    for item in _contract_items():
        forbidden = qg.answer_fragments(item)
        for token in _comma_tokens(item.condition.value_text):
            checked += 1
            question = f"{token} 아래로 떨어지면 어떻게 되는지 말씀해 주시겠어요?"
            assert qg.leaked_fragments(question, forbidden), (
                f"{item.item_id}: 원문 표기 {token!r} 을 그대로 옮겼는데 안 잡힌다"
            )
    assert checked >= 5, f"콤마 수치 표본이 줄었다: {checked}"


def test_the_comma_fix_does_not_block_normal_questions():
    """★ 오탐 — **폴백 질문 기준**으로 정상 질문이 막히지 않는다.

    막히면 인터뷰가 폴백으로 새고 유형 커버리지가 깎인다. 폴백 질문 23건을 모든 항목의 금지
    목록에 교차로 재서 고정한다(자기 항목만 재면 *"자기 답을 안 알려준다"* 만 확인되고 다른
    항목 목록에 걸리는 경우를 못 본다).

    ## 모집단이 폴백 질문이라는 것이 이 테스트의 한계다 (PR #184 리뷰, 정세현)

    다른 항목의 `value_text` 를 질문 자리에 넣어 재면 `main` 과 **1건 갈린다.** 기전이 실재한다.

        질문 쪽  '인출금액의 0.2%와 2,000원 …'   금지목록 VAR-LONG-TERM-RATIO(조각 '20')
          canonical       '…0.2%와2,000원…'   '20' 없음
          for_leak_check  '…0.2%와2000원…'    '20' 있음   ← 콤마를 지우며 생긴 런

    즉 **콤마 제거는 짧은 조각(`f in q`)의 우연 일치를 늘린다.** 긴 쪽은 안전하다 —
    6자 이상 수치 4건이 서로 6자 런을 공유하지 않는 것을 확인했다(`_shares_long_run`).

    막지 않는 이유는 F-INT-002 에서 두 오류의 무게가 다르기 때문이다. 오탐은 질문 생성이
    재시도하거나 폴백으로 가는 **마찰**이고, 미탐은 **정답이 새는 것**이다. 이 변경은 미탐
    5건을 없애고 짧은 조각 오탐을 하나 만든다.

    근본은 `MIN_LEAK_NGRAM` 을 짧은 조각에도 적용하는 쪽(두 자리 수치는 단위와 함께만 본다)
    인데, 3주차 정량평가에서 오탐률이 실제로 문제가 될 때 판단한다.
    """
    items = list(_contract_items())
    templates_by_id = {
        i.item_id: i
        for product_type in templates.CONTRACT_SAMPLE_BY_PRODUCT
        for i in templates.get(product_type).items
    }
    for item in items:
        forbidden = qg.answer_fragments(item)
        for other in items:
            question = templates_by_id[other.item_id].fallback_question
            hits = qg.leaked_fragments(question, forbidden)
            assert not hits, f"{other.item_id} 질문이 {item.item_id} 금지목록에 걸린다: {hits}"


# ── 짧은 조각 오탐 (이슈 #183 후속) ──────────────────────────────────────────
#: 짧은 수치를 **부분열**로 찾으면 다른 수 안에 들어 있어도 걸린다. `MIN_LEAK_NGRAM` 은
#: 그 문제를 길이로 우회한 것이지 푼 것이 아니었다 — 6자 이상은 우연 일치가 드물어 안 보였다.
#:
#: **임계값은 안 바꿨다.** `MIN_LEAK_NGRAM = 6` 그대로고 짧은 조각의 **비교 방식**만 바꿨다.
#: 정세현이 `#183` 에서 *"지금 임계를 정하면 근거가 폴백 질문 23건뿐"* 이라고 한 지적을
#: 임계를 안 건드리는 것으로 피한다.
_LEAK_CASES = [
    # (조각, 질문, 잡혀야 하나, 설명)
    ("45", "낙인 45% 아래로 떨어지면 어떻게 되는지 말씀해 주시겠어요?", True, "진짜 누출"),
    ("45", "2045년 만기까지 어떻게 되는지 말씀해 주시겠어요?", False, "연도 안"),
    ("45", "환급률이 145% 라면 어떻게 되나요?", False, "큰 수 안"),
    ("3", "3개월 만에 해지하시면 어떻게 되나요?", True, "진짜 누출"),
    ("3", "13% 손실이 나면 어떻게 되나요?", False, "13 안의 3"),
    ("70", "만기에 70% 미만이면 어떻게 되나요?", True, "진짜 누출"),
    ("70", "1970년대 상품과 다른 점을 아시나요?", False, "연도 안"),
    ("85", "85 아래로 내려가면 어떻게 되나요?", True, "단위 없이도 잡는다"),
]


@pytest.mark.parametrize("fragment,question,should_hit,label", _LEAK_CASES)
def test_short_numeric_fragment_needs_a_number_boundary(fragment, question, should_hit, label):
    """짧은 수치는 **하나의 수로서** 있을 때만 잡는다 — 다른 수 안에 든 것은 아니다."""
    hit = bool(qg.leaked_fragments(question, (fragment,)))
    assert hit is should_hit, f"{label}: {fragment!r} in {question!r}"


def test_unit_is_still_not_required():
    """`numbers()` 의 근거는 *"단위를 떼고 말해도 답을 알려준 것"* 이다.

    경계만 보게 바꿨지 **단위를 요구하지 않는다** — 요구하면 `"85 아래로"` 같은 누출을
    놓치고, 그건 이 검사가 존재하는 이유와 반대다.
    """
    assert qg.leaked_fragments("85 아래로 내려가면 어떻게 되나요?", ("85",))


def test_comma_notation_still_caught():
    """`#184` 가 닫은 것 — 문서 표기를 그대로 옮긴 질문. 경계 검사가 그걸 되돌리면 안 된다."""
    assert qg.leaked_fragments("526,240원만 돌아온다는 뜻인가요?", ("526240",))


def test_long_fragments_keep_substring_matching():
    """긴 어구는 부분 포함으로도 잡는다 — 루브릭 조항을 그대로 쓰지 않아도 핵심 구절만
    옮기면 답을 알려준 것이다. 경계 규칙은 짧은 조각에만 적용된다."""
    clause = "투자원금의 손실이 발생할 수 있음"
    assert qg.leaked_fragments(f"...{clause}... 이라는 뜻인가요?", (clause,))


def test_boundary_helper_scans_every_occurrence():
    """첫 등장이 큰 수 안이어도 뒤에 독립된 등장이 있으면 잡아야 한다.

    `find` 를 한 번만 부르면 첫 자리에서 판단하고 끝난다 — 그러면 `"145% 인데 45% 아래로"`
    같은 질문이 통과한다.
    """
    assert qg._appears_as_whole_number("45", "145%인데45%아래로")
    assert not qg._appears_as_whole_number("45", "145%와2045년")
