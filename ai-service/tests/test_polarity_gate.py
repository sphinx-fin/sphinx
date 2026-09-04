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
