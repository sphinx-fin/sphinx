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
        return misconception.PolarityVerdict(holds=self._holds, polarity=self._polarity)


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
