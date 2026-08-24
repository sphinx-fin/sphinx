"""F-EXT-001 상품문서 파싱. 소유: 정세현

PDF → `contracts/parsed_document.schema.json` (ParsedDocument).
데모 대상 2종(ELS 간이투자설명서, 변액보험 상품설명서)만 대응한다 — **범용 파서 아님**(타임박스,
역할분담표 v1.2 F-EXT-001). 형식 편차로 막히면 수동 JSON으로 우회하고
`parse_warnings`에 `MANUAL_OVERRIDE`를 남긴다.

계약에서 지켜야 하는 것 (`contracts/README.md` source_span 규약):

- `pages[].text`는 유니코드 NFC. 공백·개행을 임의로 접거나 제거하지 않는다 — 접는 순간
  오프셋이 무의미해지고, 추출은 성공하는데 S-01·S-05 하이라이트가 밀린다.
- `source_span`은 **페이지 상대** 오프셋의 반열린 구간 `[start, end)`. 문서 전역 오프셋이 아니다.
  `pages[page].text[start:end] == value_text`가 항등식으로 성립해야 한다.
- 표 안의 텍스트도 읽기 순서대로 `pages[].text`에 들어간다. 모든 스팬은 `tables[]`를 보지 않고
  해소돼야 한다. `tables[]`는 행열 구조가 의미를 갖는 경우의 부가 뷰일 뿐이다.
- 같은 문서를 다시 파싱하면 같은 출력이어야 한다(P2). 출력이 달라지면 `PARSER_VERSION`을 올린다.
- 파싱 실패·부분 실패는 은폐하지 않고 `parse_warnings`로 노출한다. 빈 배열이면 완전 성공.
"""
from __future__ import annotations

import os
import re
import unicodedata

import pdfplumber

#: 출력이 달라지면 올린다. 파싱 파라미터(_X_TOLERANCE 등) 변경도 출력 변경이다.
PARSER_VERSION = "0.1.0"

#: 데모 범위. contracts/parsed_document.schema.json 의 product_type enum과 1:1.
PRODUCT_TYPES = ("ELS", "VARIABLE_INSURANCE")

# pdfplumber 기본값에 의존하지 않고 명시한다 — 라이브러리 기본값이 바뀌면 같은 문서가 다른
# 출력을 내고, 그건 재현성(P2) 위반이면서 PARSER_VERSION 없이 조용히 일어난다.
_X_TOLERANCE = 3
_Y_TOLERANCE = 3

#: 폰트 CMap이 깨져 pdfminer가 글리프를 못 읽으면 "(cid:123)" 형태로 남는다.
_CID_LEFTOVER = re.compile(r"\(cid:\d+\)")

#: 캡션 후보를 찾을 표 위쪽 범위(pt).
_CAPTION_LOOKUP_HEIGHT = 28


def _nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


def parse_document(
    pdf_path: str,
    *,
    document_id: str,
    product_type: str,
    parsed_at: str | None = None,
) -> dict:
    """PDF 한 건을 ParsedDocument dict로 만든다.

    `parsed_at`은 호출자가 주입한다. 여기서 현재 시각을 찍으면 같은 문서의 두 파싱 결과가
    달라져 재현성 비교(P2)에 쓸 수 없다. 주지 않으면 키 자체를 넣지 않는다(계약상 optional).
    """
    if product_type not in PRODUCT_TYPES:
        raise ValueError(
            f"product_type은 {PRODUCT_TYPES} 중 하나여야 한다 (데모 범위): {product_type!r}"
        )

    pages: list[dict] = []
    tables: list[dict] = []
    warnings: list[dict] = []

    with pdfplumber.open(pdf_path) as pdf:
        for index, page in enumerate(pdf.pages):
            page_no = index + 1  # 계약: 1-base
            text = _extract_page_text(page, page_no, warnings)
            pages.append({"page": page_no, "text": text, "char_count": len(text)})
            tables.extend(_extract_page_tables(page, page_no, warnings))

    if not pages:
        raise ValueError(f"페이지가 없는 PDF: {pdf_path}")

    doc = {
        "document_id": document_id,
        "product_type": product_type,
        "source_file": os.path.basename(pdf_path),
        "parser_version": PARSER_VERSION,
    }
    if parsed_at is not None:
        doc["parsed_at"] = parsed_at
    doc["page_count"] = len(pages)
    doc["pages"] = pages
    doc["tables"] = tables
    doc["parse_warnings"] = warnings
    return doc


def _extract_page_text(page, page_no: int, warnings: list[dict]) -> str:
    try:
        raw = page.extract_text(x_tolerance=_X_TOLERANCE, y_tolerance=_Y_TOLERANCE)
    except Exception as exc:  # pdfminer는 깨진 폰트에서 다양한 예외를 던진다
        warnings.append({
            "page": page_no,
            "code": "TEXT_LAYER_MISSING",
            "message": f"텍스트 추출 실패: {type(exc).__name__}: {exc}",
        })
        return ""

    text = _nfc(raw or "")

    if not text.strip():
        # 스캔 이미지 페이지. OCR은 데모 범위 밖 — 은폐하지 않고 노출만 한다.
        warnings.append({
            "page": page_no,
            "code": "TEXT_LAYER_MISSING",
            "message": "텍스트 레이어가 없다(스캔 이미지 추정). 이 페이지의 스팬은 해소되지 않는다.",
        })
        return text

    suspects = []
    if _CID_LEFTOVER.search(text):
        suspects.append("(cid:N) 잔여 — 폰트 CMap 미해석")
    if "�" in text:
        suspects.append("U+FFFD 치환문자")
    if suspects:
        warnings.append({
            "page": page_no,
            "code": "ENCODING_SUSPECT",
            "message": "; ".join(suspects) + ". 이 페이지 기반 스팬은 사람이 확인해야 한다.",
        })

    return text


