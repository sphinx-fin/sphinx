"""F-EXT-001 파서 계약 테스트. 소유: 정세현

검증 대상은 "그럴듯한 텍스트가 나왔는지"가 아니라 **계약 항등식**이다:
`pages[page].text[start:end] == value_text`. 이게 깨지면 추출은 성공하는데 S-01·S-05의
하이라이트가 밀리고, 그 증상은 화면을 보기 전까지 드러나지 않는다.

세 층으로 나뉜다.
1. 순수 단위 테스트 — 스팬 해소 전략. PDF도 문서도 필요 없다.
2. 합성 PDF — 표 추출·캡션·텍스트 레이어 부재 같은 기계적 동작.
3. 실문서 — 계약 준수의 최종 확인. 데모 대상 2종 각각에 대해 돌고, 문서가 없으면 skip.
"""
import json
import pathlib
import unicodedata

import jsonschema
import pytest

from app import parsing

from conftest import SYNTHETIC_QUOTES

SCHEMA_PATH = (
    pathlib.Path(__file__).resolve().parents[2] / "contracts" / "parsed_document.schema.json"
)


@pytest.fixture(scope="session")
def schema():
    return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def synthetic_doc(synthetic_pdf):
    return parsing.parse_document(
        synthetic_pdf, document_id="doc-syn-001", product_type="ELS",
        parsed_at="2026-08-24T00:00:00Z",
    )


# --- 1. 스팬 해소 전략 (PDF 없이) ---------------------------------------------------

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


def test_verify_span_rejects_shifted_offsets():
    doc = _doc("원금 손실이 발생할 수 있습니다.")
    assert parsing.verify_span(doc, {"page": 1, "start": 0, "end": 5}, "원금 손실")
    assert not parsing.verify_span(doc, {"page": 1, "start": 1, "end": 6}, "원금 손실")
    assert not parsing.verify_span(doc, {"page": 1, "start": 0, "end": 999}, "원금 손실")


# --- 2. 합성 PDF: 기계적 동작 -------------------------------------------------------

def test_synthetic_output_satisfies_contract(synthetic_doc, schema):
    jsonschema.validate(synthetic_doc, schema)


def test_pages_are_one_based_and_contiguous(synthetic_doc):
    assert [p["page"] for p in synthetic_doc["pages"]] == \
        list(range(1, synthetic_doc["page_count"] + 1))


def test_char_count_is_codepoint_length(synthetic_doc):
    for page in synthetic_doc["pages"]:
        assert page["char_count"] == len(page["text"])


def test_pages_are_nfc_normalized(synthetic_doc):
    for page in synthetic_doc["pages"]:
        assert page["text"] == unicodedata.normalize("NFC", page["text"])


def test_parser_version_recorded(synthetic_doc):
    assert synthetic_doc["parser_version"] == parsing.PARSER_VERSION


def test_parsed_at_omitted_when_not_injected(synthetic_pdf):
    doc = parsing.parse_document(synthetic_pdf, document_id="d", product_type="ELS")
    assert "parsed_at" not in doc


def test_rejects_product_type_outside_demo_scope(synthetic_pdf):
    with pytest.raises(ValueError, match="product_type"):
        parsing.parse_document(synthetic_pdf, document_id="d", product_type="FUND")


def test_reparsing_gives_identical_output(synthetic_pdf):
    kwargs = dict(document_id="d", product_type="ELS", parsed_at="2026-08-24T00:00:00Z")
    assert parsing.parse_document(synthetic_pdf, **kwargs) == \
        parsing.parse_document(synthetic_pdf, **kwargs)


def test_synthetic_quotes_resolve(synthetic_doc):
    for item_id, page, quote in SYNTHETIC_QUOTES:
        got = parsing.resolve_span(synthetic_doc, page, quote)
        assert got is not None, f"{item_id}: p{page}에서 인용문을 찾지 못했다"
        assert parsing.verify_span(synthetic_doc, got["source_span"], got["value_text"]), item_id


