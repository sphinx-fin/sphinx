"""F-EXT-002 필수 이해항목 추출. 소유: 윤지석
출력은 contracts/risk_item.schema.json. 원문 스팬 필수 (P6), 실패는 extraction_failed로 노출 (E-EXT-03).
"""
from __future__ import annotations

from typing import Any

from .schemas import RiskItem


def extract(product_id: str, product_type: str, parsed_document: dict[str, Any]) -> list[RiskItem]:
    """파싱된 문서 → 필수 이해항목.

    TODO(윤지석):
      1. 상품유형 템플릿(ELS/변액)으로 후보 항목 프롬프트 구성
      2. llm_client.complete_json(model_cls=...)로 항목 추출
      3. **원문 스팬 검증 후처리** — value_text가 source_span 위치에 실제로 존재하는지 대조 (P6)
      4. 검증 실패 항목은 버리지 않고 status="extraction_failed"로 반환 (E-EXT-03)
    """
    raise NotImplementedError("TODO(윤지석): F-EXT-002")
