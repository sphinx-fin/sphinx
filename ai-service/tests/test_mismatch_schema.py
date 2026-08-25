"""F-DET-002 출력 스키마의 불변식 검증. 소유: 윤지석

스키마는 PR #47 로 `contracts/suitability_mismatch.schema.json` 에 승격됐다.
**이 테스트는 그 계약 파일을 직접 읽는다.** 초안 사본을 읽던 시절에는 계약이 바뀌어도
테스트가 그대로 통과하고 pydantic 미러만 조용히 갈라졌다 — 통과하는 걸 보고서야 알게 되는
종류다(ErrorCodeContractTest 가 처음 무용지물이었던 것과 같은 구조).
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.schemas import Contradiction, SuitabilityMismatch, SurveyRef

CONTRACT = (Path(__file__).resolve().parents[2] / "contracts"
            / "suitability_mismatch.schema.json")


def _contradiction(confidence: float = 0.88) -> Contradiction:
    return Contradiction(
        axis="principal_preservation",
        direction="survey_overstates_tolerance",
        survey_ref=SurveyRef(
            question_id="Q3",
            question_text="원금 손실을 감수할 수 있습니까?",
            recorded_answer="감수 가능",
        ),
        utterance_quote="원금은 지켜지는 거죠",
        reason="설문 기재와 반대되는 진술",
        confidence=confidence,
    )


def _mismatch(**overrides):
    base = dict(
        session_id="mock-session-001",
        status="evaluated",
        mismatch=True,
        confidence=0.88,
        contradictions=[_contradiction()],
        reason="설문 기재와 발화가 원금보전 축에서 모순",
        survey_schema_version="v1",
    )
    base.update(overrides)
    return SuitabilityMismatch(**base)


# ── 기획서 7-2 데모 케이스 ────────────────────────────────────────────────────
def test_demo_scenario_case_is_representable():
    """기획서 7-2 ①③ — '위험 감수 가능' 체크 + '원금은 지켜지는 거죠?'
    적색 3건 중 세 번째(설문과 발화의 모순)."""
    m = _mismatch()
    assert m.mismatch is True
    assert m.contradictions[0].direction == "survey_overstates_tolerance"
    assert m.contradictions[0].survey_ref.recorded_answer == "감수 가능"


# ── 불변식 ────────────────────────────────────────────────────────────────────
def test_mismatch_without_evidence_is_rejected():
    """P4 — 근거 없는 판정은 무효."""
    with pytest.raises(ValidationError, match="P4"):
        _mismatch(contradictions=[])


def test_insufficient_input_cannot_claim_a_mismatch():
    with pytest.raises(ValidationError):
        _mismatch(status="insufficient_input", contradictions=[])


def test_insufficient_input_cannot_carry_contradictions():
    with pytest.raises(ValidationError):
        _mismatch(status="insufficient_input", mismatch=False)


def test_insufficient_input_is_not_a_pass():
    """판정 못 한 것과 모순 없음은 다르다. 호출자가 구분할 수 있어야 한다."""
    m = SuitabilityMismatch(
        session_id="s", status="insufficient_input", mismatch=False,
        confidence=0.0, contradictions=[], reason="설문 결과가 비어 판정 불가",
    )
    assert m.status == "insufficient_input"
    assert m.mismatch is False


def test_confidence_must_equal_top_contradiction():
    with pytest.raises(ValidationError, match="최고 모순 확신도"):
        _mismatch(confidence=0.5)


def test_confidence_takes_the_maximum_of_several():
    m = _mismatch(contradictions=[_contradiction(0.4), _contradiction(0.91)], confidence=0.91)
    assert m.confidence == 0.91


def test_empty_quote_is_rejected():
    with pytest.raises(ValidationError):
        _contradiction_with_empty_quote()


def _contradiction_with_empty_quote():
    return Contradiction(
        axis="risk_tolerance", direction="survey_overstates_tolerance",
        survey_ref=SurveyRef(question_id="Q1", recorded_answer="감수 가능"),
        utterance_quote="", reason="r", confidence=0.5,
    )


def test_no_mismatch_with_no_contradictions_is_valid():
    m = _mismatch(mismatch=False, confidence=0.0, contradictions=[], reason="모순 없음")
    assert m.mismatch is False


# ── 계약 JSON Schema와의 정합 ─────────────────────────────────────────────────
def test_contract_and_pydantic_agree_on_required_fields():
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    required = set(contract["required"])
    pydantic_required = {
        name for name, f in SuitabilityMismatch.model_fields.items() if f.is_required()
    }
    # contradictions는 초안에서 required, pydantic에서는 기본값 []
    assert required - pydantic_required == {"contradictions"}
    assert pydantic_required - required == set()


def test_contract_and_pydantic_agree_on_enums():
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    c = contract["$defs"]["contradiction"]["properties"]
    schema = Contradiction.model_json_schema()
    for field in ("axis", "direction"):
        assert set(c[field]["enum"]) == set(schema["properties"][field]["enum"]), field
    assert set(contract["properties"]["status"]["enum"]) == {"evaluated", "insufficient_input"}


def test_vulnerability_weighting_is_not_in_this_schema():
    """취약 요인 가중은 강희진 소유다. 여기 새어들어오면 소유 경계가 무너진다."""
    fields = set(SuitabilityMismatch.model_fields)
    assert not fields & {"age_band", "amount_band", "experience_level",
                         "vulnerability_score", "coaching_score", "weighted_score"}


# ── 엔드포인트 (강희진 결정 ⓐ) ────────────────────────────────────────────────
def test_mismatch_endpoint_is_registered():
    """7번째 엔드포인트로 확정됐다. 세션 단위 판정이라 /score와 분리한다."""
    from fastapi.testclient import TestClient

    from app.main import app

    paths = TestClient(app).get("/openapi.json").json()["paths"]
    assert "/internal/mismatch" in paths
    assert "post" in paths["/internal/mismatch"]


def test_freeform_survey_result_is_accepted():
    """결정 ⓑ — surveyResult는 Map<String,Object> freeform이다. 정규화된 배열이 아니다."""
    from app.schemas import MismatchRequest

    req = MismatchRequest(
        session_id="s1",
        survey_result={"Q3": "감수 가능", "Q7": {"choice": 2, "label": "3년 이상"}},
        utterances=[{"item_id": "ELS-PRINCIPAL-LOSS-WARNING", "text": "원금은 지켜지는 거죠"}],
    )
    assert req.survey_result["Q3"] == "감수 가능"


def test_request_carries_no_vulnerability_factors():
    """취약 요인(연령·가입금액대·투자경험·채널)은 세션 typed 필드에서 강희진이 직접 읽는다
    (결정 ⓓ). ai-service로 나갈 이유가 없다 — P3에도 유리하다."""
    from app.schemas import MismatchRequest

    fields = set(MismatchRequest.model_fields)
    assert not fields & {"age_band", "amount_band", "experience_level", "channel"}
