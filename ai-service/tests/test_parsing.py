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
from collections import Counter

import jsonschema
import pytest

from app import parsing

from conftest import DOCS, REAL_CASES, SYNTHETIC_QUOTES

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

@pytest.fixture(scope="session")
def els_doc():
    """ELS 실문서 하나만 보는 자리.

    ❗**`real_case` 를 쓰고 `var` 회차에서 `skip` 하면 안 된다.** 이 레포는 CI 에서 건너뛴
    테스트를 **실패로 센다**(`no_skip.py` · `#37` 코멘트 ②) — 실제로 그렇게 냈다가 걸렸다.
    """
    spec = REAL_CASES["els"]
    pdf = DOCS / spec["pdf"]
    if not pdf.exists():  # 추적되는 파일이다 — 없으면 체크아웃이 온전하지 않은 것이다
        pytest.skip(f"{pdf} 없음. {spec['fetch']}")
    return parsing.parse_document(
        str(pdf), document_id=spec["document_id"],
        product_type=spec["product_type"], parsed_at="2026-08-24T00:00:00Z",
    )


#: 상환조건 표에서 **조건절부터 결론까지 한 항목으로 읽혀야 하는** 자리들.
#: ⑧ 은 `ELS-MATURITY-LOSS-CONDITION`·`ELS-KNOCKIN-BARRIER` 의 근거고, ⑦ 은 `#452`
#: 리뷰에서 `@yoonjiseok` 이 *"⑧만 고치고 ⑦은 오히려 옮겨 놨다"* 로 잡아낸 자리다.
#: ① 은 `ELS-EARLY-REDEMPTION-CONDITION`(required) 의 근거이고 **p7** 에 있다 — `#455`
#: 가 열린 자리다. 인용이 「이상인 경」에서 끊겨 결론이 통째로 빠져 있었다.
ELS_CLAUSES = [
    (7, "①", "① 1차 자동조기상환평가일", "원금 × [100%+ 5.50%]"),
    (7, "②", "② 2차 자동조기상환평가일", "원금 × [100%+ 11.00%]"),
    (8, "⑦", "⑦ 위 ⑥에 해당하지", "원금 × [100%+33.00%]"),
    (8, "⑧", "⑧ 위 ⑥에 해당하지", "이 경우 원금 손실이 발생합니다."),
]


#: 본문에 끼면 안 되는 표 칸 조각들.
#:
#:   앞의 넷은 ⑧행 수익률 칸이다 — **다른 y** 에 있어서 본문 줄 **사이로** 끼어들던 것이고
#:   `#452` 가 줄 재정렬로 뺐다.
#:
#:   뒤의 넷은 **같은 y** 라 `extract_text` 가 본문 줄 **끝에** 붙여 오던 것이다. 줄
#:   재정렬로는 못 갈라서 `#452` 가 별도 사안으로 미뤘고(`b51d050`), `#455` 가 낱말 사이
#:   간격과 칸 경계로 갈랐다. 그전까지 이 그물은 *"항목이 끊기지 않는다"* 까지만 쟀고
#:   *"칸 조각이 없다"* 는 못 쟀다 — 지금은 둘 다 잰다.
_STRAYS = [
    "-100% ~-30%", "(기초자산 중 하", "락폭이 큰 종목의", "수익률)",
    "5.50%", "11.00%", "33.00%", "(연 11.00%)",
]


@pytest.mark.parametrize("page,tag,head,tail", ELS_CLAUSES,
                         ids=[f"p{c[0]}{c[1]}" for c in ELS_CLAUSES])
