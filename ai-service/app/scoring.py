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

import logging

import re
import unicodedata
from pathlib import Path

from . import misconception, rubrics, textsim
from .llm_client import LlmClient, LlmError, client as default_client
from .schemas import Grade, Judgment, MisconceptionResponse, RiskItem

PROMPT_PATH = Path(__file__).resolve().parent / "prompts" / "F-SCR-001_v2.md"
PROMPT_VERSION = "F-SCR-001_v2"

log = logging.getLogger(__name__)

#: 이 값 이상이면 발화가 문서·루브릭 문면을 옮긴 것으로 본다. 오해 라이브러리의
#: `NGRAM_THRESHOLD`(0.62)와 같은 계산이지만 숫자는 따로 둔다 — 저쪽은 "짧은 패턴이 발화에
#: 있는가", 이쪽은 "발화가 긴 문면에서 잘려나왔는가"로 분포가 다르다.
#:
#: **dev set 실측(LLM 불필요, `test_echo_threshold_has_margin` 이 고정)**
#:
#:     실제 발화 11건        최대 0.256  (VAR-PRINCIPAL-UNDERSTOOD)
#:     문면을 그대로 옮김     1.000
#:     어미만 바꾼 부분 복창   0.800
#:
#: 0.6 은 실측 최대의 2.3배이고 부분 복창(0.80) 아래다. 처음 0.85 로 뒀더니 어미가 바뀐
#: 부분 복창을 놓쳤다 — 복창은 보통 어미를 자기 말로 바꿔 온다.
ECHO_THRESHOLD = 0.6

#: 복창 판정의 발화 길이 하한(정규화 후 문자 바이그램 개수). 이보다 짧으면 `echo_score` 가
#: 0.0 을 준다 — 계산이 성립하지 않는 구간이다.
#:
#: `containment` 의 분모가 발화 바이그램 수이므로 **분모가 작으면 우연 일치가 점수를
#: 지배한다.** PR #114 리뷰(정세현)에서 지적됐고 실측으로 재현됐다.
#:
#:     발화            바이그램   containment(조항 "투자원금의 손실이 발생할 수 있음")
#:     "손실"              1개     1.000   ← 복창이 아닌데 상한이 걸린다
#:     "원금 손실"          3개     0.667   ← 임계 0.6 초과
#:     "원금이 깎여요"       5개     0.200   안전
#:
#: 하한의 근거 — 세 숫자 사이에 둔다.
#:
#:     오발동 실측 구간        1 ~ 3개
#:     루브릭 조항 최단         6개  ("보호 한도가 있다", VAR-PARTIAL-DEPOSIT-INSURANCE)
#:     dev set 실제 발화 최단   9개  ("낸 돈은 다 돌려받는 거죠?")
#:
#: 처음 8 로 뒀다가 required 루브릭 10종(#112)이 합쳐지면서 깨졌다 — 조항 최단이 13개에서
#: 6개로 내려왔고, 하한 8 이면 **그 조항을 그대로 옮긴 복창을 놓친다.** 테스트가 잡았다.
#: 하한은 `오발동 구간 < 하한 < 조항 최단` 을 만족해야 하므로 4~5 만 가능하고 5 를 택했다.
#:
#: 이 하한은 **관대한 방향**이다(상한을 덜 씌운다). P4·P5 위반이 아닌 이유: 짧은 발화로
#: U1 을 받으려면 필수 요소를 다 말해야 하는데 그 길이로는 불가능하고, U2 이하는 게이트
#: R-04 가 이미 YELLOW 로 잡는다. 하한에 걸린 경우는 로그로 남겨 빈도를 본다.
MIN_ECHO_BIGRAMS = 5

