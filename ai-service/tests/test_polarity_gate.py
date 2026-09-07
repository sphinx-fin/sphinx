"""F-DET-001 3단계 — 극성 게이트 (오해를 **부정하는** 발화를 뺀다).

## 왜 3단계가 「더 잡는 층」이 아니라 「빼는 층」인가

한국어는 부정·양보가 **어미에 붙는다.** 오해와 그 부정은 주제가 같아서, 주제를 재는 수단은
전부 같은 곳에서 무너진다 — 같은 실패를 세 번 쟀다.

    #203  n-gram        어간까지만 자른 조각이 오해와 부정을 못 가른다
    #283  임베딩 코사인  M09 에서 정답(0.621)이 어미변형(0.560)보다 **높다**
    9/5   대조 앵커      M09 는 갈리는데(간격 +0.104) M11 은 **겹친다(-0.006)**

대조 앵커가 M09 에서 됐던 이유는 오해와 정답의 **내용**이 달라서였다(팔 수 있다 ↔ 상장이
안 됐다). M11 은 같은 명제의 부정만 다르다 — pro·anti 앵커가 둘 다 같은 주제라 margin 이
잡음(±0.05)이 된다. **주제를 재는 수단으로 극성은 안 갈린다.**

## 이 파일이 잠그는 것

    ① client 가 없으면 3단계가 아예 안 돈다 (기존 동작 불변)
    ② 부정하는 발화를 뺀다
    ③ ❗두 필드가 합의할 때만 남긴다 — holds 만 보면 비결정적이었다
    ④ ❗게이트가 죽으면 후보를 **남긴다** (P5 0.2절 — 미탐이 과탐보다 비싸다)
    ⑤ 게이트는 후보를 **만들지 않는다**

**LLM 을 부르지 않는다.** 한 번 실제로 불러서 65 초가 걸렸고, 그 뒤로 전부 스텁이다.
"""
from __future__ import annotations

import sys
import threading
import time

import logging

import pytest

from app import misconception

UTTERANCE_YES = "이것도 예금처럼 전액 보호되는 거 아닌가요"
UTTERANCE_NO = "보험료 전액이 예금자보호 대상은 아니라고 하셨죠"
PRODUCT = "VARIABLE_INSURANCE"


class _Stub:
    """`complete_json` 만 흉내낸다. 호출 인자를 남겨서 무엇을 물었는지 잰다."""

    def __init__(self, holds: bool, polarity: str = "positive") -> None:
        self._holds, self._polarity = holds, polarity
        self.calls: list[dict] = []

    def complete_json(self, **kwargs):
        self.calls.append(kwargs)
        return misconception.PolarityVerdict(belief="(스텁)", holds=self._holds, polarity=self._polarity)


class _Dead:
    def complete_json(self, **kwargs):
        raise RuntimeError("(테스트) LLM 이 죽었다")


def _matched(text: str, client=None):
    return misconception.match(text, PRODUCT, client=client).matches


# ── ① 기존 동작이 안 바뀐다 ───────────────────────────────────────────────────
def test_without_a_client_the_gate_does_not_run() -> None:
    """`client=None` 이면 3단계가 없다 — 기존 호출자가 그대로 돈다."""
    assert [m.type_id for m in _matched(UTTERANCE_YES)] == ["M11-DEPOSIT-INSURANCE-SCOPE"]
    assert [m.type_id for m in _matched(UTTERANCE_NO)] == ["M11-DEPOSIT-INSURANCE-SCOPE"], (
        "이게 게이트 없이 남는 **오탐**이다 — 맞는 말을 한 고객이 U4 로 간다")


# ── ② 부정하는 발화를 뺀다 ────────────────────────────────────────────────────
def test_the_gate_drops_an_utterance_that_denies_the_misconception() -> None:
    stub = _Stub(holds=False, polarity="negative")
    assert _matched(UTTERANCE_NO, client=stub) == []
    assert stub.calls, "게이트가 아예 안 불렸다"


def test_the_gate_keeps_an_utterance_that_asserts_it() -> None:
    stub = _Stub(holds=True, polarity="positive")
    assert [m.type_id for m in _matched(UTTERANCE_YES, client=stub)] == [
        "M11-DEPOSIT-INSURANCE-SCOPE"]


# ── ③ 두 필드가 합의할 때만 남긴다 ────────────────────────────────────────────
@pytest.mark.parametrize("polarity", ["negative", "neutral"])
def test_a_self_contradicting_verdict_drops_the_candidate(polarity: str, caplog) -> None:
    """❗`holds=True` 인데 `polarity` 가 긍정이 아니면 **뺀다.**

    실측에서 같은 입력 3회 중 1회가 정확히 이 모양으로 갈렸다. 한 응답 안의 두 출력이
    어긋나면 그 판정은 못 쓴다 — 그리고 빼는 쪽이 이 게이트의 방향(과탐 감소)과 같다.
    """
    stub = _Stub(holds=True, polarity=polarity)
    with caplog.at_level(logging.INFO):
        assert _matched(UTTERANCE_YES, client=stub) == []
    assert "자기모순" in caplog.text, "왜 뺐는지가 로그에 안 남으면 조용한 실패다"


