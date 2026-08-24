"""F-SCR-001 채점. 소유: 윤지석

기획서 5절: "이 서비스에서 AI가 하는 가장 중요한 일이다."
통제 셋을 코드로 고정한다 — 루브릭 대입, 근거 표시 의무(P4), 보수적 임계값.

**오판 비용이 비대칭이다** (기획서 5절 [채점 성능 목표와 오판 처리]):
  - 오해→이해 오판: 서비스의 존재 이유가 무너진다. 상한 1%.
  - 이해→오해 오판: 마찰. 재응답 경로가 있으므로 관리 지표(10%).
그래서 후처리는 전부 한 방향으로만 움직인다 — 안전한 쪽(낮은 등급)으로.

신뢰도 기반 황색 강등은 이 모듈이 하지 않는다. 게이트 정책이므로 gate_rules.yaml이
가진다(강희진 결정). 우리는 grade와 confidence를 정직하게 내보내는 것까지만 한다.

출력은 contracts/judgment.schema.json. 이건 *측정값*이며 게이트 판정이 아니다 (P1).
"""
from __future__ import annotations

import re
from pathlib import Path

from . import misconception, rubrics
from .llm_client import LlmClient, LlmError, client as default_client
from .schemas import Grade, Judgment, MisconceptionResponse, RiskItem

PROMPT_PATH = Path(__file__).resolve().parent / "prompts" / "F-SCR-001_v1.md"
PROMPT_VERSION = "F-SCR-001_v1"

# 신뢰도 기반 황색 강등은 **여기서 하지 않는다.**
# 강희진 결정(PR #10 리뷰): P1 경계상 게이트 정책이 채점에 섞이지 않아야 하므로
# ai-service는 진짜 등급 + confidence만 내고, gate_rules.yaml의 confidence 룰이 처리한다.
# 양쪽 다 하면 이중계산이 된다. 임계값(0.7)의 소유도 게이트로 넘어갔다.
# 근거와 선택지 비교: proposals/F-SCR-001-yellow-downgrade.md

_WS = re.compile(r"\s+")
#: 조항 합성 인용에서 허용하는 연결어·구분자. 이것만 남으면 공개 조항으로 환원된 것이다.
_CLAUSE_JOINERS = re.compile(r"[및,·/\-~()\[\]]|그리고|또는|와|과")


def score(
    item_id: str,
    question: str,
    answer_text: str,
    risk_item: RiskItem,
    product_type: str = "ELS",
    llm: LlmClient | None = None,
) -> Judgment:
    """고객 발화 → Judgment(측정값)."""
    rubric = rubrics.get(item_id)
    matched = misconception.match(answer_text, product_type)

    judgment = (llm or default_client()).complete_json(
        prompt=build_prompt(rubric, risk_item, question, answer_text),
        model_cls=Judgment,
        schema_name="Judgment",
        system=load_system_prompt(),
    )

    judgment = _pin_item_id(judgment, item_id)
    judgment = _drop_llm_misconception_type(judgment)
    verify_quote_is_verbatim(judgment, answer_text)
    verify_rubric_clause_is_published(judgment, rubric)
    return apply_misconception_floor(judgment, matched, rubric)


# ── 프롬프트 ───────────────────────────────────────────────────────────────────
def _prompt_sections() -> tuple[str, str]:
    """프롬프트 파일에서 system/user 절을 잘라낸다. 프롬프트는 코드가 아니라 산출물이다."""
    text = PROMPT_PATH.read_text(encoding="utf-8")
    system = text.split("## system", 1)[1].split("## user", 1)[0].strip()
    user = text.split("## user", 1)[1].strip()
    return system, user


def load_system_prompt() -> str:
    return _prompt_sections()[0]


def build_prompt(rubric: rubrics.Rubric, risk_item: RiskItem, question: str,
                 answer_text: str) -> str:
    _, template = _prompt_sections()
    return template.format(
        condition_text=risk_item.condition.value_text,
        item_id=rubric.item_id,
        item_name=rubric.name,
        required_elements="\n".join(f"- {e}" for e in rubric.required_elements),
        misconception_conditions="\n".join(f"- {c}" for c in rubric.misconception_conditions)
        or "- (없음)",
        question=question,
        answer_text=answer_text,
    )


# ── 후처리: 전부 안전한 방향으로만 움직인다 ────────────────────────────────────
def _pin_item_id(judgment: Judgment, item_id: str) -> Judgment:
    """LLM이 item_id를 바꿔 쓰는 것을 허용하지 않는다 — 호출자가 지정한 항목이 진실이다."""
    if judgment.item_id == item_id:
        return judgment
    return judgment.model_copy(update={"item_id": item_id})


