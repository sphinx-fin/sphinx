"""파싱 거부 → HTTP 상태의 매핑. 소유: 윤지석 (`#548` 리뷰 ⑤)

`test_parse_route.py`(정세현)가 **어느 입력이 어느 거부를 내는가**를 잰다. 이 파일이
잠그는 것은 그 다음 칸이다 — **거부가 늘어도 HTTP 동작이 안 바뀌는 것.**

## 왜 별 파일인가

`#548` 이 넷째 갈래(`DocumentAccessDenied`)를 만들었다. 타입도 늘고 테스트도 늘고 전건이
초록이었는데 **HTTP 는 하나도 안 바뀌었다** — 라우트가 하위를 손으로 열거하고 있어서 새
하위는 아무 절에도 안 걸리고 500 으로 나갔다. 그 500 을 Spring 이 502
`AI_SERVICE_UNAVAILABLE` 로 뭉치므로 **그 PR 이 없애려던 「ai-service 장애」 오진이 그대로
남는다.** 그 사실이 어느 대조에도 안 걸렸다는 것이 이 파일이 있는 이유다.
"""
from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

from app import parsing, routes
from app.main import app

client = TestClient(app)


def _parse_raising(monkeypatch, exc: Exception):
    """`parse_upload` 가 그 거부를 낸 것으로 두고 라우트만 잰다.

    입력으로 각 거부를 만들어 내는 것은 `test_parse_route.py` 의 몫이다. 여기서 그것을
    다시 하면 **같은 사실이 두 벌**이 되고, 입력 조건이 바뀔 때 두 파일이 갈린다.
    """
    def boom(*_a, **_k):
        raise exc

    monkeypatch.setattr(parsing, "parse_upload", boom)
    return client.post("/internal/parse",
                       json={"document_path": "documents/a.pdf", "product_type": "ELS"})


# ── 표가 곧 동작이다 ──────────────────────────────────────────────────────────
@pytest.mark.parametrize("refusal", sorted(routes._REFUSAL_RESPONSE, key=lambda c: c.__name__))
def test_every_mapped_refusal_gets_its_declared_status(refusal, monkeypatch) -> None:
    """★ **표에 적힌 코드가 실제로 나간다.**

    표와 동작을 따로 재면 표가 장식이 된다 — 값을 틀리게 적어도 아무도 안 문다.
    여기서는 표를 읽어 기대값을 만들므로 **틀린 항목이 곧 빨강**이다.
    """
    expected, error_code = routes._REFUSAL_RESPONSE[refusal]

    res = _parse_raising(monkeypatch, refusal("(테스트) 거부"))

    assert res.status_code == expected, res.text
    detail = res.json()["detail"]
    if error_code is None:
        assert isinstance(detail, str), f"문자열 detail 이어야 한다: {detail!r}"
        assert "(테스트) 거부" in detail, "사유가 안 실렸다 — 고칠 자리를 못 가리킨다"
    else:
        assert detail == {"code": error_code, "message": "(테스트) 거부"}, detail


def test_every_refusal_is_distinguishable_downstream() -> None:
    """★ 두 거부가 **소비자에게 같은 값**으로 보이면 `AiServiceClient` 가 못 가른다.

    `ParseRefused` docstring 이 *"고치는 자리가 전부 다르다"* 로 그 이유를 적어 뒀다.
    개수만 세면 둘이 같아져도 통과하므로 **집합 크기**로 본다.

    ## ❗상태가 아니라 **(상태, 본문 코드) 쌍**으로 본다

    처음엔 상태 코드만 서로 다른지 봤는데, 그 단정이 **이 파일이 세운 규약과 어긋났다**
    (`#551` 리뷰 · 강희진·정세현이 각자 짚었다). 규약은 *"상태로 못 가르면 본문에 코드를
    싣는다"* 인데, 상태만 보는 대조는 **그 규약대로 만든 다섯째를 막는다.**

        DocumentAccessDenied  (502, "DOCUMENT_ACCESS_DENIED")   볼륨 소유권
        DocumentMountMissing  (502, "DOCUMENT_MOUNT_MISSING")   마운트 선언  ← 정당한데 빨강이었다

    강희진이 그 다섯째를 실제로 만들어 재현했고, **가상이 아니다** — uid 10001 소유권과
    «볼륨이 아예 안 붙었다»(`#37` 계열)는 고치는 자리가 다르다.

    쌍으로 보면 «본문 코드 없는 502 가 둘» 은 여전히 빨강이다(그건 진짜로 못 가르는
    상태다). 상태 코드 공간이 갈래보다 좁아지는 순간이 온다는 그쪽 진단 그대로다.
    """
    pairs = list(routes._REFUSAL_RESPONSE.values())
    assert len(set(pairs)) == len(pairs), (
        f"(상태, 본문 코드) 쌍이 겹친다 — 소비자가 두 거부를 못 가른다: {pairs}"
    )


