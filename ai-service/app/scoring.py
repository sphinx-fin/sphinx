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
from functools import lru_cache

import re
import unicodedata
from pathlib import Path

from . import misconception, rubrics, textsim
from .config import settings
from .llm_client import LlmClient, LlmError, client as default_client
from .schemas import Grade, Judgment, MisconceptionResponse, RiskItem

class MeasurementInvalid(LlmError):
    """모델이 낸 **측정값이 우리 검증을 통과하지 못했다** (이슈 #280).

    `LlmError` 를 상속해서 라우트 동작은 그대로 둔다(502). 갈라 두는 이유는 둘이다.

    **① 재시도 가능성이 다르다.** 호출 실패·설정 누락은 다시 물어도 같고, 잘림은 같은
    입력이면 또 잘린다. 그런데 이건 **모델이 이번에 인용을 잘못한 것**이라 다시 물으면
    통과할 수 있다 — `#229` 가 그 실물이다(`els-0010` 이 1회차 P4 위반, 2회차 U2 통과).

    **② `MEASUREMENT_INVALID` 로 갈라 보낼 자리다.** 계약에 그 코드가 이미 있는데
    지금은 `AI_SERVICE_UNAVAILABLE` 과 한 통로로 나간다 — Spring 이 *"AI 가 죽었다"* 와
    *"측정을 못 믿는다"* 를 구분할 수 없다. 그 배선은 `#280` ③이고 이 PR 에 없다.
    """


#: P4·인용 검증 실패에 몇 번까지 다시 물을까 (이슈 #280 ①).
#:
#: `reexplain` 에는 `MAX_ATTEMPTS = 3` 이 있는데 **채점에는 재시도가 아예 없었다.** 같은
#: 성격의 실패인데 한쪽만 복구 경로가 있었다. 2 회면 실패 확률이 `p` 에서 `p²` 가 된다.
#:
#: ❗**이것이 `#280` 을 닫지 않는다.** `p²` 도 0 이 아니고, 무엇보다 게이트가 분모를
#: 모르는 문제(`#280` ②)는 이것과 무관하다 — 12항목만 채점되고 초록이 나오는 경로는
#: 재시도 횟수와 상관없이 열려 있다.
#: P4 검증(인용 원문 대조 · 조항 공개 대조)이 실패했을 때 다시 묻는 횟수.
#:
#: ## ❗재시도마다 seed 를 바꾼다 — 안 바꾸면 이 값이 의미가 없다
#:
#: `#281` 이 `seed` 를 기본 고정했다. 고정 seed 로 **같은 프롬프트**를 다시 물으면 같은 답이
#: 올 수 있고, 그러면 재판정이 같은 실패를 두 번 겪는 것으로 끝난다. 두 조치가 각자 맞는데
#: 합치면 기능이 없어지는 자리였다(`#160`·`#207` 에서 겪은 것과 같은 모양).
#:
#: 그래서 `_attempt_seed()` 가 시도마다 다른 값을 준다. 첫 시도는 설정값 그대로여서
#: 단발 호출의 동작은 `#281` 과 같고, 재시도만 어긋난다.
#:
#: seed 를 끈 경우(`LLM_SEED=`)는 프로바이더가 매번 다르게 답하므로 손댈 것이 없다.
#:
#: **재현성이 확보되는 것은 아니다** — 실측에서 같은 seed 로 3회가 3가지였다. seed 는
#: best-effort 이고, 이 상수가 하는 일은 *"한 번 더 물어본다"* 뿐이다.
MAX_SCORING_ATTEMPTS = 2

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

