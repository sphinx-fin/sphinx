"""0단계 골격 스모크 테스트. 소유: 윤지석

여기서 검증하는 것은 기능이 아니라 **골격의 계약**이다:
  - 6개 엔드포인트가 존재하고, 미구현은 501로 구분된다 (강희진이 붙일 때 필요)
  - PII 방어선이 실제로 요청을 거부한다 (P3)
  - 황색 강등 규칙이 U4를 건드리지 않는다 (P5)
"""
from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)

RISK_ITEM = {
    "item_id": "ELS-PRINCIPAL-LOSS-WARNING",
    "product_id": "mock-els-001",
    "name": "원금손실 조건",
    "importance": "required",
    "condition": {
        "value_text": "만기평가일에 기초자산 중 하나라도 최초기준가격의 50% 미만인 경우 …(원문 인용)",
        "source_span": {"page": 3, "start": 120, "end": 210},
    },
    "status": "extracted",
}



# ── 골격 ──────────────────────────────────────────────────────────────────────
def test_healthz_never_leaks_the_key():
    body = client.get("/healthz").json()
    assert body["status"] == "ok"
    assert "llm_api_key" not in body
    assert isinstance(body["llm_configured"], bool)


def test_all_six_internal_endpoints_are_registered():
    """AiServiceClient가 호출하는 6개. openapi 스키마 기준으로 본다 — 강희진이 읽는 표면."""
    paths = client.get("/openapi.json").json()["paths"]
    for name in ("parse", "extract", "question", "score", "misconception", "reexplain"):
        assert f"/internal/{name}" in paths, f"누락: /internal/{name}"
        assert "post" in paths[f"/internal/{name}"]


def test_unimplemented_features_return_501_not_500(monkeypatch):
    """연동하는 쪽이 '아직 없음'과 '터짐'을 구분해야 한다.

    특정 엔드포인트를 예시로 쓰면 그 기능이 구현될 때마다 테스트가 낡는다(extract →
    question 으로 두 번 옮겼다). 매핑 자체를 검증한다.
    """
    from app import routes

    def _unimplemented(*_a, **_k):
        raise NotImplementedError

    monkeypatch.setattr(routes.reexplain, "reexplain", _unimplemented)
    resp = client.post("/internal/reexplain", json={
        "risk_item": RISK_ITEM,
        "judgment": {
            "item_id": "ELS-PRINCIPAL-LOSS-WARNING", "grade": "U4", "confidence": 0.9,
            "evidence": {"utterance_quote": "원금은 지켜지는", "rubric_clause": "원금이 보장된다"},
            "reason": "오해",
        },
    })
    assert resp.status_code == 501, resp.text


def test_extract_rejects_document_without_pages():
    """pages는 스팬이 가리키는 대상이다 — 없으면 추출 자체가 성립하지 않는다."""
    resp = client.post("/internal/extract", json={
        "product_id": "mock-els-001",
        "parsed_document": {"document_id": "d", "product_type": "ELS",
                            "parser_version": "manual-0", "pages": []},
    })
    assert resp.status_code == 422


def test_llm_not_configured_maps_to_503(monkeypatch):
    """키가 없는 상태는 우리 버그가 아니다. 502(호출 실패)와도 구분한다.

    실제 .env 유무에 결과가 달라지면 안 되므로 매핑만 검증한다."""
    from app import routes
    from app.llm_client import LlmError, LlmNotConfigured

    def _raise(*_a, **_k):
        raise LlmNotConfigured("LLM_API_KEY 미설정")

    monkeypatch.setattr(routes.scoring, "score", _raise)
    body = {"item_id": "ELS-PRINCIPAL-LOSS-WARNING", "question": "q",
            "answer_text": "은행에서 파는 거니까 원금은 지켜지는 거죠", "risk_item": RISK_ITEM}
    assert client.post("/internal/score", json=body).status_code == 503

    def _fail(*_a, **_k):
        raise LlmError("upstream 5xx")

    monkeypatch.setattr(routes.scoring, "score", _fail)
    assert client.post("/internal/score", json=body).status_code == 502


def test_score_with_unknown_item_is_422():
    resp = client.post("/internal/score", json={
        "item_id": "NO-SUCH-ITEM",
        "question": "질문",
        "answer_text": "답변",
        "risk_item": RISK_ITEM,
    })
    assert resp.status_code == 422
    assert "루브릭 없음" in resp.json()["detail"]


