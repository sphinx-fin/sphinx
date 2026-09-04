"""F-INT-002 되말하기 질문 생성. 소유: 윤지석

기획서 5절 통제: *"유도심문이 되지 않도록 질문 유형을 화이트리스트로 제한하고, **질문 안에
정답이 노출되지 않게 한다**."*

되말하기는 고객이 **자기 말로** 설명하게 하는 것이다(기획서 4절). 질문이 답을 알려주면
그 항목은 측정 자체가 불가능해진다 — 채점이 통과하는데도 이해도를 잰 것이 아니게 된다.
그래서 **정답 노출 검사가 이 기능의 본체**다.

## 무엇을 정답으로 볼 것인가

두 곳에서 가져온다.

  1. **루브릭 `required_elements`** — 이해로 인정되려면 고객이 말해야 하는 것. 정의상 정답이다.
  2. **RiskItem 조건 원문의 숫자** — `amount`·`condition` 유형이 묻는 대상.
     **단위를 보지 않는다.** 원문이 `45%` 인데 질문이 "45 아래로 떨어지면" 이라고 해도
     답을 알려준 것이다. 재설명(F-INT-004)은 반대로 단위까지 일치해야 하는데, 그쪽은
     환각을 막는 것이고 이쪽은 정답 노출을 막는 것이라 규칙이 다르다.

`misconception_conditions` 는 정답이 아니라 오답이므로 검사하지 않는다. 다만 오답을
질문에 심으면 유도심문이 되므로 그것도 막는다.

## 실패하면 폴백

생성 질문이 MAX_ATTEMPTS 회 검사를 통과하지 못하면 템플릿의 `fallback_question` 을 낸다.
인터뷰가 멈추면 세션 자체가 진행되지 않는다 — 질문을 못 만드는 것은 채점을 못 하는 것보다
나쁘다. 폴백을 썼다는 사실은 `fallback_used` 로 노출한다.
"""
from __future__ import annotations

import logging
from pathlib import Path

from . import numerics, rubrics, templates
from .llm_client import LlmClient, LlmError, client as default_client
from .schemas import InterviewContext, QuestionDraft, QuestionResponse, RiskItem

PROMPT_PATH = Path(__file__).resolve().parent / "prompts" / "F-INT-002_v1.md"
PROMPT_VERSION = "F-INT-002_v1"

log = logging.getLogger(__name__)

QUESTION_TYPES = ("situation", "amount", "condition")
MAX_ATTEMPTS = 3

#: 정답 어구 대조에 쓰는 최소 길이. 짧은 조각은 우연히 겹친다("원금", "손실").
MIN_LEAK_NGRAM = 6

#: 폴백 질문이 보고하는 유형. 폴백 문장들이 전부 "…어떻게 되는지 말씀해 주시겠어요" 형태의
#: 상황설명형이라 사실에 맞고, **호출마다 달라지지 않는다.** 이전에는 allowed[0] 을 써서
#: 같은 문장이 1회차엔 situation, 2회차엔 amount 로 보고돼 유형 커버리지가 조용히 틀렸다
#: (PR #60 리뷰). `fallback_used=True` 가 유형이 선택된 것이 아님을 알린다.
FALLBACK_QUESTION_TYPE = "situation"