#: 실제 발화의 최대 echo 와 `ECHO_THRESHOLD` 사이에 있어야 하는 최소 간격.
#: 여유가 없으면 정상 이해가 황색이 된다(기획서 5절 관리지표 10%).
#:
#: 실측 최대는 `VAR-DEPOSIT-FULL`(0.368)이다 — 예금자보호 항목은 발화와 조항이 **같은 법률
#: 용어**를 쓰므로 구조적으로 높다. 복창이 아니라 어휘가 겹치는 것이고, 이 항목군에서는
#: 여유가 늘 좁을 것이다. 상수로 드러내 두면 발화를 추가할 때 어디까지 좁혀졌는지 보인다.
ECHO_MARGIN_MIN = 0.15

#: 복창일 때 씌우는 confidence 상한. 게이트 R-05(`anyConfidenceBelow 0.7`)가 이걸 받는다.
#: **임계값 자체는 게이트 소유다**(ADR-005) — 여기 있는 건 측정값의 상한이지 판정 정책이 아니다.
ECHO_CONFIDENCE_CAP = 0.3

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
    judgment = cap_confidence_if_echoed(judgment, answer_text, rubric, risk_item)
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


# ── 문면 복창: 모델에게 묻지 않고 계산한다 ─────────────────────────────────────
def echo_score(answer_text: str, rubric: rubrics.Rubric, risk_item: RiskItem) -> float:
    """발화가 문서·루브릭 문면을 그대로 옮긴 정도. 0.0~1.0.

    프롬프트 v2 는 confidence 를 *"다른 채점자에게도 같게 나올 것인가"* 로 정의했고, 그
    정의에서 가장 중요한 경우가 **복창**이다 — 요소는 다 들어 있지만 자기 말로 이해한 것인지
    따라 말한 것인지 발화만으로는 가려지지 않는다(기획서 4절 *"자기 말로 설명"*).

    이걸 모델에게 물었더니 못 했다. 같은 내용에 종결어미만 바꾼 절제 실험에서

        "…원금이 깎여서 나온다고 들었어요"   → 0.30
        "…원금이 깎여서 나옵니다"            → 0.90
        "투자원금의 손실이 발생할 수 있습니다" → 0.30   (진짜 복창)

    이 나왔다. 전문(傳聞) 종결이 복창으로 읽힌다. 어미를 판단 재료로 쓰지 말라는 지시를
    프롬프트에 세 번(문장 · 절제 규칙 · 예시) 넣어도 flash-lite 는 바뀌지 않았고, 오발동이
    진짜 복창과 같은 밴드(0.3)에 있어 게이트 임계값으로도 갈리지 않았다.

    그래서 계산으로 내린다 — 스팬을 원문에서 계산하고 유형 ID 를 라이브러리에서만 받는 것과
    같은 층이다(`모델 출력을 그대로 믿지 않는다`). 임계값이 숫자로 드러나 재현되고 심사에서
    설명 가능해진다.

    **너무 짧은 발화는 계산하지 않는다** — `MIN_ECHO_BIGRAMS` 참고. 분모가 작으면 우연
    일치가 점수를 지배해서, 복창이 아닌 두 글자 답변이 1.000 을 받는다.

    대조 대상은 **고객이 옮겨 적을 수 있는 문면**이다.
    - `condition.value_text` — 상품문서 원문 조항
    - `rubric.required_elements` — 정답 문면. 루브릭은 공개 의무 대상이라 판매자가 읽을 수 있다

    방향은 `containment(발화, 문면)` 이다 — **발화의 바이그램이 문면에 얼마나 있는지**.
    반대 방향으로 재면 긴 원문을 짧게 인용한 발화가 항상 낮게 나와 복창이 안 잡힌다.
    """
    grams = len(textsim.bigrams(textsim.normalize(answer_text)))
    if grams < MIN_ECHO_BIGRAMS:
        log.info(
            "복창 판정 생략: 발화 바이그램 %d개 < %d — 우연 일치가 점수를 지배하는 구간이다",
            grams, MIN_ECHO_BIGRAMS,
        )
        return 0.0

    references = [risk_item.condition.value_text, *rubric.required_elements]
    return max((textsim.containment(answer_text, ref) for ref in references), default=0.0)