# ── ④ 죽으면 후보를 남긴다 ────────────────────────────────────────────────────
def test_a_dead_gate_keeps_the_candidate(caplog) -> None:
    """지우는 쪽이 미탐이고 P5(0.2절)가 미탐을 과탐보다 비싸게 다룬다."""
    with caplog.at_level(logging.INFO):
        kept = _matched(UTTERANCE_YES, client=_Dead())
    assert [m.type_id for m in kept] == ["M11-DEPOSIT-INSURANCE-SCOPE"]
    assert "극성 게이트 실패" in caplog.text


# ── ⑤ 게이트는 후보를 만들지 않는다 ───────────────────────────────────────────
def test_the_gate_cannot_invent_a_candidate() -> None:
    """1·2단계가 아무것도 못 찾으면 게이트는 **불리지도 않는다.**"""
    stub = _Stub(holds=True, polarity="positive")
    assert _matched("만기가 언제인지 알려주세요", client=stub) == []
    assert stub.calls == [], "후보가 없는데 LLM 을 불렀다 — 돈과 지연이 는다"


def test_the_gate_is_asked_about_the_matched_pattern() -> None:
    """무엇을 물었는지 고정한다 — 매칭된 패턴이지 라벨(범주명)이 아니다.

    라벨은 `"예금자보호 범위 오인"` 같은 **범주명**이라 명제가 아니다. 명제형 문장을 쓰면
    `holds` 만으로도 3 회 0/9 였지만, 그건 라이브러리에 새 필드가 필요하고 그 파일은
    정세현 소유다. 합의 규칙(③)을 두니 패턴만으로 3 회 0/9 라 데이터를 안 늘렸다.
    """
    stub = _Stub(holds=True, polarity="positive")
    _matched(UTTERANCE_YES, client=stub)
    prompt = stub.calls[0]["prompt"]
    assert UTTERANCE_YES in prompt, "발화가 안 실렸다"
    assert "오해 문장:" in prompt and "예금자보호 범위 오인" not in prompt, (
        "라벨(범주명)을 오해 문장으로 실었다 — 명제가 아니라 극성을 못 묻는다")


def test_the_stage_still_says_where_it_was_found() -> None:
    """게이트를 지나도 `stage` 는 **찾은** 단계 그대로다.

    발견 경로와 확인 경로는 다른 사실이다. 하나로 덮으면 *"이 매칭이 결정론적이었나"* 를
    나중에 못 되짚는다 — 기획서 5절이 재현성을 그 단계 구분으로 설명한다.
    """
    stub = _Stub(holds=True, polarity="positive")
    got = _matched(UTTERANCE_YES, client=stub)
    assert got[0].stage == "pattern", f"stage 가 덮였다: {got[0].stage}"


# ── 계량기: 게이트가 **무엇을 했는지**가 남는다 (#398 리뷰 ①②) ────────────────
#
# ❗`#160` 에서 내가 세운 규칙의 반대 방향이다 — *"조용히 등급만 바뀌면 감사 시점에 왜 U4
# 였는지 설명할 수 없다"*. **U4 가 「안 된」 경우에도 똑같다.** floor 가 울면 `reason` 과
# `misconception_type` 이 evidence 에 남는데, 게이트가 빼면 평범한 U1~U3 에 INFO 한 줄뿐이라
# **아무 데도 안 남는다.** 로그는 evidence 가 아니다(ADR-003).
#
# 계량기는 evidence 가 아니라 **운영 관측값**이다. 집계·리포트는 `#326`·`#327`(정세현).
@pytest.fixture(autouse=True)
def _reset_meter():
    """계량기는 모듈 전역이라 테스트끼리 샌다. 매번 새로 만든다."""
    before = misconception.METER
    misconception.METER = misconception.PolarityMeter()
    yield misconception.METER
    misconception.METER = before


def test_the_meter_counts_a_kept_candidate() -> None:
    _matched(UTTERANCE_YES, client=_Stub(holds=True, polarity="positive"))
    m = misconception.METER
    assert (m.asked, m.kept, m.dropped, m.not_run) == (1, 1, 0, 0), m.summary()


