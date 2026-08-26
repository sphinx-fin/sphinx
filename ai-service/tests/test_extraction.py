"""F-EXT-002 추출 — 후처리와 실패 노출 검증. 소유: 윤지석

핵심: **스팬은 모델에게 묻지 않는다.** 인용만 받고 위치는 parsing.resolve_span 이 원문에서
찾으므로 계약 항등식 `pages[page].text[start:end] == value_text` 가 구성상 성립한다.
API 키 없이 돈다.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from app import extraction, parsing, templates
from app.llm_client import LlmClient, LlmError
from app.schemas import ExtractedCandidate, ExtractionDraft

SAMPLES = Path(__file__).resolve().parents[2] / "contracts" / "samples"


def _doc(name: str = "parsed_els_sample.json") -> dict:
    return json.loads((SAMPLES / name).read_text(encoding="utf-8"))


class FakeLlm(LlmClient):
    def __init__(self, *candidates: ExtractedCandidate) -> None:
        self.draft = ExtractionDraft(candidates=list(candidates))
        self.calls: list[dict[str, Any]] = []

    def complete_json(self, **kwargs: Any) -> ExtractionDraft:  # type: ignore[override]
        self.calls.append(kwargs)
        return self.draft


def _extract(*candidates: ExtractedCandidate, doc=None, product_type="ELS"):
    document = doc if doc is not None else _doc()
    llm = FakeLlm(*candidates)
    return extraction.extract("p-1", product_type, document, llm=llm), llm


def _expected(doc: dict, item_id: str) -> dict:
    return next(i for i in doc["_expected_risk_items"] if i["item_id"] == item_id)


# ── 계약 항등식은 구성상 성립한다 ─────────────────────────────────────────────
def test_span_identity_holds_for_every_extracted_item():
    doc = _doc()
    exp = _expected(doc, "ELS-ISSUER-CREDIT-RISK")
    result, _ = _extract(ExtractedCandidate(
        item_id="ELS-ISSUER-CREDIT-RISK", page=exp["source_span"]["page"],
        quote=exp["value_text"]))
    item = next(i for i in result.items if i.item_id == "ELS-ISSUER-CREDIT-RISK")
    assert item.status == "extracted"
    span = item.condition.source_span
    assert parsing.verify_span(
        doc, {"page": span.page, "start": span.start, "end": span.end},
        item.condition.value_text,
    )


def test_value_text_comes_from_document_not_from_the_model():
    """PDF 는 문장 중간에 개행을 넣는다. 모델 인용과 원문이 다르면 원문을 쓴다 (P6).

    이걸 어기면 항등식이 깨진 채로도 추출은 성공한 것처럼 보이고, 화면에서 하이라이트가
    밀릴 때 드러난다.
    """
    doc = _doc()
    exp = _expected(doc, "ELS-NO-DEPOSIT-INSURANCE")
    # 모델이 개행을 공백으로 바꿔 인용한 경우
    flattened = " ".join(exp["value_text"].split())
    result, _ = _extract(ExtractedCandidate(
        item_id="ELS-NO-DEPOSIT-INSURANCE", page=exp["source_span"]["page"],
        quote=flattened))
    item = next(i for i in result.items if i.item_id == "ELS-NO-DEPOSIT-INSURANCE")
    assert item.status == "extracted"
    assert item.condition.value_text == exp["value_text"]      # 원문 쪽
    assert item.condition.value_text != flattened


# ── 실패를 은폐하지 않는다 (E-EXT-03) ─────────────────────────────────────────
def test_missing_items_are_reported_as_extraction_failed():
    """빼면 S-01 추출 실패 큐에 아무것도 안 뜨고 재현율이 왜 낮은지도 알 수 없다."""
    result, _ = _extract()          # 모델이 아무것도 못 찾은 경우
    template = templates.get("ELS")
    assert len(result.items) == len(template.items)
    assert all(i.status == "extraction_failed" for i in result.items)
    codes = [w.code for w in result.warnings]
    assert codes.count("ITEM_NOT_FOUND") == len(template.items)


def test_unresolvable_quote_becomes_failed_item_with_warning():
    result, _ = _extract(ExtractedCandidate(
        item_id="ELS-NO-DEPOSIT-INSURANCE", page=1, quote="문서에 없는 문장입니다"))
    item = next(i for i in result.items if i.item_id == "ELS-NO-DEPOSIT-INSURANCE")
    assert item.status == "extraction_failed"
    assert any(w.code == "SPAN_UNRESOLVED" for w in result.warnings)


def test_item_order_follows_the_template():
    """S-01 이 항목을 나열하는 순서가 실행마다 달라지면 안 된다."""
    doc = _doc()
    exp = _expected(doc, "ELS-COOLING-PERIOD")
    result, _ = _extract(ExtractedCandidate(
        item_id="ELS-COOLING-PERIOD", page=exp["source_span"]["page"],
        quote=exp["value_text"]))
    assert [i.item_id for i in result.items] == list(templates.get("ELS").item_ids)


# ── 모델 출력을 그대로 믿지 않는다 ────────────────────────────────────────────
def test_unknown_item_id_is_discarded():
    """템플릿이 추출 범위다(기획서 5절). 목록 밖 항목은 버린다."""
    result, _ = _extract(ExtractedCandidate(item_id="ELS-MADE-UP", page=1, quote="아무거나"))
    assert "ELS-MADE-UP" not in {i.item_id for i in result.items}
    assert any(w.code == "UNKNOWN_ITEM_ID" for w in result.warnings)


def test_wrong_page_is_corrected_not_failed():
    """페이지를 틀리는 것은 인용을 틀리는 것과 다르다 — 문면이 원문에 있으면 항목은 실재한다."""
    doc = _doc()
    exp = _expected(doc, "ELS-NO-LISTING")
    wrong = exp["source_span"]["page"] + 1
    result, _ = _extract(ExtractedCandidate(
        item_id="ELS-NO-LISTING", page=wrong, quote=exp["value_text"]))
    item = next(i for i in result.items if i.item_id == "ELS-NO-LISTING")
    assert item.status == "extracted"
    assert item.condition.source_span.page == exp["source_span"]["page"]
    assert any(w.code == "PAGE_CORRECTED" for w in result.warnings)


def test_out_of_range_page_does_not_crash():
    """모델이 원본 PDF 의 인쇄된 페이지 번호를 답하는 일이 실제로 있었다(16쪽 문서에 p31)."""
    doc = _doc()
    exp = _expected(doc, "ELS-HIGH-COMPLEXITY")
    result, _ = _extract(ExtractedCandidate(
        item_id="ELS-HIGH-COMPLEXITY", page=999, quote=exp["value_text"]))
    item = next(i for i in result.items if i.item_id == "ELS-HIGH-COMPLEXITY")
    assert item.status == "extracted"


def test_duplicate_candidate_keeps_the_first():
    doc = _doc()
    exp = _expected(doc, "ELS-LOSS-SIMULATION")
    result, _ = _extract(
        ExtractedCandidate(item_id="ELS-LOSS-SIMULATION",
                           page=exp["source_span"]["page"], quote=exp["value_text"]),
        ExtractedCandidate(item_id="ELS-LOSS-SIMULATION", page=1, quote="다른 인용"),
    )
    assert sum(1 for i in result.items if i.item_id == "ELS-LOSS-SIMULATION") == 1


# ── 진단 노출 ─────────────────────────────────────────────────────────────────
def test_importance_placeholder_is_surfaced_once():
    """RiskItem.importance 가 계약상 required 라 비워둘 수 없다. 자리표시자를 숨기지 않는다."""
    result, _ = _extract()
    placeholders = [w for w in result.warnings if w.code == "IMPORTANCE_PLACEHOLDER"]
    assert len(placeholders) == 1, "항목마다 반복하지 않는다"
    assert all(i.importance == extraction.IMPORTANCE_FALLBACK for i in result.items)


def test_oversized_document_is_rejected_not_truncated():
    """조용히 잘라내면 뒷부분 항목이 전부 미검출로 잡히고 원인이 보이지 않는다.

    LlmError 가 아니라 전용 예외다 — 502 로 나가면 Spring 쪽에서 "ai-service 장애"로
    오진된다(PR #60 리뷰). 문서가 큰 것은 입력 문제다.
    """
    doc = _doc()
    doc["pages"] = [{"page": 1, "text": "가" * (extraction.MAX_DOCUMENT_CHARS + 1)}]
    with pytest.raises(extraction.DocumentTooLarge, match="청킹"):
        _extract(doc=doc)
    assert not issubclass(extraction.DocumentTooLarge, LlmError)


# ── 공시 문서 PII 범위 (실측 결함에서 나왔다) ─────────────────────────────────
def test_extraction_sends_document_with_public_scope():
    """발행사 민원부서 번호(02-785-7424)가 ACCOUNT 패턴에 걸려 추출이 멈췄다.
    기획서 7-3: "상품설명서(공시 자료이므로 개인정보가 아니다)"."""
    doc = _doc()
    exp = _expected(doc, "ELS-COOLING-PERIOD")
    _, llm = _extract(ExtractedCandidate(
        item_id="ELS-COOLING-PERIOD", page=exp["source_span"]["page"],
        quote=exp["value_text"]))
    assert llm.calls[0]["pii_scope"] == "public_document"


def test_variable_template_also_extracts():
    doc = _doc("parsed_variable_sample.json")
    exp = next(i for i in doc["_expected_risk_items"] if i["item_id"] == "VAR-FEE-DEDUCTION")
    result, _ = _extract(
        ExtractedCandidate(item_id="VAR-FEE-DEDUCTION", page=exp["source_span"]["page"],
                           quote=exp["value_text"]),
        doc=doc, product_type="VARIABLE_INSURANCE")
    item = next(i for i in result.items if i.item_id == "VAR-FEE-DEDUCTION")
    assert item.status == "extracted"


def test_unknown_product_type_raises():
    with pytest.raises(templates.TemplateNotFound):
        _extract(product_type="BOND")


# ── 인용 좁히기 구제 (실측 실패 모양) ─────────────────────────────────────────
#: 실측된 실패는 `ELS-EARLY-REDEMPTION-CONDITION` 이었다. 모델이 조건 문장 뒤에 표 열
#: 조각을 붙여 긴 인용을 냈고, 그 문자열은 원문에 존재하지 않아 항목이 통째로 실패했다.
#: 간헐적이라(LLM 비결정) 재현 대신 그 **모양**을 픽스처로 고정한다.
_TRUE_CONDITION = (
    "1차 자동조기상환평가일에 기초자산인 S&P500 지수, NIKKEI225 지수, "
    "EuroStoxx50 지수의 자동조기상환평가가격이 모두 각각의 최초기준가격의 85%인"
)
_TABLE_BLEED = " 조기상환지급일에 자동조기상환되며 상환금액은 원금 × [100%+ 5.50%]"


def _early_redemption_expectation() -> dict:
    return next(e for e in _doc()["_expected_risk_items"]
                if e["item_id"] == "ELS-EARLY-REDEMPTION-CONDITION")


def test_rescue_recovers_the_contract_span_from_a_bleeding_quote():
    """★ 구제가 계약 샘플의 정답 스팬을 그대로 되찾아야 한다.

    "대충 짧게 잘라 아무 데나 붙었다" 와 "조건 문장을 정확히 찾았다" 를 가르는 유일한
    검사다 — 좁히기가 엉뚱한 구간에 붙으면 스팬이 달라진다.
    """
    doc = _doc()
    candidate = ExtractedCandidate(
        item_id="ELS-EARLY-REDEMPTION-CONDITION", page=7,
        quote=_TRUE_CONDITION + _TABLE_BLEED,
    )
    assert extraction._resolve(candidate, doc, []) is None, "이 인용은 원문에 없어야 한다"

    warnings: list[Any] = []
    span = extraction._rescue(candidate, doc, _tpl(candidate.item_id), warnings)
    expected = _early_redemption_expectation()
    assert span is not None
    assert span["source_span"] == expected["source_span"]
    assert span["value_text"] == expected["value_text"]
    assert [w.code for w in warnings] == ["QUOTE_NARROWED"]


def test_rescue_refuses_to_drop_every_number():
    """수치가 하나도 안 남는 좁히기는 실패로 남긴다 (P6).

    조건에 숫자가 없는 RiskItem 은 실패보다 나쁘다 — 추출은 성공으로 보이는데 채점
    프롬프트의 `[상품 조건 원문]` 에 수치가 없어 "수치로 언급" 루브릭이 검증 불가가 된다.
    """
    doc = _doc()
    candidate = ExtractedCandidate(
        item_id="ELS-NO-LISTING", page=14,
        quote="본 증권은 상장하지 않을 예정이므로 옆열 999 조각",
    )
    warnings: list[Any] = []
    assert extraction._rescue(candidate, doc, _tpl(candidate.item_id), warnings) is None
    assert [w.code for w in warnings] == ["NARROWING_REFUSED"]
    assert "999" in warnings[0].message


def test_rescue_reports_which_numbers_were_dropped():
    """표 열 누출은 가짜 수치를 데려온다. 무엇이 떨어졌는지 사람이 볼 수 있어야 한다."""
    doc = _doc()
    candidate = ExtractedCandidate(
        item_id="ELS-EARLY-REDEMPTION-CONDITION", page=7,
        quote=_TRUE_CONDITION + _TABLE_BLEED,
    )
    warnings: list[Any] = []
    extraction._rescue(candidate, doc, _tpl(candidate.item_id), warnings)
    message = warnings[0].message
    assert "사람 확인 필요" in message
    assert "5.50" in message and "100" in message   # 누출 열의 수치
    assert "85" in message                          # 조건의 수치는 남았다


def test_narrowing_keeps_word_boundaries_not_just_sentences():
    """표 열 누출은 문장부호를 데려오지 않는다 — 문장 경계로만 자르면 못 구제한다."""
    quote = _TRUE_CONDITION + _TABLE_BLEED
    assert "." not in quote.replace("5.50", "").replace("S&P500", ""), "이 픽스처엔 마침표가 없다"
    assert extraction._narrowed_candidates(quote), "어절 단위 후보가 나와야 한다"


def test_narrowing_tries_longest_windows_first():
    """짧은 조각이 먼저 걸리면 조건 문면이 잘려나간다."""
    candidates = extraction._narrowed_candidates("가 나 다 라 마 바 사 아 자 차 카 타")
    lengths = [len(c) for c in candidates]
    assert lengths == sorted(lengths, reverse=True)


def test_narrowing_is_bounded():
    """`loose` 는 낱자마다 `\\s*` 를 끼운 정규식이다. 상한 없이 돌리면 실패 항목마다 느려진다."""
    long_quote = " ".join(f"토큰{i}" for i in range(60))
    assert len(extraction._narrowed_candidates(long_quote)) <= extraction.MAX_RESCUE_ATTEMPTS


def _tpl(item_id: str):
    product = "ELS" if item_id.startswith("ELS-") else "VARIABLE_INSURANCE"
    return next(i for i in templates.get(product).items if i.item_id == item_id)


# ── 역방향 픽스처 (#118 리뷰, 정세현) ────────────────────────────────────────
#: **정세현 지적이 재현된 케이스다.** 이전 규칙("수치가 하나라도 남으면 통과 + 첫 성공 즉시
#: 반환")은 *"누출 열이 진짜 조건문보다 짧다"* 는 암묵 가정에 의존했다. 여기서 깨진다 —
#: 같은 페이지의 각주(45자)가 진짜 조건(23자)보다 22자 길다.
#:
#: 재현 당시 결과: 각주 스팬(p2 665~711)을 조건으로 잡고 정답(501~524)을 놓쳤다.
#: `65` 가 빠지고 `100`·`20` 이 남았는데 통과했다.
_ELDERLY_TRUE = "숙려제도 대상 투자자(65세이상 고령투자자"
_ELDERLY_BLEED = "고난도금융투자상품: 최대원금손실 가능금액이 원금의 100분의 20을 초과하는 상품"


def test_rescue_prefers_the_cue_matching_window_not_the_longest():
    """★ 누출 조각이 진짜 조건보다 **길** 때도 정답을 골라야 한다.

    누출 조각도 원문에 실재한다 — 원 인용이 해소에 실패한 것은 두 열이 이어붙은 문자열이
    원문에 없어서지 각 조각이 없어서가 아니다. 그래서 "원문에서 찾았다" 만으로는 진짜
    조건과 구별되지 않고, 무엇이 그 항목의 조건인지는 `cue` 만 말해준다.
    """
    doc = _doc()
    expected = next(e for e in doc["_expected_risk_items"]
                    if e["item_id"] == "ELS-ELDERLY-COOLING")
    candidate = ExtractedCandidate(
        item_id="ELS-ELDERLY-COOLING", page=2,
        quote=f"{_ELDERLY_TRUE} {_ELDERLY_BLEED}",
    )
    assert len(_ELDERLY_BLEED) > len(_ELDERLY_TRUE), "역방향 픽스처가 아니다"
    assert extraction._resolve(candidate, doc, []) is None, "이 인용은 원문에 없어야 한다"

    warnings: list[Any] = []
    span = extraction._rescue(candidate, doc, _tpl("ELS-ELDERLY-COOLING"), warnings)
    assert span is not None
    assert span["source_span"] == expected["source_span"], "각주를 조건으로 잡았다"
    assert span["value_text"] == expected["value_text"]


def test_bleed_fragment_alone_would_have_resolved():
    """픽스처가 실제로 함정인지 — 누출 조각만으로도 원문에서 해소된다.

    이게 아니면 위 테스트는 "안 풀리는 것을 안 골랐다" 는 시시한 사실만 확인한다.
    """
    doc = _doc()
    bleed_only = ExtractedCandidate(
        item_id="ELS-ELDERLY-COOLING", page=2, quote=_ELDERLY_BLEED,
    )
    span = extraction._resolve(bleed_only, doc, [])
    assert span is not None, "누출 조각이 원문에서 안 풀리면 함정이 성립하지 않는다"
    expected = next(e for e in doc["_expected_risk_items"]
                    if e["item_id"] == "ELS-ELDERLY-COOLING")
    assert span["source_span"] != expected["source_span"], "다른 구간을 가리켜야 한다"


def test_off_cue_window_is_refused():
    """cue 와 무관한 창만 남으면 좁히지 않고 실패로 둔다."""
    doc = _doc()
    candidate = ExtractedCandidate(
        item_id="ELS-ELDERLY-COOLING", page=2,
        quote=f"{_ELDERLY_BLEED} 그리고 발행인의 재무현황 및 신용등급 파악",
    )
    warnings: list[Any] = []
    assert extraction._rescue(candidate, doc, _tpl("ELS-ELDERLY-COOLING"), warnings) is None
    assert [w.code for w in warnings] == ["NARROWING_REFUSED"]
    assert "cue" in warnings[0].message


def test_cue_threshold_separates_the_two_groups():
    """임계값이 실측 두 군 사이에 있어야 한다.

    `cue` 에는 **상품별 수치**(백분율·큰 수)가 없다(`test_cue_is_product_agnostic`). 그래서
    회차마다 달라지는 값에 휘둘리지 않는다 — 제도 상수(`65세` 등)는 남아 있고 무해하다.
    """
    import re

    from app import textsim

    product_specific = re.compile(r"\d+\s*%|\d{3,}")
    pairs = [
        ("ELS-ELDERLY-COOLING", _ELDERLY_TRUE, _ELDERLY_BLEED),
        ("ELS-EARLY-REDEMPTION-CONDITION", _TRUE_CONDITION, _TABLE_BLEED.strip()),
    ]
    for item_id, true_text, bleed in pairs:
        cue = _tpl(item_id).cue
        assert not product_specific.search(cue), f"{item_id}: cue 에 상품별 수치 — {cue}"
        hit = textsim.containment(cue, true_text)
        miss = textsim.containment(cue, bleed)
        assert miss < extraction.CUE_CONTAINMENT_MIN <= hit, (
            f"{item_id}: 진짜 {hit:.3f} · 누출 {miss:.3f} · 임계 {extraction.CUE_CONTAINMENT_MIN}"
        )
