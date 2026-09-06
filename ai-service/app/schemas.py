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
    value_text: str = Field(description="원문 인용만 허용 (P6 · 1절 F-EXT-002)")
    source_span: SourceSpan


class ConditionNotExtracted(Exception):
    """추출 실패 항목의 조건을 쓰려고 했다 — 계약이 허용하는 값이므로 500 이면 안 된다.

    계약(`risk_item.schema.json`)이 `status != "extracted"` 일 때 `condition: null` 을
    `allOf/if/then/else` 로 **강제**한다. 즉 이 입력은 잘못된 요청이 아니라 정상 요청이고,
    다만 그 항목으로는 질문·채점을 만들 수 없다.

    **조용히 진행하지 않는다.** 폴백 질문을 내주면 고객이 답을 하는데 그 답을 채점할 조건이
    없어서 다음 호출에서 막힌다 — 실패를 뒤로 미루는 것뿐이다. 추출 실패는 S-01 큐에서
    사람이 처리할 일이고(E-EXT-03), ai-service 는 그 사실을 명시적으로 알린다.

    재설명(F-INT-004)만 예외다 — 거기는 `_minimal()` 이 이 경우를 위해 이미 설계돼 있다.
    """


class RiskItem(Strict):
    item_id: str
    product_id: str
    name: str
    importance: Literal["required", "recommended"]
    # ❗둘 다 계약의 required 가 아니다 — status=extraction_failed 면 condition 이 null 이고
    #   failure_reason 이 채워진다(E-EXT-03: 실패를 은폐하지 않는다). 서버는 Jackson 이라
    #   null 도 그대로 실어 보내므로, Strict(extra="forbid") 아래에서 이 둘이 없으면
    #   **정상 요청이 422 로 거절된다**(이슈 #165).
    condition: Condition | None = None
    status: Literal["extracted", "extraction_failed"]
    failure_reason: str | None = None

    def require_condition(self) -> Condition:
        """조건을 쓰는 곳은 여기를 지난다. 없으면 `ConditionNotExtracted`.

        `#185` 가 `condition` 을 Optional 로 열면서 **422 가 사라진 자리에 500 이 생겼다** —
        조건이 있다고 전제하는 지점이 다섯이고(`question_gen.answer_fragments` ·
        `scoring.build_prompt` · `scoring.echo_score` · `reexplain.source_numerics` ·
        `reexplain.build_prompt`) 전부 `AttributeError` 를 냈다.

        다섯 곳에 `if is None` 을 흩어 놓지 않는 이유: 그러면 새 호출자가 생길 때마다 같은
        판단을 다시 해야 하고, 하나를 빼먹으면 그 경로만 500 으로 남는다. **조건을 꺼내는
        문법 자체를 하나로** 두면 빼먹을 자리가 없다.

        `ValueError` 를 고르지 않은 이유는 라우트가 그것을 400 계열로 뭉쳐 매핑하면 서버
        설정 오류까지 "잘못된 요청" 이 되기 때문이다(CLAUDE.md `api/` 절과 같은 이유).
        전용 타입이라 라우트가 이 경우만 골라 422 로 낸다.
        """
        if self.condition is None:
            raise ConditionNotExtracted(
                f"{self.item_id}: status={self.status} 이라 조건이 없다 — "
                f"사유: {self.failure_reason or '(미기재)'}"
            )
        return self.condition


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
    escalate: bool = Field(
        default=False,
        description="이 판정이 컴플라이언스로 올라갈 신호인가 (M08-TYING 등 "
                    "escalate: compliance). ❗판매자 응답에 넣지 않는다 — 기획 7-4 역이용 방지",
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
    """F-EXT-001 (정세현). 출력은 ParsedDocument.

    `document_path` 는 **`SPHINX_DATA_DIR` 기준 상대경로**이거나 그 안의 절대경로다
    (예: `documents/els_kiwoom_4181_simple_prospectus.pdf`). 뿌리 밖은 파일을 만지기 전에
    거부한다 — `parsing.resolve_document_path`.

    업로드된 파일이 그 뿌리에 어떻게 도달하는지는 아직 안 정해졌다(이슈 #401 의 2번).
    바이트를 직접 받는 쪽으로 정해지면 이 요청 모양이 바뀐다.
    """

    document_path: str
    product_type: ProductType = "ELS"
    document_id: str | None = Field(
        default=None,
        description="업로드 단위 식별자. 안 주면 파일명에서 만든다 — 업로더가 가진 값이 있으면 그걸 준다",
    )
    parsed_at: str | None = Field(
        default=None,
        description="호출자가 주입한다. 파서가 현재 시각을 찍으면 같은 문서의 두 파싱 결과가 "
                    "달라져 재현성 비교(P2)에 못 쓴다. 안 주면 출력에 키가 없다",
    )


class ExtractRequest(Strict):
    product_id: str
    parsed_document: ParsedDocument = Field(description="F-EXT-001 출력")

    @property
    def product_type(self) -> str:
        """상품유형은 문서가 들고 있다 — 요청에서 따로 받으면 두 값이 어긋날 수 있다."""
        return self.parsed_document.product_type


class ExtractionWarning(Strict):
    """추출을 은폐하지 않고 노출한다 (E-EXT-03). parse_warnings 와 같은 성격이다.

    ## ❗`MANUAL_SOURCE` — 표식이 층을 건너게 한다

    `#441` 이 `data/documents/x.json` 을 두면 파스 출력을 사람이 만든 것으로 대체할 수 있게
    했고, 그 사실이 `ParsedDocument.parse_warnings` 의 `MANUAL_OVERRIDE` 로 남는다.
    **그런데 추출은 `parse_warnings` 를 안 본다** — 즉 그 문서에서 나온 `RiskItem` 은
    *"사람이 만든 파스에서 왔다"* 를 잃은 채로 서버·evidence·게이트로 간다.

        parse       MANUAL_OVERRIDE 있음   ParsedDocument.is_manual → True
        extract     ❗아무것도 안 실림      RiskItem 만 나간다
        서버·게이트  구분할 방법이 없다

    `is_manual` 이 *"성능 수치를 인용할 때 구분해야 한다"* 고 적어 뒀는데, **읽는 쪽이
    `app/` 에 하나도 없었다.** 표식을 만들어 두고 아무도 안 읽으면 다음 사람이 그 표식을
    안 믿는다 — `#409`(model.jsonl)·F-EXT-003(추출 재현율)이 걸러야 할 자리가 여기다.

    그래서 **추출 경고로 한 번 더 싣는다.** 항목별이 아니라 문서 단위이므로 `item_id` 는
    비운다(계약이 nullable 로 허용하는 그 경우다).
    """

    code: Literal[
        "ITEM_NOT_FOUND",        # 템플릿 항목을 문서에서 못 찾음 → status=extraction_failed
        "SPAN_UNRESOLVED",       # 인용은 받았으나 원문에서 스팬을 해소 못 함
        "LOOSE_MATCH",           # 낱자 사이 개행까지 허용해 찾음 — 거짓 양성 가능, 사람 확인 필요
        "AMBIGUOUS_SPAN",        # 같은 문면이 페이지에 여러 번 — 어느 것인지 확정 불가
        "PAGE_CORRECTED",        # 모델이 지목한 페이지에 없어 다른 페이지에서 찾음
        "QUOTE_NARROWED",        # 긴 인용이 안 풀려 문장 경계로 좁혀 해소 — 수치는 전부 보존
        "NARROWING_REFUSED",     # 좁히면 풀리지만 수치가 빠져 거부 (P6 · 1절) → extraction_failed
        "UNKNOWN_ITEM_ID",       # 템플릿에 없는 item_id 를 모델이 만들어냄
        "IMPORTANCE_PLACEHOLDER",  # 템플릿 importance 미부여 (이슈 #26)
        "MANUAL_SOURCE",         # ❗파스 출력이 사람이 만든 것이다 (#436·#441) — 아래 참조
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


class InterviewContext(Strict):
    """면담이 지금까지 알아낸 것 — 질문 생성이 이걸 보고 다음 질문을 정한다.

    ## 왜 필요한가

    지금까지 질문 생성이 받는 것은 `risk_item` 과 `product_type` 뿐이었다. **이 고객이
    60대인지, 방금 무엇을 틀렸는지, 어떤 오해가 이미 걸렸는지 모른 채** 매번 첫 질문처럼
    만든다. 되말하기가 재려는 것은 *"이 사람이 이 문장을 만들어 낼 수 있었나"* 인데,
    같은 문장이라도 요구되는 난이도가 사람마다 다르다.

    ## ❗정답을 싣지 않는다

    여기 오는 것은 **등급과 오해 유형 ID** 뿐이다. 직전 발화도, 루브릭 조항도, 조건
    원문도 안 온다 — 그건 `answer_fragments` 가 질문에서 걸러내는 바로 그 값이고,
    맥락으로 넣으면 모델이 다음 질문에 옮겨 쓴다(유도심문).
    """

    vulnerable: bool = Field(default=False, description="코칭 정황 스코어가 임계 이상 — 눈높이를 낮춘다")
    prior_grades: list[Grade] = Field(default_factory=list, description="이 세션에서 이미 나온 등급")
    matched_misconceptions: list[str] = Field(
        default_factory=list, description="이미 걸린 오해 유형 ID — 다음 질문이 그 자리를 짚는다")


class QuestionRequest(Strict):
    risk_item: RiskItem
    asked_types: list[str] = Field(default_factory=list, description="이미 쓴 유형 — 반복 방지")
    product_type: ProductType = "ELS"
    variant: Literal["initial", "reverify"] = Field(
        default="initial",
        description="reverify 는 재설명 뒤 다시 묻는 질문 — 같은 것을 다시 묻지 않는다")
    context: InterviewContext | None = Field(
        default=None, description="면담 맥락. 없으면 첫 질문처럼 만든다")


class QuestionDraft(Strict):
    """F-INT-002 LLM 초안 (계약 아님 — 내부 타입)."""

    question: str = Field(min_length=1)
    question_type: Literal["situation", "amount", "condition"]


class QuestionResponse(Strict):
    item_id: str
    question: str
    question_type: Literal["situation", "amount", "condition"]
    fallback_used: bool = False


class InputMeta(Strict):
    """F-INT-003 입력 메타데이터 — **무엇을 말했나가 아니라 어떻게 입력했나** (이슈 #325).

    ## 왜 채점 경계까지 오나

    <b>붙여넣기로 채운 되말하기는 되말하기가 아니다.</b> 판매자가 대신 입력했거나 화면에
    뜬 설명을 복사한 것이고, **발화 내용만 보면 완벽한 U1 로 채점된다** — 텍스트로는
    구분이 안 되고 입력 방식으로만 구분된다.

    복창 판정(`cap_confidence_if_echoed`)이 묻는 것과 **같은 질문**이다: *"이게 이 사람의
    말인가."* 복창은 **텍스트**로 재고 이건 **입력 방식**으로 잰다.

    ## ❗프롬프트에 안 들어간다

    모델에게 *"이 답은 붙여넣기였다"* 를 알려주면 **등급이 그 사실에 끌린다.** 루브릭이
    재는 것은 내용이고 입력 방식이 아니다. 후처리에서 **확신도만** 깎는다(P1).

    ## PII 가 없다

    숫자와 불리언뿐이다 — 서버가 타입으로 좁혀 보낸다(`AnswerRequest.InputMeta`).
    """

    first_keystroke_delay_ms: int = 0
    total_input_ms: int = 0
    paste_detected: bool = False
    backspace_count: int = 0
    char_count: int = 0
    elderly_mode: bool = False


class ScoreRequest(Strict):
    """고객 발화는 Spring PiiGateway.mask() 통과분만 온다 (P3). 입구에서 재검사한다."""

    item_id: str
    question: str
    answer_text: str
    risk_item: RiskItem
    product_type: ProductType = "ELS"
    input_meta: InputMeta | None = Field(
        default=None, description="F-INT-003 — 확신도 후처리에만. 프롬프트에 안 들어간다")


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
    cited_spans: list[SourceSpan] = Field(description="P6(0.2절) — 재설명 수치는 원문 인용만, 생성 후 대조 검증")


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


class MismatchDraft(Strict):
    """F-DET-002 LLM 초안 (계약 아님 — 내부 타입). `ExtractionDraft`·`QuestionDraft` 와 같은 층.

    ## 왜 초안 층이 필요한가 (이슈 #253)

    `SuitabilityMismatch` 의 세션 단위 불변식 셋(`mismatch↔contradictions` ·
    `insufficient_input` · `confidence==최고값`)이 **LLM 원문 검증에서 터졌다.**
    `complete_json()` 이 `model_validate()` 로 응답을 검증하는데, 그 불변식은
    `_finalize()` 가 **우리 손으로 계산하는 값**이라 LLM 이 맞출 이유가 없다.

        LLM 원문   confidence=0.86, 최고 모순 0.9
        → ValidationError → LlmError → 라우트 502 → 게이트 R-02 가 판정 없이 지나간다

    `_finalize()` 에 같은 입력을 주면 `confidence=0.9` 로 고친다. **값은 우리가 만드는데
    검증이 먼저 걸려 그 기회를 못 얻었다** — `_drop_llm_misconception_type` ·`_pin_item_id`
    ·`_pin_axis` 와 같은 계열이고, 그 셋은 후처리에 도달하는데 이것만 못 했다.

    `gemini-3.5-flash-lite` 가 두 값을 일관되게 맞춰 줘서 안 드러났을 뿐이다 — 프롬프트가
    그 일치를 요구하지 않으므로 보장이 아니다. 다른 모델에서 6/6 재현됐다.

    ## 무엇을 버리고 무엇을 남기나

    버리는 것은 **세션 단위 집계 불변식 셋뿐**이다. `Contradiction` 자체의 검증(`axis` enum
    ·`direction` enum ·`min_length` ·0~1 범위)은 그대로 산다 — 그건 LLM 이 실제로 지켜야
    하는 것이고, 어기면 그 모순은 근거로 쓸 수 없다(P4).

    `session_id`·`status`·`mismatch`·`reason`·`confidence` 를 안 받는 이유도 같다. 전부
    `_finalize()` 가 만든다. 받아 두고 버리면 *"모델이 낸 값이 어딘가 쓰인다"* 는 오해가 남는다.
    """

    contradictions: list[Contradiction] = Field(default_factory=list)

    #: **잉여 필드를 흘려보낸다.** `Strict` 의 `extra="forbid"` 를 여기서만 푼다 —
    #: 프롬프트가 세션 단위 필드를 요구하지 않아도 모델은 낸다(실측). 안 받으면 이번엔
    #: **잉여 필드로 터져서**, 고친 자리에서 같은 실패가 다른 이유로 반복된다.
    #: `ParsedDocument` 가 계약 밖의 `_expected_risk_items` 를 같은 이유로 흘려보낸다.
    #:
    #: 버리는 것이 안전한 이유: 그 필드들은 전부 `_finalize()` 가 계산하는 값이라
    #: **모델 값이 쓰일 자리가 없다.** 받아서 무시하는 것과 안 받는 것의 결과가 같다.
    model_config = ConfigDict(extra="ignore")


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


# ── 루브릭 후보 생성 (이슈 #474 ①) ──────────────────────────────────────────────
class RubricProposeRequest(Strict):
    """문서에서 루브릭 후보를 낸다. **파일을 쓰지 않는다 — 제안만 낸다.**

    ❗승인 산출물은 `ai-service/app/rubrics/*.yaml` **파일**이다. 루브릭을 DB 에 넣고
    채점이 거기서 읽으면 `verify_rubric_clause_is_published` 가 순환한다(P4) — 근거로
    적힌 조항을 **그 자리에서 만든 기준**과 대조하게 되고, 공개 의무·재현성이 같이 무너진다.
    `#358` 에서 검색 스택을 채점 시점에 안 붙인 이유와 같은 자리다.
    """

    parsed_document: ParsedDocument = Field(description="F-EXT-001 출력")
    item_ids: list[str] | None = Field(
        default=None,
        description="후보를 낼 항목. 안 주면 그 상품유형 템플릿의 항목 전부",
    )

    @property
    def product_type(self) -> str:
        """상품유형은 문서가 들고 있다 — `ExtractRequest` 와 같은 규약이다."""
        return self.parsed_document.product_type


class RubricEvidence(Strict):
    """후보 조항의 근거 원문. **스팬을 든다** (P6 · 1절).

    문자열만 주면 사람이 승인할 때 *"이게 정말 이 문서에 있나"* 를 눈으로 찾아야 한다.
    스팬이 있으면 화면이 원문에 표시할 수 있고, `pages[page].text[start:end] == text` 로
    기계가 대조할 수 있다 — `RiskItem.condition` 이 같은 규약을 쓰는 이유와 같다.
    """

    text: str
    spans: list[SourceSpan] = Field(
        default_factory=list,
        description="원문 위치. 여러 조각이면 이어붙인 것이 text 다(페이지를 걸치는 문장)",
    )


class RubricProposal(Strict):
    """항목 하나의 후보. **사람이 고치고 승인해서 YAML 이 된다.**"""

    item_id: str
    name: str
    required_elements: list[str] = Field(description="이해로 인정되려면 말해야 하는 것 — 후보")
    misconception_conditions: list[str] = Field(
        default_factory=list, description="말하면 오해로 보는 것 — 후보"
    )
    evidence: list[RubricEvidence] = Field(
        default_factory=list, description="후보의 근거가 된 문서 원문"
    )
    already_covered: list[str] = Field(
        default_factory=list,
        description="기존 루브릭 조항과 겹치는 후보 — 사람이 새것과 헷갈리지 않게 갈라 준다",
    )
    #: ❗`u1_requires` 를 **안 낸다.** 문턱은 문서에서 유도할 수 없는 **규범**이다 —
    #: `#367` 이 그 필드를 만든 이유가 "요소 수와 다를 수 있다" 였고, 몇 개면 이해로
    #: 볼지는 파는 쪽이 정해서 공개하는 판단이다. 모델이 채우면 그 판단이 숨는다.
    has_existing_rubric: bool = Field(
        default=False, description="이미 루브릭 파일이 있는 항목인가 (있으면 사각 보완용이다)"
    )


class RubricProposeResponse(Strict):
    document_id: str
    product_type: str
    proposals: list[RubricProposal]
    warnings: list[str] = Field(
        default_factory=list,
        description="후보를 못 낸 항목 등. 은폐하지 않고 노출한다 (E-EXT-03 과 같은 규약)",
    )


# ── 루브릭 «열람» (이슈 #474 ③) ──────────────────────────────────────────────
#
# ❗**공개 의무 대상이다.** 루브릭은 *"고객이 무엇을 말해야 이해로 보는가"* 라는 판정
# 기준이고, 파는 쪽이 정해서 **공개**하는 규범이다. 그래서 이 응답은 파일에 있는 것을
# 그대로 낸다 — 요약하거나 골라 내지 않는다.
#
# 이것이 P4 의 실물 근거이기도 하다. 채점의 `evidence.rubric_clause` 는
# `verify_rubric_clause_is_published` 로 **이 파일들 안에 실재하는 문장**임이 강제된다
# (`scoring.py`). 화면이 그 목록을 보일 수 있으면 *"판정 근거가 어디서 왔는가"* 를
# 심사자가 직접 대조할 수 있다.
class RubricView(Strict):
    """루브릭 하나. `app/rubrics/<item_id>.yaml` 의 내용 그대로다."""

    item_id: str
    product_type: str
    name: str
    status: str = Field(description="confirmed | draft — draft 는 아직 사람이 확정 안 한 것")
    required_elements: list[str] = Field(
        description="이해로 인정되려면 고객이 «자기 말로» 언급해야 하는 것"
    )
    #: ❗**요소 «개수»가 아니라 이 값이 U1 문턱이다** (`#367`). 화면이 요소 목록만 보이고
    #: 이 값을 안 보이면 *"이 전부를 말해야 한다"* 로 읽힌다 — `#450` 이 정확히 그 결함이었고
    #: `VAR-PARTIAL-DEPOSIT-INSURANCE` 는 요소 2 · 문턱 **1** 이다(부분적으로 참인 항목).
    u1_requires: int = Field(description="required_elements 중 몇 개를 충족해야 U1 인가")
    misconception_conditions: list[str] = Field(
        default_factory=list, description="고객이 말하면 오해(U4)로 보는 것"
    )
    related_misconceptions: list[str] = Field(
        default_factory=list, description="오해 라이브러리 유형ID"
    )
    #: 링크가 «비어 있는 이유». 빈 목록이 「해당 없음」인지 「아직 못 걸었다」인지 가른다
    #: (`#284`·`#396` — 침묵이 정상으로 읽히는 자리를 문면으로 가른다).
    unlinked_until: list[str] | None = Field(
        default=None, description="[근거, 빼는 조건] — 관련 오해가 비어 있는 이유"
    )


class RubricListResponse(Strict):
    rubrics: list[RubricView]
    #: 전체 개수. 필터를 걸어도 «분모» 를 화면이 알 수 있게 같이 낸다 — 걸러진 목록만
    #: 보이면 *"이게 전부"* 로 읽힌다(`R-00` 이 분모를 지키는 것과 같은 이유).
    total: int