def test_the_meter_counts_a_dropped_candidate() -> None:
    _matched(UTTERANCE_NO, client=_Stub(holds=False, polarity="negative"))
    m = misconception.METER
    assert (m.asked, m.kept, m.dropped) == (1, 0, 1), m.summary()
    assert m.contradicted == 0, "자기모순이 아닌데 그렇게 셌다"
    assert m.by_type == {"M11-DEPOSIT-INSURANCE-SCOPE": 1}, m.by_type


def test_the_meter_separates_a_self_contradiction() -> None:
    """자기모순으로 뺀 것은 따로 센다 — 프롬프트가 흔들리는 신호다."""
    _matched(UTTERANCE_YES, client=_Stub(holds=True, polarity="neutral"))
    m = misconception.METER
    assert (m.dropped, m.contradicted) == (1, 1), m.summary()


class _WrongType:
    """`model_cls` 를 안 지키는 클라이언트 — 실제로 있을 수 있는 실패다."""

    def complete_json(self, **kwargs):
        return "PolarityVerdict 가 아니다"


@pytest.mark.parametrize("make_client,why",
                         [(_Dead, "예외"), (_WrongType, "타입 불일치")])
def test_a_gate_that_did_not_run_is_not_counted_as_zero(make_client, why: str) -> None:
    """★ **`not_run` 이 `dropped == 0` 과 달라야 한다** (결정 5.40 · `shadow.failed` 와 같은 자리).

    게이트가 조용히 영구히 안 도는 경로가 둘이다 — LLM 예외, 클라이언트가 `model_cls` 를
    안 지킴. 둘 다 후보를 남기므로 **판정만 보면 게이트가 없는 것과 구별되지 않는다.**
    그래서 세는 자리가 없으면 *"뺀 게 0 건"* 과 *"한 번도 안 돌았다"* 가 같아 보인다.
    """
    kept = _matched(UTTERANCE_YES, client=make_client())
    m = misconception.METER
    assert [x.type_id for x in kept] == ["M11-DEPOSIT-INSURANCE-SCOPE"], "후보를 남겨야 한다(P5)"
    assert (m.not_run, m.dropped, m.kept) == (1, 0, 0), (
        f"{why} 인데 못 돈 것으로 안 셌다 — 0 건과 「모른다」가 같아 보인다. {m.summary()}")


def test_the_meter_is_not_called_when_there_is_no_candidate() -> None:
    """후보가 없으면 게이트가 안 불리므로 계량기도 안 는다 — 분모가 부풀지 않는다."""
    _matched("만기가 언제인지 알려주세요", client=_Stub(holds=True, polarity="positive"))
    assert misconception.METER.asked == 0, misconception.METER.summary()


def test_the_meter_never_holds_an_utterance() -> None:
    """❗**발화를 안 담는다** — 로그에서 뺀 것과 같은 이유다(`#397` 리뷰 ①).

    `by_type` 의 키가 유형ID 인 것을 잠근다. 발화를 키로 쓰면 P3 밖에 사본이 생기고
    F-GTE-004 보존 정책도 안 걸린다.
    """
    _matched(UTTERANCE_NO, client=_Stub(holds=False, polarity="negative"))
    joined = " ".join(misconception.METER.by_type) + misconception.METER.summary()
    assert UTTERANCE_NO not in joined, "계량기에 발화가 들어갔다"
    assert all(k.startswith("M") for k in misconception.METER.by_type), misconception.METER.by_type


# ── 배선: 채점 경로에서 실제로 도는가 ─────────────────────────────────────────
class _ScoringAndGate:
    """채점과 극성 게이트를 **둘 다** 받는 스텁. 실제 클라이언트가 하는 일이다.

    `model_cls` 를 지키는 스텁이라야 게이트가 산다 — 안 지키면 게이트는 「안 돈 것」으로
    보고 후보를 남긴다(다른 테스트 파일의 스텁이 그 경로다).
    """

    def __init__(self, judgment, holds: bool) -> None:
        self._j, self._holds = judgment, holds
        self.schemas: list[str] = []

    def complete_json(self, **kwargs):
        self.schemas.append(kwargs.get("schema_name", "?"))
        if kwargs.get("model_cls") is misconception.PolarityVerdict:
            return misconception.PolarityVerdict(
                belief="(스텁)",
                holds=self._holds, polarity="positive" if self._holds else "negative")
        return self._j.model_copy()


def _judgment_for(item_id: str, answer: str):
    from app import rubrics
    from app.schemas import Evidence, Grade, Judgment
    r = rubrics.get(item_id)
    return Judgment(item_id=item_id, grade=Grade.U1, confidence=0.9,
                    evidence=Evidence(utterance_quote=answer[:12],
                                      rubric_clause=r.required_elements[0]),
                    reason="(테스트)")