def test_an_els_clause_runs_unbroken_from_condition_to_conclusion(els_doc, page, tag, head, tail):
    """★ 조건절부터 결론까지 **다른 항목이 끼지 않고** 이어진다 (`#436` 합격 기준).

    ⑧ 은 고치기 전 사이에 수익률 칸 조각이 100자 끼어 있었다. ⑦ 은 `#452` 의 첫 판이
    끊김을 **없앤 게 아니라 옮겨** 놓아서, *"⑤ 5차 조기상환 27.50% … 하락한 적이 없는
    경우 만기상환금액은 다음과 같습니다"* 라는 **P6 를 통과하는 오답**이 만들어졌던 자리다.

    ❗그래서 이 그물은 ⑧ 하나로는 부족하다 — `#446` 도 `#452` 첫 판도 **고친 자리 옆에서**
    깨졌다. 한 항목만 재는 그물은 옮겨 간 끊김을 구조적으로 못 본다.

    ## 같은 y 로 합쳐져 있던 조각도 이제 잰다

    예전에는 `_STRAYS` 가 **다른 y 에 있던 칸 줄**만 봤다. `extract_text` 가 같은 y 의
    칸들을 이미 한 줄로 합쳐 온 것은 줄 재정렬로 못 갈라서, 이 단정이 참인 것이
    *"칸 조각이 없다"* 가 아니라 *"항목이 끊기지 않는다"* 까지였다(`#452` 리뷰, 윤지석).

        도 각각의 최초기준가격의 45%인 45/ 45/ 45 미만으로 33.00%    ← 33.00% 가 붙어 있었다
        두 각각의 최초기준가격의 85%인 85/ 85/ 85 이상인 경 5.50%    ← ①의 인용을 끊던 자리

    `#455` 가 낱말 사이 간격과 칸 경계로 그것을 갈랐고, `_STRAYS` 에 수익률 문면 넷이
    더해졌다. **이제 두 뜻이 같다.**
    """
    text = els_doc["pages"][page - 1]["text"]
    start, end = text.index(head), text.index(tail) + len(tail)
    clause = text[start:end]
    # 결론 자체가 금액이라 끝에 수익률 문면을 담는다(`원금 × [100%+ 5.50%]`). 조각을
    # 찾는 자리는 **조건절부터 결론 직전까지**다 — 결론을 넣고 세면 늘 빨갛다.
    body = clause[:len(clause) - len(tail)]
    assert [s for s in _STRAYS if s in body] == [], f"p{page}{tag}: {clause}"


def test_cell_ordering_never_rewrites_text(real_case):
    """★ 칸 순서 읽기는 **자리를 바꾸고 가르기만** 한다 — 글자를 더하거나 지우거나 고치지 않는다.

    이걸 안 잠그면 「읽는 순서를 고친다」가 「원문을 다시 쓴다」로 조용히 번진다. 그러면
    `pages[page].text[start:end] == value_text`(1절 F-EXT-002 통제의 P6)가 원문이 아닌
    것을 가리키게 되고, 그 결함은 감사 시점까지 안 드러난다.

    ❗예전에는 **줄 다중집합이 같은가**로 쟀다. `#455` 가 같은 y 의 칸을 가르면서 줄 수가
    늘어 그 잣대가 못 쓰게 됐다. 약하게 푸는 대신 **둘로 갈라** 같은 것을 잰다.

        ① 공백을 뺀 글자 다중집합이 같다        — 더하기·지우기·고치기를 잡는다
        ② 새 줄은 전부 옛 줄 **하나 안에** 있다  — 가르기만 했지 이어 붙이지 않았다

    ②가 있어야 ①이 허용하는 「글자를 섞어 다시 쓰기」가 막힌다. 하나만 두면 안 된다.
    """
    import pdfplumber

    with pdfplumber.open(real_case["pdf"]) as pdf:
        for index, page in enumerate(pdf.pages):
            before = unicodedata.normalize(
                "NFC",
                page.extract_text(x_tolerance=parsing._X_TOLERANCE,
                                  y_tolerance=parsing._Y_TOLERANCE) or "",
            )
            after = real_case["doc"]["pages"][index]["text"]

            assert Counter("".join(before.split())) == Counter("".join(after.split())), \
                f"p{index + 1} 글자가 더해졌거나 지워졌다"

            was = before.split("\n")
            for line in after.split("\n"):
                assert not line or any(line in old for old in was), \
                    f"p{index + 1} 옛 줄 어디에도 통째로 없는 줄이다: {line!r}"


def test_lines_outside_tables_do_not_move(real_case):
    """★ 표 밖의 줄은 한 글자도 안 바뀌고 **순서도 그대로**다 — 파급을 표 안으로 가둔다.

    ❗예전에는 **줄 번호가 같은가**로 쟀다. `#455` 가 표 **안**에서 줄을 가르면 그 뒤 줄의
    번호가 통째로 밀리므로, 표 밖이 멀쩡해도 그 잣대는 빨개진다. 번호 대신 **부분열**로
    잰다 — 표 밖 줄들이 같은 문면으로 같은 순서로 남아 있는가.
    """
    import pdfplumber

    with pdfplumber.open(real_case["pdf"]) as pdf:
        for index, page in enumerate(pdf.pages):
            boxes = [t.bbox for t in page.find_tables()]
            lines = page.extract_text_lines(x_tolerance=parsing._X_TOLERANCE,
                                            y_tolerance=parsing._Y_TOLERANCE)
            outside = [
                unicodedata.normalize("NFC", line["text"])
                for line in lines
                if not any(b[1] - 1 <= (line["top"] + line["bottom"]) / 2 <= b[3] + 1
                           for b in boxes)
            ]
            got = real_case["doc"]["pages"][index]["text"].split("\n")

            at = 0
            for expected in outside:
                try:
                    at = got.index(expected, at) + 1
                except ValueError:
                    raise AssertionError(
                        f"p{index + 1} 표 밖 줄이 사라졌거나 순서가 바뀌었다: {expected!r}"
                    ) from None
