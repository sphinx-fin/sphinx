"""수동 파스 출력 우회 (이슈 #436 · F-EXT-001). 소유: 정세현

## 왜 있나

`ELS-MATURITY-LOSS-CONDITION` 이 추출에서 `NARROWING_REFUSED` 로 거부됐고(옳은 거부다 —
P6 항등식을 못 지키느니 실패로 낸다), 그 항목이 **S-04 손실 시뮬레이터의 전제**라 화면이
통째로 막혔다(E-SIM-01). 역할표 F-EXT-001 이 *"형식 편차로 막히면 수동 JSON 으로 우회"* 를
그 경우로 적어 뒀다.

## 왜 추출 결과가 아니라 파스 출력인가 (윤지석 #436)

`MANUAL_OVERRIDE` 는 **파스 층의 개념**이고 `ParsedDocument.is_manual` 이 그걸 읽는다.
추출 결과를 채우면 그 표식이 닿지 않는 층에 생기고, **같은 문서를 다시 추출할 때 또 실패한다.**

## 이 파일이 잠그는 것

    ① 옆에 .json 이 있으면 그것을 쓴다
    ② ❗MANUAL_OVERRIDE 가 없으면 붙인다 — 출력만 보고 사람이 만든 것임을 알 수 있어야 한다
    ③ ❗상품유형이 다르면 거부한다 (#427 과 같은 종류의 조용한 오답)
    ④ ❗JSON 이 깨지면 PDF 로 흘러내리지 않는다 — 고쳤다고 믿는데 옛 결과가 쓰이는 것이 최악이다
    ⑤ .json 이 없으면 예전 그대로 PDF 를 판다
"""
from __future__ import annotations

import json

import pytest

from app import parsing

DOC = {
    "document_id": "manual-els",
    "product_type": "ELS",
    "parser_version": "manual-1",
    "pages": [{"page": 1, "text": "만기평가일에 최초기준가격의 65% 미만이면 손실이 발생한다.",
               "char_count": 33}],
    "tables": [],
    "parse_warnings": [],
}


@pytest.fixture()
def root(tmp_path):
    (tmp_path / "documents").mkdir()
    return tmp_path


def _write(root, *, pdf=True, doc=DOC, raw=None):
    d = root / "documents"
    if pdf:
        (d / "x.pdf").write_bytes(b"%PDF-1.4 (broken on purpose)")
    if raw is not None:
        (d / "x.json").write_text(raw, encoding="utf-8")
    elif doc is not None:
        (d / "x.json").write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
    return "documents/x.pdf"


def test_a_sibling_json_is_used_instead_of_the_pdf(root) -> None:
    """★ .json 이 옆에 있으면 그것이 답이다 — PDF 는 안 연다(열면 깨진 바이트라 죽는다)."""
    path = _write(root)
    out = parsing.parse_upload(path, product_type="ELS", root=root)
    assert out["pages"][0]["text"].startswith("만기평가일에")


def test_the_manual_marker_is_added_when_missing(root) -> None:
    """❗표식이 없으면 붙인다. 출력만 보고 「사람이 만들었다」를 알 수 있어야 한다(#409)."""
    path = _write(root)
    out = parsing.parse_upload(path, product_type="ELS", root=root)
    codes = [w["code"] for w in out["parse_warnings"]]
    assert parsing.MANUAL_OVERRIDE_CODE in codes


def test_a_product_type_mismatch_is_refused(root) -> None:
    """❗다른 상품유형 문서를 내주면 화면·추출이 다른 상품을 본다 (#427 과 같은 종류)."""
    path = _write(root)
    with pytest.raises(parsing.DocumentPathRejected):
        parsing.parse_upload(path, product_type="VARIABLE_INSURANCE", root=root)


def test_a_broken_json_does_not_fall_back_to_the_pdf(root) -> None:
    """❗폴백하면 **고쳤다고 믿는데 옛 결과가 쓰인다.** 그게 이 우회에서 제일 나쁜 실패다."""
    path = _write(root, raw="{ 깨진 json")
    with pytest.raises(parsing.DocumentUnreadable):
        parsing.parse_upload(path, product_type="ELS", root=root)


def test_a_shapeless_json_is_refused(root) -> None:
    path = _write(root, raw='{"product_type": "ELS"}')       # pages 가 없다
    with pytest.raises(parsing.DocumentUnreadable):
        parsing.parse_upload(path, product_type="ELS", root=root)


def test_without_the_json_the_pdf_path_is_unchanged(root) -> None:
    """⑤ 기존 동작이 안 바뀐다 — .json 이 없으면 PDF 를 판다(그래서 깨진 바이트에 죽는다)."""
    d = root / "documents"
    (d / "x.pdf").write_bytes(b"%PDF-1.4 (broken)")
    with pytest.raises(parsing.DocumentUnreadable):
        parsing.parse_upload("documents/x.pdf", product_type="ELS", root=root)
