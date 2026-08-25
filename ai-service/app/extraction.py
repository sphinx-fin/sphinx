"""F-EXT-002 필수 이해항목 추출. 소유: 윤지석

기획서 5절 통제: *"상품유형 템플릿으로 추출 범위를 고정한다."*
`templates.get(product_type)` 에 없는 항목은 뽑지 않는다 — 그 목록이 F-EXT-003 재현율의
분모이기도 하다(이슈 #26).

출력은 `contracts/risk_item.schema.json`.

## 스팬은 모델에게 묻지 않는다

모델은 **인용과 페이지만** 낸다. `[start, end)` 는 `parsing.resolve_span()` 이 원문에서
찾는다. 모델이 준 숫자를 믿으면 계약 항등식
`pages[page].text[start:end] == condition.value_text` 가 깨질 수 있고, **깨진 채로도 추출은
성공한 것처럼 보인다** — 화면(S-01·S-05)에서 하이라이트가 밀릴 때 드러난다.
우리가 계산하면 항등식이 구성상 성립한다.

`value_text` 도 모델의 인용이 아니라 **원문에서 잘라낸 문자열**을 쓴다. PDF 는 문장 중간에
개행을 넣으므로 둘이 다를 수 있고, 계약이 요구하는 것은 원문 쪽이다(P6 — 수치는 원문 인용만).

## 실패를 은폐하지 않는다 (E-EXT-03)

템플릿에 있는데 문서에서 못 찾은 항목은 빼지 않고 `status="extraction_failed"` 로 낸다.
빠뜨리면 S-01 의 추출 실패 큐에 아무것도 안 뜨고, 재현율이 왜 낮은지도 알 수 없다.
스팬 해소 실패·거짓양성 가능(loose)·모호한 스팬도 `ExtractionWarning` 으로 노출한다.

## 청킹

계약 샘플 기준 문서 전체가 ELS 13k자 · 변액 20k자다. 한 번에 넣으면 모델이 문서 전체를
보므로 조각 좌표 환산 문제가 아예 없고 정확도도 낫다. 문서가 커지면 청킹이 필요해지므로
`MAX_DOCUMENT_CHARS` 를 넘으면 경고 대신 **거부**한다 — 조용히 잘라내면 뒷부분 항목이
전부 미검출로 잡히고 그 원인이 보이지 않는다.
"""
from __future__ import annotations

from pathlib import Path

from . import parsing, templates
from .llm_client import LlmClient, LlmError, client as default_client
from .schemas import (
    Condition,
    ExtractedCandidate,
    ExtractionDraft,
    ExtractionWarning,
    ExtractResponse,
    RiskItem,
    SourceSpan,
)

PROMPT_PATH = Path(__file__).resolve().parent / "prompts" / "F-EXT-002_v1.md"
PROMPT_VERSION = "F-EXT-002_v1"

#: 이 길이를 넘으면 청킹 설계가 필요하다. 조용히 잘라내지 않고 거부한다.
MAX_DOCUMENT_CHARS = 60_000

#: 템플릿에 importance 가 미부여일 때 쓰는 값(이슈 #26). RiskItem.importance 가 계약상
#: required 라 비워둘 수 없다. 기획서가 이 항목들을 "필수 이해항목"이라 부르므로 보수적으로
#: required 를 쓰고, 자리표시자임을 경고로 노출한다.
IMPORTANCE_FALLBACK = "required"


def extract(
    product_id: str,
    product_type: str,
    parsed_document: dict,
    llm: LlmClient | None = None,
) -> ExtractResponse:
    """파싱된 문서 → 필수 이해항목."""
    template = templates.get(product_type)
    doc = parsed_document

    total = sum(len(p["text"]) for p in doc["pages"])
    if total > MAX_DOCUMENT_CHARS:
        raise LlmError(
            f"문서가 {total:,}자로 한 번에 넣을 수 있는 한도({MAX_DOCUMENT_CHARS:,})를 넘는다. "
            "청킹 설계가 필요하다 — 조용히 잘라내면 뒷부분 항목이 전부 미검출로 잡힌다."
        )

    draft = (llm or default_client()).complete_json(
        prompt=build_prompt(template, doc),
        model_cls=ExtractionDraft,
        schema_name="ExtractionDraft",
        system=load_system_prompt(),
        # 공시 상품문서다. 기획서 7-3: "상품설명서(공시 자료이므로 개인정보가 아니다)".
        # 발행사 민원부서 번호 같은 법인 연락처가 인쇄돼 있어 넓은 휴리스틱이 정상 문서를
        # 막는다 — 실측으로 `02-785-7424` 가 ACCOUNT 에 걸려 추출이 멈췄다.
        pii_scope="public_document",
    )

    known = {i.item_id: i for i in template.items}
    warnings: list[ExtractionWarning] = []
    resolved: dict[str, RiskItem] = {}

    for candidate in draft.candidates:
        if candidate.item_id not in known:
            warnings.append(ExtractionWarning(
                code="UNKNOWN_ITEM_ID", item_id=candidate.item_id,
                message=f"템플릿에 없는 항목 — 추출 범위 밖이라 버린다",
            ))
            continue
        if candidate.item_id in resolved:
            continue                      # 규칙 4 — 항목당 하나
        item = _to_risk_item(candidate, known[candidate.item_id], product_id, doc, warnings)
        if item is not None:
            resolved[candidate.item_id] = item

    # E-EXT-03 — 못 찾은 항목을 빼지 않고 실패로 낸다
    for template_item in template.items:
        if template_item.item_id in resolved:
            continue
        warnings.append(ExtractionWarning(
            code="ITEM_NOT_FOUND", item_id=template_item.item_id,
            message=f"문서에서 찾지 못했다 ({template_item.name})",
        ))
        resolved[template_item.item_id] = _failed_item(template_item, product_id, warnings)

    ordered = [resolved[i.item_id] for i in template.items]
    return ExtractResponse(items=ordered, warnings=warnings)