#: 복창 판정을 건너뛰는 발화 길이 하한(정규화 후 문자 바이그램 개수). **상수가 아니라
#: 루브릭에서 유도한다** — `min(조항 바이그램) - 1`.
#:
#: ## 처음 쓴 근거가 틀렸다 (PR #114 리뷰, 정세현)
#:
#: 원래 주석은 *"분모가 작으면 우연 일치가 점수를 지배한다"* 였다. 실측이 그걸 부정한다.
#:
#:     '손실'       1bg  containment=1.000   ← 조항 문면 그대로다. 우연이 아니다
#:     '투자원금의'  4bg  containment=1.000   ← 조항 문면 그대로다
#:     '원금 손실'   3bg  containment=0.667   ← 어휘만 겹친다(순서 다름)
#:
#: 전체 루브릭으로 넓혀 재보면 두 군이 **바이그램 개수로 안 갈린다.**
#:
#:     조항 부분열(verbatim)      1bg 부터
#:     어휘만 겹침(순서 다름)      9bg 까지
#:
#: 즉 이 하한은 "진짜 복창"과 "우연 일치"를 가르는 도구가 될 수 없다. 그런 도구로 쓰려고
#: 값을 고르면 근거가 없다.
#:
#: ## 하한의 실제 역할 — 무의미한 계산을 건너뛴다
#:
#: 복창 판정의 목적은 *"요소는 다 말했는데 자기 말인지 모르겠다"* 를 잡는 것이다. 그 상황은
#: **U1 일 때만** 생기고, U1 은 필수 요소를 자기 말로 설명해야 나온다. 조항 하나를 담을
#: 길이보다 짧은 발화는 요소 미충족이라 U2 이하가 되고, 게이트 R-04 가 이미 YELLOW 로 잡는다.
#:
#: 그래서 값의 **유일한 실제 제약은 상방**이다 — 가장 짧은 조항을 그대로 옮긴 복창을 놓치지
#: 않아야 한다. 정세현이 *"상방 하나로만 값을 고정하면 경계선 바로 아래다"* 라고 한 것이
#: 정확히 이 사실을 가리킨다. 그러면 **경계선 바로 아래가 정답**이고, 상수로 박을 이유가 없다.
#:
#: 루브릭에서 유도하면 조항이 바뀔 때 자동으로 따라온다. `#112` 가 조항 최단을 13bg → 6bg 로
#: 바꿨을 때 하드코딩 값(8)이 깨진 것이 그 필요의 실측이다.
@lru_cache(maxsize=1)
def min_echo_bigrams() -> int:
    """가장 짧은 루브릭 조항보다 하나 작은 값. 하한의 유일한 제약이 상방이라 그렇다.

    ## ❗유도는 조항만 보는데 `echo_score` 는 조건 원문도 대조한다 (이슈 #128 ③)

    `echo_score` 의 대조 대상은 `[condition.value_text, *required_elements]` 인데 여기서는
    `required_elements` 만 훑는다. **조건 원문이 조항보다 짧아지면 그 원문을 그대로 옮긴
    복창을 놓친다.**

    지금은 안 뒤집힌다 — 조항 최단 6bg < 조건 원문 최단 11bg 라 조항 쪽이 항상 구속한다
    (계약 정답 26건 실측). 그건 **현재 데이터의 사실이지 유도식의 보장이 아니고**, 계약
    샘플이 바뀌면 조용히 깨진다. 런타임 데이터라 import 시점에 알 수 없어서
    `test_the_floor_sits_below_every_condition_text` 가 그 전제를 대신 지킨다.
    """
    shortest = min(
        len(textsim.bigrams(textsim.normalize(clause)))
        for rubric in rubrics.all_rubrics().values()
        for clause in rubric.required_elements
    )
    return max(1, shortest - 1)


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


def _attempt_seed(attempt: int) -> int | None:
    """시도마다 다른 seed. 첫 시도는 설정값 그대로다.

    설정에서 읽는 이유: 스텁 클라이언트는 `super().__init__()` 을 부르지 않아 `_cfg` 가
    없다(`tests/helpers.py`). 클라이언트에게 물으면 테스트 전부가 `AttributeError` 다.

    seed 가 꺼져 있으면(`None`) 그대로 `None` 을 준다 — 프로바이더가 매번 다르게 답하므로
    어긋나게 만들 것이 없다.
    """
    base = settings().llm_seed
    return None if base is None else base + attempt - 1


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
    client_ = llm or default_client()
    prompt = build_prompt(rubric, risk_item, question, answer_text)

    last: MeasurementInvalid | None = None
    for attempt in range(1, MAX_SCORING_ATTEMPTS + 1):
        judgment = client_.complete_json(
            prompt=prompt,
            model_cls=Judgment,
            schema_name="Judgment",
            system=load_system_prompt(),
            seed=_attempt_seed(attempt),
        )
        judgment = _pin_prompt_version(judgment)
        judgment = _pin_item_id(judgment, item_id)
        judgment = _drop_llm_misconception_type(judgment)
        try:
            verify_quote_is_verbatim(judgment, answer_text)
            verify_rubric_clause_is_published(judgment, rubric)
        except MeasurementInvalid as exc:
            # 재판정 사실을 로그에 남긴다. 조용히 다시 물으면 폴백률·오탐률을 볼 때
            # 한 항목이 몇 번 불렸는지 알 수 없고, 쿼터 소모도 설명이 안 된다.
            log.warning(
                "채점 재판정 %d/%d: item_id=%s — %s",
                attempt, MAX_SCORING_ATTEMPTS, item_id, exc,
            )
            last = exc
            continue
        judgment = cap_confidence_if_echoed(judgment, answer_text, rubric, risk_item)
        judgment = apply_misconception_floor(judgment, matched, rubric)
        return _pin_escalation(judgment, matched, rubric)

    # ❗여기서 U2 같은 폴백 등급을 만들지 않는다 (결정 10.10 · `#280` ③).
    # 근거를 지어내 등급을 붙이면 우리가 막겠다는 것(근거 없는 판정)을 우리가 만든다.
    # 항목이 세션에서 사라지는 문제는 **게이트가 분모를 알게 하는 쪽**으로 풀어야 한다
    # (`#280` ②, 강희진) — 여기서 등급을 지어내는 것으로는 안 된다.
    raise MeasurementInvalid(
        f"{MAX_SCORING_ATTEMPTS}회 재판정 후에도 측정이 무효다: item_id={item_id} — {last}"
    )