@pytest.mark.parametrize("holds,expected", [(True, "U4"), (False, "U1")])
def test_the_gate_decides_whether_the_floor_fires(holds: bool, expected: str) -> None:
    """★ 배선의 값 — 게이트가 후보를 빼면 **U4 상향이 안 일어난다.**

    이게 `#284` (a) 가 막혀 있던 이유다. 맞게 말한 고객(`holds=False`)이 U4 로 확정되면
    재설명 루프로 가고, 그건 **오탐** 방향이라 게이트가 없으면 링크를 걸 수 없다.
    """
    from app import scoring
    # ❗**링크가 있는 쌍이라야 floor 가 운다.** `VAR-PARTIAL-DEPOSIT-INSURANCE` 로 쓰려다
    # 안 됐는데, 그 항목은 `related_misconceptions` 가 비어 있어서다 — 그게 `#284` (a) 가
    # 아직 안 열린 이유 그 자체다. 여기서 재는 것은 **게이트↔floor 배선**이므로
    # 이미 링크된 쌍(ELS-PRINCIPAL-LOSS-WARNING ↔ M01)으로 잰다.
    item = "ELS-PRINCIPAL-LOSS-WARNING"
    answer = "은행에서 파니까 원금은 보장되는 거죠"
    llm = _ScoringAndGate(_judgment_for(item, answer), holds)

    from app.schemas import RiskItem
    risk = RiskItem(item_id=item, product_id="p", name="원금 손실",
                    importance="required", status="extracted",
                    condition={"value_text": "만기평가일에 최초기준가격의 65% 미만",
                               "source_span": {"page": 1, "start": 0, "end": 20}})
    out = scoring.score(item, "질문?", answer, risk, "ELS", llm=llm)

    assert "PolarityVerdict" in llm.schemas, (
        f"게이트가 채점 경로에서 안 불렸다 — 배선이 끊겼다. 호출: {llm.schemas}")
    assert out.grade.value == expected


def test_the_gate_runs_after_scoring_not_before() -> None:
    """❗순서를 잠근다 — 게이트가 **첫** LLM 호출이면 `#281` 이 고정한 seed 배선이 밀린다.

    `#281` 은 *"첫 시도는 설정값 그대로"* 를 잠갔고, 그 테스트가 `llm.calls[0]` 을 본다.
    게이트를 채점 앞에 두면 그 자리가 게이트 호출이 된다 — 실제로 그렇게 짰다가 세 개가
    갈렸다.
    """
    from app import scoring
    from app.schemas import RiskItem
    item = "ELS-PRINCIPAL-LOSS-WARNING"
    answer = "은행에서 파니까 원금은 보장되는 거죠"
    llm = _ScoringAndGate(_judgment_for(item, answer), True)
    risk = RiskItem(item_id=item, product_id="p", name="원금 손실",
                    importance="required", status="extracted",
                    condition={"value_text": "만기평가일에 최초기준가격의 65% 미만",
                               "source_span": {"page": 1, "start": 0, "end": 20}})
    scoring.score(item, "질문?", answer, risk, "ELS", llm=llm)
    assert llm.schemas[0] == "Judgment", f"채점보다 먼저 게이트가 돌았다: {llm.schemas}"


# ── ⑦ 판정 스키마는 strict 자격이어야 한다 (이슈 #503) ──────────────────────────
def test_the_verdict_schema_is_strict_eligible() -> None:
    """★ `PolarityVerdict` 가 `Strict` 라 `llm_client` 가 `strict: true` 를 켠다.

    이게 없으면 모델이 스키마를 최선노력으로만 따르고, «예금자보호가 **안** 되는» 같은
    정정 발화를 `holds=True` 로 3/3 결정론적으로 틀린다(alpha 재현, #503). 프롬프트 수정은
    이 위에 아무것도 더하지 않았다 — 결함은 프롬프트가 아니라 스키마가 강제되지 않은 것이었다.
    `BaseModel` 로 되돌리는 변이가 여기서 죽는다.

    ❗그리고 `belief` 가 **첫 필드**여야 한다 — 구조화 출력은 순서대로 채우므로 «지금 믿는 것»을
    먼저 쓰게 하는 것이 판정 절차다. Strict 만으로는 N=10 에서 6/10 오판이었다.
    """
    fields = list(misconception.PolarityVerdict.model_fields)
    assert fields[0] == "belief", f"belief 가 첫 필드가 아니다: {fields}"
    from app.llm_client import supports_strict

    schema = misconception.PolarityVerdict.model_json_schema()
    assert schema.get("additionalProperties") is False, "Strict 가 아니다 — strict 가 안 켜진다"
    assert set(schema["required"]) == {"belief", "holds", "polarity"}, "belief 가 빠지면 정정 발화를 다시 오해로 읽는다 (6/10)"
    assert supports_strict(schema), "llm_client 가 이 스키마에 strict 를 못 켠다"