def cap_confidence_if_echoed(
    judgment: Judgment, answer_text: str, rubric: rubrics.Rubric, risk_item: RiskItem
) -> Judgment:
    """복창이면 confidence 에 상한을 씌운다. **등급은 건드리지 않는다.**

    등급을 깎지 않는 이유: 이해한 사람과 따라 말한 사람을 발화만으로 구분할 수 없다. 등급을
    추측해 내리면 이해→오해 오판(기획서 5절 관리지표 10%)을 우리가 만드는 것이다. 가릴 수
    없다는 사실 자체를 confidence 로 보고하고, 판정은 게이트가 한다(P1).

    **강등 사실을 `reason` 에 남긴다.** 조용히 숫자만 바뀌면 감사 시점에 왜 황색이었는지
    설명할 수 없다.
    """
    echo = echo_score(answer_text, rubric, risk_item)
    if echo < ECHO_THRESHOLD or judgment.confidence <= ECHO_CONFIDENCE_CAP:
        return judgment
    return judgment.model_copy(update={
        "confidence": ECHO_CONFIDENCE_CAP,
        "reason": f"{judgment.reason} (문서 문면 복창 포함도 {echo:.2f} ≥ "
                  f"{ECHO_THRESHOLD} — 자기 말인지 가릴 수 없어 확신도 상한 적용)",
    })


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


def _canonical(text: str) -> str:
    """대조용 정규화 — 유니코드 NFC + 공백 제거.

    NFC 를 여기서 하는 이유(ADR-008): 한글은 완성형(NFC)과 조합형(NFD)이 **눈으로 같고
    바이트가 다르다.** 모델이 조합형으로 인용을 돌려주면 글자가 같은데도 P4 위반으로
    거부된다 — 실측으로 재현했다.

    ADR-008 은 `CanonicalJson` 이 직렬화 중에 정규화하지 않기로 정했다(직렬화가 내용을
    바꾸면 저장된 원문과 해시 대상이 갈린다). 그 결정은 **저장**에 관한 것이고, 여기는
    **대조**다. 원문을 고쳐 저장하는 것이 아니라 비교할 때만 같은 형태로 맞춘다 —
    `judgment.evidence.utterance_quote` 자체는 손대지 않는다.
    """
    return _WS.sub("", unicodedata.normalize("NFC", text))


def verify_quote_is_verbatim(judgment: Judgment, answer_text: str) -> None:
    """근거 인용이 실제 발화에서 나온 것인지 대조한다 (P4).

    지어낸 인용은 근거가 없는 것보다 나쁘다 — 감사 시점에 검증할 수 없는 기록이 남는다.
    유니코드 정규화와 공백만 흡수하고, 그 외의 변형은 허용하지 않는다.
    """
    quote = _canonical(judgment.evidence.utterance_quote)
    if quote and quote in _canonical(answer_text):
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
    cited = _canonical(judgment.evidence.rubric_clause)

    residual = cited
    for clause in sorted(published, key=len, reverse=True):
        residual = residual.replace(_canonical(clause), "")
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
            f"[{top.stage} {top.score}, 근거 {_source_tier(top.type_id)}] → U4 상향)"
        )
    return judgment.model_copy(update=update)


def _source_tier(type_id: str) -> str:
    """상향 근거의 종류를 판정 사유에 남긴다.

    **근거 종류로 상향 여부를 가르지 않는다.** 기획서 5절의 오해→이해 오판 상한 1%이
    구속 조건이고, `proposal_example`도 기획서 본문이 오해로 제시한 문장이므로 내용상
    근거가 없는 것이 아니다. 약한 것은 *인용 가능한 출처*이지 판정의 타당성이 아니다.

    그래서 상향은 그대로 하고 근거 등급을 기록에 남긴다. 감사·심사 시점에 조정례 근거와
    기획서 예시가 구분되고, 어느 판정이 어느 등급에 기댔는지 추적된다.
    """
    for mtype in misconception.library():
        if mtype.type_id == type_id:
            if mtype.source.is_dispute_grounded:
                return f"{mtype.source.type} {mtype.source.ref}"
            return mtype.source.type or "미기재"
    return "미기재"
