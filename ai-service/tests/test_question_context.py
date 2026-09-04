"""질문 생성이 **면담 맥락**을 본다. 소유: 윤지석 (F-INT-002)

## 무엇이 문제였나

`generate` 가 받는 것이 `risk_item` 과 `product_type` 뿐이었다. **이 고객이 60대인지,
방금 무엇을 틀렸는지, 어떤 오해가 이미 걸렸는지 모른 채** 매번 첫 질문처럼 만든다.
서버는 `asked_types` 마저 항상 빈 배열로 넘겼다(`SessionController`).

되말하기가 재려는 것은 *"이 사람이 이 문장을 만들어 낼 수 있었나"* 인데, 같은 문장이라도
요구되는 난이도가 사람마다 다르다.

## ❗재검증이 7-4 1단계를 어기고 있었다

재설명 뒤 다시 묻는 질문이 **서버에 항목별 고정 문항으로** 있었고, 그 자리 주석이 스스로
적어 뒀다 — *"사전에 확보하면 그대로 뚫린다."* 재검증은 **판매자가 미리 답을 준비시킬
동기가 가장 큰 자리**라(첫 질문에서 이미 한 번 막혔으므로) 여기가 고정이면 게이트가 뚫린다.
"""
from __future__ import annotations

import pytest

from app import question_gen
from app.schemas import Grade, InterviewContext, RiskItem


def _item() -> RiskItem:
    return RiskItem(
        item_id="ELS-PRINCIPAL-LOSS-WARNING",
        product_id="doc-els-kiwoom-4181",
        name="원금 손실 가능성",
        importance="required",
        status="extracted",
        condition={"value_text": "만기평가일에 최초기준가격의 65% 미만",
                   "source_span": {"page": 3, "start": 120, "end": 210}},
    )


def test_no_context_keeps_the_old_prompt() -> None:
    """★ 맥락이 없으면 프롬프트가 예전 그대로다 — 옛 호출부가 안 깨진다."""
    assert question_gen.context_section("initial", None) == ""


def test_vulnerable_lowers_the_bar() -> None:
    """❗눈높이가 프롬프트에 들어간다.

    지금까지 고령자 배려는 **글자 크기와 타이머**뿐이었다. 같은 질문을 60대에게도 30대에게도
    똑같이 냈다 — 심사에서 *"고령자 배려가 어디 있나요"* 의 답이 화면 설정뿐이었다.
    """
    section = question_gen.context_section("initial", InterviewContext(vulnerable=True))

    assert "눈높이" in section
    assert "한 번에 한 가지" in section


def test_a_matched_misconception_steers_the_next_question() -> None:
    """❗이미 걸린 오해가 다음 질문에 닿는다 — 지금까지 재설명 문면에만 닿았다."""
    section = question_gen.context_section(
        "initial", InterviewContext(matched_misconceptions=["M02-DEPOSIT-INSURANCE"]))

    assert "M02-DEPOSIT-INSURANCE" in section
    assert "옮겨 쓰지 않는다" in section, "오해 문장을 질문에 실으면 그건 오답을 심는 것이다"


def test_the_context_never_carries_an_answer() -> None:
    """★ 맥락에 정답이 들어갈 자리가 없다.

    `answer_fragments` 가 질문에서 걸러내는 바로 그 값(루브릭 조항·조건 원문)을 맥락으로
    넣으면 모델이 다음 질문에 옮겨 쓴다. 검사는 최종 방어선이지 설계가 아니다.
    """
    fields = set(InterviewContext.model_fields)

    assert fields == {"vulnerable", "prior_grades", "matched_misconceptions"}, (
        "맥락에 발화·루브릭·조건 원문이 들어오면 유도심문 경로가 열린다")


def test_repeated_failure_asks_for_an_easier_entry() -> None:
    section = question_gen.context_section(
        "initial", InterviewContext(prior_grades=[Grade.U3, Grade.U3, Grade.U1]))

    assert "더 쉬운 진입" in section

    one_miss = question_gen.context_section(
        "initial", InterviewContext(prior_grades=[Grade.U3, Grade.U1]))
    assert "더 쉬운 진입" not in one_miss, "한 번 틀린 것으로 난이도를 낮추면 과반응이다"


def test_reverify_says_it_is_a_second_ask() -> None:
    """❗재검증이라는 사실이 프롬프트에 들어간다 — 안 넣으면 같은 질문이 다시 나간다."""
    section = question_gen.context_section("reverify", None)

    assert "다시 묻는 것" in section
    assert "같은 문장을 다시 쓰지 말고" in section


# ── 폴백 ──────────────────────────────────────────────────────────────────────
def test_reverify_fallback_does_not_name_the_item() -> None:
    """★ 재검증 폴백이 **항목별 고정 문항이 아니다** (기획서 7-4 1단계).

    서버에 있던 하드코딩은 항목별로 갈리는 고정 문장이었고, 그래서 **사전에 확보하면
    그대로 뚫렸다.** 폴백은 없앨 수 없지만(인터뷰를 멈추지 않는다) **항목이 안 실리면
    미리 준비해도 답이 안 된다.**
    """
    reverify = question_gen._fallback(_item(), _template(), variant="reverify")

    assert reverify.fallback_used is True
    assert "ELS" not in reverify.question
    assert "원금" not in reverify.question and "예금자보호" not in reverify.question


def test_reverify_fallback_differs_from_the_initial_one() -> None:
    """❗초회 폴백을 재검증에 다시 쓰면 **같은 질문을 두 번 낸다.**"""
    initial = question_gen._fallback(_item(), _template(), variant="initial")
    reverify = question_gen._fallback(_item(), _template(), variant="reverify")

    assert initial.question != reverify.question


def test_reverify_fallback_follows_the_bar() -> None:
    plain = question_gen._fallback(_item(), _template(), variant="reverify",
                                   context=InterviewContext(vulnerable=True))
    normal = question_gen._fallback(_item(), _template(), variant="reverify")

    assert plain.question != normal.question, (
        "쉬운 말로 설명해 놓고 곧바로 어려운 문장으로 되물으면 한 응답 안에서 눈높이가 깨진다")


def _template():
    return question_gen._template_item("ELS-PRINCIPAL-LOSS-WARNING", "ELS")