def test_permission_denied_does_not_go_out_as_a_document_problem(monkeypatch) -> None:
    """★ **422 로 내면 오진이 자리를 옮겨 돌아온다.**

    이 라우트에서 422 는 *"문서가 문제다"* 를 뜻하고, `AiServiceClient.parseFailure()` 가
    그 코드에 «암호화·손상 PDF 인지 확인하라» 는 운영자 문면을 붙인다. 권한 사고는 문서도
    요청도 정상이므로 그 문면이 거짓이고, 운영자는 파일을 다시 올리다 시간을 쓴다.
    """
    res = _parse_raising(monkeypatch, parsing.DocumentAccessDenied("권한이 없다 — uid 10001"))

    assert res.status_code != 422, "권한 거부가 「문서가 안 열린다」로 나갔다"
    assert res.status_code >= 500, "요청도 문서도 정상이다 — 4xx 는 거짓이다"
    assert res.json()["detail"]["code"] == "DOCUMENT_ACCESS_DENIED"


# ── 하위를 늘리고 매핑을 안 하면 기동에서 죽는다 ────────────────────────────────
def test_the_startup_check_actually_fires() -> None:
    """★ **검사가 도는지 잰다.** 안 재면 `raise` 를 지워도 전부 초록이다.

    실물 계층을 건드리지 않는다 — `__subclasses__()` 는 약한 참조라 테스트가 만든 하위의
    수거 시점이 실행 순서에 달려 있고, 그러면 **다른 테스트가 이 테스트 때문에 갈린다.**
    그래서 검사에 뿌리와 표를 주입한다.
    """
    class _Root(Exception):
        __module__ = "app.fake"

    class _Unmapped(_Root):
        __module__ = "app.fake"

    with pytest.raises(RuntimeError) as caught:
        routes._assert_every_refusal_is_mapped(root=_Root, mapping={})

    assert "_Unmapped" in str(caught.value), str(caught.value)
    assert "_REFUSAL_RESPONSE" in str(caught.value), "어디에 무엇을 더하라는지가 없다"


def test_the_startup_check_looks_deeper_than_one_level() -> None:
    """★ 하위의 하위도 본다 — `__subclasses__()` 는 직계만 준다.

    한 겹만 보는 검사는 손자를 놓치고, 놓친 타입이 조용히 500 으로 나간다.
    """
    class _Root(Exception):
        __module__ = "app.fake"

    class _Child(_Root):
        __module__ = "app.fake"

    class _GrandChild(_Child):
        __module__ = "app.fake"

    found = {c.__name__ for c in routes._refusal_subclasses(_Root)}
    assert found == {"_Child", "_GrandChild"}, found

    with pytest.raises(RuntimeError) as caught:
        routes._assert_every_refusal_is_mapped(root=_Root, mapping={_Child: (400, None)})
    assert "_GrandChild" in str(caught.value)


def test_the_real_hierarchy_is_fully_mapped() -> None:
    """★ 실물에도 걸어 둔다 — 기동 검사가 사라져도 여기서 잡는다.

    ❗**`app.` 안에서 선언된 것만** 계약이다. 다른 테스트가 만든 임시 하위가 아직 수거되지
    않았을 수 있고, 그것까지 요구하면 이 대조가 **실행 순서에 따라 갈린다.**

    ❗**기동 검사와 같은 한계를 공유한다** (`#551` 리뷰 1, 정세현). 수집 시점에 로드된
    것만 훑으므로, 늦게 로드되는 `app.` 모듈에 거부가 생기면 **실행 순서에 따라 걸리기도
    하고 안 걸리기도 한다.** 지금 거부는 전부 `parsing.py` 안이라 그 경우가 없다 —
    조건이 바뀌면 이 단정도 같이 본다.
    """
    unmapped = sorted(cls.__name__ for cls in routes._refusal_subclasses()
                      if cls.__module__.startswith("app.")
                      and cls not in routes._REFUSAL_RESPONSE)
    assert not unmapped, f"매핑이 없는 거부: {unmapped}"


