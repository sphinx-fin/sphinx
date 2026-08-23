"""0단계 골격 스모크 테스트. 소유: 윤지석

여기서 검증하는 것은 기능이 아니라 **골격의 계약**이다:
  - 6개 엔드포인트가 존재하고, 미구현은 501로 구분된다 (강희진이 붙일 때 필요)
  - PII 방어선이 실제로 요청을 거부한다 (P3)
  - 황색 강등 규칙이 U4를 건드리지 않는다 (P5)
"""
from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app
from app.schemas import Evidence, Grade, Judgment
from app.scoring import downgrade_low_confidence

client = TestClient(app)

RISK_ITEM = {
    "item_id": "ELS-PRINCIPAL-LOSS",
    "product_id": "mock-els-001",
    "name": "원금손실 조건",
    "importance": "required",
    "condition": {
        "value_text": "만기평가일에 기초자산 중 하나라도 최초기준가격의 50% 미만인 경우 …(원문 인용)",
        "source_span": {"page": 3, "start": 120, "end": 210},
    },
    "status": "extracted",
}


def _judgment(grade: Grade, confidence: float) -> Judgment:
    return Judgment(
        item_id="ELS-PRINCIPAL-LOSS",
        grade=grade,
        confidence=confidence,
        evidence=Evidence(utterance_quote="은행에서 파는 거니까 원금은 지켜지는 거죠",
                          rubric_clause="원금손실 조건: 낙인 하회 시 손실을 인지해야 함"),
        reason="테스트",
    )


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
    resp = client.post("/internal/extract", json={
        "product_id": "mock-els-001", "parsed_document": {},
    })
    assert resp.status_code == 501, resp.text


def test_llm_not_configured_maps_to_503(monkeypatch):
    """키가 없는 상태는 우리 버그가 아니다. 502(호출 실패)와도 구분한다.

    실제 .env 유무에 결과가 달라지면 안 되므로 매핑만 검증한다."""
    from app import routes
    from app.llm_client import LlmError, LlmNotConfigured

    def _raise(*_a, **_k):
        raise LlmNotConfigured("LLM_API_KEY 미설정")

    monkeypatch.setattr(routes.scoring, "score", _raise)
    body = {"item_id": "ELS-PRINCIPAL-LOSS", "question": "q",
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
        "item_id": "ELS-PRINCIPAL-LOSS",
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


# ── P5 미탐 방지 ──────────────────────────────────────────────────────────────
def test_low_confidence_u1_is_downgraded_to_u2():
    out = downgrade_low_confidence(_judgment(Grade.U1, 0.4))
    assert out.grade is Grade.U2
    assert "강등" in out.reason  # 감사 추적


def test_low_confidence_u4_is_never_downgraded():
    """U4를 황색으로 완화하면 가장 위험한 케이스에서 미탐이 된다 (P5)."""
    out = downgrade_low_confidence(_judgment(Grade.U4, 0.4))
    assert out.grade is Grade.U4


def test_high_confidence_is_untouched():
    out = downgrade_low_confidence(_judgment(Grade.U1, 0.95))
    assert out.grade is Grade.U1
    assert out.reason == "테스트"