def _pin_escalation(
    judgment: Judgment, matched: MisconceptionResponse, rubric: rubrics.Rubric
) -> Judgment:
    """상신 신호를 싣는다 — **모델에게 묻지 않는다** (이슈 #160, 결정 10.47).

    `escalation_signal()` 이 라이브러리의 `escalate: compliance` 에서 계산한 값을 그대로
    옮긴다. `_pin_item_id`·`_pin_prompt_version` 과 같은 층이다.

    ## 왜 `apply_misconception_floor` 뒤인가

    순서가 중요하지 않아 보이지만 하나 있다 — **floor 가 등급을 바꿔도 신호는 안 바뀐다.**
    두 값이 같은 입력(`matched`)에서 나오는데 **거르는 기준이 다르다.**

        등급 상향   rubric.related_misconceptions 로 거른다  (이 항목의 오해만)
        상신 신호   거르지 않는다                            (판매자 행위는 항목 무관)

    `#160` 의 결함이 정확히 그 필터가 신호까지 삼킨 것이었다. 뒤에 두면 floor 를 고치는
    사람이 신호를 같이 건드리지 않는다.

    ## 판매자에게 안 나간다

    계약 `description` 이 *"판매자 응답(JudgmentView)에 넣지 않는다"* 를 적었고, 서버가
    `JudgmentView.of()` 허용목록(`JudgmentViewFieldsTest`)과 어휘 대조
    (`UnfairSignalNotExposedTest`)로 두 겹 잠갔다. 어떤 발화가 탐지되는지 알면 문면만 바꿔
    같은 영업을 한다(기획 7-4 역이용 방지).
    """
    return judgment.model_copy(update={"escalate": escalation_signal(matched, rubric)})


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
        condition_text=risk_item.require_condition().value_text,
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

    **너무 짧은 발화는 계산하지 않는다** — `min_echo_bigrams()` 참고. 가장 짧은 조항도 담지 못하는
    길이라 U1 이 나오지 않고, 상한을 씌워도 게이트 판정이 바뀌지 않는다.

    대조 대상은 **고객이 옮겨 적을 수 있는 문면**이다.
    - `condition.value_text` — 상품문서 원문 조항
    - `rubric.required_elements` — 정답 문면. 루브릭은 공개 의무 대상이라 판매자가 읽을 수 있다

    방향은 `containment(발화, 문면)` 이다 — **발화의 바이그램이 문면에 얼마나 있는지**.
    반대 방향으로 재면 긴 원문을 짧게 인용한 발화가 항상 낮게 나와 복창이 안 잡힌다.
    """
    grams = len(textsim.bigrams(textsim.normalize(answer_text)))
    floor = min_echo_bigrams()
    if grams < floor:
        log.info(
            "복창 판정 생략: 발화 바이그램 %d개 < %d — 가장 짧은 조항도 담지 못하는 길이라 "
            "U1 이 나오지 않는다(상한을 씌워도 판정이 바뀌지 않는다)", grams, floor,
        )
        return 0.0

    references = [risk_item.require_condition().value_text, *rubric.required_elements]
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
def _pin_prompt_version(judgment: Judgment) -> Judgment:
    """어느 프롬프트가 낸 값인지 고정한다. **모델에게 묻지 않는다** (결정 10.46).

    `confidence` 의 정의가 프롬프트 버전마다 다르다 — v1 은 등급 확신도, v2 는 재현
    가능성이다(PR #114). `evidence/` 가 append-only 라 두 정의가 같은 컬럼에 섞이면 감사
    시점에 어느 쪽으로 해석할지 판단할 근거가 없어진다.

    모델 출력을 쓰지 않는 이유는 `item_id`·`misconception_type` 과 같다 — 프롬프트 파일이
    실제로 무엇인지는 **우리가 아는 사실**이고 모델이 보고할 값이 아니다. 프롬프트에
    버전을 적어 내게 하면 파일을 바꿀 때 그 문면을 같이 안 고쳐도 아무 일이 안 일어난다.
    """
    return judgment.model_copy(update={"prompt_version": PROMPT_VERSION})


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
    raise MeasurementInvalid(
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

    raise MeasurementInvalid(
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

    ## `reason` 에 유형ID 를 적지 않는다 (이슈 #160 ②)

    상향 사실을 기록에 남기는 것은 맞다 — 조용히 등급만 바뀌면 감사 시점에 왜 U4 였는지
    설명할 수 없다. 문제는 **어디에 적느냐**였다.

    `reason` 은 `JudgmentView` 가 판매자 화면에 그대로 싣는 5개 필드 중 하나다. 유형ID 를
    거기 넣으면 `escalate: compliance` 유형이 걸렸을 때 **`#147`·`#159`·`#145` 가 막아 둔
    비노출 조치를 문자열로 우회한다** — 판매자가 `M08-TYING` 을 읽는다. 기획서 7-4 가
    불공정영업 신호를 판매자에게 보이지 않기로 한 그 지점이다.

    유형ID 는 이미 구조화된 필드(`misconception_type`)에 있고 **그 필드는 화면 경계에서
    걸러진다.** 그러니 문면에는 상향이 있었다는 사실과 그 강도·근거 등급만 남긴다 —
    감사에 필요한 것은 다 남고, 화면으로 새는 것은 없다.
    """
    relevant = [m for m in matched.matches if m.type_id in rubric.related_misconceptions]
    if not relevant:
        return judgment

    top = max(relevant, key=lambda m: m.score)
    update: dict[str, object] = {"misconception_type": top.type_id}
    if judgment.grade is not Grade.U4:
        update["grade"] = Grade.U4
        update["reason"] = (
            f"{judgment.reason} (오해 라이브러리 매칭 "
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


# ── F-GTE-003 — 컴플라이언스 상신 신호 (#160) ─────────────────────────────────
def escalation_signal(matched: MisconceptionResponse, rubric: rubrics.Rubric) -> bool:
    """이 발화가 컴플라이언스로 올라갈 신호인가.

    ## ❗`related_misconceptions` 를 거치지 않는다 — 그게 `#160` 의 결함이었다

    `apply_misconception_floor` 는 **루브릭이 관련 유형으로 선언한 오해만** 본다. 그건
    옳다 — 다른 항목의 오해가 이 항목 *등급* 을 끌어내리면 안 되니까. 그런데 그 필터가
    상신 신호까지 같이 삼켰다.

        M08-TYING 탐지 = 만점(score 1.0 · escalate true)
        어느 루브릭도 M08 을 related_misconceptions 에 안 걺
        → floor 가 첫 줄에서 반환 → misconception_type 이 안 실림
        → publishIfUnfairSales 가 첫 줄에서 반환 → **기획 [기능2] 가 한 번도 발행되지 않음**

    등급과 신호는 **묻는 것이 다르다.** 등급은 *"이 고객이 이 항목을 이해했는가"* 이고,
    꺾기는 *"판매자가 무엇을 했는가"* 다. 후자는 어느 위험항목을 채점 중이었는지와 무관하게
    성립한다 — 원금손실을 채점하다 들어도 꺾기는 꺾기다. 그래서 루브릭으로 거르지 않는다.

    `rubric` 을 받고도 안 쓰는 것은 **의도다.** 호출부에서 두 함수가 같은 입력을 받으면서
    한쪽만 필터를 쓴다는 것이 보이고, 나중에 누가 "일관성" 을 이유로 필터를 다는 것을
    `test_escalation_ignores_the_rubric_filter` 가 막는다.

    ## 유형ID 를 코드에 박지 않는다

    `misconception.match()` 가 라이브러리의 `escalate: compliance` 를 읽어 이미 계산해 둔
    값을 그대로 쓴다. `M08-TYING` 이 여기 문자열로 나타나면 안 된다 — 라이브러리에 유형이
    늘어도 코드가 안 바뀌어야 하고, 그게 `misconception.py` 가 세운 규칙이다.
    """
    del rubric                          # 위 docstring 참고 — 안 쓰는 것이 계약이다
    return matched.escalate