def test_the_system_prompt_asks_for_the_current_belief_first() -> None:
    """★ 판정 절차가 프롬프트에 있다 — 필드만 있고 지시가 없으면 모델이 빈칸을 아무렇게 채운다.

    `belief` 필드(스키마)와 «지금 믿는 것을 먼저 적으라»(프롬프트)는 한 벌이다. 한쪽만 남으면
    N=10 에서 정정 발화 오판이 되돌아온다(#503 실측: Strict 만 6/10). 지시를 지우는 변이가 여기서 죽는다.
    """
    s = misconception._POLARITY_SYSTEM
    assert "belief" in s, "프롬프트가 belief 필드를 말하지 않는다"
    assert "지금 믿는 것" in s, "«지금» 믿는 것을 적으라는 지시가 없다 — 과거 믿음이 섞인다"
    assert "고쳐 말한" in s, "고쳐 말한 것을 빼라는 지시가 없다 — 정정 발화가 오해로 읽힌다"

# ── ⑧ 후보가 «여럿» 일 때 (이슈 #498) ────────────────────────────────────────
#
# ❗**이 파일의 나머지 전부가 후보 1건으로만 돈다.** 진입이 `misconception.match(발화)` 인데
# 실발화는 후보가 최대 1개이기 때문이다 — 오늘 전수로 쟀다.
#
#     공식 코퍼스 70행   후보 분포 {0: 70}
#     dev set     35행   후보 분포 {0: 25, 1: 10}   최대 **1** · 2개 이상 0건
#
# 후보가 1개면 결과가 **전부 남거나 전부 빠지거나** 둘뿐이라, 게이트가 「일부만」 거를 때의
# 성질이 하나도 안 잠긴다. 구조상 유형당 1건씩이라 최대 10까지 가능하므로 **경로는 실재하고
# 데이터에만 없다** — 그래서 합성으로 태운다(`#493` 의 `unlinked_until` 과 같은 자리).
#
# 특히 이것이 `#498`(병렬화 제안)의 전제 조건이다: 후보를 동시에 던지고 `zip` 으로 다시
# 짝지으면 **짝짓기가 깨지는 자리가 여기인데 그물이 없었다.**
_M_KEEP = "M01-PRINCIPAL-GUARANTEE"
_M_DROP = "M02-DEPOSIT-INSURANCE"
_M_TYING = "M08-TYING"


def _synth(*type_ids: str) -> misconception.MisconceptionResponse:
    """후보 여럿을 손으로 만든다. 유형ID 는 라이브러리 실물이어야 `_escalates` 가 돈다."""
    return misconception.MisconceptionResponse(
        matches=[
            misconception.MisconceptionMatch(
                type_id=t, label=f"({t})", score=0.9,
                matched_pattern=f"패턴-{t}", stage="ngram",
            )
            for t in type_ids
        ],
        escalate=any(misconception._escalates(t) for t in type_ids),
    )


class _PerType:
    """유형별로 다른 판정을 준다 — 프롬프트에 실린 유형ID 로 가른다.

    ❗**호출 순서로 가르지 않는다.** 순서 기반 스텁은 병렬화가 들어오는 순간 회차마다
    갈리고, 그러면 무엇이 깨진 것인지 알 수 없다(`#454` 가 그 대가였다). 이 스텁은
    `#498` 이 제안한 병렬화가 들어와도 그대로 쓸 수 있다.
    """

    def __init__(self, holds_for: set[str]) -> None:
        self._holds_for = holds_for
        self.asked: list[str] = []

    def complete_json(self, **kwargs):
        prompt = kwargs.get("prompt", "")
        hit = next((t for t in (_M_KEEP, _M_DROP, _M_TYING) if f"패턴-{t}" in prompt), None)
        self.asked.append(hit or "?")
        holds = hit in self._holds_for
        # ❗`belief` 는 `#504` 로 **필수**가 됐다. 안 채우면 검증 예외 → 게이트가 «못 돌았다» 로
        #   후보를 남기고(P5 0.2절 fail-open) 테스트가 «게이트가 틀렸다» 로 읽힌다.
        return misconception.PolarityVerdict(
            belief=f"(스텁) {hit}", holds=holds,
            polarity="positive" if holds else "negative")


def test_the_gate_keeps_the_right_candidate_not_just_the_right_count(_reset_meter) -> None:
    """★ 후보 셋 중 **어느 것이** 남았는가 — 개수만 맞으면 짝짓기가 틀려도 통과한다."""
    response = _synth(_M_KEEP, _M_DROP, _M_TYING)
    client = _PerType({_M_KEEP})

    out = misconception.apply_polarity_gate(response, "아무 발화", client=client)

    assert [m.type_id for m in out.matches] == [_M_KEEP], (
        f"남은 것이 다르다 — 물어본 순서 {client.asked}"
    )
    assert set(client.asked) == {_M_KEEP, _M_DROP, _M_TYING}, "후보 전부에게 안 물었다"


