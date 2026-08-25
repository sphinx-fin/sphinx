"""상품유형 템플릿 — F-EXT-003 재현율 분모의 정합성. 소유: 윤지석

기획서 5절: "상품유형 템플릿으로 추출 범위를 고정한다."
템플릿에 없는 항목은 추출되지 않으므로 이 파일들이 곧 재현율 분모다(이슈 #26).
"""
from __future__ import annotations

import pytest

from app import templates

PRODUCT_TYPES = ("ELS", "VARIABLE_INSURANCE")


@pytest.mark.parametrize("product_type", PRODUCT_TYPES)
def test_template_loads(product_type: str):
    tpl = templates.get(product_type)
    assert tpl.items
    assert tpl.answer_set_documents, "ADR-007 — 정답지 문서 집합이 명시돼야 한다"


@pytest.mark.parametrize("product_type", PRODUCT_TYPES)
def test_template_matches_contract_exactly(product_type: str):
    """템플릿이 계약 샘플과 어긋나면 두 방향으로 조용히 틀어진다 — 템플릿에만 있는 항목은
    오탐이 되고, 계약에만 있는 항목은 재현율이 구조적으로 깎인다. 어느 쪽도 예외를 던지지
    않으므로 여기서 고정한다."""
    templates.assert_matches_contract(product_type)


@pytest.mark.parametrize("product_type", PRODUCT_TYPES)
def test_cue_is_product_agnostic(product_type: str):
    """`cue` 에 특정 회차의 수치를 박으면 다른 발행사 문서에 붙지 않는다.

    기획서 4절 "신상품에도 바로 붙는다"가 성립해야 하고, 값은 추출 시점에 원문에서
    가져온다(P6 — 수치는 원문 인용만).

    법령 상수(고난도상품 정의의 "원금의 20%" 등)는 상품마다 같으니 무해하지만, 규칙에
    예외를 두면 상품 조건값이 섞여 들어온다. 그래서 cue 에는 숫자를 아예 쓰지 않는다.
    """
    import re

    numeric = re.compile(r"\d+\s*%|\d{3,}")
    for item in templates.get(product_type).items:
        assert not numeric.search(item.cue), f"{item.item_id}: cue 에 상품별 수치 — {item.cue}"


@pytest.mark.parametrize("product_type", PRODUCT_TYPES)
def test_item_ids_are_unique_and_prefixed(product_type: str):
    tpl = templates.get(product_type)
    ids = tpl.item_ids
    assert len(set(ids)) == len(ids)
    prefix = "ELS-" if product_type == "ELS" else "VAR-"
    assert all(i.startswith(prefix) for i in ids)


def test_importance_is_pending_and_reported():
    """importance 는 정세현이 부여한다(이슈 #26). 미정 상태를 조용히 넘기지 않고 노출한다 —
    RiskItem.importance 가 계약상 required 필드이므로 비어 있으면 추출 출력이 무효다."""
    pending = {pt: templates.get(pt).items_without_importance() for pt in PRODUCT_TYPES}
    total = sum(len(v) for v in pending.values())
    if total:
        assert total == 23, f"부여 진행 중 — 미정 {total}종: {pending}"
    else:
        for pt in PRODUCT_TYPES:
            assert templates.get(pt).required_items(), f"{pt}: required 가 하나도 없다"


def test_invalid_importance_is_rejected():
    with pytest.raises(ValueError, match="importance"):
        templates._parse_entry_for_test({"item_id": "X", "name": "n", "cue": "c",
                                         "importance": "must-know"})


def test_answer_set_conflict_is_surfaced():
    """ADR-007 이 정답지를 문서 집합으로 바꿨고, 그 합집합에 문면 충돌이 하나 있다.
    상품요약서는 "예금자보호 비대상", 운용설명서는 "부분 보호"라고 한다.
    정답을 확정해야 채점 기준이 정해지므로 조용히 덮지 않는다."""
    conflicts = templates.get("VARIABLE_INSURANCE").conflicts()
    assert [c.item_id for c in conflicts] == ["VAR-NO-DEPOSIT-INSURANCE"]
    assert "불일치" in conflicts[0].conflict


def test_coverage_report_shape():
    report = templates.coverage_report()
    assert set(report) == set(PRODUCT_TYPES)
    for product_type, row in report.items():
        assert row["template_items"] == row["contract_items"], product_type
        assert row["rubric_covered"] <= row["template_items"]


# ── dev set 이 계약 샘플을 근거로 삼는지 ────────────────────────────────────────
def test_devset_risk_items_come_from_contract_sample():
    """dev set 의 RiskItem 은 계약 샘플에서 만든다 — 조항 문면을 픽스처에 복사하면 낡는다.

    실제로 그랬다. 이전 픽스처는 내가 개별로 받은 삼성증권 회차를 인용했는데 팀 데모 문서가
    키움 4181 로 정해졌고, 그 결과 스팬이 **다른 사람이 검증할 수 없는 문서**를 가리켰다.
    계약 샘플은 git 추적 대상이므로 누구나 등식을 확인할 수 있다.
    """
    import sys
    from pathlib import Path

    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
    from run_devset import load_risk_items, load_sample, verify_spans

    for product_type in PRODUCT_TYPES:
        assert not verify_spans(product_type), f"{product_type}: 계약 스팬 등식 불일치"
        items = load_risk_items(product_type)
        sample = load_sample(product_type)
        assert set(items) == {i["item_id"] for i in sample["_expected_risk_items"]}
        assert all(v.product_id == sample["document_id"] for v in items.values())


def test_no_stale_risk_item_fixture_remains():
    """복사본 픽스처를 되살리지 않는다 — 계약 샘플이 유일한 출처여야 드리프트가 없다."""
    from pathlib import Path

    stale = Path(__file__).resolve().parent / "fixtures" / "risk_items"
    assert not (stale / "els.yaml").exists(), "risk_items 픽스처가 되살아났다"
