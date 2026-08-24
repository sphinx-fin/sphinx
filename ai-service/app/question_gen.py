"""F-INT-002 질문 생성. 소유: 윤지석
화이트리스트: 상황설명형/금액환산형/조건재현형. 정답 노출 검사 → 3회 실패 시 fallback.
"""
from __future__ import annotations

from .schemas import QuestionResponse, RiskItem

QUESTION_TYPES = ("situation", "amount", "condition")
MAX_ATTEMPTS = 3


def generate(risk_item: RiskItem, asked_types: list[str] | None = None) -> QuestionResponse:
    """이해항목 → 되말하기 질문.

    TODO(윤지석):
      1. asked_types를 제외한 화이트리스트 유형에서 선택
      2. llm_client로 생성
      3. **정답 노출 검사** — 질문에 risk_item.condition.value_text의 핵심 수치·조건이
         들어가면 측정이 무효가 된다. 걸리면 재생성, MAX_ATTEMPTS회 실패 시 fallback
      4. fallback = 항목별 기본 질문(LLM 없이 미리 작성한 것) → fallback_used=True
    """
    raise NotImplementedError("TODO(윤지석): F-INT-002")