def test_parse_is_no_longer_a_stub():
    """F-EXT-001 이 붙었다 (이슈 #401 의 1번). 계약 전체는 `test_parse_route.py`(정세현).

    골격 관점에서 잠그는 것은 하나다 — 이 엔드포인트가 더 이상 501 로 답하지 않는다.
    501 이 돌아오면 실문서 흐름(업로드→파스→추출→세션)이 첫 칸에서 다시 막힌 것이다.

    경로는 뿌리(`SPHINX_DATA_DIR`) 기준 상대경로이고, 없는 파일이라 404 가 정상이다.
    """
    resp = client.post("/internal/parse", json={"document_path": "documents/없는문서.pdf"})
    assert resp.status_code != 501, resp.text


# ── P3 방어선 ─────────────────────────────────────────────────────────────────
def test_pii_in_answer_is_rejected_not_masked():
    resp = client.post("/internal/score", json={
        "item_id": "ELS-PRINCIPAL-LOSS-WARNING",
        "question": "확인 부탁드립니다",
        "answer_text": "제 번호는 010-1234-5678 입니다",
        "risk_item": RISK_ITEM,
    })
    assert resp.status_code == 422
    assert resp.json()["error"] == "pii_detected"
    assert "PHONE" in resp.json()["kinds"]


def test_pii_is_caught_in_any_nested_field():
    """필드를 하나 깜빡해도 새지 않아야 한다."""
    resp = client.post("/internal/misconception", json={
        "text": "주민번호 900101-1234567 로 가입했어요",
    })
    assert resp.status_code == 422
    assert "RRN" in resp.json()["kinds"]


def test_masked_placeholders_pass_through():
    """PiiGateway가 치환한 [PHONE] 같은 자리표시자는 막지 않는다."""
    resp = client.post("/internal/misconception", json={
        "text": "연락처는 [PHONE] 이고 원금은 지켜지는 거죠",
    })
    assert resp.status_code == 200  # PII 통과 → 실제 매칭 도달
    assert resp.json()["matches"][0]["type_id"] == "M01-PRINCIPAL-GUARANTEE"


# ── PII 검사 범위가 경로별로 맞는지 (공시 문서 vs 고객 텍스트) ──────────────────
def test_only_document_paths_relax_broad_pii_heuristics():
    """고객 텍스트가 오는 경로는 하나도 완화되면 안 된다.

    넓은 휴리스틱을 끄는 것은 공시 상품문서에만 해당한다(기획서 7-3). 목록이 잘못 늘어나면
    고객 발화의 계좌번호·카드번호가 조용히 통과한다.
    """
    from app.main import PiiGuardMiddleware

    relaxed = set(PiiGuardMiddleware.PUBLIC_DOCUMENT_PATHS)
    assert relaxed == {"/internal/parse", "/internal/extract", "/internal/rubric/propose"}

    spec = client.get("/openapi.json").json()
    paths = set(spec["paths"])
    customer_paths = {p for p in paths if p.startswith("/internal/")} - relaxed
    assert customer_paths == {
        "/internal/question", "/internal/score",
        "/internal/misconception", "/internal/mismatch", "/internal/reexplain",
    }

    # ❗**왜 안전한지를 목록이 아니라 스키마에서 유도한다** (이슈 #474 ①).
    #
    # 위 두 단정은 트립와이어다 — 목록이 늘면 사람이 멈춰서 생각하게 만든다. 그런데
    # 「생각했다」와 「안전하다」는 다르다. 완화된 경로가 안전한 조건은 하나다:
    # **본문에 고객 텍스트를 받는 필드가 없다.** 그것을 요청 스키마에서 직접 잰다.
    #
    # 이 단정이 없으면, 고객 발화를 받는 경로를 목록에 넣어도 위 두 줄만 고치면 통과한다.
    # ❗**최상위 필드명만 본다** — `parsed_document.pages[].text` 같은 중첩은 안 본다
    # (`#476` 리뷰, 정세현). 지금은 그게 맞다: 완화 목록에 있는 경로는 전부 **공시 문서
    # 본문**을 받고, 그 본문에 표 수치가 붙어 13자리로 읽히는 것이 완화의 이유다
    # (기획서 7-3). 중첩까지 훑으면 `text` 라는 이름 하나로 그 설계와 충돌한다.
    #
    # 바뀌는 조건은 하나다 — **완화 경로가 고객 발화를 중첩으로 받게 되는 날.** 그때는
    # 이 검사가 조용히 통과하므로, 아래 목록이 아니라 **어디를 훑는가**를 고쳐야 한다.
    CUSTOMER_TEXT_FIELDS = {"answer_text", "text", "utterances", "question"}
    schemas = spec["components"]["schemas"]
    for path in sorted(relaxed):
        body = spec["paths"][path]["post"].get("requestBody")
        if body is None:
            continue
        ref = body["content"]["application/json"]["schema"]["$ref"].rsplit("/", 1)[-1]
        fields = set(schemas[ref].get("properties", {}))
        leaked = fields & CUSTOMER_TEXT_FIELDS
        assert not leaked, (
            f"{path} 는 넓은 PII 휴리스틱이 꺼진 경로인데 고객 텍스트 필드 {sorted(leaked)} 를 "
            "받는다 — 그 경로로 온 발화의 계좌·카드번호가 조용히 통과한다 (기획서 7-3)"
        )
    assert len(relaxed) >= 3, "완화 목록이 비면 위 루프가 0회 돌고 조용히 통과한다"


