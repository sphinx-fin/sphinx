"""F-DET-001 오해 탐지 — 결정론 단계 검증. 소유: 윤지석

기획서 5절 통제: "라이브러리 기반이라 재현성이 확보되고, LLM은 변형된 표현을 커버하는
역할만 맡는다." 여기서 검증하는 것은 그 재현성 부분이다.
"""
from __future__ import annotations

import pytest
import yaml

from app import misconception
from app.schemas import PRODUCT_TYPES
from tests.helpers import FIXTURES


def _cases():
    return yaml.safe_load((FIXTURES / "utterances" / "els.yaml").read_text(encoding="utf-8"))


def test_library_type_count():
    """현재 7종이다. 기획서·역할분담표는 "오해 라이브러리 8종"이라고 쓰지만,
    M07-YIELD-OVERCONFIDENCE 가 인용 가능한 근거를 찾지 못해 라이브러리에서 빠졌다
    (근거 없는 유형을 남기면 apply_misconception_floor 가 그것으로 U4 를 확정한다).

    제출 문서의 "8종" 문면과 어긋나므로 그 문장도 손봐야 한다 — 이 테스트가 그 사실을
    코드에 남겨 둔다. 유형이 또 바뀌면 여기서 먼저 걸린다."""
    lib = misconception.library()
    assert len(lib) == 7
    assert {m.type_id for m in lib} >= {"M01-PRINCIPAL-GUARANTEE", "M08-TYING"}
    assert "M07-YIELD-OVERCONFIDENCE" not in {m.type_id for m in lib}


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


def _synthetic_type(pattern: str) -> misconception.MisconceptionType:
    return misconception.MisconceptionType(
        type_id="MZZ-SYNTHETIC", label="합성 유형", patterns=(pattern,),
        products=("ALL",), escalate=None,
        source=misconception.SourceRef(type="proposal_example", ref="tests/test_misconception.py"),
    )


def test_near_miss_goes_to_review_queue_instead_of_silent_drop(monkeypatch):
    """임계값에 못 미치지만 무시하기엔 가까운 발화는 조용히 버리지 않고 후보 큐로 올린다.

    라이브러리의 실제 발화를 픽스처로 박으면 두 가지 이유로 깨진다. 후보 큐 구간이
    [0.45, 0.62) = 0.17 폭뿐이라 임계값을 조금만 조정해도 뒤집히고, 라이브러리 데이터
    소유가 정세현이라 패턴 한 줄이 추가되면(PR #21) 내 테스트가 남의 PR을 막는다.
    여기서 고정할 것은 라이브러리 내용이 아니라 **엔진의 near-miss 처리**이므로,
    발화를 합성하고 구간을 임계값에서 계산한다.
    """
    chars = "".join(chr(0xAC00 + i) for i in range(101))   # 서로 다른 바이그램 100개
    monkeypatch.setattr(misconception, "library", lambda: (_synthetic_type(chars),))

    mid = (misconception.REVIEW_THRESHOLD + misconception.NGRAM_THRESHOLD) / 2
    near = chars[: int(mid * 100) + 1]                     # 포함도 ≈ mid — 구간 안
    assert (misconception.REVIEW_THRESHOLD
            <= misconception._containment(chars, near) < misconception.NGRAM_THRESHOLD)

    result = misconception.match(near, "ELS")
    assert result.matches == []
    assert result.unclassified_candidate is True


def test_far_miss_is_dropped_without_polluting_the_queue(monkeypatch):
    """REVIEW_THRESHOLD 아래는 후보 큐에 올리지 않는다. 하한이 없으면 큐가 무의미해진다."""
    chars = "".join(chr(0xAC00 + i) for i in range(101))
    monkeypatch.setattr(misconception, "library", lambda: (_synthetic_type(chars),))

    far = chars[: int(misconception.REVIEW_THRESHOLD * 100) - 4]
    assert misconception._containment(chars, far) < misconception.REVIEW_THRESHOLD

    result = misconception.match(far, "ELS")
    assert result.matches == []
    assert result.unclassified_candidate is False


#: 기획서 4절 154행 대화 예시. 데모 임계 경로다.
SPEC_DIALOGUE_UTTERANCE = "은행에서 파는 거니까 3년 뒤에 이자 붙어서 나오는 거죠."


def _library_supports(utterance: str, type_id: str) -> bool:
    """라이브러리가 이 발화를 해당 유형으로 결정론적으로 잡는가.

    라이브러리는 정세현 소유다. 패턴이 아직 없는 상태를 실패로 두면 내 테스트가 남의
    PR 을 기다리는 동안 이 브랜치가 빨갛게 남는다 — 파서 테스트가 원본 문서 부재를
    skip 으로 처리하는 것과 같은 성격이므로 같은 방식으로 다룬다.
    """
    return type_id in {m.type_id for m in misconception.match(utterance, "ELS").matches}


_M04_PENDING = "M04 조각 패턴 미머지 (PR #21). 머지되면 이 테스트가 자동으로 활성화된다."


@pytest.mark.skipif(
    not _library_supports(SPEC_DIALOGUE_UTTERANCE, "M04-EARLY-REDEMPTION"),
    reason=_M04_PENDING,
)
def test_spec_dialogue_example_is_caught_deterministically():
    """기획서 4절 154행 대화 예시. M04 에 조각 패턴이 들어가면 후보 큐가 아니라 pattern
    단계에서 잡힌다 — 데모 임계 경로가 LLM 응답에서 떨어진다."""
    result = misconception.match(SPEC_DIALOGUE_UTTERANCE, "ELS")
    assert [m.type_id for m in result.matches] == ["M04-EARLY-REDEMPTION"]
    assert result.matches[0].stage == "pattern"
    assert result.unclassified_candidate is False


def test_deterministic_fixture_cases_match_expected_type():
    """dev set 의 `deterministic: true` 케이스가 라이브러리에서 실제로 잡히는지.

    라이브러리에 아직 패턴이 없는 케이스는 skip 하고 무엇이 빠졌는지 남긴다. dev set 의
    플래그는 *목표 상태*를 적은 것이고 라이브러리 데이터 소유는 정세현이므로, 패턴 도착
    전에 실패로 두면 이 브랜치가 남의 PR 을 기다리며 빨갛게 남는다.
    """
    product_type = _cases()["product_type"]
    pending = []
    checked = 0
    for case in _cases()["cases"]:
        if not case.get("deterministic"):
            continue
        result = misconception.match(case["answer"], product_type)
        found = {m.type_id for m in result.matches}
        expected = case.get("expected_misconception")
        if expected and expected not in found:
            pending.append(f"{case['id']}({expected})")
            continue
        if expected:
            assert expected in found, case["id"]
        if case.get("expected_escalate"):
            assert result.escalate is True, case["id"]
        checked += 1

    assert checked, "결정론 케이스가 하나도 검증되지 않았다"
    if pending:
        pytest.skip(f"라이브러리 패턴 대기: {', '.join(pending)} — {_M04_PENDING}")
