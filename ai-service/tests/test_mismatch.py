"""F-DET-002 판정 로직 — 후처리 검증. 소유: 윤지석

핵심 불변식: **모순은 양쪽이 다 추적 가능해야 한다**(P4). 발화 인용은 실제 발화에서,
설문 참조는 실제 설문에서 와야 한다. 한쪽이라도 지어낸 것이면 그 모순을 버린다.
API 키 없이 돈다.
"""
from __future__ import annotations

import unicodedata
from typing import Any

import pytest

from app import mismatch
from app.llm_client import LlmClient
from app.schemas import Contradiction, SuitabilityMismatch, SurveyRef

#: 문항 키는 #44 에서 확정된 `s02-survey-v1` 세트다. `_surveySchemaVersion` 을 **일부러**
#: 넣어 둔다 — 메타키가 문항으로 새는 결함(#98 ②)의 회귀 고정이다.
SURVEY = {
    "_surveySchemaVersion": "s02-survey-v1",
    "SUIT-PRINCIPAL-LOSS": "손실이 나더라도 감수할 수 있다",
    "SUIT-HORIZON": "5년 이상 묶어둘 수 있다",
}
UTTERANCES = [{"item_id": "ELS-PRINCIPAL-LOSS-WARNING",
               "text": "원금은 절대 손해 보면 안 됩니다. 은행이라 믿고 온 거예요."}]


class FakeLlm(LlmClient):
    def __init__(self, result: SuitabilityMismatch) -> None:
        self.result = result
        self.calls: list[dict[str, Any]] = []

    def complete_json(self, **kwargs: Any) -> SuitabilityMismatch:  # type: ignore[override]
        self.calls.append(kwargs)
        return self.result


def _contradiction(
    quote: str = "원금은 절대 손해 보면 안 됩니다",
    question_id: str = "SUIT-PRINCIPAL-LOSS",
    recorded: str = "손실이 나더라도 감수할 수 있다",
    confidence: float = 0.9,
) -> Contradiction:
    return Contradiction(
        axis="principal_preservation", direction="survey_overstates_tolerance",
        survey_ref=SurveyRef(question_id=question_id, recorded_answer=recorded),
        utterance_quote=quote, reason="설문 기재와 반대되는 진술", confidence=confidence,
    )


def _llm_result(*contradictions: Contradiction) -> SuitabilityMismatch:
    top = max((c.confidence for c in contradictions), default=0.0)
    return SuitabilityMismatch(
        session_id="from-llm", status="evaluated", mismatch=bool(contradictions),
        confidence=top, contradictions=list(contradictions), reason="LLM 사유",
    )


def _detect(*contradictions: Contradiction, survey=None, utterances=None):
    llm = FakeLlm(_llm_result(*contradictions))
    result = mismatch.detect(
        "s-1", survey if survey is not None else SURVEY,
        utterances if utterances is not None else UTTERANCES,
        survey_schema_version="v1", llm=llm,
    )
    return result, llm


# ── 입력 부족: 판정 못 함과 모순 없음을 구분한다 ──────────────────────────────
def test_empty_survey_is_insufficient_not_pass():
    """mismatch=false 만 돌려주면 호출자가 '적합'으로 읽는다."""
    result, llm = _detect(_contradiction(), survey={})
    assert result.status == "insufficient_input"
    assert result.mismatch is False
    assert result.contradictions == []
    assert not llm.calls, "입력이 부족하면 LLM 을 부르지 않는다"


def test_no_utterance_is_insufficient():
    result, _ = _detect(_contradiction(), utterances=[{"item_id": "X", "text": "  "}])
    assert result.status == "insufficient_input"
    assert "발화" in result.reason


def test_too_short_utterance_is_insufficient():
    """한두 마디로 성향을 단정하면 오판이 된다."""
    result, _ = _detect(_contradiction(), utterances=[{"item_id": "X", "text": "네."}])
    assert result.status == "insufficient_input"
    assert str(mismatch.MIN_UTTERANCE_CHARS) in result.reason


