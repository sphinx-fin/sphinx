"""상품유형 템플릿 — F-EXT-003 재현율 분모의 정합성. 소유: 윤지석

기획서 5절: "상품유형 템플릿으로 추출 범위를 고정한다."
템플릿에 없는 항목은 추출되지 않으므로 이 파일들이 곧 재현율 분모다(이슈 #26).
"""
from __future__ import annotations

import re

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


# ── dev set 커버리지 ──────────────────────────────────────────────────────────
def test_every_rubric_has_a_devset_case():
    """루브릭이 있으면 dev set 케이스도 있어야 한다.

    문면만 맞춰둔 루브릭은 실제로 어떻게 채점되는지 모르는 상태다 — 변액 4종이 한동안
    그랬다. 루브릭을 늘릴 때 검증도 같이 늘도록 여기서 고정한다.
    """
    import yaml

    from app import rubrics

    fixtures = _fixtures_dir()
    covered: set[str] = set()
    for path in sorted((fixtures / "utterances").glob("*.yaml")):
        spec = yaml.safe_load(path.read_text(encoding="utf-8"))
        covered |= {c["item_id"] for c in spec["cases"]}

    uncovered = sorted(set(rubrics.all_rubrics()) - covered)
    assert not uncovered, f"루브릭은 있는데 dev set 케이스가 없다: {uncovered}"


def test_devset_item_ids_have_rubrics():
    """반대 방향 — dev set 이 루브릭 없는 항목을 채점하려 하면 RubricNotFound 로 죽는다."""
    import yaml

    from app import rubrics

    known = set(rubrics.all_rubrics())
    for path in sorted((_fixtures_dir() / "utterances").glob("*.yaml")):
        spec = yaml.safe_load(path.read_text(encoding="utf-8"))
        for case in spec["cases"]:
            assert case["item_id"] in known, f"{case['id']}: 루브릭 없음 {case['item_id']}"


def test_devset_covers_both_product_types():
    specs = sorted((_fixtures_dir() / "utterances").glob("*.yaml"))
    import yaml

    types = {yaml.safe_load(p.read_text(encoding="utf-8"))["product_type"] for p in specs}
    assert types == set(PRODUCT_TYPES), f"상품유형 누락: {set(PRODUCT_TYPES) - types}"


def _fixtures_dir():
    from pathlib import Path

    return Path(__file__).resolve().parent / "fixtures"


# ── 분모 매핑 (PR #104 로 샘플 파일이 셋이 됐다) ────────────────────────────────
def test_every_product_type_has_a_denominator_sample():
    """상품유형마다 분모 샘플이 정확히 하나 매핑돼 있어야 한다."""
    from app.schemas import PRODUCT_TYPES as CANON

    assert set(templates.CONTRACT_SAMPLE_BY_PRODUCT) == set(CANON)


def test_unmapped_product_type_raises_instead_of_skipping():
    """이전에는 None 을 돌려주고 assert_matches_contract() 가 조용히 통과했다 —
    새 상품유형을 추가하면 계약 대조가 검사 없이 지나갔다."""
    with pytest.raises(templates.ContractSampleMissing, match="매핑"):
        templates.contract_item_ids("BOND")


def test_missing_sample_file_raises(monkeypatch):
    monkeypatch.setitem(templates.CONTRACT_SAMPLE_BY_PRODUCT, "ELS", "없는파일.json")
    with pytest.raises(templates.ContractSampleMissing, match="파일이 없다"):
        templates.contract_item_ids("ELS")


def test_ops_manual_sample_is_not_the_denominator():
    """변액은 교차 검증용 운용설명서 샘플이 함께 있다(PR #104). 그건 분모가 아니다."""
    assert templates.CONTRACT_SAMPLE_BY_PRODUCT["VARIABLE_INSURANCE"] == \
        "parsed_variable_sample.json"
    assert len(templates.contract_item_ids("VARIABLE_INSURANCE")) == 10


# ── 표 셀 항목의 단위 선언 (이슈 #175) ───────────────────────────────────────
#: 표에서는 단위가 **표 상단 선언**에 있고 값이 든 행에는 없다. `value_text` 가 행 하나라
#: `source_values()` 가 `('526240', None)` 을 내고, 재설명이 `"526,240원"` 이라고 쓰면
#: **값은 원문에 그대로 있는데 환각으로 걸린다.**
#:
#: 그래서 항목이 단위를 선언한다. **손으로 지어낸 값이면 안 된다** — 아래 테스트가 계약
#: 샘플에서 그 선언을 실제로 찾아 대조한다.
_UNIT_DECLARATION = re.compile(r"\(단위\s*[:：]\s*([^)]*)\)")

#: 선언 문면의 단위 → `numerics.UNIT_CLASSES` 의 부류.
_UNIT_TOKEN_TO_CLASS = {"원": "원", "%": "pct"}


def test_declared_units_come_from_the_document():
    """★ 템플릿의 `units` 가 **원문 선언과 같아야 한다.**

    이 대조가 없으면 `units` 는 손으로 적은 값이 되고, 그러면 재설명이 쓸 수 있는 단위를
    사람이 임의로 넓히는 통로가 된다 — P6(수치는 원문 인용만)이 막으려는 그 방향이다.
    """
    import json
    import sys
    from pathlib import Path

    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
    from run_devset import CONTRACT_SAMPLES, SAMPLE_BY_PRODUCT

    checked = 0
    for product_type, name in SAMPLE_BY_PRODUCT.items():
        raw = json.loads((CONTRACT_SAMPLES / name).read_text(encoding="utf-8"))
        pages = {p["page"]: p["text"] for p in raw["pages"]}
        spans = {e["item_id"]: e["source_span"] for e in raw["_expected_risk_items"]}
        for item in templates.get(product_type).items:
            if not item.units:
                continue
            span = spans.get(item.item_id)
            assert span, f"{item.item_id}: units 를 선언했는데 계약 정답이 없다"
            before = pages[span["page"]][: span["start"]]
            found = _UNIT_DECLARATION.findall(before)
            assert found, (
                f"{item.item_id}: 원문에 `(단위 : …)` 선언이 없는데 units 를 적었다 — "
                "손으로 지어낸 값이면 안 된다"
            )
            tokens = [tok.strip() for tok in found[-1].split(",")]
            declared = {_UNIT_TOKEN_TO_CLASS.get(tok, tok) for tok in tokens}
            assert set(item.units) == declared, (
                f"{item.item_id}: 템플릿 {item.units} ↔ 원문 선언 {sorted(declared)}"
            )
            checked += 1
    assert checked, "units 를 선언한 항목이 없다 — 이 대조가 아무것도 안 하고 있다"


def test_only_table_items_declare_units():
    """자연어 항목이 units 를 달면 재설명이 쓸 수 있는 단위가 근거 없이 넓어진다."""
    from app import numerics

    for product_type in PRODUCT_TYPES:
        for item in templates.get(product_type).items:
            if not item.units:
                continue
            unknown = set(item.units) - set(numerics.UNIT_CLASSES.values())
            assert not unknown, f"{item.item_id}: numerics 가 모르는 단위 부류 {sorted(unknown)}"
