"""F-EXT-002 필수 이해항목 추출. 소유: 윤지석
출력은 contracts/risk_item.schema.json. 원문 스팬 필수 (P6), 실패는 extraction_failed로 노출 (E-EXT-03).
"""
from __future__ import annotations

from .schemas import ParsedDocument, RiskItem


def extract(product_id: str, product_type: str, parsed_document: ParsedDocument) -> list[RiskItem]:
    """파싱된 문서 → 필수 이해항목.

    TODO(윤지석):
      1. 상품유형 템플릿(ELS/변액)으로 후보 항목 프롬프트 구성
      2. llm_client.complete_json(model_cls=...)로 항목 추출
      3. **원문 스팬 검증 후처리** — 계약 규약의 항등식으로 검사한다 (P6):
         parsed_document.page_text(page)[start:end] == value_text
         페이지 상대 오프셋이며 반열린 구간이다. 문서 전역 오프셋이 아니다.
      4. 검증 실패 항목은 버리지 않고 status="extraction_failed"로 반환 (E-EXT-03)
    """
    raise NotImplementedError("TODO(윤지석): F-EXT-002")