# ── P4: 양쪽 추적 가능성 ──────────────────────────────────────────────────────
def test_fabricated_utterance_quote_is_dropped():
    result, _ = _detect(_contradiction(quote="고객이 원금 보전을 요구함"))
    assert result.contradictions == []
    assert result.mismatch is False
    assert result.status == "evaluated"      # 판정은 했고 모순이 확인되지 않은 것


def test_unknown_survey_question_is_dropped():
    """설문에 없는 문항을 참조하면 그 모순은 근거가 없다."""
    result, _ = _detect(_contradiction(question_id="Q99_없는문항"))
    assert result.contradictions == []


def test_mismatched_recorded_answer_is_dropped():
    """문항은 있지만 기재값을 다르게 옮긴 경우 — 근거가 원본과 어긋난다."""
    result, _ = _detect(_contradiction(recorded="원금 손실을 감수할 수 없다"))
    assert result.contradictions == []


def test_one_bad_contradiction_does_not_discard_the_others():
    """세션 단위 판정이라 전체를 버리면 실제 모순을 놓친다 — 개별로 걸러낸다."""
    good = _contradiction()
    bogus = _contradiction(quote="지어낸 인용", confidence=0.99)
    result, _ = _detect(good, bogus)
    assert [c.utterance_quote for c in result.contradictions] == [good.utterance_quote]
    assert result.mismatch is True
    assert result.confidence == good.confidence


def test_decomposed_hangul_quote_is_accepted():
    """조합형/완성형은 눈으로 같고 바이트가 다르다 — F-SCR-001 과 같은 규칙을 쓴다."""
    nfd = unicodedata.normalize("NFD", "원금은 절대 손해 보면 안 됩니다")
    assert not unicodedata.is_normalized("NFC", nfd)
    result, _ = _detect(_contradiction(quote=nfd))
    assert len(result.contradictions) == 1


# ── 탐지 자신감 임계값 (ADR-005 — 이 임계값은 ai-service 소유) ────────────────
def test_low_confidence_does_not_set_mismatch_but_is_kept():
    """근접 사례를 버리지 않는다 — direction 이 코칭 문구를 좌우하고(강희진 결정 ⓒ),
    조용히 지우면 왜 통과했는지 추적할 수 없다."""
    result, _ = _detect(_contradiction(confidence=0.5))
    assert result.mismatch is False
    assert len(result.contradictions) == 1
    assert "자신감" in result.reason


def test_one_confident_contradiction_sets_mismatch():
    result, _ = _detect(_contradiction(confidence=0.4), _contradiction(confidence=0.85))
    assert result.mismatch is True
    assert len(result.contradictions) == 2       # 둘 다 남는다
    assert result.confidence == 0.85             # 최고값


# ── LLM 출력을 그대로 믿지 않는다 ─────────────────────────────────────────────
def test_session_id_is_pinned_to_caller_value():
    result, _ = _detect(_contradiction())
    assert result.session_id == "s-1"            # LLM 은 "from-llm" 을 냈다


def test_mismatch_and_confidence_are_recomputed():
    """LLM 이 mismatch=true, confidence 를 내도 우리가 다시 계산한다."""
    llm = FakeLlm(SuitabilityMismatch(
        session_id="x", status="evaluated", mismatch=True, confidence=0.99,
        contradictions=[_contradiction(quote="지어낸 인용", confidence=0.99)],
        reason="LLM 사유",
    ))
    result = mismatch.detect("s-1", SURVEY, UTTERANCES, llm=llm)
    assert result.mismatch is False and result.confidence == 0.0


def test_survey_schema_version_is_carried_through():
    result, _ = _detect(_contradiction())
    assert result.survey_schema_version == "v1"