def test_the_surviving_order_follows_the_input(_reset_meter) -> None:
    """★ 남은 것들의 **순서**가 입력 순서다.

    지금은 리스트 컴프리헨션이라 자명하지만, `#498` 이 제안한 병렬화는 결과를 `zip` 으로
    다시 짝짓는다 — **그때 이 단정이 짝짓기를 잠근다.**
    """
    response = _synth(_M_TYING, _M_KEEP, _M_DROP)
    out = misconception.apply_polarity_gate(
        response, "아무 발화", client=_PerType({_M_TYING, _M_DROP}))
    assert [m.type_id for m in out.matches] == [_M_TYING, _M_DROP]


def test_the_meter_counts_each_candidate_not_each_call(_reset_meter) -> None:
    """★ 계량기가 **후보당** 센다 — 후보 1건으로는 「호출당」과 구별되지 않는다."""
    misconception.apply_polarity_gate(
        _synth(_M_KEEP, _M_DROP, _M_TYING), "아무 발화", client=_PerType({_M_KEEP}))

    meter = _reset_meter
    assert meter.asked == 3
    assert meter.kept == 1
    assert meter.dropped == 2
    # `by_type` 은 유형이 둘 이상이어야 뜻이 생긴다 — 빠진 것만 유형별로 센다.
    assert meter.by_type == {_M_DROP: 1, _M_TYING: 1}


def test_dropping_the_tying_candidate_clears_the_signal_but_keeps_the_others(_reset_meter) -> None:
    """★❗`escalate` 재계산 — 이 함수의 docstring 이 약속한 것인데 안 잠겨 있었다.

    > `escalate` 를 다시 계산한다. 게이트가 `M08-TYING` 후보를 빼면 그 신호도 같이
    > 사라져야 한다 — 안 그러면 *"오해는 없는데 꺾기 신호는 있다"* 가 되고, 그 상태는
    > `F-GTE-003` 에서 설명할 수가 없다.

    ❗**재미있는 경우는 「M08 은 빠지고 다른 후보는 남는」 것**인데, 후보 1건으로는 그
    상태를 만들 수 없다(전부 빠지면 `matches` 도 비어 아무것도 구별되지 않는다).
    """
    response = _synth(_M_KEEP, _M_TYING)
    assert response.escalate, "전제 — M08 이 있으면 신호가 서 있다"

    out = misconception.apply_polarity_gate(
        response, "아무 발화", client=_PerType({_M_KEEP}))

    assert [m.type_id for m in out.matches] == [_M_KEEP], "다른 후보는 남아야 한다"
    assert not out.escalate, "M08 이 빠졌는데 꺾기 신호가 남았다 — F-GTE-003 이 설명 못 한다"


def test_keeping_the_tying_candidate_keeps_the_signal(_reset_meter) -> None:
    """양성 대조 — 위 테스트가 «항상 끄는» 구현으로도 통과하지 않게 한다.

    ❗**후보를 하나는 빼야 한다.** 전부 남으면 `apply_polarity_gate` 가
    `len(kept) == len(matches)` 로 **원본을 그대로 돌려주고**(짧은 경로) `escalate`
    재계산을 아예 안 지난다 — 첫 판이 그래서 `"escalate": False` 변조를 **안 물었다.**
    양성 대조가 대조를 안 하고 있던 자리다.
    """
    out = misconception.apply_polarity_gate(
        _synth(_M_KEEP, _M_DROP, _M_TYING), "아무 발화",
        client=_PerType({_M_KEEP, _M_TYING}))

    assert [m.type_id for m in out.matches] == [_M_KEEP, _M_TYING], "M02 만 빠져야 한다"
    assert out.escalate, "M08 이 남았는데 꺾기 신호가 꺼졌다"


# ── 병렬 경로 (이슈 #498) ────────────────────────────────────────────────────
#
# `conftest.py` 가 스위트 전체에서 이 층을 **순차로** 덮는다(스텁이 호출 순서에 매여 있다).
# 그래서 병렬 경로는 여기서 **스레드 안전한 스텁으로** 따로 잰다 —
# `test_parallel_consistency.py` 가 `#437` 에 대해 하는 것과 같은 자리다.
class _ThreadSafeVerdicts:
    """호출 순서에 안 매인 스텁. 패턴 문면으로 답을 정한다."""

    def __init__(self, drop: set[str] | None = None) -> None:
        self._drop = drop or set()
        self._lock = threading.Lock()
        self.threads: set[str] = set()
        self.calls = 0

    def complete_json(self, **kwargs):
        with self._lock:
            self.calls += 1
            self.threads.add(threading.current_thread().name)
        time.sleep(0.05)                      # 병렬이면 겹치고 순차면 쌓인다
        holds = not any(d in kwargs["prompt"] for d in self._drop)
        return misconception.PolarityVerdict(
            belief="(스텁)", holds=holds, polarity="positive" if holds else "negative")


