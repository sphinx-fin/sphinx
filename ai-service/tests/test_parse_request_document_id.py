"""`/internal/parse` 의 `document_id` 가 **id 이거나 없다.** 소유: 윤지석 (이슈 #578)

`ParseRequest` 는 `app/schemas.py`(내 파일)이고 엔드포인트는 F-EXT-001(정세현)이다 — 그래서
라우트 계약(`test_parse_route.py`)과 갈라 여기 둔다. 여기서 잠그는 것은 **그 한 필드가 받는
값의 범위** 하나다.

## 왜 이 파일이 있나

`#576` 이 서버가 `document_id` 를 넘기게 만들면서 *"파일명 폴백이 운영 경로에서 빠진다"* 를
보장한다. **받는 쪽에는 그것을 지키는 것이 없었다**(이슈 #578 실측)::

    document_id=''     스키마 통과 → 출력 doc-els-kiwoom-4181-simple-prospectus   ❗폴백이 돌았다
    document_id='   '  스키마 통과 → 출력 '   '                                    ❗그대로 쌓인다

앞은 `parsing.py` 의 `document_id or derive_document_id(path)` 에서 빈 문자열이 falsy 로
떨어지는 것이고, 뒤는 아무 검사가 없는 것이다. 둘 다 `extracted_risk_items.document_id` 에
닿아 결정 1.37 의 «한 열에 두 규칙» 을 만든다.

## ❗`None` 은 막지 않는다

*"안 준다"* 는 정당하고 그때 폴백이 도는 것이 설계다 — 단독 실행 경로가 실재한다. 그리고
`AiServiceClient` javadoc 이 *"저쪽 스키마에서는 nullable 이고 비면 파일명에서 만든다"* 를
근거로 삼고 있어서, `None` 을 막으면 그 문장이 거짓이 된다. **막는 것은 「빈 값」이지
「없음」이 아니다** — 아래 첫 테스트가 그 구분을 잰다.
"""
from __future__ import annotations

import pathlib

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app import parsing, schemas
from app.main import app

client = TestClient(app)

#: `test_parse_route.py` 의 같은 이름 픽스처는 그 파일 안에 있다(module scope). conftest 로
#: 올리면 정세현 님 파일의 의존이 바뀌므로 여기 한 벌 둔다 — 같은 문서를 가리킨다.
DOC_REL = "documents/els_kiwoom_4181_simple_prospectus.pdf"


@pytest.fixture(scope="module")
def real_pdf() -> pathlib.Path:
    """데모 대상 실문서. 추적되는 파일이라(#30) 없으면 체크아웃이 온전하지 않은 것이다."""
    path = parsing.documents_root() / DOC_REL
    if not path.is_file():
        pytest.skip(f"{DOC_REL} 없음 — 추적되는 파일이다(#30)")
    return path


def _request(**kw) -> schemas.ParseRequest:
    return schemas.ParseRequest(document_path="documents/x.pdf", product_type="ELS", **kw)


# ── 「없음」은 정당하다 ─────────────────────────────────────────────────────────
def test_an_absent_document_id_is_still_allowed() -> None:
    """★ 키를 아예 안 주는 것과 `null` 은 통과한다 — 단독 실행 경로가 그것이다.

    ❗이 단정이 **먼저** 온다. 「빈 값을 막는다」를 「안 줘도 막는다」로 넓히면 파서를 혼자
    돌려 보는 경로가 죽고, `AiServiceClient` javadoc 의 근거(*"nullable 이고 비면 파일명에서
    만든다"*)도 같이 거짓이 된다.
    """
    assert _request().document_id is None
    assert _request(document_id=None).document_id is None


def test_a_real_id_passes_through_unchanged() -> None:
    """값을 손대지 않는다 — 여기서 정규화하면 보낸 값과 저장된 값이 갈린다."""
    assert _request(document_id="doc-els-kiwoom-4181").document_id == "doc-els-kiwoom-4181"


# ── 빈 값은 폴백을 되살린다 ──────────────────────────────────────────────────────
@pytest.mark.parametrize("blank", ["", " ", "   ", "\t", "\n"])
def test_a_blank_document_id_is_rejected(blank: str) -> None:
    """❗빈 문자열은 **폴백을 조용히 되살린다** — 그래서 「없음」과 다르게 다룬다."""
    with pytest.raises(ValidationError, match="비어 있다"):
        _request(document_id=blank)


@pytest.mark.parametrize("padded", [" doc-x", "doc-x ", " doc-x ", "\tdoc-x"])
def test_surrounding_whitespace_is_rejected(padded: str) -> None:
    """❗`' doc-x '` 와 `'doc-x'` 는 **다른 문서**가 된다. 그 차이는 로그에서 안 보인다."""
    with pytest.raises(ValidationError, match="앞뒤에 공백"):
        _request(document_id=padded)


