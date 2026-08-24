"""F-EXT-001 테스트 픽스처. 소유: 정세현

`data/documents/`(수집 문서)는 git 제외이므로, 파서 테스트가 그 문서에 의존하면 다른 사람의
체크아웃에서는 아예 돌지 않는다. 그래서 이미 머지된 `contracts/samples/*.json`의 페이지
텍스트를 거꾸로 PDF로 찍어 픽스처로 쓴다 — 텍스트 원본은 계약 샘플 한 곳뿐이다.

의도적으로 줄바꿈을 폭에 맞춰 접는다. 실제 공시 PDF는 문장 중간에서 개행되고, 그게
`resolve_span`이 다뤄야 하는 조건이기 때문이다. 여기서 한 줄에 다 그려버리면 테스트가
현실보다 쉬워진다.
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


@pytest.fixture(scope="session", autouse=True)
def _register_font():
    pdfmetrics.registerFont(UnicodeCIDFont(FONT))


@pytest.fixture(scope="session")
def repo_root():
    return REPO_ROOT


@pytest.fixture(scope="session")
def els_sample():
    return json.loads((SAMPLES / "parsed_els_sample.json").read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def var_sample():
    return json.loads((SAMPLES / "parsed_variable_sample.json").read_text(encoding="utf-8"))


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


def _new_page(c):
    c.setFont(FONT, FONT_SIZE)
    return A4[1] - MARGIN


def _render(path, page_texts, tables_by_page=None):
    """page_texts: {페이지번호: 텍스트}. 번호가 빈 페이지는 채움 문구로 채운다."""
    tables_by_page = tables_by_page or {}
    c = canvas.Canvas(str(path), pagesize=A4)
    max_page = max(page_texts)
    usable = A4[0] - 2 * MARGIN

    for page_no in range(1, max_page + 1):
        y = _new_page(c)
        text = page_texts.get(page_no, f"- {page_no} -\n(이 페이지는 데모 범위 밖입니다.)")
        y = _draw_text(c, _wrap(text, usable), y)
        for table in tables_by_page.get(page_no, []):
            y = _draw_table(c, table["rows"], y - LEADING, table.get("caption"))
        c.showPage()
    c.save()
    return str(path)


def _page_texts(sample):
    return {p["page"]: p["text"] for p in sample["pages"]}


@pytest.fixture(scope="session")
def els_pdf(tmp_path_factory, els_sample):
    tables = {}
    for t in els_sample["tables"]:
        tables.setdefault(t["page"], []).append(t)
    path = tmp_path_factory.mktemp("fixtures") / "els_simple_prospectus_demo.pdf"
    return _render(path, _page_texts(els_sample), tables)


@pytest.fixture(scope="session")
def var_pdf(tmp_path_factory, var_sample):
    path = tmp_path_factory.mktemp("fixtures") / "variable_insurance_demo.pdf"
    return _render(path, _page_texts(var_sample))


@pytest.fixture(scope="session")
def scanned_pdf(tmp_path_factory):
    """텍스트 레이어가 없는 페이지 — TEXT_LAYER_MISSING 경고 검증용."""
    path = tmp_path_factory.mktemp("fixtures") / "scanned_no_text.pdf"
    c = canvas.Canvas(str(path), pagesize=A4)
    c.rect(MARGIN, MARGIN, 200, 120, fill=1)
    c.showPage()
    c.save()
    return str(path)