class _Match:
    def __init__(self, type_id: str) -> None:
        self.type_id = type_id
        self.matched_pattern = f"패턴-{type_id}"


def _parallel(monkeypatch):
    monkeypatch.setattr(misconception, "_polarity_parallel_enabled", lambda: True)


def test_the_verdicts_keep_the_input_order(monkeypatch) -> None:
    """★ 후보와 판정이 **안 섞인다.** 섞이면 엉뚱한 후보가 빠지는데 조용하다."""
    _parallel(monkeypatch)
    matches = [_Match(f"M{i:02d}") for i in range(6)]
    # ❗**기대값이 회문이면 안 된다.** 처음엔 {M01, M04} 를 뺐는데 그러면 기대값이
    #   [T,F,T,T,F,T] 라 **뒤집어도 같아서** 순서가 섞이는 변이가 통과했다.
    client = _ThreadSafeVerdicts(drop={"패턴-M00", "패턴-M01"})

    holds = misconception._verdicts(client, matches, "발화")

    assert holds == [False, False, True, True, True, True]
    assert holds != list(reversed(holds)), "기대값이 회문이면 이 대조가 순서를 안 잰다"


def test_the_meter_counts_exactly_under_concurrency(monkeypatch) -> None:
    """★ `+= 1` 은 원자적이지 않다 — 락이 없으면 **건수가 조용히 샌다.**"""
    _parallel(monkeypatch)
    misconception.METER.__init__()             # 이 테스트 안에서만 세기 위해 초기화
    matches = [_Match(f"M{i:02d}") for i in range(12)]
    client = _ThreadSafeVerdicts(drop={f"패턴-M{i:02d}" for i in range(0, 12, 2)})

    misconception._verdicts(client, matches, "발화")

    assert misconception.METER.asked == 12
    assert misconception.METER.kept + misconception.METER.dropped == 12
    # 합계와 내역이 어긋나는 것이 by_type 락 누락의 유일한 증상이다
    assert sum(misconception.METER.by_type.values()) == misconception.METER.dropped


