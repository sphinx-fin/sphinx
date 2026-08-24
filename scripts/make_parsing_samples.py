"""contracts/samples/ 파싱 출력 샘플 생성기. 소유: 정세현

오프셋을 손으로 쓰면 반드시 틀리므로 계산해서 박는다.
text[start:end] == value_text 를 전건 검증하며, 깨지면 종료 코드가 0이 아니다.

    python3 scripts/make_parsing_samples.py

파서(F-EXT-001)가 완성되면 이 스크립트와 산출물은 실제 파서 출력으로 대체한다.
"""
import json, unicodedata, pathlib, sys

OUT = pathlib.Path(__file__).resolve().parent.parent / "contracts" / "samples"
OUT.mkdir(parents=True, exist_ok=True)


def nfc(s):
    return unicodedata.normalize("NFC", s)


def build(doc, pages_text, quotes, tables, warnings):
    pages = []
    for pno, t in pages_text:
        t = nfc(t)
        pages.append({"page": pno, "text": t, "char_count": len(t)})
    by_page = {p["page"]: p["text"] for p in pages}

    spans = []
    for item_id, name, pno, quote in quotes:
        q = nfc(quote)
        text = by_page[pno]
        start = text.find(q)
        if start < 0:
            sys.exit(f"FAIL: {item_id} quote not found on page {pno}")
        end = start + len(q)
        assert text[start:end] == q, item_id
        spans.append({
            "item_id": item_id, "name": name,
            "value_text": q,
            "source_span": {"page": pno, "start": start, "end": end},
        })

    doc = dict(doc)
    doc["page_count"] = len(pages)
    doc["pages"] = pages
    doc["tables"] = tables
    doc["parse_warnings"] = warnings
    doc["_expected_risk_items"] = spans
    return doc


els_p1 = """[간이투자설명서] 파생결합증권(ELS) 제0000회

1. 상품 개요
본 증권은 기초자산의 가격 변동에 따라 손익이 결정되는 파생결합증권입니다.
이 금융투자상품은 예금자보호법에 따라 보호되지 않습니다.
투자원금의 손실이 발생할 수 있으며, 그 손실은 투자자에게 귀속됩니다."""

els_p3 = """3. 상환 조건

가. 조기상환
각 조기상환평가일에 기초자산 모두가 행사가격 이상인 경우 액면금액과 수익을 지급합니다.

나. 만기상환
만기평가일에 기초자산 중 어느 하나라도 최초기준가격의 50% 미만인 경우 원금 손실이 발생할 수 있습니다.
이 경우 손실률은 최초기준가격 대비 하락률에 연동됩니다."""

els = build(
    {
        "document_id": "doc-els-demo-001",
        "product_type": "ELS",
        "source_file": "els_simple_prospectus_demo.pdf",
        "parser_version": "manual-0",
        "parsed_at": "2026-08-22T00:00:00Z",
    },
    [(1, els_p1), (3, els_p3)],
    [
        ("ELS-NO-DEPOSIT-INSURANCE", "예금자보호 비대상", 1,
         "이 금융투자상품은 예금자보호법에 따라 보호되지 않습니다."),
        ("ELS-PRINCIPAL-LOSS", "원금손실 조건", 3,
         "만기평가일에 기초자산 중 어느 하나라도 최초기준가격의 50% 미만인 경우 원금 손실이 발생할 수 있습니다."),
    ],
    [{"page": 3, "caption": "기초자산 및 행사가격",
      "rows": [["기초자산", "최초기준가격", "행사가격(조기)", "낙인"],
               ["HSCEI", "6,000.00", "5,400.00", "3,000.00"],
               ["S&P500", "5,000.00", "4,500.00", "2,500.00"]]}],
    [{"code": "MANUAL_OVERRIDE", "page": None,
      "message": "파서 미구현 구간의 수동 샘플. 윤지석 F-EXT-002 프롬프트 착수용 — 파서 완성 시 대체한다."}],
)

var_p1 = """[변액보험 상품설명서]

1. 상품의 성격
이 계약은 실적배당형 상품으로 투자 결과에 따라 해지환급금이 원금보다 적을 수 있습니다.
납입한 보험료 중 일부는 사업비로 차감되며, 특별계정에 투입되는 금액은 납입 보험료보다 적습니다.
계약을 조기에 해지하는 경우 해지환급금이 납입 보험료를 크게 밑돌 수 있습니다."""

var = build(
    {
        "document_id": "doc-var-demo-001",
        "product_type": "VARIABLE_INSURANCE",
        "source_file": "variable_insurance_demo.pdf",
        "parser_version": "manual-0",
        "parsed_at": "2026-08-22T00:00:00Z",
    },
    [(1, var_p1)],
    [
        ("VAR-PRINCIPAL-LOSS", "원금손실 가능성", 1,
         "이 계약은 실적배당형 상품으로 투자 결과에 따라 해지환급금이 원금보다 적을 수 있습니다."),
        ("VAR-FEE-DEDUCTION", "사업비 차감", 1,
         "납입한 보험료 중 일부는 사업비로 차감되며, 특별계정에 투입되는 금액은 납입 보험료보다 적습니다."),
        ("VAR-EARLY-SURRENDER", "조기해지 불이익", 1,
         "계약을 조기에 해지하는 경우 해지환급금이 납입 보험료를 크게 밑돌 수 있습니다."),
    ],
    [],
    [{"code": "MANUAL_OVERRIDE", "page": None,
      "message": "파서 미구현 구간의 수동 샘플. 윤지석 F-EXT-002 프롬프트 착수용 — 파서 완성 시 대체한다."}],
)

for name, obj in [("parsed_els_sample.json", els), ("parsed_variable_sample.json", var)]:
    (OUT / name).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {name}: pages={obj['page_count']} items={len(obj['_expected_risk_items'])}")
    for s in obj["_expected_risk_items"]:
        sp = s["source_span"]
        page_text = next(p["text"] for p in obj["pages"] if p["page"] == sp["page"])
        ok = page_text[sp["start"]:sp["end"]] == s["value_text"]
        print(f"  {'OK ' if ok else 'BAD'} {s['item_id']} p{sp['page']} [{sp['start']},{sp['end']})")
