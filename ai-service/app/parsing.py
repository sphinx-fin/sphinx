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

import json
import logging
import os
import re
import unicodedata
from pathlib import Path

import pdfplumber

from .config import settings

log = logging.getLogger(__name__)

#: `parsed_document.schema.json` 의 parse_warnings 코드. 파서가 아니라 사람이 만들었다는 표식이고,
#: `ParsedDocument.is_manual` 이 이 값을 본다 — 성능 수치를 인용할 때 걸러야 하는 자리다(#409).
MANUAL_OVERRIDE_CODE = "MANUAL_OVERRIDE"

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

    for strategy, pattern in _patterns(q):
        m = re.search(pattern, text)
        if m:
            return _span(page, text, m.start(), m.end(), strategy)
    return None


def _patterns(q: str):
    """느슨해지는 순서로 매칭 전략을 낸다. resolve_span과 find_occurrences가 같은 걸 써야 한다."""
    yield "exact", re.escape(q)
    # 공백이 개행으로 바뀐 경우
    yield "whitespace", r"\s+".join(re.escape(tok) for tok in q.split())
    # 낱자 사이에 개행이 끼어든 경우. 실제 공시 PDF에서 가장 흔하다 —
    # 키움 4181 간이투자설명서의 이해항목 7건 중 3건이 이 단계에서만 잡혔다.
    yield "loose", r"\s*".join(re.escape(ch) for ch in q if not ch.isspace())


def _span(page: int, text: str, start: int, end: int, match: str) -> dict:
    return {
        "source_span": {"page": page, "start": start, "end": end},
        "value_text": text[start:end],
        "match": match,
    }


def find_occurrences(doc: dict, page: int, quote: str) -> list[dict]:
    """같은 문면이 여러 번 나오면 스팬이 모호하다. 정답 세트를 만들 때 개수를 먼저 확인한다.

    resolve_span과 **같은 전략 순서**를 쓴다. 다르게 구현하면 resolve_span이 찾은 문면을
    여기서는 0회 출현이라고 보고하게 되고, 모호성 검사가 조용히 무력해진다.
    """
    text = page_text(doc, page)
    q = _nfc(quote)
    if not q:
        return []
    for _strategy_name, pattern in _patterns(q):
        found = [
            _span(page, text, m.start(), m.end(), _strategy_name)
            for m in re.finditer(pattern, text)
        ]
        if found:
            return found
    return []


def verify_span(doc: dict, source_span: dict, value_text: str) -> bool:
    """계약 항등식 검사. F-EXT-002 후처리와 같은 식을 쓴다."""
    text = page_text(doc, source_span["page"])
    start, end = source_span["start"], source_span["end"]
    if not (0 <= start <= end <= len(text)):
        return False
    return text[start:end] == _nfc(value_text)


# --- 라우트 진입점 -----------------------------------------------------------------
# `/internal/parse` 가 부르는 것은 `parse_upload()` 하나다. 경로 해소와 실패 분류를 라우트가
# 아니라 여기 두는 이유가 둘이다. `routes.py` 는 얇게 유지한다는 그 파일의 규약이 하나이고,
# **어떤 실패가 요청 잘못이고 어떤 것이 문서 잘못인지는 파서만 안다**는 것이 다른 하나다.


class ParseRefused(Exception):
    """파싱을 시작하지 못했다. 하위 타입이 이유를 가르고, 라우트가 그것으로 상태 코드를 고른다.

    세 이유를 한 코드로 묶으면 안 된다 — 고치는 자리가 전부 다르다. 경로 규칙 위반은
    부르는 쪽 배선, 파일 없음은 업로드·마운트, 못 읽음은 문서 자체다.
    """


class DocumentPathRejected(ParseRefused):
    """허용된 뿌리 밖을 가리킨다. 파일을 만지기 전에 거부한다."""


class DocumentNotFound(ParseRefused):
    """뿌리 안이지만 그 파일이 없다."""


class DocumentUnreadable(ParseRefused):
    """PDF 로 열리지 않는다 — 형식 오류·암호화·페이지 0."""


