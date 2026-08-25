"""채점 테스트 헬퍼. 소유: 윤지석

`conftest.py`(정세현, F-EXT-001 픽스처)와 경로가 겹쳤으므로 분리했다. conftest는 pytest가
자동 로드하는 공용 파일이라 두 사람의 코드를 섞을 자리가 아니다. 여기 있는 것은 pytest
픽스처가 아니라 평범한 헬퍼이므로 일반 모듈이 맞다.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

from app import rubrics
from app.llm_client import LlmClient
from app.schemas import Evidence, Grade, Judgment

FIXTURES = Path(__file__).resolve().parent / "fixtures"


class FakeLlm(LlmClient):
    """LLM 없이 후처리 로직을 검증한다.

    후처리(근거 대조·오해 상향·신뢰도 강등)가 채점 품질의 실질이므로, API 키 없이
    돌아가야 한다. 그래야 CI에서도 회귀를 잡는다.
    """

    def __init__(self, judgment: Judgment) -> None:  # super().__init__ 호출하지 않는다
        self.judgment = judgment
        self.calls: list[dict[str, Any]] = []

    def complete_json(self, **kwargs: Any) -> Judgment:  # type: ignore[override]
        self.calls.append(kwargs)
        return self.judgment


def make_judgment(
    grade: Grade = Grade.U1,
    confidence: float = 0.9,
    quote: str = "원금은 지켜지는 거죠",
    item_id: str = "ELS-PRINCIPAL-LOSS-WARNING",
    reason: str = "판정 사유",
    misconception_type: str | None = None,
    rubric_clause: str | None = None,
) -> Judgment:
    """rubric_clause를 주지 않으면 해당 item_id 루브릭의 첫 필수 요소를 쓴다.

    scoring.verify_rubric_clause_is_published()가 공개 조항인지 대조하므로, 항목과
    무관한 조항을 기본값으로 두면 테스트가 검증 로직에 걸린다.
    """
    return Judgment(
        item_id=item_id,
        grade=grade,
        confidence=confidence,
        evidence=Evidence(
            utterance_quote=quote,
            rubric_clause=rubric_clause or rubrics.get(item_id).required_elements[0],
        ),
        reason=reason,
        misconception_type=misconception_type,
    )