# ── 프롬프트 ──────────────────────────────────────────────────────────────────
def test_prompt_contains_survey_and_utterances():
    prompt = mismatch.build_prompt(SURVEY, UTTERANCES)
    for key, value in SURVEY.items():
        assert key in prompt and str(value) in prompt
    assert UTTERANCES[0]["text"] in prompt
    assert UTTERANCES[0]["item_id"] in prompt


def test_system_prompt_forbids_inventing_survey_keys():
    system = mismatch.load_system_prompt()
    assert "question_id" in system
    assert "빈 배열" in system, "모순이 없으면 빈 배열을 내라는 지시가 있어야 한다"


def test_detect_takes_no_vulnerability_inputs():
    """취약 가중은 강희진 소유(ADR-005) — 연령·금액대·경험·채널은 세션 typed 필드에서
    서버가 직접 읽는다. 이 함수가 그것을 받기 시작하면 소유 경계가 무너지고, P3 상으로도
    ai-service 로 나갈 이유가 없는 속성이 흘러들어온다.

    문자열 검사가 아니라 시그니처로 확인한다 — docstring 이 "여기서 하지 않는다"고
    설명하는 것까지 걸리면 테스트가 문서를 막는다.
    """
    import inspect

    params = set(inspect.signature(mismatch.detect).parameters)
    assert not params & {"age_band", "amount_band", "experience_level", "channel",
                         "vulnerability_weights", "coaching_score"}
    assert params == {"session_id", "survey_result", "utterances",
                      "survey_schema_version", "llm"}


# ── 설문 맵: 문항과 메타데이터 (#98 ②, 규약 #44) ──────────────────────────────
def test_metadata_keys_are_not_questions():
    kept = mismatch.survey_questions(SURVEY)
    assert "_surveySchemaVersion" not in kept
    assert set(kept) == {"SUIT-PRINCIPAL-LOSS", "SUIT-HORIZON"}


def test_metadata_key_does_not_reach_the_prompt():
    """모델이 `_surveySchemaVersion` 을 설문 문항으로 읽으면 안 된다."""
    llm = FakeLlm(SuitabilityMismatch(
        session_id="S", status="evaluated", mismatch=False, confidence=0.0,
        contradictions=[], reason="없다",
    ))
    mismatch.detect("S", SURVEY, UTTERANCES, llm=llm)
    prompt = llm.calls[0]["prompt"]
    assert "_surveySchemaVersion" not in prompt
    assert "SUIT-PRINCIPAL-LOSS" in prompt


def test_metadata_key_cannot_be_cited_as_survey_evidence():
    """★ 이게 #98 이 적은 것보다 나쁜 쪽이다.

    `_is_traceable` 은 `question_id in survey_result` 만 봤다. 메타키도 맵에 있으므로
    **문항이 아닌 것을 근거로 든 모순이 P4 대조를 통과했다.** 필터를 `build_prompt` 안에만
    두면 이 경로는 그대로 남는다 — 그래서 입구에서 걸러야 한다.
    """
    bogus = _contradiction(
        question_id="_surveySchemaVersion", recorded="s02-survey-v1", confidence=0.95,
    )
    llm = FakeLlm(SuitabilityMismatch(
        session_id="S", status="evaluated", mismatch=True, confidence=0.95,
        contradictions=[bogus], reason="메타키를 근거로 든 판정",
    ))
    out = mismatch.detect("S", SURVEY, UTTERANCES, llm=llm)
    assert out.contradictions == []
    assert out.mismatch is False


