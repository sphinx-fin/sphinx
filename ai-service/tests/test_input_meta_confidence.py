"""입력 방식이 확신도에 닿는다 (이슈 #325 2단계).

**붙여넣기로 채운 되말하기는 되말하기가 아니다.** 판매자가 대신 입력했거나 화면에 뜬 설명을
복사한 것이고, **발화 내용만 보면 완벽한 U1 로 채점된다** — 텍스트로는 구분이 안 되고
입력 방식으로만 구분된다.

`#340` 이 그 값을 기록하게 했지만 **아무도 안 읽었다.** 여기서 읽는다.
"""
from __future__ import annotations

import logging

from app import scoring
from app.schemas import Grade, InputMeta, Judgment, RiskItem

ANSWER = "제가 낸 돈보다 적게 돌려받을 수도 있다는 뜻으로 이해했습니다"


def _risk_item() -> RiskItem:
    return RiskItem(
        item_id="ELS-PRINCIPAL-LOSS-WARNING", product_id="p", name="원금 손실",
        importance="required", status="extracted",
        condition={"value_text": "만기평가일에 최초기준가격의 65% 미만",
                   "source_span": {"page": 1, "start": 0, "end": 20}},
    )


def _judgment(confidence: float = 1.0, grade: Grade = Grade.U1) -> Judgment:
    from app import rubrics
    rubric = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING")
    return Judgment(
        item_id=rubric.item_id, grade=grade, confidence=confidence,
        evidence={"utterance_quote": ANSWER[:10], "rubric_clause": rubric.required_elements[0]},
        reason="사유",
    )


def _typed(**over) -> InputMeta:
    base = dict(first_keystroke_delay_ms=1200, total_input_ms=9000,
                paste_detected=False, backspace_count=3, char_count=len(ANSWER),
                elderly_mode=False)
    base.update(over)
    return InputMeta(**base)


class _Stub:
    def __init__(self, judgment): self._j = judgment
    def complete_json(self, **kwargs): return self._j.model_copy()


def _score(input_meta, judgment=None):
    return scoring.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", ANSWER, _risk_item(), "ELS",
                         llm=_Stub(judgment or _judgment()), input_meta=input_meta)


def test_typing_normally_keeps_the_confidence() -> None:
    assert _score(_typed()).confidence == 1.0


def test_pasting_caps_the_confidence(caplog) -> None:
    """★ 붙여넣기가 확신도에 닿는다 — 지금까지 기록만 되고 아무도 안 읽었다.

    ❗**사유는 로그에만 남는다.** `reason` 에 적으면 판매자 화면으로 나가고, 그건
    *"손으로 옮겨 적으면 된다"* 를 알려주는 것이다(#372 리뷰). 감사 경로는 로그와
    불변 기록(`inputMeta` 원본)이다.
    """
    with caplog.at_level(logging.INFO):
        result = _score(_typed(paste_detected=True))

    assert result.confidence == scoring.PASTED_CONFIDENCE_CAP
    assert "사유=붙여넣기" in caplog.text, (
        "화면에서 뺐다고 기록에서까지 빼면 감사 시점에 왜 깎였는지 설명할 수 없다")


def test_no_typing_time_is_caught_without_the_paste_flag(caplog) -> None:
    """❗신호가 둘이다 — 붙여넣기 플래그는 **안 잡히는 경우가 있다.**

    IME 조합 중 붙여넣기나 일부 모바일 키보드에서 이벤트가 안 온다. 타이핑 시간이
    사실상 0 인데 글자가 있는 것은 **구조적으로** 같은 상태다.
    """
    with caplog.at_level(logging.INFO):
        result = _score(_typed(paste_detected=False, total_input_ms=0))

    assert result.confidence == scoring.PASTED_CONFIDENCE_CAP
    assert "사유=타이핑" in caplog.text


def test_a_short_quick_answer_is_not_flagged() -> None:
    """짧고 빠른 답은 안 잡는다 — "네" 는 U3 로 갈 일이지 대필 정황이 아니다."""
    assert _score(_typed(total_input_ms=0, char_count=2)).confidence == 1.0


def test_the_grade_never_changes() -> None:
    """★ **P1** — 붙여넣기가 곧 오해는 아니다. 고객이 자기 메모를 붙여넣었을 수도 있다.

    확신도만 깎고 판정은 게이트가 한다. R-05 가 물면 YELLOW 이고 그건 *"재설명이
    필요하다"* 이지 *"판매 차단"* 이 아니다 — 정황의 크기에 비례한다.
    """
    result = _score(_typed(paste_detected=True))

    assert result.grade == Grade.U1


