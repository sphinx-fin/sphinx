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

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Grade(str, Enum):
    """U1 이해 / U2 부분이해 / U3 미이해 / U4 오해 (명세서 0.5절)"""

    U1 = "U1"
    U2 = "U2"
    U3 = "U3"
    U4 = "U4"


# contracts/parsed_document.schema.json 의 product_type enum과 동일해야 한다.
# data/misconception_library/misconceptions.yaml 의 products 값도 여기에 맞춘다 —
# misconception.assert_products_are_canonical() 이 로딩 시점에 검사한다.
ProductType = Literal["ELS", "VARIABLE_INSURANCE"]
PRODUCT_TYPES: tuple[str, ...] = ("ELS", "VARIABLE_INSURANCE")


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
    prompt_version: str | None = Field(
        default=None,
        description="이 판정을 낸 채점 프롬프트 버전. **모델이 채우지 않는다** — "
                    "scoring 이 후처리에서 고정한다(결정 10.46 · 계약 10.38)",
    )


# ── contracts/parsed_document.schema.json (계약 소유: 정세현) ──────────────────
class ParsedPage(Strict):
    page: int = Field(ge=1, description="1부터 시작 (0-base 아님)")
    text: str = Field(description="유니코드 NFC. 공백·개행을 접지 않는다 — 오프셋이 무의미해진다")
    char_count: int | None = None


class ParsedTable(Strict):
    """부가 뷰다. 여기 없는 텍스트는 없다 — pages[].text가 전량이므로
    모든 스팬은 tables를 보지 않고 해소된다."""

    page: int = Field(ge=1)
    caption: str | None = None
    rows: list[list[str]]


class ParseWarning(Strict):
    code: Literal["TABLE_STRUCTURE_LOST", "TEXT_LAYER_MISSING",
                  "ENCODING_SUSPECT", "MANUAL_OVERRIDE"]
    message: str
    page: int | None = None


class ParsedDocument(Strict):
    """F-EXT-001 출력 → F-EXT-002 입력.

    **source_span 규약** (계약 $comment): `RiskItem.condition.source_span`의 start/end는
    해당 페이지의 `pages[].text`에 대한 **페이지 상대** 오프셋이며 반열린 구간 [start, end)다.
    문서 전역 오프셋이 아니다. 따라서 아래 항등식이 성립해야 한다:

        pages[page].text[start:end] == condition.value_text

    F-EXT-002의 원문 스팬 검증 후처리가 이 등식으로 검사한다. 기준이 어긋나면 추출은
    성공하는데 화면(S-01·S-05)에서 하이라이트가 밀린다.
    """

    document_id: str = Field(description="업로드 단위. product_id와 다르다")
    product_type: ProductType
    parser_version: str = Field(description="같은 문서를 다시 파싱해도 같은 출력이어야 한다 (P2)")
    pages: list[ParsedPage] = Field(min_length=1)
    source_file: str | None = None
    parsed_at: str | None = None
    page_count: int | None = Field(default=None, ge=1)
    tables: list[ParsedTable] = Field(default_factory=list)
    parse_warnings: list[ParseWarning] = Field(default_factory=list)

    model_config = ConfigDict(extra="ignore")  # 계약 밖의 `_expected_risk_items` 등을 허용

    def page_text(self, page: int) -> str | None:
        for p in self.pages:
            if p.page == page:
                return p.text
        return None

    @property
    def is_manual(self) -> bool:
        """사람이 만든 샘플인지. 성능 수치를 인용할 때 구분해야 한다."""
        return any(w.code == "MANUAL_OVERRIDE" for w in self.parse_warnings)


# ── /internal/* 요청·응답 (엔드포인트 스펙 제안 — 강희진 확정 대기) ──────────────
class ParseRequest(Strict):
    """F-EXT-001 (정세현). 출력은 ParsedDocument."""

    document_path: str
    product_type: ProductType = "ELS"


class ExtractRequest(Strict):
    product_id: str
    parsed_document: ParsedDocument = Field(description="F-EXT-001 출력")

    @property
    def product_type(self) -> str:
        """상품유형은 문서가 들고 있다 — 요청에서 따로 받으면 두 값이 어긋날 수 있다."""
        return self.parsed_document.product_type


