"""`/internal/*` 라우트. 소유: 윤지석 (엔드포인트 목록은 강희진 `AiServiceClient`와 합의)

라우트는 얇게 유지한다 — 검증 → PII 재검사 → 기능 함수 호출 → 응답. 기능 로직은
각 모듈의 순수 함수에 둔다(HTTP 없이 단위 테스트·프롬프트 튜닝을 돌릴 수 있어야 한다).

미구현 기능은 500이 아니라 **501 Not Implemented**를 돌려준다 — 강희진이 붙일 때
"아직 없음"과 "터짐"을 구분할 수 있어야 한다.

주의: F-DET-002(적합성 모순)는 `AiServiceClient`의 6개 엔드포인트 목록에 없다.
/internal/score에 태울지 7번째 엔드포인트를 낼지 강희진과 확정한 뒤 추가한다.
스키마도 미정이므로 여기서 임의로 만들지 않는다.
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException, status

from . import extraction, misconception, question_gen, reexplain, rubrics, scoring
from .llm_client import LlmError, LlmNotConfigured
from .pii import PiiDetected, assert_clean
from .schemas import (
    ExtractRequest,
    ExtractResponse,
    Judgment,
    MisconceptionRequest,
    MisconceptionResponse,
    ParseRequest,
    QuestionRequest,
    QuestionResponse,
    ReexplainRequest,
    ReexplainResponse,
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
        items = extraction.extract(body.product_id, body.product_type, body.parsed_document)
    except NotImplementedError:
        raise _not_implemented("F-EXT-002 추출")
    except LlmError as exc:
        raise _llm_unavailable(exc)
    return ExtractResponse(items=items)


# ── F-INT-002 ─────────────────────────────────────────────────────────────────
@router.post("/question", response_model=QuestionResponse)
def question(body: QuestionRequest) -> QuestionResponse:
    try:
        return question_gen.generate(body.risk_item, body.asked_types)
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


# ── F-INT-004 (콘텐츠) ────────────────────────────────────────────────────────
@router.post("/reexplain", response_model=ReexplainResponse)
def do_reexplain(body: ReexplainRequest) -> ReexplainResponse:
    try:
        return reexplain.reexplain(body.risk_item, body.judgment)
    except NotImplementedError:
        raise _not_implemented("F-INT-004 재설명")
    except LlmError as exc:
        raise _llm_unavailable(exc)


__all__ = ["router", "PiiDetected"]
