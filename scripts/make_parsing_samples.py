"""contracts/samples/ 파싱 출력 샘플 생성기. 소유: 정세현

    python3 scripts/make_parsing_samples.py

두 샘플의 성격이 다르다.

- **ELS** — 실문서(키움증권 제4181회 간이투자설명서)를 실제 파서로 파싱한 출력이다.
  `data/documents/`(git 제외)에 문서가 있어야 한다. 없으면 `scripts/fetch_documents.py`를
  먼저 돌린다. `parse_warnings`는 파서가 낸 그대로이며, 사람이 만든 게 아니므로
  `MANUAL_OVERRIDE`가 붙지 않는다.
- **변액보험** — 아직 실문서가 없어 수동 샘플이다. `MANUAL_OVERRIDE`로 표시한다.
  생명보험협회/생보사 공시실에서 상품설명서를 확보하면 ELS와 같은 경로로 대체한다.

`_expected_risk_items`는 계약에 포함되지 않는다(`_` 접두어). F-EXT-002(윤지석)가 이 문서에서
뽑아야 할 정답과 원문 스팬이고, F-EXT-003 정답 세트의 출발점이다. 오프셋을 손으로 쓰면 반드시
틀리므로 `app.parsing.resolve_span`으로 계산해서 박고, `text[start:end] == value_text`를
전건 검증한다 — 깨지면 종료 코드가 0이 아니다.
"""
import json
import pathlib
import sys
import unicodedata

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "contracts" / "samples"
sys.path.insert(0, str(ROOT / "ai-service"))

from app import parsing  # noqa: E402

ELS_PDF = ROOT / "data" / "documents" / "els_kiwoom_4181_simple_prospectus.pdf"


def nfc(s):
    return unicodedata.normalize("NFC", s)


# --- ELS: 실문서 파싱 -------------------------------------------------------------
# (item_id, 이해항목 이름, 페이지, 문서에서 찾을 문면)
# 상품 고유 수치(배리어 45%/70%, 조기상환 85%)를 그대로 인용한다. 일반론 문장만 뽑으면
# 어느 ELS에나 들어맞아 F-EXT-003의 정답 세트로 쓸모가 없다.
ELS_ITEMS = [
    ("ELS-NO-DEPOSIT-INSURANCE", "예금자보호 비대상", 1,
     "이 파생결합증권은「예금자보호법」에 따라 보호되지 않는 금융투자상품으로"),
    ("ELS-PRINCIPAL-LOSS-WARNING", "원금손실 가능성 고지", 1,
     "투자원금의 손실이 발생할 수 있으므로 투자에 신중을 기하여"),
    ("ELS-HIGH-COMPLEXITY", "고난도금융투자상품", 2,
     "본 증권은 투자자가 이해하기 어려운 고난도금융투자상품으로"),
    ("ELS-ELDERLY-COOLING", "고령투자자 숙려제도 대상", 2,
     "숙려제도 대상 투자자(65세이상 고령투자자"),
    ("ELS-EARLY-REDEMPTION-CONDITION", "조기상환 조건(1차 85%)", 7,
     "1차 자동조기상환평가일에 기초자산인 S&P500 지수, NIKKEI225 지수, "
     "EuroStoxx50 지수의 자동조기상환평가가격이 모두 각각의 최초기준가격의 85%인"),
    ("ELS-KNOCKIN-BARRIER", "낙인 배리어 45%", 10,
     "낙인구간(각각 최초기준가격의 45%인 45/ 45/ 45) 미만으로 하락한 적이 있고"),
    ("ELS-MATURITY-LOSS-CONDITION", "만기 손실조건 70%", 10,
     "만기평가일에 수익률이 낮은 종목이 최초기준가격의 70% 미만인 경우 "
     "아래와 같이 손실이 발생할 수 있습니다"),
    ("ELS-TOTAL-LOSS-SCENARIO", "전액손실 사례", 10,
     "1억 + [1억 × (-100%)]= 0 원 상환(전액손실)"),
    ("ELS-LOSS-SIMULATION", "과거 데이터 수익률 모의실험", 11,
     "기초자산의 과거 데이터를 이용한 수익률 모의실험"),
    ("ELS-MIDWAY-REDEMPTION-COST", "중도상환 원금손실", 13,
     "실제 중도상환금액은 상기한 공정가액(기준가)의 95% 이상"),
    ("ELS-COOLING-PERIOD", "숙려기간 2영업일", 13,
     "판매과정 중 2 영업일 이상의 숙려기간을 갖습니다"),
    ("ELS-NO-LISTING", "환금성 제약(비상장)", 14,
     "본 증권은 상장하지 않을 예정이므로"),
    ("ELS-ISSUER-CREDIT-RISK", "발행사 신용위험", 14,
     "본 증권은 발행인의 신용으로 발행되는 무보증 증권이므로"),
]


