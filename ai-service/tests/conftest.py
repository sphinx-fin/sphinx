"""F-EXT-001 테스트 픽스처. 소유: 정세현

픽스처가 두 종류다.

- **합성 PDF** — 이 파일 안에 텍스트를 두고 실행 시점에 PDF로 찍는다. 표 추출·캡션 추정·
  텍스트 레이어 부재 같은 기계적 동작을 문서 없이 검증하기 위한 것이다. 폭에 맞춰 줄바꿈을
  접는다 — 실제 공시 PDF가 문장 중간에서 개행하고, 그게 스팬 해소가 다뤄야 하는 조건이다.
- **실문서** — `data/documents/`(git 제외)의 데모 대상 2종. 없으면 해당 테스트를 skip 한다.
  계약 준수는 결국 실문서로 확인해야 하지만, 문서가 없는 체크아웃에서 테스트 전체가 죽으면
  안 된다.

합성 픽스처의 텍스트를 `contracts/samples/`에서 가져오지 않는다. 그 샘플은 이제 실문서 파싱
출력이고, 실문서 텍스트를 합성 PDF로 되돌려 다시 파싱하는 건 아무것도 검증하지 않는다.
"""
import json
import pathlib
import sys

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from reportlab.lib.pagesizes import A4  # noqa: E402
from reportlab.pdfbase import pdfmetrics  # noqa: E402
from reportlab.pdfbase.cidfonts import UnicodeCIDFont  # noqa: E402
from reportlab.pdfgen import canvas  # noqa: E402

#: reportlab 내장 한국어 CID 폰트. 폰트 파일을 레포에 넣지 않아도 되고, pdfminer가
#: UniKS-UCS2-H 예약 CMap을 알고 있어 추출이 된다.
FONT = "HYSMyeongJo-Medium"
FONT_SIZE = 10.5
LEADING = 16
MARGIN = 60

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SAMPLES = REPO_ROOT / "contracts" / "samples"
DOCS = REPO_ROOT / "data" / "documents"

#: 데모 대상 2종. 파서 대응은 여기까지다(역할분담표 v1.2 타임박스) — 범용 파서가 아니다.
REAL_CASES = {
    "els": {
        "pdf": "els_kiwoom_4181_simple_prospectus.pdf",
        "document_id": "doc-els-kiwoom-4181",
        "product_type": "ELS",
        "sample": "parsed_els_sample.json",
        "fetch": "python3 scripts/fetch_documents.py els-4181",
    },
    "var": {
        "pdf": "var_samsung_b2601_product_summary.pdf",
        "document_id": "doc-var-samsung-b2601",
        "product_type": "VARIABLE_INSURANCE",
        "sample": "parsed_variable_sample.json",
        "fetch": "생보협 공시실에서 수동 취득 — python3 scripts/fetch_documents.py var-b2601",
    },
}


@pytest.fixture(scope="session", autouse=True)
def _register_font():
    pdfmetrics.registerFont(UnicodeCIDFont(FONT))


@pytest.fixture(scope="session")
def repo_root():
    return REPO_ROOT


