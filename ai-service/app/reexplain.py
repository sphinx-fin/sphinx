"""F-INT-004 맞춤 재설명 콘텐츠. 소유: 윤지석 (루프 오케스트레이션은 강희진)

기획서 4절: *"고객의 이해 수준과 연령·경험에 맞춰 설명을 다시 만든다. 고령 고객에게는
비유 중심으로, 짧은 문장으로, 숫자는 그림으로, 큰 글씨로 바꾼다."*
*"설명 시간을 줄이는 것이 목표가 아니라 짧은 시간에 이해되게 만드는 것이 목표다."*

기획서 5절 통제: *"상품 조건값은 원문 인용만 허용하고, **AI가 수치를 생성하지 못하게 막는다**."*

## 여기는 질문 생성과 반대다

F-INT-002 는 질문에서 수치를 **금지**한다(정답 노출). F-INT-004 는 수치를 **써야** 하지만
**원문에서 온 것만** 써야 한다. 그래서 검사 방향이 뒤집힌다 — 생성된 설명의 모든 수치가
`risk_item.condition.value_text` 에 있는지 대조한다(P6).

환각 수치가 고객에게 노출되면 이 시스템의 존재 이유가 무너진다. 오해를 잡겠다면서 새로운
오해를 만드는 것이다. 그래서 검사에 걸리면 재시도하고, 끝내 안 되면 **원문 조건을 그대로
제시하는 최소 설명**으로 내려간다 — 그건 정의상 P6 안전하다.
"""
from __future__ import annotations

from pathlib import Path

from . import numerics, templates
from .llm_client import LlmClient, LlmError, client as default_client
from .schemas import Grade, Judgment, ReexplainResponse, RiskItem, SourceSpan

PROMPT_PATH = Path(__file__).resolve().parent / "prompts" / "F-INT-004_v1.md"
PROMPT_VERSION = "F-INT-004_v1"

MAX_ATTEMPTS = 3

GRADE_LABELS = {
    Grade.U1: "이해", Grade.U2: "부분이해", Grade.U3: "미이해", Grade.U4: "오해",
}

#: 기획서 3절이 1순위 대상으로 지정한 층. 연령대를 받지 못하면 이 기준으로 쓴다.
DEFAULT_AUDIENCE_NOTE = (
    "듣는 사람은 60대 이상 고령 고객이라고 가정한다. 비유를 중심으로, 문장을 짧게, "
    "전문용어 없이 쓴다."
)

def reexplain(
    risk_item: RiskItem,
    judgment: Judgment,
    age_band: str | None = None,
    experience_level: str | None = None,
    llm: LlmClient | None = None,
) -> ReexplainResponse:
    """오해·미이해 판정 → 맞춤 재설명."""
    if risk_item.condition is None:
        # 계약이 허용하는 값이다(status=extraction_failed → condition: null). 여기만 거부하지
        # 않는 이유는 `_minimal()` 이 **이 경우를 위해 이미 설계돼 있기** 때문이다 — 인용
        # 형식을 쓰지 않고 cited_spans 를 비운다(#60 리뷰). 강희진의 재검증 루프가 진행할
        # 것을 주는 쪽이 낫고, 그 문면은 P4·P6 을 어기지 않는다.
        return _minimal(risk_item)

    allowed = source_numerics(risk_item)
    units = declared_units(risk_item)
    client_ = llm or default_client()

    for _ in range(MAX_ATTEMPTS):
        try:
            draft = client_.complete_json(
                prompt=build_prompt(risk_item, judgment, age_band, experience_level),
                model_cls=ReexplainResponse,
                schema_name="ReexplainResponse",
                system=load_system_prompt(),
            )
        except LlmError:
            break                      # 루프를 멈추지 않는다 — 최소 설명으로 내려간다
        if fabricated_numerics(draft.content, allowed, units):
            continue
        return ReexplainResponse(
            item_id=risk_item.item_id,
            content=draft.content,
            cited_spans=_cited_spans(draft.content, risk_item, allowed),
        )

    return _minimal(risk_item)


# ── P6: 수치 대조 ─────────────────────────────────────────────────────────────
def source_numerics(risk_item: RiskItem) -> frozenset[tuple[str, str | None]]:
    """조건 원문에 있는 수치. 설명에서 쓸 수 있는 것의 전부다."""
    return numerics.source_values(risk_item.require_condition().value_text)


def declared_units(risk_item: RiskItem) -> tuple[str, ...]:
    """항목이 선언한 단위. 표 셀 항목에만 있다 (이슈 #175).

    템플릿을 못 찾으면 빈 튜플이다 — **없는 쪽이 엄격한 방향**이므로 조용히 느슨해지지
    않는다. 상품유형은 `item_id` 접두어가 아니라 템플릿 대조로 찾는다(접두어 규약은
    계약에 없다).
    """
    for template in templates.all_templates().values():
        for item in template.items:
            if item.item_id == risk_item.item_id:
                return item.units
    return ()


