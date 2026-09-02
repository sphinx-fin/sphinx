"""측정 무효가 상류 장애와 **다른 코드로** 나간다 (이슈 #280 ③).

## 왜 본문에 코드를 싣나

Spring 계약에서 두 실패가 **둘 다 502** 다(`contracts/openapi.yaml` `ApiError.code`).

    MEASUREMENT_INVALID     502   모델이 낸 측정값이 우리 검증을 통과 못 했다
    AI_SERVICE_UNAVAILABLE  502   상류가 죽었다

상태 코드로는 못 가른다. 502 를 다른 값으로 바꾸는 것은 **더 틀린다** — 이 실패는 진짜로
상류(우리) 문제이고 요청은 정상이었다. 그래서 본문에 기계가 읽을 코드를 싣는다.

## 지금은 Spring 이 안 읽는다 — 그래도 먼저 나간다

`AiServiceClient` 는 상태 코드만 보고 `AiServiceException` 을 던진다(강희진 영역).
받는 배선이 붙기 전까지 동작은 지금과 같고, **깨지는 것이 없다.** 여기서 내보내는 것까지가
내 몫이라 먼저 잠근다.
"""
from __future__ import annotations

from typing import Any

import pytest
from fastapi.testclient import TestClient

from app import scoring
from app.llm_client import LlmClient, LlmError
from app.main import app
from app.routes import MEASUREMENT_INVALID_CODE
from app.schemas import Judgment

from helpers import make_judgment  # noqa: E402

client = TestClient(app)

RISK_ITEM = {
    "item_id": "ELS-PRINCIPAL-LOSS-WARNING",
    "product_id": "mock-els-001",
    "name": "원금손실 가능성 고지",
    "importance": "required",
    "status": "extracted",
    "condition": {
        "value_text": "투자원금의 손실이 발생할 수 있습니다",
        "source_span": {"page": 3, "start": 0, "end": 22},
    },
}

BODY = {
    "item_id": RISK_ITEM["item_id"],
    "question": "원금이 어떻게 되는지 말씀해 주시겠어요?",
    "answer_text": "은행에서 파는 거니까 원금은 지켜지는 거죠?",
    "risk_item": RISK_ITEM,
    "product_type": "ELS",
}


class _AlwaysFabricates(LlmClient):
    """근거 인용을 지어낸다 — 재판정해도 같다. 실제 실패 모양 그대로다."""

    def __init__(self) -> None:  # super().__init__ 호출하지 않는다
        self.calls = 0

    def complete_json(self, **_kwargs: Any) -> Judgment:
        self.calls += 1
        return make_judgment(quote="이 발화에 없는 문장이다")


class _Outage(LlmClient):
    """상류가 죽은 경우."""

    def __init__(self) -> None:
        pass

    def complete_json(self, **_kwargs: Any) -> Judgment:
        raise LlmError("LLM 호출 실패: Connection refused")


def _post(llm: LlmClient):
    original = scoring.default_client
    scoring.default_client = lambda: llm            # type: ignore[assignment]
    try:
        return client.post("/internal/score", json=BODY)
    finally:
        scoring.default_client = original           # type: ignore[assignment]


def test_measurement_invalid_carries_a_machine_readable_code() -> None:
    """★ 본문에 코드가 실린다. 상태 코드는 502 그대로다."""
    resp = _post(_AlwaysFabricates())
    assert resp.status_code == 502, resp.text
    detail = resp.json()["detail"]
    assert isinstance(detail, dict), f"코드를 실을 자리가 없다: {detail!r}"
    assert detail["code"] == MEASUREMENT_INVALID_CODE
    assert "재판정" in detail["message"], detail["message"]


def test_the_code_matches_the_spring_contract() -> None:
    """문자열을 여기서 지어내지 않는다 — 계약의 `ApiError.code` 와 같아야 한다.

    다른 값을 내보내면 Spring 이 그 코드를 모르고, 프론트는 계약에 없는 코드를 받는다.
    `ErrorCodeContractTest` 가 서버 쪽 세 벌을 대조하는데 **우리는 그 대조에 없다.**
    """
    from pathlib import Path

    import yaml

    contract = Path(__file__).resolve().parents[2] / "contracts" / "openapi.yaml"
    spec = yaml.safe_load(contract.read_text(encoding="utf-8"))
    codes = spec["components"]["schemas"]["ApiError"]["properties"]["code"]["enum"]
    assert MEASUREMENT_INVALID_CODE in codes, (
        f"계약에 없는 코드를 내보낸다: {MEASUREMENT_INVALID_CODE} — {codes}"
    )


def test_measurement_invalid_is_not_swallowed_by_the_generic_handler() -> None:
    """★ `MeasurementInvalid` 는 `LlmError` 의 부분집합이다 — 잡는 순서가 뒤집히면 삼켜진다.

    삼켜져도 502 는 그대로라 **응답 코드만 보는 테스트로는 안 잡힌다.** 그래서 상류 장애와
    나란히 두고 **둘이 다르다는 것**을 잰다.
    """
    assert issubclass(scoring.MeasurementInvalid, LlmError), "전제가 바뀌었다"

    invalid = _post(_AlwaysFabricates()).json()["detail"]
    outage = _post(_Outage()).json()["detail"]

    assert isinstance(invalid, dict) and isinstance(outage, str), (
        f"둘이 같은 모양으로 나간다 — 상류가 구분할 수 없다: {invalid!r} / {outage!r}"
    )


def test_outage_still_looks_like_it_always_did() -> None:
    """상류 장애 경로는 안 건드린다 — 문자열 detail 그대로다.

    내부 오류 응답 형식은 아직 계약에 없다(결정 10.40). 전부 구조화하는 것은 그 결정이
    난 뒤에 한 번에 한다. 지금 한 자리만 구조화한 것은 **다른 방법이 없어서**다.
    """
    resp = _post(_Outage())
    assert resp.status_code == 502
    assert "Connection refused" in resp.json()["detail"]


def test_it_retried_before_giving_up() -> None:
    """포기하기 전에 재판정을 거친다 — 한 번 만에 502 를 내면 `#282` 가 무의미하다."""
    llm = _AlwaysFabricates()
    _post(llm)
    assert llm.calls == scoring.MAX_SCORING_ATTEMPTS, llm.calls