def _drop_llm_misconception_type(judgment: Judgment) -> Judgment:
    """LLM이 채운 misconception_type을 버린다. 유형ID는 라이브러리에서만 온다.

    실측에서 모델이 `M-PRINCIPAL-GUARANTEE`처럼 **존재하지 않는 유형ID를 지어냈다.**
    유형ID는 오해 지도 대시보드 집계 키이고 분쟁조정례 근거와 1:1로 묶여야 하므로,
    환각된 ID가 하나 섞이면 집계가 조용히 오염된다. 기획서 5절의 "라이브러리 기반이라
    재현성이 확보되고 LLM은 변형된 표현을 커버하는 역할만" 을 그대로 적용한다.
    """
    if judgment.misconception_type is None:
        return judgment
    return judgment.model_copy(update={"misconception_type": None})


def verify_quote_is_verbatim(judgment: Judgment, answer_text: str) -> None:
    """근거 인용이 실제 발화에서 나온 것인지 대조한다 (P4).

    지어낸 인용은 근거가 없는 것보다 나쁘다 — 감사 시점에 검증할 수 없는 기록이 남는다.
    공백만 정규화하고, 그 외의 변형은 허용하지 않는다.
    """
    quote = _WS.sub("", judgment.evidence.utterance_quote)
    if quote and quote in _WS.sub("", answer_text):
        return
    raise LlmError(
        f"근거 인용이 발화에 없음 (P4 위반): item_id={judgment.item_id} "
        f"quote={judgment.evidence.utterance_quote!r}"
    )


def verify_rubric_clause_is_published(judgment: Judgment, rubric: rubrics.Rubric) -> None:
    """인용된 루브릭 조항이 **공개된 루브릭 안의 것**인지 대조한다 (P4, 강희진 리뷰).

    `utterance_quote`는 발화 원문과 대조하는데 `rubric_clause`는 비어있지만 않으면
    통과하던 비대칭을 없앤다. 기획서 5절의 통제는 *"루브릭을 공개하고, 근거 표시를
    의무화한다"* 이므로, 근거로 적힌 조항이 공개된 루브릭에 없으면 그 의무가 형식만 남는다.

    모델이 조항 두 개를 "A 및 B"처럼 합쳐 인용하는 경우가 실측에서 나왔다. 합성 인용도
    공개 조항으로 환원되면 추적 가능하므로 허용한다 — 인용문에서 조항들을 걷어낸 잔여가
    구분자·공백뿐이면 통과다. 잔여에 내용이 남으면 루브릭 밖의 문장을 만든 것이다.
    """
    published = tuple(rubric.required_elements) + tuple(rubric.misconception_conditions)
    cited = _WS.sub("", judgment.evidence.rubric_clause)

    residual = cited
    for clause in sorted(published, key=len, reverse=True):
        residual = residual.replace(_WS.sub("", clause), "")
    if not _CLAUSE_JOINERS.sub("", residual):
        return

    raise LlmError(
        f"루브릭 밖의 조항을 인용했다 (P4 위반): item_id={judgment.item_id} "
        f"clause={judgment.evidence.rubric_clause!r}"
    )


def apply_misconception_floor(
    judgment: Judgment, matched: MisconceptionResponse, rubric: rubrics.Rubric
) -> Judgment:
    """오해 라이브러리가 잡은 것은 U4 아래로 내려가지 않는다.

    라이브러리 매칭은 결정론적이고 근거가 분쟁조정례다(기획서 5절). LLM이 이해로
    판정했더라도 그 진술이 이미 분쟁까지 간 오해 문장이면 오해로 본다 —
    오해→이해 오판 상한 1%를 지키는 쪽으로만 움직인다.

    단, **루브릭이 관련 유형으로 선언한 오해만** 이 항목의 판정을 바꾼다.
    다른 항목의 오해가 이 항목 등급을 끌어내리면 안 된다.
    """
    relevant = [m for m in matched.matches if m.type_id in rubric.related_misconceptions]
    if not relevant:
        return judgment

    top = max(relevant, key=lambda m: m.score)
    update: dict[str, object] = {"misconception_type": top.type_id}
    if judgment.grade is not Grade.U4:
        update["grade"] = Grade.U4
        update["reason"] = (
            f"{judgment.reason} (오해 라이브러리 {top.type_id} 매칭 "
            f"[{top.stage} {top.score}] → U4 상향)"
        )
    return judgment.model_copy(update=update)
