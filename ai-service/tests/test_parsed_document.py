"""ParsedDocument 미러가 계약과 어긋나지 않는지. 소유: 윤지석

`app/schemas.py`의 ParsedDocument는 `contracts/parsed_document.schema.json`(계약 소유:
정세현)의 미러다. 계약이 바뀌면 이 테스트가 먼저 깨져야 한다 — 런타임에서 발견하면
이미 추출 결과가 오염된 뒤다.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.schemas import ParsedDocument

CONTRACTS = Path(__file__).resolve().parents[2] / "contracts"
SAMPLES = CONTRACTS / "samples"
SCHEMA = CONTRACTS / "parsed_document.schema.json"

_MISSING = "contracts/ 에 파싱 계약이 아직 없다 (feat/F-EXT-001-parsing-contract 머지 대기)"


def _samples() -> list[Path]:
    return sorted(SAMPLES.glob("parsed_*_sample.json")) if SAMPLES.is_dir() else []


@pytest.mark.skipif(not SCHEMA.is_file(), reason=_MISSING)
def test_mirror_matches_contract_required_fields():
    contract = json.loads(SCHEMA.read_text(encoding="utf-8"))
    required = set(contract["required"])
    mine = {n for n, f in ParsedDocument.model_fields.items() if f.is_required()}
    assert mine == required, f"계약 {required} vs 미러 {mine}"


@pytest.mark.skipif(not SCHEMA.is_file(), reason=_MISSING)
def test_mirror_matches_contract_enums():
    contract = json.loads(SCHEMA.read_text(encoding="utf-8"))
    schema = ParsedDocument.model_json_schema()
    assert set(contract["properties"]["product_type"]["enum"]) == \
        set(schema["properties"]["product_type"]["enum"])
    codes = contract["properties"]["parse_warnings"]["items"]["properties"]["code"]["enum"]
    mine = schema["$defs"]["ParseWarning"]["properties"]["code"]["enum"]
    assert set(codes) == set(mine)


@pytest.mark.skipif(not _samples(), reason=_MISSING)
@pytest.mark.parametrize("path", _samples(), ids=lambda p: p.stem)
def test_contract_samples_validate(path: Path):
    doc = ParsedDocument.model_validate(json.loads(path.read_text(encoding="utf-8")))
    assert doc.pages
    assert doc.parser_version


@pytest.mark.skipif(not _samples(), reason=_MISSING)
@pytest.mark.parametrize("path", _samples(), ids=lambda p: p.stem)
def test_contract_samples_are_real_parser_output(path: Path):
    """샘플은 실문서 파서 출력이어야 한다(F-EXT-001 완료 후).

    수동 샘플로 되돌아가면 여기서 잡힌다 — 사람이 만든 문서로 추출 품질을 말하면
    성능 수치의 출처가 무너진다."""
    doc = ParsedDocument.model_validate(json.loads(path.read_text(encoding="utf-8")))
    assert not doc.is_manual, "MANUAL_OVERRIDE — 수동 샘플로 되돌아갔다"


@pytest.mark.skipif(not _samples(), reason=_MISSING)
@pytest.mark.parametrize("path", _samples(), ids=lambda p: p.stem)
def test_span_identity_holds_for_expected_items(path: Path):
    """계약 규약: pages[page].text[start:end] == value_text.

    F-EXT-002의 스팬 검증 후처리가 이 등식을 쓴다. 여기서 깨지면 규약 이해가 틀린 것이다.
    """
    raw = json.loads(path.read_text(encoding="utf-8"))
    doc = ParsedDocument.model_validate(raw)
    expected = raw.get("_expected_risk_items") or []
    assert expected, "샘플에 _expected_risk_items가 있어야 한다"
    for item in expected:
        span = item["source_span"]
        text = doc.page_text(span["page"])
        assert text is not None, f"{item['item_id']}: page {span['page']} 없음"
        assert text[span["start"]:span["end"]] == item["value_text"], item["item_id"]


@pytest.mark.skipif(not _samples(), reason=_MISSING)
def test_char_count_is_codepoint_length():
    for path in _samples():
        doc = ParsedDocument.model_validate(json.loads(path.read_text(encoding="utf-8")))
        for page in doc.pages:
            if page.char_count is not None:
                assert page.char_count == len(page.text), f"{path.stem} page {page.page}"