def generate(
    risk_item: RiskItem,
    asked_types: list[str] | None = None,
    product_type: str = "ELS",
    llm: LlmClient | None = None,
    *,
    variant: str = "initial",
    context: InterviewContext | None = None,
) -> QuestionResponse:
    """이해항목 → 되말하기 질문.

    ## `variant="reverify"` 가 왜 있나 — 7-4 1단계

    재설명 뒤 다시 묻는 질문이 서버에 **고정 문항으로 하드코딩**돼 있었고, 그 자리 주석이
    스스로 이렇게 적어 뒀다.

        이 목은 기획서 7-4 1단계(우회 비용 상향)를 만족하지 않는다. … 항목별로 갈리기만
        할 뿐 고정 문항이다. **사전에 확보하면 그대로 뚫린다.**

    7-4 가 요구하는 것은 *"질문이 상품 문서에서 자동 생성되므로 고정 문항을 사전에 확보하는
    것이 불가능"* 한 상태다. 재검증이야말로 **판매자가 미리 답을 준비시킬 동기가 가장 큰
    자리**라 — 첫 질문에서 이미 한 번 막혔으므로 — 여기가 고정이면 게이트가 뚫린다.

    ## 맥락은 무엇을 바꾸나

        vulnerable=True          한 번에 한 요소만 · 짧은 문장 (기획서 175행)
        matched_misconceptions   이미 걸린 오해를 정면으로 확인하는 쪽으로
        prior_grades             앞에서 계속 U3 면 더 쉬운 진입을 고른다
        variant="reverify"       "다시" 를 명시하고 같은 유형을 피한다
    """
    template_item = _template_item(risk_item.item_id, product_type)
    forbidden = answer_fragments(risk_item)
    allowed = [t for t in QUESTION_TYPES if t not in set(asked_types or ())] or list(QUESTION_TYPES)

    client_ = llm or default_client()
    attempts: list[str] = []           # 폴백으로 내려갈 때만 쓴다 — 왜 내려갔는지 (이슈 #234)
    for _ in range(MAX_ATTEMPTS):
        try:
            draft = client_.complete_json(
                prompt=build_prompt(risk_item, template_item, forbidden, asked_types, allowed,
                                    variant=variant, context=context),
                model_cls=QuestionDraft,
                schema_name="QuestionDraft",
                system=load_system_prompt(),
            )
        except LlmError as exc:
            attempts.append(f"llm_error({type(exc).__name__})")
            break                      # 폴백으로 내려간다 — 인터뷰를 멈추지 않는다
        if draft.question_type not in allowed:
            attempts.append(f"type_not_allowed({draft.question_type})")
            continue                   # 화이트리스트·중복 위반
        leaked = leaked_fragments(draft.question, forbidden)
        if leaked:
            attempts.append(f"leaked({'·'.join(leaked)})")
            continue
        return QuestionResponse(
            item_id=risk_item.item_id, question=draft.question,
            question_type=draft.question_type, fallback_used=False,
        )

    _log_fallback(risk_item.item_id, attempts, allowed)
    return _fallback(risk_item, template_item, variant=variant, context=context)


def _log_fallback(item_id: str, attempts: list[str], allowed: list[str]) -> None:
    """폴백으로 내려간 사실과 **이유**를 남긴다 (이슈 #234 1항).

    ## 왜 필요한가

    폴백은 LLM 이 만든 질문이 아니라 템플릿의 고정 문장이다. 인터뷰를 멈추지 않으려는
    설계이고 그건 맞지만, **비율이 높으면 F-INT-002 가 사실상 안 도는 것**이다.

    `#199` 작업 중 실제 생성에서 23건 중 6건(26%)이 폴백이었는데, 원인을 보려다 쿼터가
    소진돼 멈췄다. **그 뒤로 이 값을 다시 볼 방법이 없었다** — `fallback_used` 는 응답에
    실리지만 서버가 받아서 버리고(`AiServiceClient.Question` 이 안 들고 간다) 여기에도
    로그가 없었다. 화면·기록·로그 어디에도 안 남는다.

    ## 무엇을 남기나 — 이유가 본체다

    "폴백이 났다" 만으로는 고칠 수 없다. 세 갈래가 완전히 다른 일이기 때문이다.

        leaked(...)          프롬프트가 정답을 흘린다 → 프롬프트 수정
        type_not_allowed(…)  화이트리스트 밖 유형을 낸다 → 프롬프트 규칙
        llm_error(...)       모델이 안 돌았다 → 쿼터·네트워크. 우리 문제가 아니다

    누출 조각을 그대로 싣는 이유는 그것이 **어느 어휘가 새는지**를 바로 말해 주기 때문이다.
    조각의 출처는 루브릭과 조건 원문이고 **루브릭은 공개 의무 대상**이므로(기획서 5절)
    로그에 남겨도 새로 드러나는 것이 없다. 고객 발화는 이 경로에 아예 없다(P3).

    `warning` 이 아니라 `info` 다. 폴백은 설계된 정상 경로이고, 경고로 올리면 진짜 경고와
    같은 레벨에 섞인다(`#121` 이 로그 레벨 설정을 넣으며 세운 기준과 같다).
    """
    log.info(
        "F-INT-002 폴백: item_id=%s 시도=%d 허용유형=%s 사유=%s",
        item_id, len(attempts), ",".join(allowed), _collapse(attempts) or "(없음)",
    )


