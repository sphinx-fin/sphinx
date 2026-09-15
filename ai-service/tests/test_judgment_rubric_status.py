"""판정이 **어느 상태의 기준으로 나왔는지**를 들고 나간다. 소유: 윤지석 (이슈 #609 ①)

## 왜 이 필드가 필요한가

`status: confirmed` 는 *"근거자료 검토를 마쳤다"* 는 뜻인데 그 값을 읽는 코드가 채점 경로에
없었다 — `#617` 이 그 사실을 기동 로그와 못 박기로 드러냈다. 실물은 이렇다.

    ELS      10종 · draft 0종
    변액       7종 · draft 7종   ← 전부. S-02 에서 변액을 고르면 그 세션 전부가 검토 전 기준이다

채점은 안 바꾼다(draft 를 빼면 「승인했는데 안 돈다」가 된다 · `#475` ⓐ). 대신 **판정이 그
사실을 들고 나가** 화면·교부 문서가 「검토 전 기준」을 말할 수 있게 한다.

## 이 파일이 잠그는 것

    ① 값이 루브릭에서 온다 — 모델이 아니라
    ② draft 와 confirmed 가 실제로 갈린다 (`#617` 의 못 박기가 여기서 뒤집힌다)
    ③ 모델이 채워 보내도 우리 값이 이긴다
    ④ 계약에 optional 이지만 **우리는 항상 싣는다** — 「없다」는 옛 레코드의 뜻이다
"""
from __future__ import annotations

import pytest

from app import rubrics, scoring
from app.schemas import Condition, Judgment, RiskItem, SourceSpan
from tests.helpers import FakeLlm, make_judgment

#: 변액 항목 하나 — 지금 변액은 전부 draft 다(`#617` 의 `_DRAFT_IN_SCORING`).
DRAFT_ITEM = "VAR-FEE-DEDUCTION"
#: ELS 는 전부 confirmed 다.
CONFIRMED_ITEM = "ELS-PRINCIPAL-LOSS-WARNING"


def _risk_item(item_id: str) -> RiskItem:
    return RiskItem(
        item_id=item_id, product_id="mock-001", name="항목", importance="required",
        status="extracted",
        condition=Condition(value_text="원문 인용",
                            source_span=SourceSpan(page=1, start=0, end=4)),
    )


#: 인용이 발화에 축자로 있어야 한다(P4 · `verify_quote_is_verbatim`) — 아니면 재판정 뒤
#: `MEASUREMENT_INVALID` 로 죽어서 이 파일이 재려는 자리에 닿지 못한다.
ANSWER = "제가 이해하기로는 원금은 지켜지는 거죠"


def _score(item_id: str, product_type: str, judgment: Judgment) -> Judgment:
    return scoring.score(item_id, "질문?", ANSWER,
                         _risk_item(item_id), product_type, llm=FakeLlm(judgment))


@pytest.mark.parametrize("item_id,product_type,expected", [
    (CONFIRMED_ITEM, "ELS", "confirmed"),
    (DRAFT_ITEM, "VARIABLE_INSURANCE", "draft"),
])
def test_the_judgment_carries_the_rubric_status(item_id, product_type, expected) -> None:
    """★ 두 상태가 판정에서 **실제로 갈린다.**

    `#617` 의 못 박기(`test_scoring_cannot_tell_the_two_apart`)가 *"채점 입력이 두 상태에서
    글자까지 같다"* 를 단정한다 — 이 테스트는 그 **뒤쪽**을 잰다. 입력은 같아도 **출력이
    상태를 들고 나간다**는 것이 `#609` ① 의 ⓑ 다.
    """
    got = _score(item_id, product_type, make_judgment(item_id=item_id))
    assert got.rubric_status == expected
    assert got.rubric_status == rubrics.get(item_id).status, "루브릭 파일이 진실이다"


def test_the_model_does_not_get_to_say_it() -> None:
    """★ 모델이 채워 보내도 **우리 값이 이긴다** — `_pin_prompt_version` 과 같은 층이다.

    루브릭 파일이 실제로 무엇인지는 **우리가 아는 사실**이고 모델이 보고할 값이 아니다.
    `Judgment` 가 LLM 구조화 출력 스키마이기도 해서(`complete_json(model_cls=Judgment)`)
    모델이 이 칸을 채워 보낼 **수 있다** — 그때 그 값을 쓰면 파일을 바꿔도 아무 일이 안
    일어난다.
    """
    lying = make_judgment(item_id=DRAFT_ITEM).model_copy(
        update={"rubric_status": "confirmed"})
    got = _score(DRAFT_ITEM, "VARIABLE_INSURANCE", lying)
    assert got.rubric_status == "draft", "모델이 낸 값이 살아남았다"


def test_we_always_carry_it_even_though_the_contract_allows_none() -> None:
    """❗계약에서 optional 인 것은 **옛 레코드** 때문이지 우리가 비워도 된다는 뜻이 아니다.

    `rubrics.get()` 이 준 루브릭에서 읽으므로 `None` 이 될 수 없다. 비어 나가기 시작하면
    소비자가 「모른다」와 「검토됐다」를 가르는 근거가 사라진다 — 그 구별이 이 필드의 존재
    이유다(`#284` 의 「빈 것 ↔ 없는 것」과 같은 자리).
    """
    for item_id, product_type in ((CONFIRMED_ITEM, "ELS"),
                                  (DRAFT_ITEM, "VARIABLE_INSURANCE")):
        got = _score(item_id, product_type, make_judgment(item_id=item_id))
        assert got.rubric_status is not None, f"{item_id}: 비워 나갔다"


def test_absent_is_not_confirmed() -> None:
    """❗**기본값이 `confirmed` 가 아니다.**

    `source` 는 *"없으면 MEASURED 로 읽는다"* 인데 여기는 반대다 — 기본값을 confirmed 로
    두면 이 필드가 생기기 전 레코드 **전부**가 「검토된 기준으로 판정했다」로 읽힌다.
    실제로는 모르는 것이고, 그게 이 필드를 만드는 이유 자체를 지운다.
    """
    assert Judgment.model_fields["rubric_status"].default is None
