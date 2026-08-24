"""F-DET-001 오해 탐지 — 결정론 단계 검증. 소유: 윤지석

기획서 5절 통제: "라이브러리 기반이라 재현성이 확보되고, LLM은 변형된 표현을 커버하는
역할만 맡는다." 여기서 검증하는 것은 그 재현성 부분이다.
"""
from __future__ import annotations

import yaml

from app import misconception
from app.schemas import PRODUCT_TYPES
from tests.helpers import FIXTURES


def _cases():
    return yaml.safe_load((FIXTURES / "utterances" / "els.yaml").read_text(encoding="utf-8"))


def test_library_loads_all_eight_types():
    lib = misconception.library()
    assert len(lib) == 8
    assert {m.type_id for m in lib} >= {"M01-PRINCIPAL-GUARANTEE", "M08-TYING"}


def test_demo_utterance_is_caught_deterministically():
    """기획서 7-2 데모 메인 ③. LLM 응답에 의존하면 데모의 임계 경로가 비결정적이 된다."""
    result = misconception.match("은행에서 파는 거니까 원금은 지켜지는 거죠?", "ELS")
    assert [m.type_id for m in result.matches] == ["M01-PRINCIPAL-GUARANTEE"]
    assert result.matches[0].stage == "pattern"
    assert result.matches[0].score == 1.0


def test_tying_escalates_from_library_data_not_code():
    """escalate는 라이브러리 필드에서 읽는다 — 유형ID를 코드에 박지 않았음을 확인."""
    result = misconception.match("대출받으려면 이것도 들어야 한다고 하셔서요", "ELS")
    assert result.escalate is True
    assert any(m.type_id == "M08-TYING" for m in result.matches)


def test_escalate_field_is_data_driven():
    escalating = {m.type_id for m in misconception.library() if m.escalate == "compliance"}
    assert escalating == {"M08-TYING"}, "라이브러리가 바뀌면 이 테스트가 알려준다"


def test_correct_understanding_is_not_flagged():
    """이해→오해 오판(마찰)의 최소 방어선."""
    result = misconception.match("낙인 밑으로 떨어지면 원금이 깎이는 거네요", "ELS")
    assert result.matches == []
    assert result.escalate is False


def test_product_scope_is_respected():
    """변액 전용 오해가 ELS 발화에 붙지 않는다."""
    result = misconception.match("낸 돈은 다 돌려받는 거죠", "ELS")
    assert [m.type_id for m in result.matches] == []
    result = misconception.match("낸 돈은 다 돌려받는 거죠", "VARIABLE_INSURANCE")
    assert [m.type_id for m in result.matches] == ["M06-SURRENDER-VALUE"]


def test_library_products_stay_within_contract():
    """라이브러리 products 값이 계약(contracts/parsed_document.schema.json)을 벗어나면
    match()가 예외도 로그도 없이 빗나가고 해당 상품 오해가 하나도 안 잡힌다.
    그 조용한 실패를 막는 지점이므로 여기서 고정한다."""
    misconception.assert_products_are_canonical()
    allowed = set(PRODUCT_TYPES) | {"ALL"}
    for mtype in misconception.library():
        assert set(mtype.products) <= allowed, f"{mtype.type_id}: {mtype.products}"


def test_legacy_short_value_no_longer_matches():
    """VARIABLE_INS 는 폐기된 표기다. 조용히 받아주지 않는다 —
    상류가 옛 값을 보내면 스키마(ProductType)가 422로 거부해야 한다."""
    result = misconception.match("낸 돈은 다 돌려받는 거죠", "VARIABLE_INS")
    assert result.matches == []


def test_near_miss_goes_to_review_queue_instead_of_silent_drop():
    """기획서 4절 대화 예시는 라이브러리 표현과 차이가 커서 미매칭이다.
    조용히 버리지 않고 미분류 후보로 올린다."""
    result = misconception.match("은행에서 파는 거니까 3년 뒤에 이자 붙어서 나오는 거죠.", "ELS")
    assert result.matches == []
    assert result.unclassified_candidate is True


def test_deterministic_fixture_cases_match_expected_type():
    for case in _cases()["cases"]:
        if not case.get("deterministic"):
            continue
        result = misconception.match(case["answer"], _cases()["product_type"])
        found = {m.type_id for m in result.matches}
        if case.get("expected_misconception"):
            assert case["expected_misconception"] in found, case["id"]
        if case.get("expected_escalate"):
            assert result.escalate is True, case["id"]
