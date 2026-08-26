"""`/internal/*` 라우트. 소유: 윤지석 (엔드포인트 목록은 강희진 `AiServiceClient`와 합의)

라우트는 얇게 유지한다 — 검증 → PII 재검사 → 기능 함수 호출 → 응답. 기능 로직은
각 모듈의 순수 함수에 둔다(HTTP 없이 단위 테스트·프롬프트 튜닝을 돌릴 수 있어야 한다).

미구현 기능은 500이 아니라 **501 Not Implemented**를 돌려준다 — 강희진이 붙일 때
"아직 없음"과 "터짐"을 구분할 수 있어야 한다.

F-DET-002(적합성 모순)는 7번째 엔드포인트 `/internal/mismatch`로 확정됐다(강희진 결정).
모순 판정은 설문 전체 + 세션 발화 전체가 입력이라 항목 단위 /internal/score와 분리한다.
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException, status

from . import (extraction, misconception, mismatch, question_gen, reexplain, rubrics,
               scoring, templates)
from .llm_client import LlmError, LlmNotConfigured
from .pii import PiiDetected, assert_clean
from .schemas import (
    ExtractRequest,
    ExtractResponse,
    Judgment,
    MisconceptionRequest,
    MisconceptionResponse,
    MismatchRequest,
    ParseRequest,
    QuestionRequest,
    QuestionResponse,
    ReexplainRequest,
    ReexplainResponse,
    SuitabilityMismatch,
    ScoreRequest,
)

log = logging.getLogger(__name__)

router = APIRouter(prefix="/internal", tags=["internal"])


def _not_implemented(feature: str, owner: str = "윤지석") -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_501_NOT_IMPLEMENTED,
        detail=f"{feature} 미구현 (TODO({owner}))",
    )


def _llm_unavailable(exc: LlmError) -> HTTPException:
    """LLM 실패는 502 — 우리 버그가 아니라 상류 의존성 문제임을 구분한다."""
    code = (
        status.HTTP_503_SERVICE_UNAVAILABLE
        if isinstance(exc, LlmNotConfigured)
        else status.HTTP_502_BAD_GATEWAY
    )
    return HTTPException(status_code=code, detail=str(exc))


# ── F-EXT-001 (정세현) ─────────────────────────────────────────────────────────
@router.post("/parse")
def parse(body: ParseRequest) -> dict:
    """상품문서 파싱. 구현은 `parsing.py`(정세현 소유) — 이 파일에서 대신 구현하지 않는다."""
    raise _not_implemented("F-EXT-001 파싱", owner="정세현")


# ── F-EXT-002 ─────────────────────────────────────────────────────────────────
@router.post("/extract", response_model=ExtractResponse)
def extract(body: ExtractRequest) -> ExtractResponse:
    try:
        return extraction.extract(
            body.product_id, body.product_type,
            body.parsed_document.model_dump(),
        )
    except extraction.DocumentTooLarge as exc:
        # 문서가 큰 것은 상류 LLM 장애가 아니라 입력 문제다 — 502 로 나가면
        # Spring 쪽에서 "ai-service 장애"로 오진된다 (PR #60 리뷰)
        raise HTTPException(status_code=413, detail=str(exc)) from exc
    except templates.TemplateNotFound as exc:
        # 템플릿 없는 상품유형은 추출 범위가 정의되지 않았다 — 500 이 아니라 422다
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except NotImplementedError:
        raise _not_implemented("F-EXT-002 추출")
    except LlmError as exc:
        raise _llm_unavailable(exc)


# ── F-INT-002 ─────────────────────────────────────────────────────────────────
@router.post("/question", response_model=QuestionResponse)
def question(body: QuestionRequest) -> QuestionResponse:
    try:
        return question_gen.generate(body.risk_item, body.asked_types, body.product_type)
    except templates.TemplateNotFound as exc:
        # 템플릿 밖 항목은 인터뷰 대상이 아니다 — 500 이 아니라 422다
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except NotImplementedError:
        raise _not_implemented("F-INT-002 질문 생성")
    except LlmError as exc:
        raise _llm_unavailable(exc)


# ── F-SCR-001 ─────────────────────────────────────────────────────────────────
@router.post("/score", response_model=Judgment)
def score(body: ScoreRequest) -> Judgment:
    """고객 발화 채점. 미들웨어가 이미 본문을 검사했지만 발화는 여기서 한 번 더 본다 (P3)."""
    assert_clean(body.answer_text, "score.answer_text")
    try:
        return scoring.score(
            body.item_id, body.question, body.answer_text, body.risk_item, body.product_type
        )
    except rubrics.RubricNotFound as exc:
        # 루브릭이 없는 항목은 채점하지 않는다 — 근거 없는 판정은 무효다 (P4)
        raise HTTPException(status_code=422, detail=f"루브릭 없음: {body.item_id}") from exc
    except NotImplementedError:
        raise _not_implemented("F-SCR-001 채점")
    except LlmError as exc:
        raise _llm_unavailable(exc)


# ── F-DET-001 ─────────────────────────────────────────────────────────────────
@router.post("/misconception", response_model=MisconceptionResponse)
def detect_misconception(body: MisconceptionRequest) -> MisconceptionResponse:
    assert_clean(body.text, "misconception.text")
    try:
        return misconception.match(body.text, body.product_type)
    except NotImplementedError:
        raise _not_implemented("F-DET-001 오해 탐지")
    except LlmError as exc:
        raise _llm_unavailable(exc)


# ── F-DET-002 ─────────────────────────────────────────────────────────────────
@router.post("/mismatch", response_model=SuitabilityMismatch)
def detect_mismatch(body: MismatchRequest) -> SuitabilityMismatch:
    """설문 기재 vs 발화 모순. 세션 단위 판정이므로 /score와 분리한다.

    출력의 `mismatch`가 gate_rules.yaml R-02로 들어간다. 취약 요인 가중·코칭 스코어는
    여기서 하지 않는다 — 강희진 소유(역할분담표 v1.2 §38).
    """
    for utterance in body.utterances:
        text = utterance.get("text")
        if isinstance(text, str):
            assert_clean(text, "mismatch.utterances[].text")
    try:
        return mismatch.detect(
            body.session_id, body.survey_result, body.utterances, body.survey_schema_version
        )
    except NotImplementedError:
        raise _not_implemented("F-DET-002 적합성 모순 탐지")
    except LlmError as exc:
        raise _llm_unavailable(exc)


# ── F-INT-004 (콘텐츠) ────────────────────────────────────────────────────────
@router.post("/reexplain", response_model=ReexplainResponse)
def do_reexplain(body: ReexplainRequest) -> ReexplainResponse:
    try:
        return reexplain.reexplain(
            body.risk_item, body.judgment, body.age_band, body.experience_level
        )
    except NotImplementedError:
        raise _not_implemented("F-INT-004 재설명")
    except LlmError as exc:
        raise _llm_unavailable(exc)


__all__ = ["router", "PiiDetected"]