def test_the_meter_does_not_leak_under_a_hammering(monkeypatch) -> None:
    """★ 락이 진짜 필요한지 — **경합을 만들어서** 잰다.

    ❗**두 번 약했다.** ① 후보 12건을 병렬로 도는 것만으로는 안 난다(워커 4개 · 호출마다
    sleep 이라 겹칠 확률이 낮다). ② 계량기를 직접 두드려도 **기본 전환 간격(5ms)에서는
    안 난다** — 락을 빼는 변이가 3,200회 두드림을 그대로 통과했다.

    그래서 `sys.setswitchinterval` 을 낮춰 **선점을 강제한다.** 그 조건에서 재면 실제로
    샌다(락 없이 16,000 중 11,065). 경합 테스트는 **경합을 만들어야** 대조가 된다.
    """
    misconception.METER.__init__()
    original_interval = sys.getswitchinterval()
    sys.setswitchinterval(1e-6)
    monkeypatch_undo = lambda: sys.setswitchinterval(original_interval)
    workers, per_worker = 8, 2000

    def hammer():
        for _ in range(per_worker):
            misconception.METER.record_dropped("M01", contradicted=True)

    try:
        threads = [threading.Thread(target=hammer) for _ in range(workers)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
    finally:
        monkeypatch_undo()

    total = workers * per_worker
    assert misconception.METER.dropped == total
    assert misconception.METER.contradicted == total
    assert misconception.METER.by_type["M01"] == total, (
        "합계와 내역이 어긋난다 — by_type 의 read-modify-write 가 락 밖에 있다"
    )


def test_the_candidates_actually_run_at_the_same_time(monkeypatch) -> None:
    """★ 「병렬로 만들었다」가 참인지 — 스레드가 실제로 갈리는지 본다.

    배선이 맞아도 도는지는 다른 층이다. 여기서 안 재면 `_verdicts` 가 순차로 떨어져도
    위 두 단정이 그대로 통과한다.
    """
    _parallel(monkeypatch)
    matches = [_Match(f"M{i:02d}") for i in range(4)]
    client = _ThreadSafeVerdicts()

    started = time.perf_counter()
    misconception._verdicts(client, matches, "발화")
    elapsed = time.perf_counter() - started

    assert len(client.threads) > 1, f"한 스레드에서만 돌았다: {client.threads}"
    assert elapsed < 4 * 0.05, f"순차만큼 걸렸다({elapsed:.2f}s) — 병렬이 안 돈다"


def test_a_single_candidate_does_not_touch_the_pool(monkeypatch) -> None:
    """후보가 하나면 스레드를 안 쓴다 — 얻는 것 없이 넣었다 꺼내는 값만 든다."""
    _parallel(monkeypatch)
    client = _ThreadSafeVerdicts()

    misconception._verdicts(client, [_Match("M01")], "발화")

    assert client.threads == {threading.current_thread().name}


def test_an_exception_does_not_kill_scoring(monkeypatch) -> None:
    """★ 게이트는 **과탐을 줄이는 장치이지 판정을 만드는 장치가 아니다**(P1).

    `_polarity_holds` 는 스스로 전부 잡지만, `executor.map` 은 안에서 난 예외를 결과를
    꺼낼 때 올린다 — 그 계약이 깨지면 게이트가 채점을 죽인다. 한 겹 더 막았는지 잰다.
    """
    _parallel(monkeypatch)
    monkeypatch.setattr(misconception, "_polarity_holds",
                        lambda *a, **k: (_ for _ in ()).throw(RuntimeError("(테스트)")))

    holds = misconception._verdicts(object(), [_Match("M01"), _Match("M02")], "발화")

    assert holds == [True, True], "실패는 후보를 남기는 쪽으로 떨어져야 한다 (P5 0.2절)"


# ── 계량기 조회 경로 (이슈 #483) ─────────────────────────────────────────────
#
# `#327` ①~④ 는 evidence 에 이미 쌓여 있어 세는 코드만 없었는데, 이 계량기는 **애초에
# 기록으로 안 간다.** 그래서 조회 경로가 먼저 필요하다.
def test_the_summary_endpoint_reports_the_meter() -> None:
    """★ 계량기 값이 그대로 나온다."""
    from fastapi.testclient import TestClient

    from app.main import app

    misconception.METER.__init__()
    misconception.METER.record_kept()
    misconception.METER.record_dropped("M08-TYING", contradicted=True)
    misconception.METER.record_not_run()

    body = TestClient(app).get("/internal/polarity/summary").json()

    assert body == {"asked": 3, "kept": 1, "dropped": 1, "contradicted": 1,
                    "not_run": 1, "by_type": {"M08-TYING": 1}}


def test_not_run_is_visible_and_distinct_from_zero() -> None:
    """★ **이 엔드포인트가 존재하는 이유다.**

    게이트는 실패하면 후보를 남기므로(P5 0.2절) `dropped == 0` 과 `not_run > 0` 이
    **판정에서 구별되지 않는다.** 결정 5.40 — 못 잰 값은 0 이 아니라 「모른다」다.
    """
    from fastapi.testclient import TestClient

    from app.main import app
    client = TestClient(app)

    misconception.METER.__init__()
    quiet = client.get("/internal/polarity/summary").json()

    misconception.METER.__init__()
    misconception.METER.record_not_run()
    misconception.METER.record_not_run()
    broken = client.get("/internal/polarity/summary").json()

    assert quiet["dropped"] == broken["dropped"] == 0, "판정 쪽 증상이 같다는 것이 전제다"
    assert quiet["not_run"] == 0 and broken["not_run"] == 2, "그 둘을 가르는 값이 이것뿐이다"


def test_reading_the_summary_does_not_reset_it() -> None:
    """읽기가 값을 바꾸면 두 소비자가 서로의 값을 지운다."""
    from fastapi.testclient import TestClient

    from app.main import app
    client = TestClient(app)

    misconception.METER.__init__()
    misconception.METER.record_kept()

    first = client.get("/internal/polarity/summary").json()
    second = client.get("/internal/polarity/summary").json()

    assert first == second == {"asked": 1, "kept": 1, "dropped": 0, "contradicted": 0,
                               "not_run": 0, "by_type": {}}


def test_the_summary_is_behind_internal_auth() -> None:
    """★ `by_type` 에 `M08-TYING` 이 들어온다 — **무인증 자리에 두면 안 된다**(기획 7-4).

    `/healthz` 가 `GUARDED_PREFIX` 밖이라 거기 실을 수 없는 이유이고, 이 경로가
    `/internal/` 접두어를 지키는지가 그 경계다.
    """
    from app.main import GUARDED_PREFIX

    from fastapi.testclient import TestClient

    from app.main import app
    paths = [p for p in TestClient(app).get("/openapi.json").json()["paths"]
             if "polarity" in p]
    assert paths, "요약 경로가 스키마에 없다"
    assert all(p.startswith(GUARDED_PREFIX) for p in paths), (
        f"인증 밖에 있다: {paths} — by_type 이 M08-TYING 을 담는다"
    )