# ── 후처리 ────────────────────────────────────────────────────────────────────
def _to_risk_item(
    candidate: ExtractedCandidate,
    template_item: templates.TemplateItem,
    product_id: str,
    doc: dict,
    warnings: list[ExtractionWarning],
) -> RiskItem | None:
    """인용을 원문 스팬으로 해소한다. 못 하면 None — 호출자가 실패 항목으로 만든다."""
    span = _resolve(candidate, doc, warnings)
    if span is None:
        warnings.append(ExtractionWarning(
            code="SPAN_UNRESOLVED", item_id=candidate.item_id,
            message=f"인용을 원문에서 찾지 못했다: {candidate.quote[:40]!r}",
        ))
        return None

    if span["match"] == "loose":
        warnings.append(ExtractionWarning(
            code="LOOSE_MATCH", item_id=candidate.item_id,
            message="낱자 사이 개행까지 허용해 찾았다 — 거짓 양성 가능, 사람 확인 필요",
        ))

    occurrences = parsing.find_occurrences(doc, span["source_span"]["page"], candidate.quote)
    if len(occurrences) > 1:
        warnings.append(ExtractionWarning(
            code="AMBIGUOUS_SPAN", item_id=candidate.item_id,
            message=f"같은 문면이 페이지에 {len(occurrences)}회 — 어느 것인지 확정 불가",
        ))

    return RiskItem(
        item_id=candidate.item_id,
        product_id=product_id,
        name=template_item.name,
        importance=_importance(template_item, warnings),
        status="extracted",
        condition=Condition(
            value_text=span["value_text"],          # 모델 인용이 아니라 원문
            source_span=SourceSpan(**span["source_span"]),
        ),
    )


def _resolve(candidate: ExtractedCandidate, doc: dict, warnings: list[ExtractionWarning]):
    """모델이 지목한 페이지를 먼저 보고, 없으면 나머지 페이지를 훑는다.

    페이지를 틀리는 것은 인용을 틀리는 것과 다르다 — 문면이 원문에 있으면 항목은 실재하고
    위치만 잘못 짚은 것이다. 그 경우를 실패로 처리하면 실재하는 항목이 미검출이 된다.
    다만 조용히 넘기지 않고 PAGE_CORRECTED 로 남긴다.
    """
    pages = [p["page"] for p in doc["pages"]]
    if candidate.page in pages:
        span = parsing.resolve_span(doc, candidate.page, candidate.quote)
        if span is not None:
            return span

    for page in pages:
        if page == candidate.page:
            continue
        span = parsing.resolve_span(doc, page, candidate.quote)
        if span is not None:
            warnings.append(ExtractionWarning(
                code="PAGE_CORRECTED", item_id=candidate.item_id,
                message=f"모델이 p{candidate.page} 라고 했으나 p{page} 에서 찾았다",
            ))
            return span
    return None


def _failed_item(
    template_item: templates.TemplateItem, product_id: str,
    warnings: list[ExtractionWarning],
) -> RiskItem:
    """E-EXT-03 — 실패를 은폐하지 않는다. 조건이 비어도 스키마는 값을 요구하므로
    빈 스팬과 사유 문면을 넣고 status 로 구분한다."""
    return RiskItem(
        item_id=template_item.item_id,
        product_id=product_id,
        name=template_item.name,
        importance=_importance(template_item, warnings),
        status="extraction_failed",
        condition=Condition(
            value_text="(추출 실패 — 문서에서 해당 조건을 찾지 못했다)",
            source_span=SourceSpan(page=1, start=0, end=0),
        ),
    )


def _importance(template_item: templates.TemplateItem, warnings: list[ExtractionWarning]) -> str:
    if template_item.importance_assigned:
        return template_item.importance          # type: ignore[return-value]
    if not any(w.code == "IMPORTANCE_PLACEHOLDER" for w in warnings):
        warnings.append(ExtractionWarning(
            code="IMPORTANCE_PLACEHOLDER",
            message=f"템플릿 importance 미부여 — {IMPORTANCE_FALLBACK} 로 채웠다 (이슈 #26)",
        ))
    return IMPORTANCE_FALLBACK


# ── 프롬프트 ──────────────────────────────────────────────────────────────────
def _prompt_sections() -> tuple[str, str]:
    text = PROMPT_PATH.read_text(encoding="utf-8")
    system = text.split("## system", 1)[1].split("## user", 1)[0].strip()
    user = text.split("## user", 1)[1].strip()
    return system, user


def load_system_prompt() -> str:
    return _prompt_sections()[0]


def build_prompt(template: templates.ProductTemplate, doc: dict) -> str:
    _, user = _prompt_sections()
    template_lines = "\n".join(
        f"- {i.item_id} ({i.name}): {i.cue}"
        + (f" [{i.section_hint}]" if i.section_hint else "")
        for i in template.items
    )
    document_lines = "\n\n".join(
        f"=== p{p['page']} ===\n{p['text']}" for p in doc["pages"]
    )
    return user.format(template_lines=template_lines, document_lines=document_lines)