#: pdfplumber 는 pdfminer 예외를 자기 타입으로 감싼다. 핀이 없는 의존성이라(requirements.txt)
#: 클래스 위치가 버전에 따라 움직일 수 있어 임포트를 방어한다 — 여기서 못 잡으면 깨진 PDF 가
#: 422 가 아니라 500 으로 나가고, 그건 "문서가 잘못됐다" 가 아니라 "우리가 터졌다" 로 읽힌다.
def _unreadable_errors() -> tuple[type[BaseException], ...]:
    found: list[type[BaseException]] = []
    try:
        from pdfplumber.utils.exceptions import PdfminerException
        found.append(PdfminerException)
    except ImportError:  # pragma: no cover - 버전 방어
        pass
    try:
        from pdfminer.psparser import PSException
        found.append(PSException)
    except ImportError:  # pragma: no cover - 버전 방어
        pass
    return tuple(found)


_UNREADABLE = _unreadable_errors()

#: 파일명 → document_id 에서 살릴 문자.
_ID_UNSAFE = re.compile(r"[^a-z0-9]+")


def documents_root() -> Path:
    """문서를 읽어도 되는 유일한 뿌리 — `SPHINX_DATA_DIR`(컨테이너에서는 읽기 전용 `/data`).

    **전용 환경변수를 새로 만들지 않는다.** 업로드된 파일이 여기 어떻게 도달하는지는 아직
    안 정해졌고(이슈 #401 의 2번), 결정 전에 knob 을 박으면 결정이 그 knob 에 맞춰진다.
    지금 데모가 파싱하는 문서는 전부 `data/documents/` 에 있고 그건 이미 마운트돼 있다.
    """
    return settings().data_dir


def derive_document_id(pdf_path: str | Path) -> str:
    """파일명에서 만든 문서 id. 같은 파일이면 같은 값이다(P2).

    계약상 `document_id` 는 **업로드 단위** 식별자라 원래 업로더가 가진 값이고 파서가 정할
    것이 아니다 — 호출자가 주면 그걸 쓴다. 여기서 만드는 것은 영속 층(#401 의 3번)이 붙기
    전까지 이 엔드포인트를 혼자 돌려볼 수 있게 하는 값이다.
    """
    stem = _ID_UNSAFE.sub("-", Path(pdf_path).stem.lower()).strip("-")
    return f"doc-{stem}" if stem else "doc-unnamed"


def resolve_document_path(document_path: str, *, root: str | Path | None = None) -> Path:
    """요청의 경로를 실제 파일 경로로 바꾼다. 뿌리 밖이면 파일을 만지지 않고 거부한다.

    상대경로는 뿌리 기준이고, 절대경로는 뿌리 안일 때만 받는다.

    **존재 확인보다 먼저 거부한다.** 순서가 반대면 뿌리 밖 경로에도 "있다/없다"가 갈려
    나가고, 그 차이만으로 호스트에 무슨 파일이 있는지 훑을 수 있다. `..` 뿐 아니라
    심볼릭 링크로 밖을 가리키는 것도 막아야 해서 `resolve()` 로 끝까지 푼 뒤에 비교한다.
    """
    base = Path(root if root is not None else documents_root()).expanduser().resolve()

    raw = (document_path or "").strip()
    if not raw:
        raise DocumentPathRejected("document_path 가 비었다")
    if "\x00" in raw:
        raise DocumentPathRejected("document_path 에 NUL 문자가 있다")

    candidate = Path(raw).expanduser()
    if not candidate.is_absolute():
        candidate = base / candidate

    try:
        resolved = candidate.resolve()
    except OSError as exc:  # 순환 심볼릭 링크 등
        raise DocumentPathRejected(f"경로를 해소할 수 없다: {document_path!r} ({exc})") from exc

    if resolved != base and base not in resolved.parents:
        raise DocumentPathRejected(
            f"document_path 가 허용된 뿌리 밖이다: {document_path!r} "
            f"(뿌리는 SPHINX_DATA_DIR — 상대경로로 주는 것이 정상이다. 예: documents/xxx.pdf)"
        )
    return resolved