def fabricated_numerics(content: str, allowed, units: tuple[str, ...] = ()) -> list[str]:
    """설명에 있는데 원문에는 없는 수치. 비어있어야 통과다.

    **단위 부류까지 본다**(numerics.UNIT_CLASSES). 숫자만 재사용하면 `45%` 배리어를
    "45년 기다리면 원금을 돌려받는다" 로 바꿔 말하는 것이 통과하고, 그건 오해를 잡겠다면서
    새 오해를 만드는 것이다(PR #60 리뷰 ③).
    """
    return numerics.fabricated(content, allowed, units)


def _cited_spans(
    content: str, risk_item: RiskItem, allowed: frozenset[str]
) -> list[SourceSpan]:
    """설명이 원문에 근거한다면 그 스팬을 남긴다 (리포트 근거).

    입력이 항목 하나이므로 인용 대상도 그 항목의 스팬 하나다. 수치를 하나라도 옮겨 썼거나
    조건 문면의 긴 조각을 인용했으면 근거로 기록한다.
    """
    canon_content = numerics.canonical(content)
    used_number = any(n in canon_content for n, _ in allowed)
    condition = numerics.canonical(risk_item.require_condition().value_text)
    used_phrase = any(
        condition[i:i + 12] in canon_content for i in range(max(0, len(condition) - 11))
    )
    return [risk_item.require_condition().source_span] if (used_number or used_phrase) else []


# ── 폴백 ──────────────────────────────────────────────────────────────────────
def _minimal(risk_item: RiskItem) -> ReexplainResponse:
    """원문 조건을 그대로 제시하는 최소 설명.

    재설명을 아예 못 내면 강희진의 재검증 루프가 진행할 것이 없다. 다듬어지지 않은 원문이라도
    보여주는 편이 낫고, 무엇보다 **환각 수치가 섞인 설명보다 낫다.**

    **`extraction_failed` 항목은 원문 인용 형식을 쓰지 않는다** (PR #60 리뷰 ②).
    그 항목의 `condition.value_text` 는 실패 사유 문면이고 문서에 없는 문장이다. 그것을
    "설명서에는 이렇게 적혀 있습니다" 로 인용하면
      - P6 — 상품설명서에 없는 문장을 원문 인용으로 고객 화면에 낸다
      - P4 — `text[0:0]` 인 빈 슬라이스를 가리키는 근거가 리포트에 남는다
    `cited_spans` 도 비운다. 이 파일이 *"근거 없는 설명에 스팬을 붙이면 리포트가 거짓 근거를
    갖는다"* 고 쓰고 있는데 폴백이 그 예외가 되어 있었다.
    """
    if risk_item.status == "extraction_failed":
        return ReexplainResponse(
            item_id=risk_item.item_id,
            content=(
                f"{risk_item.name}은(는) 상품 문서에서 해당 내용을 확인하지 못했습니다.\n\n"
                "담당자에게 이 항목의 조건을 직접 확인해 주세요."
            ),
            cited_spans=[],
        )
    return ReexplainResponse(
        item_id=risk_item.item_id,
        content=(
            f"{risk_item.name}에 대해 상품 설명서에는 이렇게 적혀 있습니다.\n\n"
            f"“{risk_item.require_condition().value_text}”\n\n"
            "이 부분을 담당자와 함께 다시 확인해 주세요."
        ),
        cited_spans=[risk_item.require_condition().source_span],
    )


# ── 프롬프트 ──────────────────────────────────────────────────────────────────
def _prompt_sections() -> tuple[str, str]:
    text = PROMPT_PATH.read_text(encoding="utf-8")
    system = text.split("## system", 1)[1].split("## user", 1)[0].strip()
    user = text.split("## user", 1)[1].strip()
    return system, user


def load_system_prompt() -> str:
    return _prompt_sections()[0]


def audience_note(age_band: str | None, experience_level: str | None) -> str:
    if not age_band and not experience_level:
        return DEFAULT_AUDIENCE_NOTE
    parts = []
    if age_band:
        parts.append(f"연령대 {age_band}")
    if experience_level:
        parts.append(f"투자경험 {experience_level}")
    return (
        f"듣는 사람은 {', '.join(parts)} 이다. 그에 맞춰 어휘와 문장 길이를 조절한다. "
        "경험이 적으면 비유를 더 쓰고, 고령이면 문장을 더 짧게 한다."
    )


def build_prompt(
    risk_item: RiskItem, judgment: Judgment,
    age_band: str | None = None, experience_level: str | None = None,
) -> str:
    _, user = _prompt_sections()
    misconception = (
        f"오해 유형: {judgment.misconception_type}"
        if judgment.misconception_type else "오해 유형: (라이브러리 미매칭)"
    )
    prompt = user.format(
        item_name=f"{risk_item.item_id} — {risk_item.name}",
        condition_text=risk_item.require_condition().value_text,
        grade=judgment.grade.value,
        grade_label=GRADE_LABELS[judgment.grade],
        misconception_note=misconception,
        reason=judgment.reason,
        utterance=judgment.evidence.utterance_quote,
    )
    system_note = audience_note(age_band, experience_level)
    return prompt.replace("{audience_note}", system_note)