def test_rrn_is_blocked_even_on_document_paths():
    """공시 문서에 주민번호가 있으면 그건 문서 쪽 사고다 — 좁은 패턴은 어느 범위에서도 검사한다."""
    resp = client.post("/internal/extract", json={
        "product_id": "p", "parsed_document": {
            "document_id": "d", "product_type": "ELS", "parser_version": "x",
            "pages": [{"page": 1, "text": "가입자 900101-1234567 기재"}]},
    })
    assert resp.status_code == 422
    assert "RRN" in resp.json()["kinds"]


def test_corporate_phone_in_document_is_not_blocked(monkeypatch):
    """발행사 민원부서 번호(02-785-7424)가 ACCOUNT 패턴에 걸려 추출이 멈췄던 실측 사례.

    검증 대상은 미들웨어이므로 핸들러를 대체한다 — 통과시키면 실제 LLM 을 부르고
    테스트가 네트워크에 의존한다(처음 작성했을 때 65초가 걸렸다).
    """
    from app import routes
    from app.schemas import ExtractResponse

    monkeypatch.setattr(routes.extraction, "extract",
                        lambda *a, **k: ExtractResponse(items=[], warnings=[]))
    resp = client.post("/internal/extract", json={
        "product_id": "p", "parsed_document": {
            "document_id": "d", "product_type": "ELS", "parser_version": "x",
            "pages": [{"page": 1, "text": "민원부서(02-785-7424) 또는 홈페이지로 문의"}]},
    })
    assert resp.status_code == 200, "공시 문서의 법인 연락처를 막으면 정상 문서가 거부된다"


def test_no_merge_conflict_markers_are_committed():
    """★ 실제로 한 번 통과했다 — docstring 안에 남은 마커는 파서도 테스트도 안 잡는다.

    #60 이 squash 로 머지되면서 스택 하위 브랜치에 `add/add` 충돌이 났고, `question_gen.py`
    의 `answer_fragments` **docstring 안**에 마커가 남은 채 푸시됐다. 문자열 리터럴이므로
    `SyntaxError` 가 없고 198건이 전부 초록이었다.

    조용한 실패라 로딩 시점으로 끌어올릴 수도 없다 — 문법상 정상인 문자열이다. 그래서
    파일을 훑는 검사로 둔다.
    """
    from pathlib import Path

    root = Path(__file__).resolve().parents[1]
    markers = ("<<<<<<< ", ">>>>>>> ")
    hits = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or ".venv" in path.parts or "__pycache__" in path.parts:
            continue
        if path.suffix not in {".py", ".md", ".yaml", ".yml", ".json", ".txt"}:
            continue
        # 이 테스트 자신은 마커 문자열을 데이터로 들고 있다
        if path.resolve() == Path(__file__).resolve():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for lineno, line in enumerate(text.split("\n"), 1):
            if line.startswith(markers) or line == "=======":
                hits.append(f"{path.relative_to(root)}:{lineno}")
    assert not hits, f"머지 충돌 마커가 남았다: {hits}"


def test_no_duplicate_test_names():
    """★ 같은 파일에 같은 이름의 테스트가 둘이면 **앞의 것이 조용히 안 돈다.**

    실제로 그랬다 — `test_scoring.py` 에서 슬라이스 편집이 블록을 복제해
    `test_capped_confidence_records_the_reason` 이 두 번 정의됐고, pytest 는 뒤의 것만
    수집한다. 개수가 늘어나므로 초록이 오히려 늘어 보인다.

    파서를 들이지 않고 `def test_` 시작 줄만 센다 — 이 검사가 잡아야 하는 것이 그 형태다.
    """
    import re
    from collections import Counter
    from pathlib import Path

    pattern = re.compile(r"^def (test_\w+)", re.MULTILINE)
    problems = []
    for path in sorted(Path(__file__).resolve().parent.glob("test_*.py")):
        names = pattern.findall(path.read_text(encoding="utf-8"))
        dupes = [n for n, c in Counter(names).items() if c > 1]
        if dupes:
            problems.append(f"{path.name}: {sorted(dupes)}")
    assert not problems, f"중복 테스트 이름 — 앞의 것이 안 돈다: {problems}"
