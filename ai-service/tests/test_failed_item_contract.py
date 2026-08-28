"""추출 실패 항목이 계약 모양으로 나가고, 그 값을 받는 쪽이 500 을 내지 않는다.

## 두 방향을 한 파일에 두는 이유

`#185` 는 **입력**을 계약에 맞췄다(`condition` Optional · `failure_reason` 추가). 그러면
422 가 사라진 자리에 500 이 생기고, 동시에 우리 **출력**이 아직 계약 밖이라는 것이 드러났다.
같은 계약 조항(`status != "extracted"` → `condition: null`)의 양쪽이므로 같이 잠근다.

계약이 정본이다 — 두 방향 모두 `contracts/risk_item.schema.json` 을 읽어 대조한다.
문면을 여기 복사하면 계약이 바뀔 때 이 파일만 옛말을 한다.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

from app import extraction, templates
from app.llm_client import LlmClient
from app.main import app
from app.schemas import ConditionNotExtracted, ExtractionDraft, RiskItem

CONTRACT = Path(__file__).resolve().parents[2] / "contracts" / "risk_item.schema.json"
SAMPLES = Path(__file__).resolve().parents[2] / "contracts" / "samples"

client = TestClient(app)


@pytest.fixture(scope="module")
def validator() -> Draft202012Validator:
    schema = json.loads(CONTRACT.read_text(encoding="utf-8"))
    assert "allOf" in schema, (
        "계약의 조건부 제약(status → condition)이 사라졌다 — 이 파일이 잠그는 것이 그것이다"
    )
    return Draft202012Validator(schema)


class _NothingFound(LlmClient):
    """모델이 아무것도 못 찾은 경우. 템플릿 전건이 실패 항목이 된다."""

    def __init__(self) -> None:  # super().__init__ 호출하지 않는다
        pass

    def complete_json(self, **_kwargs: Any) -> ExtractionDraft:
        return ExtractionDraft(candidates=[])


def _extract_all_failed(product_type: str = "ELS", sample: str = "parsed_els_sample.json"):
    doc = json.loads((SAMPLES / sample).read_text(encoding="utf-8"))
    return extraction.extract("mock-els-001", product_type, doc, llm=_NothingFound())


# ── 출력이 계약을 지킨다 ──────────────────────────────────────────────────────
@pytest.mark.parametrize(
    "product_type,sample",
    [("ELS", "parsed_els_sample.json"),
     ("VARIABLE_INSURANCE", "parsed_variable_sample.json")],
)
def test_every_extracted_item_validates_against_the_contract(
    validator: Draft202012Validator, product_type: str, sample: str
) -> None:
    """★ 추출 출력 전건을 jsonschema 로 검증한다.

    이전에는 실패 항목이 가짜 `Condition` 을 들고 나가 **13/13 이 계약 위반**이었다.
    `#185` 의 `test_model_fields_match_the_contract` 로는 안 잡힌다 — 필드 집합과
    required/optional 만 보고 `allOf/if/then/else` 를 보지 않는다.
    """
    result = extraction.extract(
        "mock-001", product_type,
        json.loads((SAMPLES / sample).read_text(encoding="utf-8")),
        llm=_NothingFound(),
    )
    assert result.items, "항목이 없으면 아무것도 검증하지 않는다"
    for item in result.items:
        errors = sorted(validator.iter_errors(json.loads(item.model_dump_json())), key=str)
        assert not errors, f"{item.item_id}: " + " · ".join(e.message for e in errors)


def test_failed_item_has_null_condition_and_a_reason() -> None:
    """계약이 `status != "extracted"` 일 때 `condition: null` 을 강제한다."""
    result = _extract_all_failed()
    failed = [i for i in result.items if i.status == "extraction_failed"]
    assert failed, "실패 항목이 없으면 이 테스트가 아무것도 안 잰다"
    for item in failed:
        assert item.condition is None, f"{item.item_id}: 가짜 Condition 이 남았다"
        assert item.failure_reason, f"{item.item_id}: 사유가 비었다"


def test_failure_reason_comes_from_the_warnings() -> None:
    """사유를 따로 쓰지 않는다 — 같은 사실이 두 곳에 생기면 갈린다.

    항목별 경고가 이미 왜 실패했는지 말하고 있고, 원인이 더 구체적인 경고
    (`SPAN_UNRESOLVED` 등)가 있으면 그것이 앞선다.
    """
    doc = json.loads((SAMPLES / "parsed_els_sample.json").read_text(encoding="utf-8"))

    class _Unresolvable(LlmClient):
        def __init__(self) -> None:
            pass

        def complete_json(self, **_kwargs: Any) -> ExtractionDraft:
            from app.schemas import ExtractedCandidate
            return ExtractionDraft(candidates=[ExtractedCandidate(
                item_id="ELS-NO-LISTING", page=14, quote="문서에 없는 문장입니다",
            )])

    result = extraction.extract("mock-els-001", "ELS", doc, llm=_Unresolvable())
    item = next(i for i in result.items if i.item_id == "ELS-NO-LISTING")
    assert item.status == "extraction_failed"
    # 구체적인 원인이 앞선다 — 일반 문면("문서에서 찾지 못했다")만 남으면 사람이 할 일을 모른다
    assert "원문에서 찾지 못했다" in item.failure_reason, item.failure_reason


def test_failure_reason_is_capped() -> None:
    """경고를 여러 개 이어붙이면 길어진다. 전체는 `warnings` 에 남는다."""
    long_warnings = [
        extraction.ExtractionWarning(code="SPAN_UNRESOLVED", item_id="X", message="가" * 200),
        extraction.ExtractionWarning(code="LOOSE_MATCH", item_id="X", message="나" * 200),
    ]
    reason = extraction._failure_reason("X", long_warnings)
    assert len(reason) == extraction.MAX_FAILURE_REASON_CHARS
    assert reason.endswith("…")


def test_failure_reason_ignores_warnings_of_other_items() -> None:
    """`item_id` 가 없는 경고(`IMPORTANCE_PLACEHOLDER`)와 남의 경고는 사유가 아니다."""
    warnings = [
        extraction.ExtractionWarning(code="IMPORTANCE_PLACEHOLDER", message="자리표시자"),
        extraction.ExtractionWarning(code="SPAN_UNRESOLVED", item_id="OTHER", message="남의 것"),
    ]
    assert extraction._failure_reason("X", warnings) == "문서에서 해당 조건을 찾지 못했다"


# ── 입력을 받는 쪽이 500 을 내지 않는다 ───────────────────────────────────────
FAILED_ITEM = {
    "item_id": "ELS-PRINCIPAL-LOSS-WARNING",
    "product_id": "mock-els-001",
    "name": "원금손실 가능성 고지",
    "importance": "required",
    "condition": None,
    "status": "extraction_failed",
    "failure_reason": "표 구조를 못 읽었다",
}

JUDGMENT = {
    "item_id": "ELS-PRINCIPAL-LOSS-WARNING", "grade": "U3", "confidence": 0.5,
    "evidence": {"utterance_quote": "모르겠어요",
                 "rubric_clause": "투자원금의 손실이 발생할 수 있음"},
    "reason": "모른다고 답했다",
}


def test_require_condition_raises_a_dedicated_type() -> None:
    """범용 예외면 라우트가 서버 설정 오류까지 같은 코드로 뭉친다."""
    item = RiskItem(**FAILED_ITEM)
    with pytest.raises(ConditionNotExtracted) as exc:
        item.require_condition()
    assert "ELS-PRINCIPAL-LOSS-WARNING" in str(exc.value)
    assert "표 구조를 못 읽었다" in str(exc.value), "사유가 메시지에 실려야 사람이 판단한다"


@pytest.mark.parametrize(
    "path,body",
    [
        ("/internal/question", {"risk_item": FAILED_ITEM}),
        ("/internal/score", {
            "item_id": "ELS-PRINCIPAL-LOSS-WARNING",
            "question": "원금이 어떻게 되는지 말씀해 주시겠어요?",
            "answer_text": "잘 모르겠어요",
            "risk_item": FAILED_ITEM,
        }),
    ],
    ids=["question", "score"],
)
def test_extraction_failed_item_is_422_not_500(path: str, body: dict) -> None:
    """★ 계약이 허용하는 값이므로 500 이면 안 된다.

    `#185` 가 `condition` 을 Optional 로 열면서 **422 가 사라진 자리에 500 이 생겼다.**
    실측으로 세 엔드포인트 · 다섯 지점이 `AttributeError` 를 냈다.

    조용히 진행하지 않는 이유: 폴백 질문을 내주면 고객이 답을 하는데 그 답을 채점할 조건이
    없어서 다음 호출에서 막힌다 — 실패를 뒤로 미루는 것뿐이다. 추출 실패는 S-01 큐에서
    사람이 처리할 일이다(E-EXT-03).
    """
    resp = client.post(path, json=body)
    assert resp.status_code == 422, resp.text
    assert "표 구조를 못 읽었다" in resp.text, "사유가 응답에 실려야 화면이 이유를 말할 수 있다"


def test_reexplain_falls_back_instead_of_refusing() -> None:
    """재설명만 예외다 — `_minimal()` 이 이 경우를 위해 이미 설계돼 있다.

    거부하면 강희진의 재검증 루프가 진행할 것이 없다. `_minimal()` 은 `extraction_failed`
    일 때 원문 인용 형식을 쓰지 않고 `cited_spans` 를 비운다(#60 리뷰) — 그 문면은 P4·P6 을
    어기지 않으므로 내보내도 안전하다.
    """
    resp = client.post("/internal/reexplain",
                       json={"risk_item": FAILED_ITEM, "judgment": JUDGMENT})
    assert resp.status_code == 200, resp.text
    payload = resp.json()
    assert payload["cited_spans"] == [], "근거 없는 설명에 스팬을 붙이면 리포트가 거짓 근거를 갖는다"
    assert "확인하지 못했습니다" in payload["content"]
