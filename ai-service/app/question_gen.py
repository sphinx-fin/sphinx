"""F-INT-002 되말하기 질문 생성. 소유: 윤지석

기획서 5절 통제: *"유도심문이 되지 않도록 질문 유형을 화이트리스트로 제한하고, **질문 안에
정답이 노출되지 않게 한다**."*

되말하기는 고객이 **자기 말로** 설명하게 하는 것이다(기획서 4절). 질문이 답을 알려주면
그 항목은 측정 자체가 불가능해진다 — 채점이 통과하는데도 이해도를 잰 것이 아니게 된다.
그래서 **정답 노출 검사가 이 기능의 본체**다.

## 무엇을 정답으로 볼 것인가

두 곳에서 가져온다.

  1. **루브릭 `required_elements`** — 이해로 인정되려면 고객이 말해야 하는 것. 정의상 정답이다.
  2. **RiskItem 조건 원문의 수치** — 퍼센트·금액·기간. `amount`·`condition` 유형이 묻는 대상.

`misconception_conditions` 는 정답이 아니라 오답이므로 검사하지 않는다. 다만 오답을
질문에 심으면 유도심문이 되므로 그것도 막는다.

## 실패하면 폴백

생성 질문이 MAX_ATTEMPTS 회 검사를 통과하지 못하면 템플릿의 `fallback_question` 을 낸다.
인터뷰가 멈추면 세션 자체가 진행되지 않는다 — 질문을 못 만드는 것은 채점을 못 하는 것보다
나쁘다. 폴백을 썼다는 사실은 `fallback_used` 로 노출한다.
"""
from __future__ import annotations

import re
import unicodedata
from pathlib import Path

from . import rubrics, templates
from .llm_client import LlmClient, LlmError, client as default_client
from .schemas import QuestionDraft, QuestionResponse, RiskItem

PROMPT_PATH = Path(__file__).resolve().parent / "prompts" / "F-INT-002_v1.md"
PROMPT_VERSION = "F-INT-002_v1"

QUESTION_TYPES = ("situation", "amount", "condition")
MAX_ATTEMPTS = 3

#: 조건 원문에서 정답으로 취급하는 수치. 퍼센트·금액·기간·배수.
_NUMERIC = re.compile(r"\d+(?:\.\d+)?\s*(?:%|퍼센트|원|만원|억|개월|년|영업일|배)")
#: 정답 어구 대조에 쓰는 최소 길이. 짧은 조각은 우연히 겹친다("원금", "손실").
MIN_LEAK_NGRAM = 6

_WS = re.compile(r"\s+")


def generate(
    risk_item: RiskItem,
    asked_types: list[str] | None = None,
    product_type: str = "ELS",
    llm: LlmClient | None = None,
) -> QuestionResponse:
    """이해항목 → 되말하기 질문."""
    template_item = _template_item(risk_item.item_id, product_type)
    forbidden = answer_fragments(risk_item)
    allowed = [t for t in QUESTION_TYPES if t not in set(asked_types or ())] or list(QUESTION_TYPES)

    client_ = llm or default_client()
    for _ in range(MAX_ATTEMPTS):
        try:
            draft = client_.complete_json(
                prompt=build_prompt(risk_item, template_item, forbidden, asked_types, allowed),
                model_cls=QuestionDraft,
                schema_name="QuestionDraft",
                system=load_system_prompt(),
            )
        except LlmError:
            break                      # 폴백으로 내려간다 — 인터뷰를 멈추지 않는다
        if draft.question_type not in allowed:
            continue                   # 화이트리스트·중복 위반
        if leaked_fragments(draft.question, forbidden):
            continue
        return QuestionResponse(
            item_id=risk_item.item_id, question=draft.question,
            question_type=draft.question_type, fallback_used=False,
        )

    return _fallback(risk_item, template_item, allowed)


# ── 정답 노출 검사 ────────────────────────────────────────────────────────────
def _canonical(text: str) -> str:
    """대조용 정규화 — NFC + 공백 제거. scoring·mismatch 와 같은 규칙."""
    return _WS.sub("", unicodedata.normalize("NFC", text))


