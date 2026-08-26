"""F-EXT-002 추출 — 후처리와 실패 노출 검증. 소유: 윤지석

핵심: **스팬은 모델에게 묻지 않는다.** 인용만 받고 위치는 parsing.resolve_span 이 원문에서
찾으므로 계약 항등식 `pages[page].text[start:end] == value_text` 가 구성상 성립한다.
API 키 없이 돈다.
"""
from __future__ import annotations

import json
from dataclasses import replace
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
def test_assigned_importance_needs_no_placeholder():
    """#100 이 23종에 importance 를 부여해 이슈 #26 이 닫혔다 — 자리표시자가 나올 일이 없다.

    회귀 고정이다. 누가 템플릿의 importance 를 다시 비우면 여기서 깨진다.
    `IMPORTANCE_FALLBACK` 이 "required" 라서 값만 보면 **부여된 것과 채워진 것을 구별할 수
    없다.** 그래서 경고 부재와 **템플릿이 준 값과의 일치**를 함께 본다.
    """
    result, _ = _extract()
    assert [w for w in result.warnings if w.code == "IMPORTANCE_PLACEHOLDER"] == []
    assigned = {i.item_id: i.importance for i in templates.get("ELS").items}
    assert assigned, "템플릿이 비었다"
    assert all(v in ("required", "recommended") for v in assigned.values()), assigned
    assert {i.item_id: i.importance for i in result.items} == assigned


def test_unassigned_importance_is_surfaced_once(monkeypatch):
    """미부여를 조용히 넘기지 않는다 — 원래 의도를 이 경로에 남겨 둔다.

    항목 **두 건**을 비운다. 한 건만 비우면 "항목마다 반복하지 않는다"를 확인할 수 없다.
    """
    tpl = templates.get("ELS")
    blank_ids = set(tpl.item_ids[:2])
    blanked = tuple(replace(i, importance=None) if i.item_id in blank_ids else i
                    for i in tpl.items)
    monkeypatch.setattr(templates, "get", lambda _product_type: replace(tpl, items=blanked))

    result, _ = _extract()
    placeholders = [w for w in result.warnings if w.code == "IMPORTANCE_PLACEHOLDER"]
    assert len(placeholders) == 1, "항목마다 반복하지 않는다"
    assert "#26" in placeholders[0].message
    assert all(i.importance == extraction.IMPORTANCE_FALLBACK
               for i in result.items if i.item_id in blank_ids)


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