# ── 축 고정 (#44 회신) ────────────────────────────────────────────────────────
def test_axis_comes_from_question_id_not_from_the_model():
    """모델이 축을 틀리게 내도 `question_id` 가 정한다.

    v1 은 모델이 문항 문면을 읽어 축을 판단했고 그게 '약한 고리'였다. 키가 축을 말하므로
    (#44 ①) 축은 계산되는 값이다.
    """
    # 축 기본값은 principal_preservation 인데 참조는 기간 문항이다.
    wrong = _contradiction(question_id="SUIT-HORIZON", recorded="5년 이상 묶어둘 수 있다")
    llm = FakeLlm(SuitabilityMismatch(
        session_id="S", status="evaluated", mismatch=True, confidence=0.9,
        contradictions=[wrong], reason="축이 틀린 판정",
    ))
    out = mismatch.detect("S", SURVEY, UTTERANCES, llm=llm)
    assert [c.axis for c in out.contradictions] == ["investment_horizon"]


def test_axis_map_is_one_to_one_with_the_contract():
    """계약 enum 과 매핑이 어긋나면 한쪽 축이 영원히 안 나오거나 판정 시점에 죽는다."""
    mismatch.assert_axis_map_matches_contract()
    assert len(mismatch.AXIS_BY_QUESTION) == len(set(mismatch.AXIS_BY_QUESTION.values()))


def test_unknown_survey_question_is_rejected_not_guessed():
    """설문 세트가 바뀌면 축 매핑도 같이 올라와야 한다 — 조용히 빼고 판정하지 않는다."""
    with pytest.raises(mismatch.UnknownSurveyQuestion) as exc:
        mismatch.survey_questions({"SUIT-CRYPTO-EXPERIENCE": "없다"})
    assert "SUIT-CRYPTO-EXPERIENCE" in str(exc.value)


def test_survey_with_only_metadata_is_insufficient_not_evaluated():
    """메타키만 온 것은 '모순 없음'이 아니라 '판정 불가'다."""
    out = mismatch.detect("S", {"_surveySchemaVersion": "s02-survey-v1"}, UTTERANCES)
    assert out.status == "insufficient_input"
    assert out.mismatch is False


# ── 엔드포인트 ────────────────────────────────────────────────────────────────
def test_unknown_survey_question_is_422_not_502():
    """세트 버전 불일치는 요청 문제다. 502 로 내면 "AI 가 안 된다"로 읽힌다."""
    from fastapi.testclient import TestClient

    from app.main import app

    resp = TestClient(app).post("/internal/mismatch", json={
        "session_id": "S",
        "survey_result": {"SUIT-CRYPTO-EXPERIENCE": "없다"},
        "utterances": [{"text": "원금은 절대 손해 보면 안 됩니다. 은행이라 믿고 왔어요."}],
    })
    assert resp.status_code == 422
    assert "SUIT-CRYPTO-EXPERIENCE" in resp.json()["detail"]


def test_axis_mismatch_is_logged_not_silent(caplog):
    """덮어쓰기만 하고 비교하지 않으면 프롬프트가 매핑 표를 안 읽는 것을 알 수 없다.

    PR #113 리뷰(정세현) — `#74` 에서 약속한 "불일치를 경고로 남긴다" 의 남은 절반이다.
    판정에는 영향이 없다(계산값이 이긴다). 계약에 필드를 늘리지 않고 로그로 둔다.
    """
    import logging

    wrong = _contradiction(question_id="SUIT-HORIZON", recorded="5년 이상 묶어둘 수 있다")
    assert wrong.axis == "principal_preservation", "픽스처 기본값이 기간 축이 아니어야 한다"

    with caplog.at_level(logging.INFO, logger="app.mismatch"):
        pinned = mismatch._pin_axis(wrong)

    assert pinned.axis == "investment_horizon"
    assert any("축 불일치" in r.message for r in caplog.records), caplog.text


def test_matching_axis_is_not_logged(caplog):
    """일치할 때도 로그를 남기면 불일치가 묻힌다."""
    import logging

    right = _contradiction(question_id="SUIT-PRINCIPAL-LOSS")   # 기본 axis 와 같다
    with caplog.at_level(logging.INFO, logger="app.mismatch"):
        mismatch._pin_axis(right)
    assert not [r for r in caplog.records if "축 불일치" in r.message]