# ── 그 빈 값이 실제로 폴백을 켰다는 것을 여기서 고정한다 ──────────────────────────
def test_a_blank_value_gets_two_different_wrong_answers_downstream(real_pdf) -> None:
    """★ **이 검사가 무엇을 막는지**를 파서 쪽 실물로 잰다 (이슈 #578).

    스키마만 잠그면 «왜 빈 값이 나쁜가» 가 문면으로만 남는다. 파서는 그대로이므로 여기서
    직접 불러 고정한다 — 그리고 재 보니 **한 가지가 아니었다.**

        parse_upload('')     → doc-els-kiwoom-4181-simple-prospectus   폴백이 켜진다
        parse_document('')   → ''                                      그대로 담긴다

    라우트가 밟는 것은 `parse_upload` 라 운영 경로의 증상은 **폴백**이다(`document_id or
    derive_document_id(path)` 에서 빈 문자열이 falsy). 그 아래 `parse_document` 는 받은 값을
    쓰므로 빈 문자열이 그대로 남는다.

    ❗**같은 입력에 층마다 다른 오답이 나온다** — 그래서 «빈 값이면 무엇이 되나» 에 답이
    없고, 받는 입구에서 거부하는 것이 맞다. 두 층 중 하나만 고치면 다른 하나가 남는다.

    나중에 `parsing.py` 가 이 갈래를 고치면 이 단정이 울고, 그때 이 파일의 근거도 같이 손본다.
    """
    derived = parsing.derive_document_id(real_pdf)
    rel = DOC_REL

    assert parsing.parse_upload(rel, product_type="ELS", document_id="")["document_id"] == derived, \
        "빈 값이 폴백을 안 켰다면 이 스키마 검사의 근거가 바뀐 것이다"
    assert parsing.parse_document(str(real_pdf), document_id="", product_type="ELS")["document_id"] == "", \
        "아래 층이 빈 값을 거르기 시작했다면 위 문단의 「두 오답」이 하나로 줄어든 것이다"

    given = parsing.parse_upload(rel, product_type="ELS", document_id="doc-given")
    assert given["document_id"] == "doc-given"


# ── 라우트가 그 거부를 422 로 낸다 ───────────────────────────────────────────────
def test_the_route_rejects_a_blank_document_id_with_422() -> None:
    """❗스키마 거부가 **라우트 밖으로** 나가는 것까지 본다.

    422 는 「요청이 계약을 벗어났다」이고, 서버 쪽에서 「ai-service 장애」(502)와 갈린다 —
    `#551`·`#556` 이 그 구분을 세운 자리다. 조용히 폴백으로 떨어지는 것과 정반대다.
    """
    r = client.post("/internal/parse",
                    json={"document_path": "documents/x.pdf", "product_type": "ELS", "document_id": ""})

    assert r.status_code == 422, r.text
    assert "document_id" in r.text


# ── 폴백이 돌면 그것이 보인다 (이슈 #578 ②) ─────────────────────────────────────
def test_the_filename_fallback_announces_itself(real_pdf, caplog) -> None:
    """★ 폴백이 **조용하지 않다** — 닿을 때마다 한 줄 남긴다.

    `derive_document_id` docstring 이 *"운영 경로가 이 함수에 닿으면 그것이 결함"* 이라고
    선언하는데 그 사건에 신호가 없었다. 알파 로그에 이 줄이 **없어야** 운영 경로가 정말 안
    닿는 것이고, 그것이 미결 10.87(재추출)의 완료 조건이기도 하다.

    ❗자리가 `derive_document_id` 안인 이유는 **폴백 호출부가 둘**이라서다 — 호출부에 각각
    적으면 두 벌이 되고 세 번째가 생기면 또 빠진다(PR #588 리뷰). 그래서 아래는 **두 경로
    모두**에서 그 줄이 나오는 것을 본다.
    """
    derived = parsing.derive_document_id(real_pdf)

    with caplog.at_level("INFO", logger="app.parsing"):
        parsing.parse_upload(DOC_REL, product_type="ELS", document_id=None)

    hits = [r for r in caplog.records if "파일명에서 만들었다" in r.getMessage()]
    assert hits, "폴백이 돌았는데 아무 줄도 안 남았다 — 「닿으면 결함」에 신호가 없다"
    assert hits[0].levelname == "INFO", (
        "단독 실행에서는 이것이 정상 경로다 — 경고로 올리면 배경이 되고 운영에서 진짜 났을 때 안 보인다")
    assert derived in hits[0].getMessage(), "파생값이 없으면 「어느 규칙으로 만들어졌나」에 못 답한다"


def test_a_given_document_id_leaves_no_fallback_line(real_pdf, caplog) -> None:
    """❗**값을 준 호출은 그 줄을 안 남긴다** — 안 그러면 로그가 늘 켜져 신호가 안 된다.

    이 단정이 있어야 «알파에 이 줄이 없어야 한다» 가 성립한다. 폴백과 무관하게 매번 찍히면
    그 문장이 거짓이 되고, 위 테스트는 그것을 못 가른다.
    """
    with caplog.at_level("INFO", logger="app.parsing"):
        parsing.parse_upload(DOC_REL, product_type="ELS", document_id="doc-given")

    assert not [r for r in caplog.records if "파일명에서 만들었다" in r.getMessage()], \
        "호출자가 값을 줬는데 폴백 줄이 나왔다"