def _extract_page_tables(page, page_no: int, warnings: list[dict]) -> list[dict]:
    try:
        found = page.find_tables()
    except Exception as exc:
        warnings.append({
            "page": page_no,
            "code": "TABLE_STRUCTURE_LOST",
            "message": f"표 탐지 실패: {type(exc).__name__}: {exc}",
        })
        return []

    out = []
    for table in found:
        try:
            raw_rows = table.extract()
        except Exception as exc:
            warnings.append({
                "page": page_no,
                "code": "TABLE_STRUCTURE_LOST",
                "message": f"표 추출 실패: {type(exc).__name__}: {exc}",
            })
            continue

        rows = [[_nfc(cell) if cell else "" for cell in row] for row in raw_rows]
        rows = [row for row in rows if any(cell.strip() for cell in row)]
        if not rows:
            continue

        widths = {len(row) for row in rows}
        if len(widths) > 1:
            # 셀 병합·괘선 누락으로 행 길이가 어긋난 경우. 값은 그대로 내보내되 표시한다 —
            # 파서가 조용히 채워 넣으면 어느 열이 밀렸는지 아무도 모른다.
            warnings.append({
                "page": page_no,
                "code": "TABLE_STRUCTURE_LOST",
                "message": f"행 길이 불일치 {sorted(widths)} — 셀 병합 또는 괘선 누락 추정",
            })

        out.append({
            "page": page_no,
            "caption": _guess_caption(page, table),
            "rows": rows,
        })
    return out


def _guess_caption(page, table) -> str | None:
    """표 바로 위 한 줄을 캡션 후보로 본다. 확신이 없으면 None (계약상 nullable)."""
    try:
        x0, top, x1, _bottom = table.bbox
        band = (
            max(0, x0 - 4),
            max(0, top - _CAPTION_LOOKUP_HEIGHT),
            min(page.width, x1 + 4),
            max(0, top - 1),
        )
        if band[1] >= band[3]:
            return None
        text = page.crop(band).extract_text(
            x_tolerance=_X_TOLERANCE, y_tolerance=_Y_TOLERANCE
        )
    except Exception:
        return None

    lines = [ln.strip() for ln in _nfc(text or "").splitlines() if ln.strip()]
    return lines[-1] if lines else None


# --- 스팬 해소 -------------------------------------------------------------------
# F-EXT-002(윤지석)의 원문 스팬 검증과 F-EXT-003 정답 세트가 같은 규약을 쓰도록 여기 둔다.
# 두 곳에서 따로 오프셋을 계산하면 미묘하게 다른 두 규약이 생긴다.

def page_text(doc: dict, page: int) -> str:
    for p in doc["pages"]:
        if p["page"] == page:
            return p["text"]
    raise KeyError(f"page {page} not in document {doc['document_id']!r}")


def resolve_span(doc: dict, page: int, quote: str) -> dict | None:
    """`quote`가 해당 페이지에서 시작·끝나는 위치를 페이지 상대 `[start, end)`로 준다.

    반환 dict의 `value_text`는 **문서에서 실제로 잘라낸 문자열**이다. PDF는 문장 중간에
    개행을 넣기 때문에 질의한 `quote`와 다를 수 있고, 계약 항등식
    `text[start:end] == value_text`를 지키려면 호출자는 질의문이 아니라 이 값을 써야 한다.

    `match`:
      - `exact`      — 그대로 발견
      - `whitespace` — 공백 구간만 개행 등으로 치환된 형태로 발견
      - `loose`      — 문자 사이 어디에나 공백이 끼어든 형태로 발견 (줄바꿈으로 낱자가 갈린 경우).
                       거짓 양성이 가능하므로 사람이 확인해야 한다.
    없으면 None.
    """
    text = page_text(doc, page)
    q = _nfc(quote)
    if not q:
        return None

    start = text.find(q)
    if start >= 0:
        return _span(page, text, start, start + len(q), "exact")

    for strategy, pattern in (
        ("whitespace", r"\s+".join(re.escape(tok) for tok in q.split())),
        ("loose", r"\s*".join(re.escape(ch) for ch in q if not ch.isspace())),
    ):
        m = re.search(pattern, text)
        if m:
            return _span(page, text, m.start(), m.end(), strategy)
    return None


def _span(page: int, text: str, start: int, end: int, match: str) -> dict:
    return {
        "source_span": {"page": page, "start": start, "end": end},
        "value_text": text[start:end],
        "match": match,
    }


def find_occurrences(doc: dict, page: int, quote: str) -> list[dict]:
    """같은 문면이 여러 번 나오면 스팬이 모호하다. 정답 세트를 만들 때 개수를 먼저 확인한다."""
    text = page_text(doc, page)
    q = _nfc(quote)
    if not q:
        return []
    out, at = [], text.find(q)
    while at >= 0:
        out.append(_span(page, text, at, at + len(q), "exact"))
        at = text.find(q, at + 1)
    return out


def verify_span(doc: dict, source_span: dict, value_text: str) -> bool:
    """계약 항등식 검사. F-EXT-002 후처리와 같은 식을 쓴다."""
    text = page_text(doc, source_span["page"])
    start, end = source_span["start"], source_span["end"]
    if not (0 <= start <= end <= len(text)):
        return False
    return text[start:end] == _nfc(value_text)
