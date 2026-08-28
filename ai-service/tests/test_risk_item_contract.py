"""risk_item 모델이 계약대로 오는 요청을 받는가 (이슈 #165).

서버가 실제로 보내는 두 모양을 그대로 넣어 본다. `Strict` 가 `extra="forbid"` 라
계약에 있는 필드가 모델에 없으면 **정상 요청이 422 로 거절된다** — 그리고 그 사실은
두 모듈을 같이 띄우기 전까지 안 드러난다.

실제로 그랬다. `failure_reason` 이 모델에 없어서 `/internal/question`·`/internal/score`·
`/internal/reexplain` 이 전부 422 였고, 인터뷰가 첫 화면에서 죽었다. 그때 **양쪽 테스트가
전부 초록**이었다 — 서버는 요청 본문을 jsonPath 로 몇 개만 집었고, 여기서는 `RiskItem` 을
파이썬에서 직접 만들었다(`failure_reason` 은 레포 전체에서 한 번도 안 쓰였다).

그래서 이 파일은 **서버가 보내는 모양**으로 짠다. 파이썬에서 편한 모양으로 만들면
같은 구멍이 다시 생긴다.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.schemas import RiskItem

CONTRACT = Path(__file__).resolve().parents[2] / "contracts" / "risk_item.schema.json"

# Jackson 은 null 도 생략하지 않는다 — 서버가 보내는 그대로다.
EXTRACTED = {
    "item_id": "ELS-PRINCIPAL-LOSS-WARNING",
    "product_id": "mock-els-001",
    "name": "원금손실 조건",
    "importance": "required",
    "condition": {
        "value_text": "만기평가일에 …(원문 인용)",
        "source_span": {"page": 3, "start": 120, "end": 210},
    },
    "status": "extracted",
    "failure_reason": None,
}

FAILED = {
    "item_id": "ELS-BROKEN",
    "product_id": "mock-els-001",
    "name": "파싱 실패 항목",
    "importance": "required",
    "condition": None,
    "status": "extraction_failed",
    "failure_reason": "표 구조를 못 읽었다",
}


@pytest.mark.parametrize("body", [EXTRACTED, FAILED], ids=["extracted", "extraction_failed"])
def test_server_shapes_are_accepted(body: dict) -> None:
    """서버가 보내는 두 모양이 그대로 통과한다."""
    item = RiskItem(**body)
    assert item.item_id == body["item_id"]
    assert item.failure_reason == body["failure_reason"]


def test_extraction_failed_carries_its_reason() -> None:
    """❗실패 항목이 사유를 들고 온다 — E-EXT-03 이 지키려는 것이 이 경로다.

    failure_reason 만 고치고 condition 을 Optional 로 안 두면 정상 항목은 통과하는데
    **실패 항목은 여전히 막힌다.** 그러면 "실패를 은폐하지 않는다" 가 성립하지 않는다 —
    실패한 항목이 아예 시스템을 통과 못 하기 때문이다.
    """
    item = RiskItem(**FAILED)
    assert item.condition is None
    assert item.failure_reason == "표 구조를 못 읽었다"


def test_model_fields_match_the_contract() -> None:
    """모델 필드 집합이 계약의 properties 와 같다.

    이슈 #165 2항. 키 하나가 어긋나면 `extra="forbid"` 아래에서 정상 요청이 422 가 되고,
    반대로 계약에 있는데 모델에 없으면 그 값이 조용히 버려진다.

    required 도 같이 본다 — `failure_reason` 만 맞추고 `condition` 의 optional 여부가
    어긋나면 추출 실패 경로만 막히는데, 그건 위 테스트가 없으면 안 보인다.
    """
    schema = json.loads(CONTRACT.read_text(encoding="utf-8"))
    props = set(schema["properties"])
    required = set(schema["required"])
    assert props, "계약에서 properties 를 못 읽었다 — 양쪽이 비면 집합이 같아져 조용히 통과한다"

    assert set(RiskItem.model_fields) == props

    optional_in_contract = props - required
    optional_in_model = {
        name for name, f in RiskItem.model_fields.items() if not f.is_required()
    }
    assert optional_in_model == optional_in_contract, (
        "계약에서 required 가 아닌 필드는 모델에서도 optional 이어야 한다. "
        "condition 이 그렇다 — status=extraction_failed 면 null 로 온다"
    )
