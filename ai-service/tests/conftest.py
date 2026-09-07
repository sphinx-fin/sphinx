"""F-EXT-001 테스트 픽스처. 소유: 정세현

픽스처가 두 종류다.

- **합성 PDF** — 이 파일 안에 텍스트를 두고 실행 시점에 PDF로 찍는다. 표 추출·캡션 추정·
  텍스트 레이어 부재 같은 기계적 동작을 문서 없이 검증하기 위한 것이다. 폭에 맞춰 줄바꿈을
  접는다 — 실제 공시 PDF가 문장 중간에서 개행하고, 그게 스팬 해소가 다뤄야 하는 조건이다.
- **실문서** — `data/documents/`의 데모 대상 2종. **이 파일들은 추적된다**(PR #30) — 그래서
  `git clone` 만으로도, CI 러너에서도 이 테스트가 돈다. 없으면 skip 하는 경로는 남겨두지만
  그건 문서를 지운 체크아웃을 위한 것이지 정상 상태가 아니다. CI 는 skip 을 실패로 센다
  (`.github/workflows/ci.yml`) — 러너에서 skip 이 나오면 그건 편의가 아니라 검산이 안
  돌았다는 뜻이다.

합성 픽스처의 텍스트를 `contracts/samples/`에서 가져오지 않는다. 그 샘플은 이제 실문서 파싱
출력이고, 실문서 텍스트를 합성 PDF로 되돌려 다시 파싱하는 건 아무것도 검증하지 않는다.
"""
import json
import logging
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


#: `app` 로거는 `propagate=False` 다 — `configure_logging()` 이 그렇게 켠다(PR #121).
#: root 에도 핸들러가 있으면 같은 줄이 두 번 찍히고, **한 줄이 두 번 나오면 빈도 관측이
#: 정확히 두 배로 틀린다.** 운영 설정으로는 맞다.
#:
#: 그런데 그 설정이 테스트에 새어 들어온다. `app.main` 을 임포트하는 순간(모듈 수준에서
#: `configure_logging()` 을 부른다) `app.*` 레코드가 root 로 안 올라가고, `caplog` 은
#: 기본적으로 **root 에 핸들러를 붙여** 잡는다. 그러면 `app.*` 로그를 보는 단정이
#: **조용히 아무것도 못 보게** 된다.
#:
#: ## 지금은 pytest 버전이 이걸 가리고 있다
#:
#: 최신 pytest 는 `propagate=False` 인 로거에도 캡처 핸들러를 직접 붙여 준다. 그래서 같은
#: 코드가 버전에 따라 갈린다 — 실측이다(`tests/test_skeleton.py` 가 `app.main` 을 임포트한다).
#:
#:     pytest 9.1.1   app.handlers = [StreamHandler, LogCaptureHandler, LogCaptureHandler]   통과
#:     pytest 8.3.4   app.handlers = [StreamHandler]                                          1 failed
#:     pytest 7.4.4   app.handlers = [StreamHandler]                                          1 failed
#:
#:     tests/test_mismatch.py::test_axis_mismatch_is_logged_not_silent
#:       assert any("축 불일치" in r.message for r in caplog.records)   → AssertionError
#:
#: `requirements-dev.txt` 의 `pytest` 에 **핀이 없다.** 지금 초록인 것은 해석기가 최신을
#: 고르기 때문이고, 그건 우리가 정한 것이 아니다. 그리고 깨질 때 나오는 실패 메시지가
#: *"축 불일치 로그가 안 찍혔다"* 라 **원인과 전혀 다른 곳을 가리킨다.**
#:
#: ## 그래서 여기서 붙인다
#:
#: `propagate` 를 되돌리지 않는다 — 그러면 테스트가 운영과 **다른 로깅 설정**으로 돌고,
#: `#121` 이 막은 이중 출력이 테스트에서만 되살아난다. 대신 최신 pytest 가 하는 일을 그대로
#: 한다: `caplog` 의 핸들러를 `app` 로거에도 달아 두고 끝나면 뗀다. 이미 붙어 있으면(최신
#: pytest) 아무것도 안 한다.
@pytest.fixture(autouse=True)
def _caplog_reaches_app_logger(caplog):
    app_logger = logging.getLogger("app")
    if app_logger.propagate:
        # `configure_logging()` 이 아직 안 돌았다 — root 경유로 잡히므로 손대지 않는다.
        # 여기서 붙이면 root 와 양쪽에 잡혀 `caplog.records` 에 같은 줄이 두 번 들어간다.
        yield
        return
    handler = caplog.handler
    if handler in app_logger.handlers:
        yield                       # 최신 pytest 가 이미 붙였다
        return
    app_logger.addHandler(handler)
    try:
        yield
    finally:
        app_logger.removeHandler(handler)


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
        # 이 파일들은 추적된다(#30). 여기에 걸린다는 것은 체크아웃이 온전하지 않다는
        # 뜻이지 "아직 안 받았다" 가 아니다 — CI 는 이 skip 을 실패로 센다.
        pytest.skip(f"{pdf.relative_to(REPO_ROOT)} 없음 — 추적되는 파일이다(#30). {spec['fetch']}")
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


#: ❗**자기일관성 재질의를 스위트 기본은 순차로 돌린다** (이슈 #437 (다)).
#:
#: 이 레포의 스텁 상당수가 **호출 순서대로** 답을 돌려준다(`SequenceLlm`). 투기 호출이
#: 켜지면 그 큐를 두 스레드가 같이 꺼내서 어느 쪽이 `bad` 를 받는지가 회차마다 갈린다 —
#: 운영에서는 두 요청이 서로 독립이라 없는 문제이고, **스텁의 성질**이다.
#:
#: 그래서 여기서 끄고, 병렬 경로는 `test_parallel_consistency.py` 가 스레드 안전한 스텁으로
#: 따로 잰다. 그 파일이 이 픽스처를 다시 덮는다.
#:
#: 끄는 자리를 `settings()` 스위치가 아니라 `_parallel_enabled` 로 둔 이유는 계량기다 —
#: 스위치를 끄면 `METER.disabled` 가 테스트마다 올라서 그 숫자가 뜻을 잃는다.
@pytest.fixture(autouse=True)
def _consistency_probe_is_serial_in_tests(monkeypatch):
    from app import scoring

    monkeypatch.setattr(scoring, "_parallel_enabled", lambda: False)


#: 극성 게이트(F-DET-001 3단계)도 같은 이유로 스위트에서는 순차다 (이슈 #498).
#:
#: 위와 같은 성질이다 — `test_polarity_gate.py` 의 스텁이 `complete_json` 인자를 리스트에
#: 모으고 그 **순서**를 단정한다. 병렬로 돌면 회차마다 갈리는데, 운영에서는 후보들이 서로
#: 독립이라 없는 문제다.
#:
#: ❗**여기는 계량기 문제가 없다.** `#437` 은 스위치를 끄면 `METER.disabled` 가 올라서
#: 함수로 덮어야 했는데, 극성 게이트는 병렬 여부를 안 센다(호출 수가 안 바뀌므로).
#: 그래도 **같은 자리에 덮는다** — 두 층이 다른 방식으로 꺼지면 다음 사람이 한쪽만 보고
#: *"병렬이 왜 안 도나"* 를 틀린 데서 찾는다.
@pytest.fixture(autouse=True)
def _polarity_gate_is_serial_in_tests(monkeypatch):
    from app import misconception

    monkeypatch.setattr(misconception, "_polarity_parallel_enabled", lambda: False)