def _manual_override(path: Path, *, product_type: str,
                     document_id: str | None, parsed_at: str | None) -> dict:
    """옆에 놓인 파스 출력 JSON 을 그대로 쓴다 (이슈 #436 · 윤지석 #436 코멘트).

    ## 왜 추출 결과가 아니라 파스 출력인가

    ``MANUAL_OVERRIDE`` 는 **파스 층의 개념**이다 — `parsed_document.schema.json` 의
    `parse_warnings` 코드이고 `ParsedDocument.is_manual` 이 그걸 읽는다. 추출 결과를 손으로
    채우면 그 표식이 닿지 않는 층에 생기고, `#409`(model.jsonl)·F-EXT-003(추출 재현율)이
    나중에 *"이 문서는 사람이 만들었다"* 로 걸러야 할 자리가 거기다.

    그리고 추출 결과를 채우면 **같은 문서를 다시 추출할 때 또 실패한다.** 파스 출력을 고치면
    추출이 정상 경로로 성공하고 **그 성공이 재현된다.**

    ## ❗조용히 갈아치우지 않는다

    이 경로는 **암묵적**이다(요청은 여전히 `.pdf` 를 가리킨다). 그래서 셋을 강제한다.

        MANUAL_OVERRIDE 경고를 반드시 싣는다   없으면 여기서 붙인다 — 출력만 보고 알 수 있어야 한다
        상품유형이 다르면 거부한다             #427 과 같은 종류의 조용한 오답을 막는다
        JSON 이 깨지면 PDF 로 안 흘러내린다    폴백하면 "고쳤는데 안 고쳐진" 상태가 조용히 산다

    되돌리는 것은 이 파일을 지우는 것뿐이다 — PDF 는 그대로 있다.
    """
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        # ❗PDF 로 폴백하지 않는다. 사람이 고쳐 둔 것이 안 읽히는데 파싱이 성공하면,
        # 그 사람은 고쳤다고 믿고 우리는 옛 결과를 쓴다.
        raise DocumentUnreadable(f"수동 파스 출력을 못 읽는다: {path.name} — {exc}") from exc
    if not isinstance(doc, dict) or not doc.get("pages"):
        raise DocumentUnreadable(f"수동 파스 출력이 ParsedDocument 모양이 아니다: {path.name}")

    got = doc.get("product_type")
    if got and got != product_type:
        # 요청 상품유형과 다른 문서를 내주면 화면·추출이 다른 상품을 본다 (#427 과 같은 종류).
        raise DocumentPathRejected(
            f"수동 파스 출력의 product_type 이 요청과 다르다: {got} != {product_type}")

    doc["product_type"] = product_type
    if document_id:
        doc["document_id"] = document_id
    elif not doc.get("document_id"):
        doc["document_id"] = derive_document_id(path)
    if parsed_at:
        doc["parsed_at"] = parsed_at

    warnings = list(doc.get("parse_warnings") or [])
    if not any(w.get("code") == MANUAL_OVERRIDE_CODE for w in warnings):
        warnings.append({
            "page": None,
            "code": MANUAL_OVERRIDE_CODE,
            "message": f"파서가 아니라 사람이 만든 파스 출력이다: {path.name}",
        })
    doc["parse_warnings"] = warnings

    log.info("F-EXT-001 수동 파스 출력을 사용한다: %s (페이지 %d)", path.name, len(doc["pages"]))
    return doc


def parse_upload(
    document_path: str,
    *,
    product_type: str,
    document_id: str | None = None,
    parsed_at: str | None = None,
    root: str | Path | None = None,
) -> dict:
    """`/internal/parse` 본체. 경로를 해소하고 `parse_document()` 를 부른다.

    `parsed_at` 은 여기서도 찍지 않는다 — 안 주면 키가 없는 채로 나간다. 파서가 현재 시각을
    넣으면 같은 문서의 두 파싱 결과가 달라져 재현성 비교(P2)에 쓸 수 없다. 기록이 필요한
    쪽(서버)이 자기 시각을 찍는 것이 맞다.
    """
    path = resolve_document_path(document_path, root=root)
    if not path.is_file():
        raise DocumentNotFound(f"문서가 없다: {document_path!r}")

    override = path.with_suffix(".json")
    if override.is_file():
        return _manual_override(override, product_type=product_type,
                                document_id=document_id, parsed_at=parsed_at)

    try:
        return parse_document(
            str(path),
            document_id=document_id or derive_document_id(path),
            product_type=product_type,
            parsed_at=parsed_at,
        )
    except ValueError as exc:
        # parse_document 가 내는 것: 데모 범위 밖 product_type · 페이지 0.
        # 둘 다 입력 문제라 500 이 아니다.
        raise DocumentUnreadable(str(exc)) from exc
    except _UNREADABLE as exc:
        raise DocumentUnreadable(
            f"PDF 로 열리지 않는다: {document_path!r} ({type(exc).__name__}: {exc})"
        ) from exc
