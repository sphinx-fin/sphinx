"""`/internal/parse` 라우트 계약. 소유: 정세현 (이슈 #401 의 1번)

파서 자체가 계약을 지키는지는 `test_parsing.py` 가 잰다. 여기서 잠그는 것은 **라우트가
무엇을 어떤 코드로 돌려주는가** 다.

두 가지가 조용히 틀릴 수 있어서 테스트로 고정한다.

1. **실패 셋이 한 코드로 뭉치는 것.** 경로 규칙 위반 · 파일 없음 · PDF 로 안 열림은 고치는
   자리가 전부 다르다(부르는 쪽 배선 · 업로드/마운트 · 문서 자체). 뭉치면 `AiServiceClient`
   가 그 셋을 못 가르고, 셋 다 "ai-service 장애" 로 보인다.
2. **라우트가 파서 출력을 손대는 것.** `response_model` 을 붙이거나 시각을 찍으면 같은
   문서의 두 파싱 결과가 달라지고, 그 순간 P2 재현성 비교의 대상이 파서 출력이 아니게 된다.
"""
from __future__ import annotations

import json
import pathlib

import jsonschema
import pytest
from fastapi.testclient import TestClient

from app import parsing
from app.main import app

client = TestClient(app)

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA = json.loads(
    (REPO_ROOT / "contracts" / "parsed_document.schema.json").read_text(encoding="utf-8")
)

#: 뿌리(`SPHINX_DATA_DIR`) 기준 상대경로 — 요청이 실제로 이 모양이어야 한다.
DOC_REL = "documents/els_kiwoom_4181_simple_prospectus.pdf"


def _parse(**body):
    return client.post("/internal/parse", json=body)


@pytest.fixture(scope="module")
def real_pdf() -> pathlib.Path:
    """데모 대상 실문서. 추적되는 파일이라(#30) 없으면 체크아웃이 온전하지 않은 것이다."""
    path = parsing.documents_root() / DOC_REL
    if not path.is_file():
        pytest.skip(f"{DOC_REL} 없음 — 추적되는 파일이다(#30). python3 scripts/fetch_documents.py els-4181")
    return path


@pytest.fixture(scope="module")
def parsed(real_pdf) -> dict:
    res = _parse(document_path=DOC_REL, product_type="ELS")
    assert res.status_code == 200, res.text
    return res.json()


# --- 스텁이 아니다 -----------------------------------------------------------------

def test_route_is_no_longer_a_stub(real_pdf):
    """501 이 남아 있으면 뒤 구간(추출·세션 배선)이 전부 무의미하다 — 이슈 #401 의 전제."""
    assert _parse(document_path=DOC_REL, product_type="ELS").status_code == 200


# --- 성공 경로 ---------------------------------------------------------------------

def test_response_satisfies_contract(parsed):
    jsonschema.validate(parsed, SCHEMA)


def test_route_does_not_touch_parser_output(parsed, real_pdf):
    """라우트를 통과한 것과 **`parse_upload` 를 직접 부른 것**이 같아야 한다.

    다르면 직렬화가 한 겹 낀 것이고, 그 겹은 `test_parsing.py` 가 재는 계약 항등식
    (`pages[page].text[start:end] == value_text`) 밖에서 오프셋을 흔들 수 있다.

    ❗**기준이 `parse_document` 에서 `parse_upload` 로 바뀌었다**(이슈 #436 · PR #441).
    수동 파스 출력 우회가 생기면서 `.json` 이 옆에 있는 문서는 라우트가 PDF 를 안 판다 —
    `parse_document` 와 비교하면 **우회가 걸린 문서에서 이 테스트가 항상 깨진다.** 이 테스트가
    재려는 것은 「파서 출력 == HTTP 본문」이고, 라우트 본체는 `parse_upload` 이므로 그쪽이
    옳은 기준이다. 우회가 걸리는지 자체는 `test_manual_parse_override.py` 가 잰다.
    """
    direct = parsing.parse_upload(
        "documents/" + real_pdf.name,
        product_type="ELS",
        document_id=parsing.derive_document_id(real_pdf),
    )
    assert parsed == direct


def test_same_request_twice_is_identical(real_pdf):
    """P2 — 같은 요청이 같은 출력이어야 한다. 라우트가 시각을 찍으면 여기서 깨진다."""
    first = _parse(document_path=DOC_REL, product_type="ELS").json()
    second = _parse(document_path=DOC_REL, product_type="ELS").json()
    assert first == second


def test_parsed_at_is_omitted_when_not_injected(parsed):
    assert "parsed_at" not in parsed


