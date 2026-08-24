"""F-EXT-001 파서 계약 테스트. 소유: 정세현

검증 대상은 "그럴듯한 텍스트가 나왔는지"가 아니라 **계약 항등식**이다:
`pages[page].text[start:end] == value_text`. 이게 깨지면 추출은 성공하는데 S-01·S-05의
하이라이트가 밀리고, 그 증상은 화면을 보기 전까지 드러나지 않는다.
"""
import json
import pathlib

import jsonschema
import pytest

from app import parsing

SCHEMA_PATH = (
    pathlib.Path(__file__).resolve().parents[2] / "contracts" / "parsed_document.schema.json"
)


@pytest.fixture(scope="session")
def schema():
    return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def els_doc(els_pdf):
    return parsing.parse_document(
        els_pdf, document_id="doc-els-demo-001", product_type="ELS",
        parsed_at="2026-08-24T00:00:00Z",
    )


@pytest.fixture(scope="session")
def var_doc(var_pdf):
    return parsing.parse_document(
        var_pdf, document_id="doc-var-demo-001", product_type="VARIABLE_INSURANCE",
        parsed_at="2026-08-24T00:00:00Z",
    )


# --- 계약 준수 -------------------------------------------------------------------

def test_els_output_satisfies_contract(els_doc, schema):
    jsonschema.validate(els_doc, schema)


def test_var_output_satisfies_contract(var_doc, schema):
    jsonschema.validate(var_doc, schema)


def test_pages_are_one_based_and_contiguous(els_doc):
    assert [p["page"] for p in els_doc["pages"]] == list(range(1, els_doc["page_count"] + 1))


def test_char_count_is_codepoint_length(els_doc, var_doc):
    for doc in (els_doc, var_doc):
        for page in doc["pages"]:
            assert page["char_count"] == len(page["text"])


def test_pages_are_nfc_normalized(els_doc, var_doc):
    import unicodedata
    for doc in (els_doc, var_doc):
        for page in doc["pages"]:
            assert page["text"] == unicodedata.normalize("NFC", page["text"])


def test_parser_version_recorded(els_doc):
    assert els_doc["parser_version"] == parsing.PARSER_VERSION


def test_parsed_at_omitted_when_not_injected(els_pdf):
    doc = parsing.parse_document(els_pdf, document_id="d", product_type="ELS")
    assert "parsed_at" not in doc


def test_rejects_product_type_outside_demo_scope(els_pdf):
    with pytest.raises(ValueError, match="product_type"):
        parsing.parse_document(els_pdf, document_id="d", product_type="FUND")


# --- P2 재현성 -------------------------------------------------------------------

def test_reparsing_gives_identical_output(els_pdf):
    kwargs = dict(document_id="doc-els-demo-001", product_type="ELS",
                  parsed_at="2026-08-24T00:00:00Z")
    first = parsing.parse_document(els_pdf, **kwargs)
    second = parsing.parse_document(els_pdf, **kwargs)
    assert first == second


# --- 스팬 해소: 계약 샘플의 정답 인용문이 실제 파서 출력에서 해소되는가 ------------------

def _expected(sample):
    return [(s["item_id"], s["source_span"]["page"], s["value_text"])
            for s in sample["_expected_risk_items"]]


def test_els_expected_quotes_resolve(els_doc, els_sample):
    for item_id, page, quote in _expected(els_sample):
        got = parsing.resolve_span(els_doc, page, quote)
        assert got is not None, f"{item_id}: p{page}에서 인용문을 찾지 못했다"
        assert parsing.verify_span(els_doc, got["source_span"], got["value_text"]), item_id


def test_var_expected_quotes_resolve(var_doc, var_sample):
    for item_id, page, quote in _expected(var_sample):
        got = parsing.resolve_span(var_doc, page, quote)
        assert got is not None, f"{item_id}: p{page}에서 인용문을 찾지 못했다"
        assert parsing.verify_span(var_doc, got["source_span"], got["value_text"]), item_id


def test_resolved_value_text_is_the_document_slice_not_the_query(els_doc, els_sample):
    """PDF가 문장 중간에서 개행하면 잘라낸 값과 질의문이 다르다.

    이때 F-EXT-002가 질의문을 그대로 value_text로 쓰면 항등식이 깨진다 — 그래서
    resolve_span은 문서 슬라이스를 돌려주고, 호출자는 그걸 써야 한다.
    """
    item_id, page, quote = _expected(els_sample)[1]
    got = parsing.resolve_span(els_doc, page, quote)
    text = parsing.page_text(els_doc, page)
    sp = got["source_span"]
    assert text[sp["start"]:sp["end"]] == got["value_text"]
    assert got["value_text"].replace("\n", "").replace(" ", "") == quote.replace(" ", "")