def answer_fragments(risk_item: RiskItem) -> tuple[str, ...]:
    """질문에 들어가면 안 되는 문면 조각.

    루브릭이 없는 항목도 있다(커버리지 7/23, 이슈 #26). 그 경우 조건 원문의 수치만으로
    검사한다 — 루브릭이 없다고 검사를 건너뛰면 그 항목만 유도심문이 통과한다.
    """
    fragments: list[str] = []
    fragments.extend(_NUMERIC.findall(risk_item.condition.value_text))
    try:
        rubric = rubrics.get(risk_item.item_id)
    except rubrics.RubricNotFound:
        return tuple(dict.fromkeys(fragments))
    fragments.extend(rubric.required_elements)
    fragments.extend(rubric.misconception_conditions)   # 오답 심기도 유도심문이다
    return tuple(dict.fromkeys(fragments))


def leaked_fragments(question: str, forbidden: tuple[str, ...]) -> list[str]:
    """질문에 정답 조각이 들어갔는지. 걸린 조각 목록을 돌려준다.

    긴 어구는 부분 포함으로도 잡는다 — 루브릭 조항을 그대로 쓰지 않아도 핵심 구절만
    옮기면 답을 알려준 것이다. 짧은 조각(MIN_LEAK_NGRAM 미만)은 우연 일치가 많아
    수치에 한해서만 본다.
    """
    q = _canonical(question)
    hits = []
    for fragment in forbidden:
        f = _canonical(fragment)
        if not f:
            continue
        if len(f) < MIN_LEAK_NGRAM:
            if f in q:                                  # 수치 등 짧은 조각
                hits.append(fragment)
            continue
        if f in q or _shares_long_run(f, q):
            hits.append(fragment)
    return hits


def _shares_long_run(fragment: str, question: str) -> bool:
    """조각의 연속 부분열이 질문에 통째로 들어있는지 (MIN_LEAK_NGRAM 이상)."""
    n = MIN_LEAK_NGRAM
    return any(fragment[i:i + n] in question for i in range(len(fragment) - n + 1))


# ── 폴백 ──────────────────────────────────────────────────────────────────────
def _fallback(
    risk_item: RiskItem, template_item: templates.TemplateItem, allowed: list[str]
) -> QuestionResponse:
    if not template_item.fallback_question:
        raise LlmError(
            f"{risk_item.item_id}: 생성 실패에 쓸 fallback_question 이 템플릿에 없다 — "
            "인터뷰를 멈추지 않으려면 항목마다 하나는 있어야 한다"
        )
    return QuestionResponse(
        item_id=risk_item.item_id,
        question=template_item.fallback_question,
        question_type=allowed[0],
        fallback_used=True,
    )


def _template_item(item_id: str, product_type: str) -> templates.TemplateItem:
    template = templates.get(product_type)
    for item in template.items:
        if item.item_id == item_id:
            return item
    raise templates.TemplateNotFound(
        f"{product_type} 템플릿에 없는 항목: {item_id} — 추출 범위 밖이다"
    )


# ── 프롬프트 ──────────────────────────────────────────────────────────────────
def _prompt_sections() -> tuple[str, str]:
    text = PROMPT_PATH.read_text(encoding="utf-8")
    system = text.split("## system", 1)[1].split("## user", 1)[0].strip()
    user = text.split("## user", 1)[1].strip()
    return system, user


def load_system_prompt() -> str:
    return _prompt_sections()[0]


def build_prompt(
    risk_item: RiskItem,
    template_item: templates.TemplateItem,
    forbidden: tuple[str, ...],
    asked_types: list[str] | None,
    allowed: list[str],
) -> str:
    """조건 원문을 프롬프트에 넣지 않는다.

    원문에는 정답 수치가 그대로 들어있어서, 모델에게 보여주면 질문에 옮겨 쓸 가능성이
    올라간다. 모델이 알아야 하는 것은 *무엇을 묻는지*이고 *답이 무엇인지*가 아니다.
    """
    _, user = _prompt_sections()
    return user.format(
        item_name=f"{risk_item.item_id} — {template_item.name}",
        answer_elements="\n".join(f"- {f}" for f in forbidden) or "- (없음)",
        asked_types=", ".join(asked_types or []) or "(없음)",
        allowed_types=", ".join(allowed),
    )
