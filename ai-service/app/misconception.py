"""F-DET-001 오해 탐지. 소유: 윤지석 (라이브러리: 정세현)
1차 패턴 매칭(data/misconception_library) → 2차 LLM 유사도. M08-TYING → escalate 플래그 반환.
"""
from __future__ import annotations

from pathlib import Path

from .schemas import MisconceptionResponse

LIBRARY_PATH = Path(__file__).resolve().parents[2] / "data" / "misconception_library" / "misconceptions.yaml"
SIMILARITY_THRESHOLD = 0.80


def match(text: str, product_type: str) -> MisconceptionResponse:
    """발화 → 오해 유형 매칭.

    TODO(윤지석):
      1. 1차: LIBRARY_PATH의 patterns 문자열 매칭 (라이브러리는 정세현 소유 — 읽기만)
      2. 2차: 임베딩 코사인 유사도 + SIMILARITY_THRESHOLD
         (LLM 호출보다 임베딩이 맞다 — 임계값이 설명 가능한 숫자로 드러나고 재현성이 생긴다)
      3. 경계 케이스·미분류만 LLM 판정 → unclassified_candidate=True로 후보 큐 적재
      4. escalate: 매칭된 유형의 `escalate` 필드를 **읽어서** 판단.
         M08-TYING을 코드에 하드코딩하지 않는다 — 데이터 소유는 정세현이다.
    """
    raise NotImplementedError("TODO(윤지석): F-DET-001")
