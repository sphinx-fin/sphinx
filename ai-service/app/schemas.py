"""`contracts/`의 JSON Schema를 pydantic으로 미러링. 소유: 윤지석

**계약의 단일 진실은 `contracts/*.schema.json`(소유: 강희진)이다.** 이 파일은 그 미러이며,
Java `domain/` 레코드가 같은 스키마를 미러링하는 것과 같은 위치다. 스키마가 바뀌면
여기도 따라 바꾼다 — 반대 방향은 없다.

필드명은 **snake_case** = `contracts/*.schema.json` 그대로.
주의: Java 레코드는 camelCase(`itemId`, `misconceptionType`)여서 직렬화 설정에 따라
Spring이 camelCase를 기대할 수 있다. 계약 파일이 snake_case이므로 여기서는 snake_case를
따르고, 실제 연동 시 강희진과 맞춘다. (→ CLAUDE.local.md 설계 이슈)
"""
from __future__ import annotations

from enum import Enum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class Grade(str, Enum):
    """U1 이해 / U2 부분이해 / U3 미이해 / U4 오해 (명세서 0.5절)"""

    U1 = "U1"
    U2 = "U2"
    U3 = "U3"
    U4 = "U4"


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


# ── contracts/risk_item.schema.json ────────────────────────────────────────────
class SourceSpan(Strict):
    page: int
    start: int
    end: int


class Condition(Strict):
    value_text: str = Field(description="원문 인용만 허용 (P6)")
    source_span: SourceSpan


class RiskItem(Strict):
    item_id: str
    product_id: str
    name: str
    importance: Literal["required", "recommended"]
    condition: Condition
    status: Literal["extracted", "extraction_failed"]


# ── contracts/judgment.schema.json ────────────────────────────────────────────
class Evidence(Strict):
    """P4 — 둘 중 하나라도 비면 판정 무효. 빈 문자열도 막는다."""

    utterance_quote: str = Field(min_length=1)
    rubric_clause: str = Field(min_length=1)


class Judgment(Strict):
    item_id: str
    grade: Grade
    confidence: float = Field(ge=0.0, le=1.0)
    evidence: Evidence
    reason: str = Field(min_length=1, description="판정 사유 1문장")
    misconception_type: str | None = Field(
        default=None, description="F-DET-001 매칭 시 유형ID (예: M08-TYING)"
    )


# ── /internal/* 요청·응답 (엔드포인트 스펙 제안 — 강희진 확정 대기) ──────────────
class ParseRequest(Strict):
    """F-EXT-001 (정세현). `contracts/parsed_document.schema.json` 미정 — 확정 시 교체."""

    document_path: str
    product_type: Literal["ELS", "VARIABLE_INS"] = "ELS"


class ExtractRequest(Strict):
    product_id: str
    product_type: Literal["ELS", "VARIABLE_INS"] = "ELS"
    parsed_document: dict[str, Any] = Field(description="F-EXT-001 출력 (형식 미확정)")


class ExtractResponse(Strict):
    items: list[RiskItem]


class QuestionRequest(Strict):
    risk_item: RiskItem
    asked_types: list[str] = Field(default_factory=list, description="이미 쓴 유형 — 반복 방지")


class QuestionResponse(Strict):
    item_id: str
    question: str
    question_type: Literal["situation", "amount", "condition"]
    fallback_used: bool = False


class ScoreRequest(Strict):
    """고객 발화는 Spring PiiGateway.mask() 통과분만 온다 (P3). 입구에서 재검사한다."""

    item_id: str
    question: str
    answer_text: str
    risk_item: RiskItem
    product_type: Literal["ELS", "VARIABLE_INS"] = "ELS"


class MisconceptionMatch(Strict):
    type_id: str
    label: str
    score: float = Field(ge=0.0, le=1.0)
    matched_pattern: str
    stage: Literal["pattern", "embedding", "llm"]


class MisconceptionRequest(Strict):
    text: str
    product_type: Literal["ELS", "VARIABLE_INS"] = "ELS"


class MisconceptionResponse(Strict):
    matches: list[MisconceptionMatch]
    escalate: bool = Field(default=False, description="M08-TYING 등 escalate:compliance → F-GTE-003")
    unclassified_candidate: bool = Field(default=False, description="미분류 오해 후보 큐 적재 대상")


class ReexplainRequest(Strict):
    risk_item: RiskItem
    judgment: Judgment


class ReexplainResponse(Strict):
    item_id: str
    content: str
    cited_spans: list[SourceSpan] = Field(description="P6 — 수치는 원문 인용만, 생성 후 대조 검증")
