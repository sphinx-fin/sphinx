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


def test_unimplemented_features_return_501_not_500():
    """강희진이 연결할 때 '아직 없음'과 '터짐'이 구분돼야 한다."""
    resp = client.post("/internal/question", json={"risk_item": RISK_ITEM})
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


def test_parse_is_owned_by_someone_else():
    resp = client.post("/internal/parse", json={"document_path": "data/documents/els-001.pdf"})
    assert resp.status_code == 501
    assert "정세현" in resp.json()["detail"]


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
    assert relaxed == {"/internal/parse", "/internal/extract"}

    paths = set(client.get("/openapi.json").json()["paths"])
    customer_paths = {p for p in paths if p.startswith("/internal/")} - relaxed
    assert customer_paths == {
        "/internal/question", "/internal/score",
        "/internal/misconception", "/internal/mismatch", "/internal/reexplain",
    }


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