# --- 스팬 해소 전략 (PDF 없이 순수 단위 테스트) ------------------------------------

def _doc(text):
    return {"document_id": "t", "pages": [{"page": 1, "text": text, "char_count": len(text)}]}


def test_exact_match():
    quote = "원금 손실이 발생할 수 있습니다."
    got = parsing.resolve_span(_doc(f"가나 {quote} 다라"), 1, quote)
    assert got["match"] == "exact"
    assert got["source_span"] == {"page": 1, "start": 3, "end": 3 + len(quote)}
    assert got["value_text"] == quote


def test_whitespace_match_when_space_became_newline():
    got = parsing.resolve_span(_doc("원금 손실이\n발생할 수 있습니다."), 1,
                               "원금 손실이 발생할 수 있습니다.")
    assert got["match"] == "whitespace"
    assert got["value_text"] == "원금 손실이\n발생할 수 있습니다."


def test_loose_match_when_newline_split_a_token():
    got = parsing.resolve_span(_doc("최초기준가격의 50% 미\n만인 경우"), 1,
                               "최초기준가격의 50% 미만인 경우")
    assert got["match"] == "loose"
    assert got["value_text"] == "최초기준가격의 50% 미\n만인 경우"


def test_returns_none_when_absent():
    assert parsing.resolve_span(_doc("원금 보장 상품입니다."), 1, "예금자보호") is None


def test_find_occurrences_flags_ambiguity():
    doc = _doc("원금 손실. 그리고 또 원금 손실.")
    assert len(parsing.find_occurrences(doc, 1, "원금 손실")) == 2


def test_verify_span_rejects_shifted_offsets():
    doc = _doc("원금 손실이 발생할 수 있습니다.")
    assert parsing.verify_span(doc, {"page": 1, "start": 0, "end": 5}, "원금 손실")
    assert not parsing.verify_span(doc, {"page": 1, "start": 1, "end": 6}, "원금 손실")
    assert not parsing.verify_span(doc, {"page": 1, "start": 0, "end": 999}, "원금 손실")


# --- 표 ---------------------------------------------------------------------------

def test_els_table_extracted(els_doc):
    tables = [t for t in els_doc["tables"] if t["page"] == 3]
    assert tables, "p3 기초자산 표를 찾지 못했다"
    flat = [cell for row in tables[0]["rows"] for cell in row]
    assert "HSCEI" in flat
    assert "6,000.00" in flat


def test_table_caption_guessed(els_doc):
    tables = [t for t in els_doc["tables"] if t["page"] == 3]
    assert tables[0]["caption"] == "기초자산 및 행사가격"


def test_table_text_is_also_in_page_text(els_doc):
    """계약: 표 안 텍스트도 pages[].text에 있어야 한다 — 스팬이 tables[] 없이 해소되도록."""
    for table in els_doc["tables"]:
        text = parsing.page_text(els_doc, table["page"])
        for row in table["rows"]:
            for cell in row:
                if cell.strip():
                    assert cell in text, f"{cell!r}가 p{table['page']} 본문에 없다"


# --- 경고 -------------------------------------------------------------------------

def test_clean_parse_has_no_warnings(var_doc):
    assert var_doc["parse_warnings"] == []


def test_text_layer_missing_is_reported(scanned_pdf):
    doc = parsing.parse_document(scanned_pdf, document_id="d", product_type="ELS")
    codes = [w["code"] for w in doc["parse_warnings"]]
    assert "TEXT_LAYER_MISSING" in codes
    assert doc["pages"][0]["text"] == ""


def test_find_occurrences_uses_same_strategies_as_resolve_span():
    """resolve_span이 찾은 문면을 find_occurrences가 0회라고 하면 모호성 검사가 무력해진다."""
    doc = _doc("이 파생결\n합증권은 보호되지 않습니다. 또 파생결\n합증권은 보호되지 않습니다.")
    quote = "파생결합증권은 보호되지 않습니다."
    got = parsing.resolve_span(doc, 1, quote)
    assert got["match"] == "loose"
    occ = parsing.find_occurrences(doc, 1, quote)
    assert len(occ) == 2
    assert all(parsing.verify_span(doc, o["source_span"], o["value_text"]) for o in occ)


def test_find_occurrences_prefers_exact_over_looser_tiers():
    doc = _doc("원금 손실. 원금\n손실.")
    occ = parsing.find_occurrences(doc, 1, "원금 손실")
    assert [o["match"] for o in occ] == ["exact"]