class ExtractionWarning(Strict):
    """추출을 은폐하지 않고 노출한다 (E-EXT-03). parse_warnings 와 같은 성격이다."""

    code: Literal[
        "ITEM_NOT_FOUND",        # 템플릿 항목을 문서에서 못 찾음 → status=extraction_failed
        "SPAN_UNRESOLVED",       # 인용은 받았으나 원문에서 스팬을 해소 못 함
        "LOOSE_MATCH",           # 낱자 사이 개행까지 허용해 찾음 — 거짓 양성 가능, 사람 확인 필요
        "AMBIGUOUS_SPAN",        # 같은 문면이 페이지에 여러 번 — 어느 것인지 확정 불가
        "PAGE_CORRECTED",        # 모델이 지목한 페이지에 없어 다른 페이지에서 찾음
        "QUOTE_NARROWED",        # 긴 인용이 안 풀려 문장 경계로 좁혀 해소 — 수치는 전부 보존
        "NARROWING_REFUSED",     # 좁히면 풀리지만 수치가 빠져 거부 (P6) → extraction_failed
        "UNKNOWN_ITEM_ID",       # 템플릿에 없는 item_id 를 모델이 만들어냄
        "IMPORTANCE_PLACEHOLDER",  # 템플릿 importance 미부여 (이슈 #26)
    ]
    item_id: str | None = None
    message: str


class ExtractResponse(Strict):
    items: list[RiskItem]
    warnings: list[ExtractionWarning] = Field(default_factory=list)


# ── F-EXT-002 LLM 초안 (계약 아님 — 내부 타입) ────────────────────────────────
class ExtractedCandidate(Strict):
    """모델이 낸 후보. **offset 을 받지 않는다** — 스팬은 우리가 원문에서 계산한다.

    모델이 준 숫자를 믿으면 계약 항등식(`text[start:end] == value_text`)이 깨질 수 있고,
    깨진 채로도 추출은 성공한 것처럼 보인다. 인용만 받고 위치는 parsing.resolve_span 이
    찾는다 — 그러면 항등식이 구성상 성립한다.
    """

    item_id: str
    page: int = Field(ge=1)
    quote: str = Field(min_length=1, description="문서에서 그대로 잘라낸 문면")


class ExtractionDraft(Strict):
    candidates: list[ExtractedCandidate]


class QuestionRequest(Strict):
    risk_item: RiskItem
    asked_types: list[str] = Field(default_factory=list, description="이미 쓴 유형 — 반복 방지")
    product_type: ProductType = "ELS"


class QuestionDraft(Strict):
    """F-INT-002 LLM 초안 (계약 아님 — 내부 타입)."""

    question: str = Field(min_length=1)
    question_type: Literal["situation", "amount", "condition"]


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
    product_type: ProductType = "ELS"


class MisconceptionMatch(Strict):
    type_id: str
    label: str
    score: float = Field(ge=0.0, le=1.0)
    matched_pattern: str
    stage: Literal["pattern", "ngram", "embedding", "llm"]
    """pattern/ngram = 결정론적, embedding/llm = 변형 표현 커버 (기획서 5절)"""


class MisconceptionRequest(Strict):
    text: str
    product_type: ProductType = "ELS"


class MisconceptionResponse(Strict):
    matches: list[MisconceptionMatch]
    escalate: bool = Field(default=False, description="M08-TYING 등 escalate:compliance → F-GTE-003")
    unclassified_candidate: bool = Field(default=False, description="미분류 오해 후보 큐 적재 대상")


class ReexplainRequest(Strict):
    """F-INT-004 콘텐츠. 루프 오케스트레이션(항목당 최대 2회)은 강희진 소유다.

    `age_band`·`experience_level` 은 **선택**이다. 기획서 4절이 *"고객의 이해 수준과
    연령·경험에 맞춰 설명을 다시 만든다. 고령 고객에게는 비유 중심으로"* 를 요구하고,
    기획서 7-3 이 LLM 처리 단계에 남기는 것으로 *"상품코드, 상품 조건, 연령대, 금액구간"*
    을 명시했다.

    F-DET-002 에서 연령대를 받지 않기로 한 것과 이유가 다르다 — 그쪽은 취약 요인 **가중**이
    서버 소유라서였고(ADR-005), 이쪽은 콘텐츠 생성에 직접 필요하다. 없으면 고령 고객 기준
    (기획서 3절이 1순위 대상으로 지정한 층)으로 쓴다.
    """

    risk_item: RiskItem
    judgment: Judgment
    age_band: str | None = None
    experience_level: str | None = None


class ReexplainResponse(Strict):
    item_id: str
    content: str
    cited_spans: list[SourceSpan] = Field(description="P6 — 수치는 원문 인용만, 생성 후 대조 검증")


# ── F-DET-002 적합성 모순 (초안 — proposals/suitability_mismatch.schema.json) ──
# contracts/ 미반영. 강희진 확인 후 이동한다 (proposals/F-DET-002-mismatch.md 참고).
class SurveyRef(Strict):
    question_id: str
    question_text: str | None = None
    recorded_answer: str = Field(min_length=1, description="고객이 설문에 기재한 값. 원문 그대로")