@pytest.fixture(scope="session")
def els_sample():
    """실문서 파싱 출력 = 계약 샘플. `_expected_risk_items`가 정답 스팬이다."""
    return json.loads((SAMPLES / "parsed_els_sample.json").read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def var_sample():
    return json.loads((SAMPLES / "parsed_variable_sample.json").read_text(encoding="utf-8"))


@pytest.fixture(scope="session", params=list(REAL_CASES), ids=list(REAL_CASES))
def real_case(request):
    """실문서 1건을 파싱해 커밋된 샘플과 함께 준다. 문서가 없으면 skip."""
    from app import parsing

    spec = REAL_CASES[request.param]
    pdf = DOCS / spec["pdf"]
    if not pdf.exists():
        pytest.skip(f"{pdf.relative_to(REPO_ROOT)} 없음 (git 제외). {spec['fetch']}")
    kwargs = dict(
        document_id=spec["document_id"],
        product_type=spec["product_type"],
        parsed_at="2026-08-24T00:00:00Z",
    )
    return {
        "key": request.param,
        "pdf": str(pdf),
        "kwargs": kwargs,
        "doc": parsing.parse_document(str(pdf), **kwargs),
        "sample": json.loads((SAMPLES / spec["sample"]).read_text(encoding="utf-8")),
    }


# --- 합성 PDF 생성 ----------------------------------------------------------------

def _wrap(text, max_width):
    """글자 단위 그리디 줄바꿈. 한국어 조판은 단어 중간에서도 끊긴다."""
    out = []
    for para in text.split("\n"):
        if not para:
            out.append("")
            continue
        line = ""
        for ch in para:
            if pdfmetrics.stringWidth(line + ch, FONT, FONT_SIZE) > max_width and line:
                out.append(line)
                line = ch.lstrip(" ")
            else:
                line += ch
        out.append(line)
    return out


def _draw_text(c, lines, y):
    for line in lines:
        if line:
            c.drawString(MARGIN, y, line)
        y -= LEADING
    return y


def _draw_table(c, rows, y, caption=None):
    """괘선을 실제로 그린다 — pdfplumber 기본 표 탐지가 선을 근거로 하기 때문."""
    if caption:
        c.drawString(MARGIN, y, caption)
        y -= LEADING + 4

    n_cols = max(len(r) for r in rows)
    width = A4[0] - 2 * MARGIN
    col_w = width / n_cols
    row_h = 22
    top = y
    bottom = y - row_h * len(rows)

    for i in range(len(rows) + 1):
        line_y = top - i * row_h
        c.line(MARGIN, line_y, MARGIN + width, line_y)
    for j in range(n_cols + 1):
        line_x = MARGIN + j * col_w
        c.line(line_x, top, line_x, bottom)

    for i, row in enumerate(rows):
        for j, cell in enumerate(row):
            c.drawString(MARGIN + j * col_w + 4, top - (i + 1) * row_h + 7, cell)
    return bottom - LEADING


def _render(path, page_texts, tables_by_page=None):
    """page_texts: {페이지번호: 텍스트}. 번호가 빈 페이지는 채움 문구로 채운다."""
    tables_by_page = tables_by_page or {}
    c = canvas.Canvas(str(path), pagesize=A4)
    max_page = max(page_texts)
    usable = A4[0] - 2 * MARGIN

    for page_no in range(1, max_page + 1):
        c.setFont(FONT, FONT_SIZE)
        y = A4[1] - MARGIN
        text = page_texts.get(page_no, f"- {page_no} -\n(이 페이지는 데모 범위 밖입니다.)")
        y = _draw_text(c, _wrap(text, usable), y)
        for table in tables_by_page.get(page_no, []):
            y = _draw_table(c, table["rows"], y - LEADING, table.get("caption"))
        c.showPage()
    c.save()
    return str(path)


# --- 합성 픽스처 내용 -------------------------------------------------------------
# ELS 간이투자설명서를 닮은 최소 문서. 실문서에서 가져온 문면이 아니라 테스트 소유 데이터다.

SYNTHETIC_P1 = """[간이투자설명서] 파생결합증권(ELS) 제0000회

1. 상품 개요
본 증권은 기초자산의 가격 변동에 따라 손익이 결정되는 파생결합증권입니다.
이 금융투자상품은 예금자보호법에 따라 보호되지 않습니다.
투자원금의 손실이 발생할 수 있으며, 그 손실은 투자자에게 귀속됩니다."""

SYNTHETIC_P3 = """3. 상환 조건

가. 조기상환
각 조기상환평가일에 기초자산 모두가 행사가격 이상인 경우 액면금액과 수익을 지급합니다.

나. 만기상환
만기평가일에 기초자산 중 어느 하나라도 최초기준가격의 50% 미만인 경우 원금 손실이 발생할 수 있습니다.
이 경우 손실률은 최초기준가격 대비 하락률에 연동됩니다."""

SYNTHETIC_TABLE = {
    "caption": "기초자산 및 행사가격",
    "rows": [
        ["기초자산", "최초기준가격", "행사가격(조기)", "낙인"],
        ["HSCEI", "6,000.00", "5,400.00", "3,000.00"],
        ["S&P500", "5,000.00", "4,500.00", "2,500.00"],
    ],
}

#: 합성 문서에서 해소돼야 하는 문면. (item_id, 페이지, 인용문)
SYNTHETIC_QUOTES = [
    ("SYN-NO-DEPOSIT-INSURANCE", 1, "이 금융투자상품은 예금자보호법에 따라 보호되지 않습니다."),
    ("SYN-PRINCIPAL-LOSS", 3,
     "만기평가일에 기초자산 중 어느 하나라도 최초기준가격의 50% 미만인 경우 "
     "원금 손실이 발생할 수 있습니다."),
]


@pytest.fixture(scope="session")
def synthetic_pdf(tmp_path_factory):
    path = tmp_path_factory.mktemp("fixtures") / "synthetic_els.pdf"
    return _render(path, {1: SYNTHETIC_P1, 3: SYNTHETIC_P3}, {3: [SYNTHETIC_TABLE]})


@pytest.fixture(scope="session")
def scanned_pdf(tmp_path_factory):
    """텍스트 레이어가 없는 페이지 — TEXT_LAYER_MISSING 경고 검증용."""
    path = tmp_path_factory.mktemp("fixtures") / "scanned_no_text.pdf"
    c = canvas.Canvas(str(path), pagesize=A4)
    c.rect(MARGIN, MARGIN, 200, 120, fill=1)
    c.showPage()
    c.save()
    return str(path)