def test_it_does_not_raise_an_already_lower_confidence() -> None:
    """복창 캡(0.3)이 이미 걸렸으면 0.4 로 **올리지 않는다** — 캡은 상한이지 값이 아니다."""
    result = _score(_typed(paste_detected=True), _judgment(confidence=0.3))

    assert result.confidence == 0.3


def test_absent_input_meta_changes_nothing() -> None:
    """안 보내도 된다 — 옛 화면과 스크립트가 이 필드 없이 부른다."""
    assert _score(None).confidence == 1.0


def test_the_prompt_never_sees_it() -> None:
    """❗모델에게 알려주면 **등급이 그 사실에 끌린다.**

    루브릭이 재는 것은 내용이지 입력 방식이 아니다. 후처리에서만 쓴다.
    """
    seen: list[str] = []

    class _Capturing(_Stub):
        def complete_json(self, **kwargs):
            seen.append(kwargs.get("prompt", ""))
            return super().complete_json(**kwargs)

    scoring.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", ANSWER, _risk_item(), "ELS",
                  llm=_Capturing(_judgment()), input_meta=_typed(paste_detected=True))

    joined = "\n".join(seen)
    assert "붙여넣기" not in joined
    assert "paste" not in joined.lower()


def test_the_log_says_it_did_not_change_the_grade(caplog) -> None:
    with caplog.at_level(logging.INFO, logger="app.scoring"):
        _score(_typed(paste_detected=True))

    assert "입력 방식 확신도 상한" in caplog.text
    assert "등급은 안 바꾼다" in caplog.text


def test_the_input_signal_never_reaches_the_reason() -> None:
    """❗**입력 방식 캡은 계기를 `reason` 에 안 적는다** (#372 리뷰).

    `reason` 은 `JudgmentView` 로 **판매자 화면에 그대로 나간다.** `JudgmentViewFieldsTest`
    는 레코드 **컴포넌트 이름**만 보므로 `inputMeta` 라는 필드가 없는 것은 잠그지만,
    그 내용이 **문자열 안으로** 들어오는 것은 안 본다 — 결정 3.24 가 이름 붙인
    *"필드 이름으로 막은 방어가 문자열 조립에는 안 걸린다"* 그 모양이다.

    ## ❗이 테스트는 **이 캡 하나**를 잰다

    처음엔 이름과 문면이 *"어느 캡도"* 라고 말했는데 **함수 하나만 불렀다.** 그물이
    자기가 덮는다고 말한 범위를 안 덮으면 **그걸 읽은 다음 사람이 덮였다고 믿는다** —
    이 PR 이 세 번째로 밟았다고 적은 그 모양이 한 칸 위에서 반복되는 것이다.

    전수로 만드는 것은 별건이다(`#380`). 단순히 루프를 넓히면 **`#160 ②` 가 남기기로
    정한 문면을 문다** — `apply_misconception_floor` 의 *"오해 라이브러리 매칭 … → U4
    상향"* 은 금지어가 아니라 **허용된 문면**이다. 목록이 아니라 **축**을 먼저 세워야 한다.

        측정값·상향 사실   "포함도 1.00 ≥ 0.6" · "→ U4 상향"    허용 — 다음 행동을 안 지정
        계기·다음 행동     "붙여넣기" · "타이핑 50ms"           금지 — 무엇을 바꾸면 되는지 지목
    """
    banned = ("붙여넣기", "타이핑", "paste", "ms", "입력")

    pasted = InputMeta(first_keystroke_delay_ms=0, total_input_ms=0, paste_detected=True,
                       backspace_count=0, char_count=40, elderly_mode=False)
    typed_instantly = InputMeta(first_keystroke_delay_ms=0, total_input_ms=50,
                                paste_detected=False, backspace_count=0, char_count=40,
                                elderly_mode=False)

    for meta in (pasted, typed_instantly):
        capped = scoring.cap_confidence_if_pasted(_judgment(), meta)
        assert capped.confidence == scoring.PASTED_CONFIDENCE_CAP, "캡이 안 걸리면 아무것도 안 잰다"
        for word in banned:
            assert word not in capped.reason, (
                f"'{word}' 가 판매자 화면에 나간다 — 계기를 알려주면 **손으로 옮겨 적으면 "
                f"된다**를 알려주는 것이다(기획 7-4). 문면: {capped.reason}")