def test_a_subclass_of_a_mapped_refusal_uses_the_nearest_ancestor() -> None:
    """표에 자기 항목이 없으면 가장 가까운 조상의 매핑을 쓴다.

    기동 검사가 그 경우를 이미 막지만, 여기서도 **삼키지 않는 것**을 확인한다 —
    `_refused` 가 조용히 500 을 만들면 이 층이 있는 이유가 없어진다.
    """
    class _Narrower(parsing.DocumentUnreadable):
        __module__ = "tests.fake"          # 실물 계약 밖 — 위 대조가 이걸 요구하지 않는다

    got = routes._refused(_Narrower("좁힌 사유"))
    assert got.status_code == routes._REFUSAL_RESPONSE[parsing.DocumentUnreadable][0]


# ── 다섯째 갈래: 같은 400 인데 고칠 자리가 다르다 (이슈 #598) ─────────────────────
def test_the_manual_type_mismatch_is_distinguishable_from_a_wiring_bug(tmp_path, monkeypatch) -> None:
    """★ **실물로 가른다** — 표 대조가 아니라 실제 요청 둘을 나란히 세운다.

    `DocumentPathRejected` 가 나는 자리가 다섯인데 넷은 **부르는 쪽 배선**이고 다섯째는
    **운영자가 놓은 파일의 내용**이다(`#441` 의 수동 파스 출력). 상태가 같아서 서버가
    구별할 재료가 없었고, code 없는 400 이 502 `AI_SERVICE_UNAVAILABLE` 로 나가
    **운영자가 ai-service 를 재시작하게** 만들었다 — `#556` 이 없애려던 고리다(이슈 #598).

    ❗위 `test_every_mapped_refusal_gets_its_declared_status` 는 예외를 **주입해서** 표를
    재고, 여기는 **그 예외가 실제로 나는 상황**을 만든다. 둘이 다른 층이다 — 표를 맞게
    적어도 `_manual_override` 가 옛 타입을 계속 던지면 그 대조는 초록이다.
    """
    docs = tmp_path / "documents"
    docs.mkdir()
    (docs / "x.pdf").write_bytes(b"%PDF-1.4 (broken on purpose)")
    (docs / "x.json").write_text(json.dumps({
        "product_type": "VARIABLE_INSURANCE",     # 요청은 ELS 로 보낸다
        "parser_version": "manual-1",
        "pages": [{"page": 1, "text": "만기평가일에 최초기준가격의 65% 미만이면 손실이 발생한다.",
                   "char_count": 33}],
        "tables": [],
        "parse_warnings": [],
    }, ensure_ascii=False), encoding="utf-8")
    # ❗`SPHINX_DATA_DIR` 을 setenv 해도 안 먹는다 — `settings()` 가 캐시라 import 시점에
    #   이미 채워져 있다. 라우트 테스트가 쓰는 방식(`test_parse_route.py`)을 따른다.
    monkeypatch.setattr(parsing, "documents_root", lambda: tmp_path)

    mismatch = client.post("/internal/parse",
                           json={"document_path": "documents/x.pdf", "product_type": "ELS"})
    wiring = client.post("/internal/parse",
                         json={"document_path": "../etc/passwd", "product_type": "ELS"})

    assert mismatch.status_code == wiring.status_code == 400, "둘 다 400 이라는 것이 이 이슈의 전제다"
    assert mismatch.json()["detail"] == {
        "code": "MANUAL_PARSE_TYPE_MISMATCH",
        "message": mismatch.json()["detail"]["message"],
    }, mismatch.text
    assert isinstance(wiring.json()["detail"], str), (
        "배선 버그 넷은 코드 없는 문자열 그대로다 — 그 갈래까지 구조화하면 계약이 넓어진다")

    # ❗문면이 **어느 파일**인지 말한다 — 「요청이 잘못됐다」로 읽히면 고칠 자리가 틀린다.
    assert "x.json" in mismatch.json()["detail"]["message"], (
        "어느 파일을 고쳐야 하는지가 없으면 운영자가 요청 쪽을 뒤진다")


def test_the_mismatch_still_answers_to_the_old_except_clause() -> None:
    """❗**옛 호출자가 그대로 잡는다** — 갈래를 늘리는 변경이 조용히 500 을 만들지 않는다.

    `ManualParseTypeMismatch` 를 `DocumentPathRejected` 의 하위로 둔 이유가 이것이다.
    `except DocumentPathRejected` 로 잡던 자리가 계속 잡고, `_refused()` 의 `__mro__`
    훑기도 자기 항목이 없으면 부모로 떨어진다(`#551` 이 세운 그 규약).
    """
    assert issubclass(parsing.ManualParseTypeMismatch, parsing.DocumentPathRejected)
    assert issubclass(parsing.ManualParseTypeMismatch, parsing.ParseRefused)