def test_wrapped_sentence_resolves_to_document_slice(synthetic_doc):
    """PDF가 문장 중간에서 개행하면 잘라낸 값과 질의문이 다르다.

    이때 F-EXT-002가 질의문을 그대로 value_text로 쓰면 항등식이 깨진다 — 그래서
    resolve_span은 문서 슬라이스를 돌려주고, 호출자는 그걸 써야 한다.
    """
    _item_id, page, quote = SYNTHETIC_QUOTES[1]
    got = parsing.resolve_span(synthetic_doc, page, quote)
    assert got["match"] != "exact", "이 문장은 폭에 맞춰 접히므로 그대로는 안 잡혀야 한다"
    text = parsing.page_text(synthetic_doc, page)
    sp = got["source_span"]
    assert text[sp["start"]:sp["end"]] == got["value_text"]
    assert got["value_text"].replace("\n", "").replace(" ", "") == quote.replace(" ", "")


def test_table_extracted_with_caption(synthetic_doc):
    tables = [t for t in synthetic_doc["tables"] if t["page"] == 3]
    assert tables, "p3 기초자산 표를 찾지 못했다"
    flat = [cell for row in tables[0]["rows"] for cell in row]
    assert "HSCEI" in flat
    assert "6,000.00" in flat
    assert tables[0]["caption"] == "기초자산 및 행사가격"


def test_table_text_is_also_in_page_text(synthetic_doc):
    """계약: 표 안 텍스트도 pages[].text에 있어야 한다 — 스팬이 tables[] 없이 해소되도록."""
    for table in synthetic_doc["tables"]:
        text = parsing.page_text(synthetic_doc, table["page"])
        for row in table["rows"]:
            for cell in row:
                if cell.strip():
                    assert cell in text, f"{cell!r}가 p{table['page']} 본문에 없다"


def test_text_layer_missing_is_reported(scanned_pdf):
    doc = parsing.parse_document(scanned_pdf, document_id="d", product_type="ELS")
    codes = [w["code"] for w in doc["parse_warnings"]]
    assert "TEXT_LAYER_MISSING" in codes
    assert doc["pages"][0]["text"] == ""


# --- 3. 실문서: 계약 준수 최종 확인 (데모 대상 2종) ---------------------------------

def test_real_document_satisfies_contract(real_case, schema):
    jsonschema.validate(real_case["doc"], schema)


def test_real_document_parses_without_warnings(real_case):
    """실문서에서 경고가 나기 시작하면 문서가 교체됐거나 파서가 퇴행한 것이다."""
    assert real_case["doc"]["parse_warnings"] == [], real_case["doc"]["parse_warnings"]


def test_real_document_pages_are_nfc_and_counted(real_case):
    for page in real_case["doc"]["pages"]:
        assert page["char_count"] == len(page["text"])
        assert page["text"] == unicodedata.normalize("NFC", page["text"])


def test_real_document_matches_committed_sample(real_case):
    """계약 샘플은 이 문서의 파서 출력이어야 한다 (contracts/README.md 완료 조건).

    어긋나면 샘플을 다시 생성해야 한다: python3 scripts/make_parsing_samples.py
    """
    doc, sample = real_case["doc"], real_case["sample"]
    for key in ("document_id", "product_type", "parser_version", "page_count"):
        assert doc[key] == sample[key], key
    assert [p["text"] for p in doc["pages"]] == [p["text"] for p in sample["pages"]]


def test_real_document_sample_is_parser_output_not_handmade(real_case):
    """MANUAL_OVERRIDE 가 남아 있으면 아직 사람이 만든 샘플이다."""
    codes = [w["code"] for w in real_case["sample"]["parse_warnings"]]
    assert "MANUAL_OVERRIDE" not in codes


def test_real_document_expected_spans_hold(real_case):
    doc, items = real_case["doc"], real_case["sample"]["_expected_risk_items"]
    assert len(items) >= 9, "이해항목이 너무 적다 — 정답 세트로 쓸 수 없다"
    for item in items:
        assert parsing.verify_span(doc, item["source_span"], item["value_text"]), \
            item["item_id"]


def test_real_document_expected_spans_are_unambiguous(real_case):
    """같은 문면이 여러 번 나오면 어느 쪽을 가리키는지 알 수 없어 정답으로 못 쓴다."""
    doc = real_case["doc"]
    for item in real_case["sample"]["_expected_risk_items"]:
        page = item["source_span"]["page"]
        occ = parsing.find_occurrences(doc, page, item["value_text"])
        assert len(occ) == 1, f"{item['item_id']}: p{page}에 {len(occ)}회 출현"


def test_real_document_reparsing_is_deterministic(real_case):
    first = parsing.parse_document(real_case["pdf"], **real_case["kwargs"])
    second = parsing.parse_document(real_case["pdf"], **real_case["kwargs"])
    assert first == second


