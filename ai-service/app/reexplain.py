"""맞춤 재설명 생성. 소유: 윤지석. 수치는 RiskItem 원문 인용만 (P6) — 생성 후 대조 검증.

F-INT-004의 콘텐츠 부분만 담당한다. 루프 오케스트레이션(항목당 최대 2회)은 강희진.
"""
from __future__ import annotations

from .schemas import Judgment, ReexplainResponse, RiskItem


def reexplain(risk_item: RiskItem, judgment: Judgment) -> ReexplainResponse:
    """오해·미이해 판정 → 맞춤 재설명.

    TODO(윤지석):
      1. judgment.grade / misconception_type에 따라 설명 전략 분기
      2. llm_client로 생성 — 수치는 risk_item.condition.value_text 인용만 허용 (P6)
      3. **생성 후 대조 검증** — 본문의 수치가 원문에 없으면 재생성. 환각 수치가
         고객에게 노출되면 이 시스템의 존재 이유가 무너진다
      4. 인용한 스팬을 cited_spans로 반환 (리포트 근거로 쓰인다)
    """
    raise NotImplementedError("TODO(윤지석): F-INT-004 콘텐츠")