def test_parsed_at_passes_through_when_injected(real_pdf):
    res = _parse(document_path=DOC_REL, product_type="ELS", parsed_at="2026-08-24T00:00:00Z")
    assert res.json()["parsed_at"] == "2026-08-24T00:00:00Z"


def test_document_id_is_derived_from_filename(parsed):
    assert parsed["document_id"] == "doc-els-kiwoom-4181-simple-prospectus"


def test_caller_document_id_wins(real_pdf):
    """계약상 `document_id` 는 업로드 단위 식별자다 — 업로더가 준 값이 있으면 그것이 정본이다."""
    res = _parse(document_path=DOC_REL, product_type="ELS", document_id="doc-els-kiwoom-4181")
    assert res.json()["document_id"] == "doc-els-kiwoom-4181"


def test_source_file_is_a_name_not_a_path(parsed):
    """계약: `source_file` 은 파일명이다. 경로가 실리면 호스트 배치가 응답으로 새어 나간다."""
    assert parsed["source_file"] == "els_kiwoom_4181_simple_prospectus.pdf"


# --- 실패 셋이 서로 다른 코드로 나간다 ---------------------------------------------

@pytest.mark.parametrize("path", [
    "../README.md",
    "documents/../../README.md",
    "/etc/passwd",
    "",
])
def test_path_outside_root_is_rejected_before_touching_the_file(path):
    """400 이고 404 가 아니다.

    404 로 답하면 **뿌리 밖 파일의 존재 여부가 상태 코드로 새어 나간다** — 있으면 다른
    실패, 없으면 404 가 되어 그것만으로 호스트를 훑을 수 있다. 그래서 존재 확인보다
    먼저 거부한다.
    """
    res = _parse(document_path=path, product_type="ELS")
    assert res.status_code == 400, res.text


def test_symlink_escape_is_rejected(tmp_path, monkeypatch):
    """`..` 만 막으면 부족하다 — 뿌리 안의 링크가 밖을 가리킬 수 있다."""
    outside = tmp_path / "outside.pdf"
    outside.write_bytes(b"%PDF-1.4\n")
    root = tmp_path / "root"
    root.mkdir()
    (root / "link.pdf").symlink_to(outside)
    monkeypatch.setattr(parsing, "documents_root", lambda: root)

    res = _parse(document_path="link.pdf", product_type="ELS")
    assert res.status_code == 400, res.text


def test_missing_file_inside_root_is_404(tmp_path, monkeypatch):
    monkeypatch.setattr(parsing, "documents_root", lambda: tmp_path)
    res = _parse(document_path="documents/없는문서.pdf", product_type="ELS")
    assert res.status_code == 404, res.text


def test_file_that_is_not_a_pdf_is_422_not_500(tmp_path, monkeypatch):
    """깨진 문서는 입력 문제다. 500 으로 나가면 "우리가 터졌다" 로 읽히고 사람이 엉뚱한
    곳을 본다 — `/extract` 가 큰 문서를 413 으로 내는 것과 같은 이유(PR #60 리뷰)."""
    monkeypatch.setattr(parsing, "documents_root", lambda: tmp_path)
    (tmp_path / "sales.pdf").write_text("이건 PDF 가 아니다", encoding="utf-8")

    res = _parse(document_path="sales.pdf", product_type="ELS")
    assert res.status_code == 422, res.text


def test_product_type_outside_demo_scope_is_rejected(real_pdf):
    """데모 범위는 2종이다. 범용 파서가 아니라는 사실이 요청 층에서 먼저 걸려야 한다."""
    res = _parse(document_path=DOC_REL, product_type="FUND")
    assert res.status_code == 422, res.text


# --- 경로 해소 규칙 (HTTP 없이) -----------------------------------------------------

def test_relative_path_resolves_against_root(tmp_path):
    got = parsing.resolve_document_path("documents/a.pdf", root=tmp_path)
    assert got == (tmp_path / "documents" / "a.pdf").resolve()


def test_absolute_path_inside_root_is_allowed(tmp_path):
    inside = tmp_path / "documents" / "a.pdf"
    got = parsing.resolve_document_path(str(inside), root=tmp_path)
    assert got == inside.resolve()


def test_nul_byte_is_rejected(tmp_path):
    with pytest.raises(parsing.DocumentPathRejected):
        parsing.resolve_document_path("a\x00.pdf", root=tmp_path)


def test_derive_document_id_is_stable_and_slugged():
    assert parsing.derive_document_id("/x/els_kiwoom_4181_simple_prospectus.pdf") == \
        "doc-els-kiwoom-4181-simple-prospectus"
    assert parsing.derive_document_id("/x/VAR Samsung B2601.PDF") == "doc-var-samsung-b2601"