def _collapse(reasons: list[str]) -> str:
    """같은 사유가 이어지면 `×N` 으로 접는다.

    세 번 다 같은 조각이 새면 `leaked(45) | leaked(45) | leaked(45)` 가 되는데, 시도 횟수는
    이미 `시도=` 에 있으므로 같은 말을 두 번 하는 셈이다. **접어도 정보가 안 준다** —
    오히려 *"세 번 다 같은 원인"* 과 *"매번 다른 원인"* 이 한눈에 갈린다. 앞의 것은 프롬프트가
    일관되게 새는 것이고 뒤의 것은 모델이 흔들리는 것이라 할 일이 다르다.
    """
    out: list[str] = []
    for reason in reasons:
        if out and out[-1][0] == reason:
            out[-1][1] += 1
        else:
            out.append([reason, 1])
    return " | ".join(r if n == 1 else f"{r} ×{n}" for r, n in out)


# ── 정답 노출 검사 ────────────────────────────────────────────────────────────
def answer_fragments(risk_item: RiskItem) -> tuple[str, ...]:
    """질문에 들어가면 안 되는 문면 조각.

    루브릭이 없는 항목도 있다(recommended 항목 일부, 이슈 #26). 그 경우 조건 원문의
    수치만으로 검사한다 — 루브릭이 없다고 검사를 건너뛰면 그 항목만 유도심문이 통과한다.
    """
    fragments: list[str] = []
    fragments.extend(numerics.numbers(risk_item.require_condition().value_text))
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
    숫자에 한해서만 본다 — 숫자는 조사·어미가 붙지 않아 우연 일치가 거의 없다.

    **양쪽이 같은 정규화를 지나야 한다** (이슈 #183). `numbers()` 가 금지 조각에서 콤마를
    지우는데 질문 쪽은 안 지워서, **문서 표기를 그대로 옮긴 질문이 통과했다** — 계약 정답의
    콤마 수치 5건 전건이 그랬다. `for_leak_check()` 가 양쪽에서 자릿수 콤마를 지운다.
    """
    q = numerics.for_leak_check(question)
    hits = []
    for fragment in forbidden:
        f = numerics.for_leak_check(fragment)
        if not f:
            continue
        if len(f) < MIN_LEAK_NGRAM:
            if _short_fragment_leaked(f, question, q):
                hits.append(fragment)
            continue
        if f in q or _shares_long_run(f, q):
            hits.append(fragment)
    return hits


def _short_fragment_leaked(fragment: str, question: str, normalized: str) -> bool:
    """짧은 조각이 질문에 있는지. **수치는 토큰 동일성, 나머지는 부분열.**

    ## 수치를 부분열로 찾으면 안 된다 (이슈 #183 후속)

        조각 45   "낙인 45% 아래로"        잡아야 한다  ✅
        조각 45   "2045년 만기까지"        ❗오탐 — 연도 안
        조각  3   "13% 손실이 나면"        ❗오탐 — 13 안의 3

    한 자리·두 자리 수치는 한국어 질문에서 연도·비율·개월 수로 흔하므로 **구조적으로**
    오탐이 난다. `MIN_LEAK_NGRAM` 은 그 문제를 길이로 우회한 것이지 푼 것이 아니었다.

    ## 경계를 여기서 다시 정의하지 않는다 (PR #199 리뷰, 정세현)

    처음엔 문자열을 훑어 **양옆이 숫자가 아닌지**로 판단했다. 그게 `canonical()` 과 맞물려
    **진짜 누출을 놓쳤다.** `for_leak_check()` 는 공백을 지우므로 나란한 두 수가 숫자로
    인접해지고, 그 판단이 둘을 **하나의 수**로 읽는다.

        질문   '3개월 시점 900,000 526,240 58.4'
        정규화 '3개월시점90000052624058.4'
                          ^^^^^^^^^^^^ 526240 과 58.4 가 붙었다
        조각 '58.4'  →  놓친다

    표 한 행을 그대로 옮긴 질문이 정확히 이 모양이고(`#175`), `#184` 가 *"가장 자연스러운
    누출 방식"* 이라고 닫은 경로가 여기서 되살아난다. 긴 조각은 부분열 분기라 그대로 잡히고
    **짧은 것만 조용히 빠진다.**

    그래서 `numbers()` 가 이미 아는 것을 다시 만들지 않는다. 그 함수는 `_for_numbers()`
    (공백을 **접는다**, 지우지 않는다) 위에서 돌아 *"어디까지가 한 수인가"* 를 안다.
    **경계 정의를 추출기 한 곳에 남긴다** — 이 레포가 정규화를 한 벌로 두는 이유와 같다.

    ## 비숫자 조각은 부분열 그대로

    2~5자 한글 어구는 토큰 대조로는 안 걸린다. docstring 이 *"짧은 조각은 숫자에 한해서만
    본다"* 라고 이미 적고 있으므로 숫자 여부로 가른다 — 문면과 동작을 맞춘다.
    """
    if fragment.replace(".", "").isdigit():
        return fragment in numerics.numbers(question)
    return fragment in normalized


def _shares_long_run(fragment: str, question: str) -> bool:
    """조각의 연속 부분열이 질문에 통째로 들어있는지 (MIN_LEAK_NGRAM 이상)."""
    n = MIN_LEAK_NGRAM
    return any(fragment[i:i + n] in question for i in range(len(fragment) - n + 1))


# ── 폴백 ──────────────────────────────────────────────────────────────────────
#: 재검증 폴백 — 항목 문면을 안 쓴다. 항목별 고정 문장을 두면 **사전에 확보 가능한 문항**이
#: 다시 생기고, 그게 7-4 1단계가 막으려는 것이다(서버에 있던 하드코딩이 정확히 그 모양이었다).
#: 그래서 여기 있는 것은 **무엇을 물어야 하는지 항목이 안 정하는** 문장 하나뿐이고,
#: 눈높이만 갈린다. 항목이 안 실리므로 미리 준비해도 답이 안 되고, `fallback_used=True` 가
#: LLM 이 만든 것이 아님을 알린다.
REVERIFY_FALLBACK = "방금 설명드린 내용 중에서 가장 중요하다고 이해하신 것을 본인 말씀으로 다시 말씀해 주시겠어요?"
REVERIFY_FALLBACK_PLAIN = "방금 설명드린 것 중에 가장 중요한 게 뭐라고 이해하셨는지 편하게 말씀해 주시겠어요?"


def _fallback(
    risk_item: RiskItem,
    template_item: templates.TemplateItem,
    *,
    variant: str = "initial",
    context: InterviewContext | None = None,
) -> QuestionResponse:
    if variant == "reverify":
        plain = context is not None and context.vulnerable
        return QuestionResponse(
            item_id=risk_item.item_id,
            question=REVERIFY_FALLBACK_PLAIN if plain else REVERIFY_FALLBACK,
            question_type=FALLBACK_QUESTION_TYPE,
            fallback_used=True,
        )
    if not template_item.fallback_question:
        raise LlmError(
            f"{risk_item.item_id}: 생성 실패에 쓸 fallback_question 이 템플릿에 없다 — "
            "인터뷰를 멈추지 않으려면 항목마다 하나는 있어야 한다"
        )
    return QuestionResponse(
        item_id=risk_item.item_id,
        question=template_item.fallback_question,
        question_type=FALLBACK_QUESTION_TYPE,
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
    *,
    variant: str = "initial",
    context: InterviewContext | None = None,
) -> str:
    """조건 원문을 프롬프트에 넣지 않는다.

    원문에는 정답 수치가 그대로 들어있어서, 모델에게 보여주면 질문에 옮겨 쓸 가능성이
    올라간다. 모델이 알아야 하는 것은 *무엇을 묻는지*이고 *답이 무엇인지*가 아니다.
    """
    _, user = _prompt_sections()
    base = user.format(
        item_name=f"{risk_item.item_id} — {template_item.name}",
        answer_elements="\n".join(f"- {f}" for f in forbidden) or "- (없음)",
        asked_types=", ".join(asked_types or []) or "(없음)",
        allowed_types=", ".join(allowed),
    )
    extra = context_section(variant, context)
    return f"{base}\n{extra}" if extra else base


def context_section(variant: str, context: InterviewContext | None) -> str:
    """맥락을 프롬프트에 붙이는 절. 없으면 빈 문자열 — 옛 동작 그대로다.

    ❗**템플릿 파일을 안 고친다.** `F-INT-002_v1.md` 는 프롬프트 자산이고 버전이 판정
    기록에 실린다(`prompt_version`). 맥락은 요청마다 달라지는 값이라 자산이 아니라
    **조립**이다 — 템플릿에 넣으면 같은 버전이 서로 다른 프롬프트를 뜻하게 된다.

    ❗**정답이 들어갈 자리가 없다.** 등급과 유형 ID 만 온다(`InterviewContext` 참고).
    `leaked_fragments` 가 생성된 질문을 다시 검사하므로 여기서 새어도 질문에서 걸리지만,
    애초에 안 넣는 것이 맞다 — 검사는 최종 방어선이지 설계가 아니다.
    """
    lines: list[str] = []
    if variant == "reverify":
        lines.append(
            "- 이 질문은 **재설명 뒤 다시 묻는 것**이다. 고객이 앞서 같은 항목에 답했고 "
            "설명을 한 번 더 들었다. **같은 문장을 다시 쓰지 말고** 다른 각도로 묻는다.")
    if context is None:
        return "\n".join(lines) and "\n## 면담 맥락\n" + "\n".join(lines)

    if context.vulnerable:
        lines.append(
            "- 이 고객은 **눈높이를 낮춰야 한다**(연령·투자경험·금액대 정황). 한 번에 한 "
            "가지만 묻고, 문장을 짧게, 전문용어 대신 일상어로 쓴다.")
    if context.matched_misconceptions:
        lines.append(
            "- 이 면담에서 이미 확인된 오해가 있다: "
            + ", ".join(context.matched_misconceptions)
            + ". 그 오해가 이 항목에도 걸리는지 **정면으로 확인하는 쪽**으로 묻는다. "
              "다만 오해 문장을 질문에 옮겨 쓰지 않는다 — 그건 오답을 심는 것이다.")
    if context.prior_grades:
        low = sum(1 for g in context.prior_grades if getattr(g, "value", g) in ("U3", "U4"))
        if low >= 2:
            lines.append(
                f"- 앞선 {len(context.prior_grades)}개 항목 중 {low}개가 이해되지 않았다. "
                "**더 쉬운 진입**으로 시작한다 — 상황을 먼저 그려 주고 그다음에 묻는다.")
    return "\n".join(lines) and "\n## 면담 맥락\n" + "\n".join(lines)