def test_real_document_records_its_source(real_case):
    """샘플이 어느 문서에서 나왔는지 추적 불가능하면 재생성도 검증도 못 한다."""
    source = real_case["sample"]["_source"]
    assert source["sha256"] and len(source["sha256"]) == 64
    assert source["fetch_key"]


# --- 4. 표 안은 칸 순서로 읽는다 (이슈 #436) ---------------------------------------
#
# `extract_text` 는 줄을 y 하나로 세워서, 한 행 안의 오른쪽 칸 줄이 왼쪽 칸 줄들 *사이* y 에
# 떨어지면 두 칸이 번갈아 들어간다. 그러면 **인용의 쓸모**가 깨진다 — 조건부터 결론까지
# 걸치는 인용에 남의 칸 글자가 끼므로, 모델은 깨끗한 조각만 인용하고 결론을 버린다.
#
# ❗**`13/13` 은 합격 기준이 아니다.** 회차마다 어디까지 인용하는지가 갈리므로 끊김이 남아
# 있어도 통과하는 회차가 있다(`#436`, 윤지석). 잴 것은 **원문에서 끊김이 해소됐는가**고,
# 그건 LLM 없이 여기서 잰다.

def test_the_els_maturity_clause_runs_unbroken_from_condition_to_conclusion(real_case):
    """★ ⑧항이 조건절부터 결론까지 남의 칸 글자 없이 이어진다 (`#436` 합격 기준).

    이 문장이 `ELS-MATURITY-LOSS-CONDITION`·`ELS-KNOCKIN-BARRIER` 의 근거고, 결론
    ("이 경우 원금 손실이 발생합니다.")까지 걸쳐야 손실 조건으로 읽힌다. 고치기 전에는
    사이에 수익률 칸 조각이 100자 끼어 있었다.
    """
    if real_case["key"] != "els":
        pytest.skip("ELS 문서의 자리다")
    text = real_case["doc"]["pages"][7]["text"]
    head, tail = "⑧ 위 ⑥에 해당하지", "이 경우 원금 손실이 발생합니다."
    start, end = text.index(head), text.index(tail) + len(tail)
    clause = text[start:end]
    strays = ["33.00%", "(연 11.00%)", "-100% ~-30%",
              "(기초자산 중 하", "락폭이 큰 종목의", "수익률)"]
    assert [s for s in strays if s in clause] == [], clause


def test_cell_ordering_only_permutes_lines(real_case):
    """★ 칸 순서 읽기는 **자리바꿈**이다 — 줄을 더하거나 지우거나 고치지 않는다.

    이걸 안 잠그면 「읽는 순서를 고친다」가 「원문을 다시 쓴다」로 조용히 번진다. 그러면
    `pages[page].text[start:end] == value_text`(1절 F-EXT-002 통제의 P6)가 원문이 아닌
    것을 가리키게 되고, 그 결함은 감사 시점까지 안 드러난다.
    """
    import pdfplumber

    with pdfplumber.open(real_case["pdf"]) as pdf:
        for index, page in enumerate(pdf.pages):
            before = page.extract_text(x_tolerance=parsing._X_TOLERANCE,
                                       y_tolerance=parsing._Y_TOLERANCE) or ""
            after = real_case["doc"]["pages"][index]["text"]
            assert sorted(unicodedata.normalize("NFC", before).split("\n")) == \
                sorted(after.split("\n")), f"p{index + 1}"


def test_lines_outside_tables_do_not_move(real_case):
    """★ 표 밖의 줄은 한 글자도 안 움직인다 — 파급을 표 안으로 가둔다."""
    import pdfplumber

    with pdfplumber.open(real_case["pdf"]) as pdf:
        for index, page in enumerate(pdf.pages):
            boxes = [t.bbox for t in page.find_tables()]
            lines = page.extract_text_lines(x_tolerance=parsing._X_TOLERANCE,
                                            y_tolerance=parsing._Y_TOLERANCE)
            outside = {
                i: line["text"] for i, line in enumerate(lines)
                if not any(b[1] - 1 <= (line["top"] + line["bottom"]) / 2 <= b[3] + 1
                           for b in boxes)
            }
            got = real_case["doc"]["pages"][index]["text"].split("\n")
            for i, expected in outside.items():
                assert got[i] == unicodedata.normalize("NFC", expected), \
                    f"p{index + 1} L{i + 1}"
