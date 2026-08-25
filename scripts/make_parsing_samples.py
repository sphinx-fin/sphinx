"""contracts/samples/ 파싱 출력 샘플 생성기. 소유: 정세현

    python3 scripts/make_parsing_samples.py

두 샘플 모두 **실문서를 실제 파서로 파싱한 출력**이다. `data/documents/`(git 추적)에 문서가
있어야 한다 — 없으면 `scripts/fetch_documents.py`가 안내한다. 수동으로 만든 부분이 없으므로
`parse_warnings`에 `MANUAL_OVERRIDE`가 붙지 않는다.

- **ELS** — 키움증권 제4181회 간이투자설명서 (금투협, 자동 수집)
- **변액보험** — 삼성생명 삼성 탄탄한 변액연금보험(B2601) 상품요약서 (생보협, 수동 취득)

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

DOCS = ROOT / "data" / "documents"
ELS_PDF = DOCS / "els_kiwoom_4181_simple_prospectus.pdf"
VAR_PDF = DOCS / "var_samsung_b2601_product_summary.pdf"


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


def resolve_items(doc, items):
    """정답 인용문을 스팬으로 바꾼다. 손으로 오프셋을 쓰면 반드시 틀리므로 계산해서 박는다.

    문면을 못 찾거나 같은 페이지에 여러 번 나오면(=어느 쪽인지 알 수 없으면) 실패로 끝낸다.
    모호한 스팬을 정답 세트에 넣으면 F-EXT-003의 재현율·정밀도가 문서가 아니라 우연에 좌우된다.
    """
    spans, failed = [], []
    for item_id, name, page, quote in items:
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
    return spans


def build_els():
    if not ELS_PDF.exists():
        sys.exit(
            f"FAIL: {ELS_PDF.relative_to(ROOT)} 가 없다 (레포에 있어야 한다 — git 추적 대상).\n"
            f"      python3 scripts/fetch_documents.py els-4181"
        )

    doc = parsing.parse_document(
        str(ELS_PDF),
        document_id="doc-els-kiwoom-4181",
        product_type="ELS",
        # 파서가 현재 시각을 찍으면 재파싱마다 샘플 diff가 생긴다. 문서 수집일로 고정한다.
        parsed_at="2026-08-24T00:00:00Z",
    )

    doc["_source"] = {
        "note": "금융투자협회 전자공시 > 파생결합증권등 청약정보 비교공시. 원본은 data/documents/ 에 있다.",
        "fetch_key": "els-4181",
        "original_file": "간이투자설명서(ELS 4181).pdf",
        "sha256": "6b95fec7d5c8aee6e28a620bf569ee6c179e926bf5a365ddd00bab0209cd18eb",
    }
    doc["_expected_risk_items"] = resolve_items(doc, ELS_ITEMS)
    return doc


# --- 변액보험: 실문서 파싱 ---------------------------------------------------------
# 방카슈랑스 채널 상품이라 "은행에서 파니까 원금은 보장"(M01) 오해와 직결된다.
# p12의 "보험은 은행의 저축과는 달리" 문면과 해약환급금 예시표가 그 반박 근거다.
VAR_ITEMS = [
    ("VAR-PERFORMANCE-LINKED", "실적배당형 성격", 2,
     "이 보험계약은 실적배당형 상품이므로 보험금 및 해약환급금이 특별계정의 운용실적에 "
     "따라 변동됩니다"),
    ("VAR-PRINCIPAL-LOSS", "원금손실 가능성", 2,
     "중도해지시 해약환급금에 대한 최저보증이 없으므로 원금손실이 발생할 수 있으며, "
     "그 손실은 모두 계약자에게 귀속됩니다"),
    ("VAR-NO-DEPOSIT-INSURANCE", "예금자보호 비대상", 2,
     "이 보험계약은 예금자보호법에 따라 보호되지 않습니다"),
    ("VAR-WITHDRAWAL-FEE", "중도인출 수수료", 3,
     "인출금액의 0.2%와 2,000원 중 작은 금액 이내에서"),
    ("VAR-CONTRACT-COST", "계약체결·관리비용 정의", 10,
     "계약체결비용 및 계약관리비용이란 보험회사가 보험계약의 체결, 유지 및 관리 등에 "
     "필요한 경비로 사용하기 위하여 보험료 중 일정비율을 책정한 것을 말합니다"),
    ("VAR-FEE-DEDUCTION", "월공제액 차감 구조", 12,
     "월공제액[위험보험료, 계약체결비용 및 계약관리비용, 최저사망지급금 보증비용, "
     "최저실적배당종신연금 보증비용 등]을 차감한 금액에서 미상각신계약비(해약공제액)을 "
     "차감하여 계산됩니다"),
    ("VAR-NOT-BANK-SAVINGS", "은행 저축과 다름", 12,
     "보험은 은행의 저축과는 달리 위험보장과 저축을 겸비한 제도로서"),
    ("VAR-SURRENDER-BELOW-PREMIUM", "해약환급금 < 납입보험료", 12,
     "중도해지시 지급되는 해약환급금은 납입한 보험료보다 적거나 없을 수도 있는 것입니다"),
    ("VAR-EARLY-SURRENDER-RATIO", "조기해지 환급률 58.4%", 12,
     "3개월 900,000 526,240 58.4"),
    ("VAR-LONG-TERM-RATIO", "20년 경과 환급률 43.9%", 12,
     "20년 36,000,000 15,827,930 43.9"),
]


def build_variable():
    if not VAR_PDF.exists():
        sys.exit(
            f"FAIL: {VAR_PDF.relative_to(ROOT)} 가 없다 (레포에 있어야 한다 — git 추적 대상).\n"
            f"      생보협 공시실에서 수동 취득해야 한다: "
            f"python3 scripts/fetch_documents.py var-b2601"
        )
    doc = parsing.parse_document(
        str(VAR_PDF),
        document_id="doc-var-samsung-b2601",
        product_type="VARIABLE_INSURANCE",
        parsed_at="2026-08-24T00:00:00Z",
    )
    doc["_source"] = {
        "note": "생명보험협회 공시실 > 상품비교공시 > 변액보험 > 저축성 상품비교 > 상품요약서. "
                "판매채널 방카슈랑스. 원본은 data/documents/ 에 있다.",
        "fetch_key": "var-b2601",
        "original_file": "상품요약서 — 삼성 탄탄한 변액연금보험(B2601)(무배당)[최저연금보증형]",
        "sha256": "2e993c829820cf270bd6304ddaa5e9f64bb92fdc7ac685c6d799f8ec24e463ab",
    }
    doc["_expected_risk_items"] = resolve_items(doc, VAR_ITEMS)
    return doc


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