class Contradiction(Strict):
    axis: Literal[
        "risk_tolerance", "principal_preservation", "loss_capacity",
        "investment_horizon", "product_understanding", "purpose",
    ]
    direction: Literal["survey_overstates_tolerance", "survey_understates_tolerance"]
    """survey_overstates_tolerance = 설문이 실제 발화보다 위험 감수적.
    기획서 3절 판결 동향의 '스스로 기재한 투자성향'이 문제되는 방향.
    어느 방향을 게이트에 걸지는 룰의 결정이다 (P1) — 여기서는 라벨링만 한다."""

    survey_ref: SurveyRef
    utterance_quote: str = Field(min_length=1, description="발화 그대로. 요약·재구성 금지")
    item_id: str | None = None
    reason: str = Field(min_length=1)
    confidence: float = Field(ge=0.0, le=1.0)


class SuitabilityMismatch(Strict):
    """세션 단위 판정. gate_rules.yaml R-02의 suitabilityMismatch로 들어간다.

    취약 요인 가중(연령·가입비중·투자경험·채널)은 여기 없다 — 강희진 소유로 확정됐다
    (결정 ⓓ). `resources/vulnerability_weights.yaml` → 코칭 스코어 → 세션 메타 경로이며
    스키마에 필드를 추가하지 않는다.

    분담: **판정 = ai-service / 취약가중·코칭·메타 = 강희진** (역할분담표 v1.2 §38).
    `confidence`는 필수다 — 그것이 우리가 내는 '탐지 자신감'이고, 취약 가중과는 별개다.
    `direction`은 게이트가 무시하지만(모순이면 R-02 RED) 코칭 문구를 좌우하므로 유지한다.
    """

    session_id: str
    status: Literal["evaluated", "insufficient_input"]
    mismatch: bool
    confidence: float = Field(ge=0.0, le=1.0)
    contradictions: list[Contradiction] = Field(default_factory=list)
    reason: str = Field(min_length=1)
    survey_schema_version: str | None = None

    @model_validator(mode="after")
    def _check_invariants(self) -> "SuitabilityMismatch":
        # P4 — 근거 없는 판정은 무효
        if self.mismatch and not self.contradictions:
            raise ValueError("mismatch=true인데 contradictions가 비었다 (P4 위반)")
        # 판정 못 한 것을 '적합'으로 읽히게 두지 않는다
        if self.status == "insufficient_input" and (self.mismatch or self.contradictions):
            raise ValueError("insufficient_input은 mismatch=false이고 근거가 없어야 한다")
        # confidence는 확인된 모순 중 최고값
        if self.contradictions:
            top = max(c.confidence for c in self.contradictions)
            if abs(self.confidence - top) > 1e-9:
                raise ValueError(f"confidence({self.confidence})가 최고 모순 확신도({top})와 다르다")
        return self


class MismatchRequest(Strict):
    """POST /internal/mismatch — 7번째 엔드포인트로 확정(강희진 결정 ⓐ).

    모순 판정은 설문 전체 + 세션 내 발화 전체가 입력이라 항목 단위 `/internal/score`와
    분리한다. 호출 시점은 게이트 판정 직전(또는 충분한 답변 확보 후)이다.

    `survey_result`는 **freeform 매핑**이다(강희진 결정 ⓑ). `CreateSession.surveyResult`의
    `Map<String, Object>`를 그대로 받는다. 키를 문항 식별자로, 값을 기재 답변으로 본다.

    키 규약(이슈 #44): `SUIT-` 로 시작하는 키만 문항이다. 그 외(`_surveySchemaVersion` 등)는
    메타데이터이고 `mismatch.survey_questions()` 가 입구에서 걸러낸다.

    `axis`는 설문이 주지 않지만(결정 ⓑ) 문항 키가 축을 말하므로 `question_id`에서
    결정론적으로 나온다(`mismatch.AXIS_BY_QUESTION`). 모델 추론이 아니다 — 문항 문면을
    다듬어도 축은 흔들리지 않는다.
    취약 요인(연령·가입금액대·투자경험·채널)은 여기 오지 않는다. 세션 typed 필드에서
    강희진이 직접 읽고 가중한다(결정 ⓓ).
    """

    session_id: str
    survey_result: dict[str, Any] = Field(
        description="CreateSession.surveyResult 원형. 키=문항 식별자, 값=기재 답변"
    )
    utterances: list[dict[str, Any]] = Field(
        description="[{item_id, text}] — 세션 내 발화. Spring PiiGateway 통과분만 (P3)"
    )
    survey_schema_version: str | None = None