def build_els():
    if not ELS_PDF.exists():
        sys.exit(
            f"FAIL: {ELS_PDF.relative_to(ROOT)} 가 없다 (data/documents/ 는 git 제외).\n"
            f"      python3 scripts/fetch_documents.py els-4181"
        )

    doc = parsing.parse_document(
        str(ELS_PDF),
        document_id="doc-els-kiwoom-4181",
        product_type="ELS",
        # 파서가 현재 시각을 찍으면 재파싱마다 샘플 diff가 생긴다. 문서 수집일로 고정한다.
        parsed_at="2026-08-24T00:00:00Z",
    )

    spans, failed = [], []
    for item_id, name, page, quote in ELS_ITEMS:
        got = parsing.resolve_span(doc, page, quote)
        if got is None:
            failed.append(f"{item_id}: p{page}에서 인용문을 찾지 못했다 — {quote[:40]}...")
            continue
        occ = parsing.find_occurrences(doc, page, quote)
        if len(occ) > 1:
            failed.append(f"{item_id}: p{page}에 {len(occ)}회 출현 — 스팬이 모호하다")
            continue
        spans.append({
            "item_id": item_id,
            "name": name,
            "value_text": got["value_text"],
            "source_span": got["source_span"],
            "match": got["match"],
        })
    if failed:
        sys.exit("FAIL:\n  " + "\n  ".join(failed))

    doc["_source"] = {
        "note": "금융투자협회 전자공시 > 파생결합증권등 청약정보 비교공시. 원본은 git 제외(data/documents/).",
        "fetch_key": "els-4181",
        "original_file": "간이투자설명서(ELS 4181).pdf",
        "sha256": "6b95fec7d5c8aee6e28a620bf569ee6c179e926bf5a365ddd00bab0209cd18eb",
    }
    doc["_expected_risk_items"] = spans
    return doc


# --- 변액보험: 수동 샘플 (실문서 확보 전) -------------------------------------------

def build_manual(doc, pages_text, quotes, tables, warnings):
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
        spans.append({
            "item_id": item_id, "name": name,
            "value_text": q,
            "source_span": {"page": pno, "start": start, "end": start + len(q)},
            "match": "exact",
        })

    doc = dict(doc)
    doc["page_count"] = len(pages)
    doc["pages"] = pages
    doc["tables"] = tables
    doc["parse_warnings"] = warnings
    doc["_expected_risk_items"] = spans
    return doc


var_p1 = """[변액보험 상품설명서]

1. 상품의 성격
이 계약은 실적배당형 상품으로 투자 결과에 따라 해지환급금이 원금보다 적을 수 있습니다.
납입한 보험료 중 일부는 사업비로 차감되며, 특별계정에 투입되는 금액은 납입 보험료보다 적습니다.
계약을 조기에 해지하는 경우 해지환급금이 납입 보험료를 크게 밑돌 수 있습니다."""


def build_variable():
    return build_manual(
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
          "message": "파서 미구현 구간이 아니라 문서 미확보 구간의 수동 샘플. 변액보험 상품설명서를 "
                     "생보협/생보사 공시실에서 확보하면 ELS와 같이 실제 파서 출력으로 대체한다."}]
    )


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, obj in [("parsed_els_sample.json", build_els()),
                      ("parsed_variable_sample.json", build_variable())]:
        (OUT / name).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n",
                               encoding="utf-8")
        print(f"wrote {name}: pages={obj['page_count']} "
              f"items={len(obj['_expected_risk_items'])} "
              f"warnings={len(obj['parse_warnings'])}")
        for s in obj["_expected_risk_items"]:
            sp = s["source_span"]
            page_text = next(p["text"] for p in obj["pages"] if p["page"] == sp["page"])
            ok = page_text[sp["start"]:sp["end"]] == s["value_text"]
            print(f"  {'OK ' if ok else 'BAD'} {s['item_id']:32s} p{sp['page']:<3d} "
                  f"[{sp['start']},{sp['end']}) {s['match']}")
            if not ok:
                sys.exit(f"FAIL: {s['item_id']} 스팬 항등식 위반")


if __name__ == "__main__":
    main()
