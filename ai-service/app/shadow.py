"""루브릭이 **선언만 하고 강제 못 하는** 오해가 실제로 몇 번 걸리는지 잰다.

소유: 윤지석 (F-DET-001 · 이슈 #284 (b) 의 결정 근거)

## 무엇이 문제인가

루브릭에 목록이 둘 있고 성격이 다르다.

    misconception_conditions   프롬프트에 실린다. 모델이 읽고 판단한다    → 강제력 없음
    related_misconceptions     apply_misconception_floor 가 U4 로 올린다  → 강제력 있음

`#298` 이 그 비율을 기동 로그에 냈다 — **선언 46 · 링크 24 · 라이브러리 9종.** 그리고
`#284` 를 낳은 `ELS-ISSUER-CREDIT-RISK` 는 링크가 있어서 *"강제된다"* 로 세어지는데,
정작 `els-0028` 이 말한 조건(*"기초자산만 오르면 안전하다"*)은 그 링크가 안 덮는다.

## ❗왜 지금 켜지 않고 재기만 하나

조건 문면 → 유형ID 대응을 붙이는 것이 `#284` 의 `(b)` 이고 **채점 동작을 바꾸는 일**이다.
지금 켜면 등급이 달라지는데 **얼마나 달라지는지 아무도 모른다** — 3주차 정량평가 전에
등급 분포를 흔들면 그 회차 수치를 못 쓴다.

그래서 **판정을 안 건드리고 「켰으면 바뀌었을 건수」만** 낸다. 그 숫자가 있으면 켤지를
근거로 정한다. 지금은 그 판단을 근거 없이 해야 한다.

## 왜 임베딩이 아니라 바이그램인가

`#358` 이 임베딩·리랭킹을 들여오는데 **리랭커가 비결정적이다**(`#281`). 채점에 들어갈
후보를 비결정적으로 고르면 **같은 발화가 회차마다 다른 등급**을 받는다 — P2 가 막는 것이다.

여기 쓰는 `textsim.containment` 는 매처가 이미 쓰는 그 함수이고 **결정론적**이다. 그래서
이 측정이 그대로 켜질 수 있는 후보가 된다 — 재기만 하고 못 켜는 방법을 고르면 측정이
의사결정에 못 쓰인다.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field

from . import misconception, rubrics, textsim

log = logging.getLogger(__name__)

#: 매처와 **같은 임계값**을 쓴다. 다른 숫자를 쓰면 여기서 잰 건수가 "켰을 때" 를 안 말한다.
THRESHOLD = misconception.NGRAM_THRESHOLD


@dataclass(frozen=True)
class ShadowHit:
    """강제 통로가 없는 조건 하나가 발화에 걸렸다."""

    item_id: str
    condition: str
    score: float
    #: 이 조건이 걸렸을 때 등급이 실제로 달라졌을 것인가.
    #: 이미 U4 면 안 달라진다 — 그건 "켜도 얻는 게 없는" 건이다.
    would_change_grade: bool


@dataclass
class ShadowMeter:
    """켰으면 어떻게 됐을지의 누적. 프로세스와 함께 사라진다 — 운영 관측값이다.

    ❗<b>발화를 안 담는다.</b> 조건 문면(공개 의무 대상)과 항목 ID 만 센다 — 고객이 무엇을
    말했는지가 여기 쌓이면 그건 다른 종류의 저장이다(P3).
    """

    scored: int = 0
    utterances_with_hit: int = 0
    would_change: int = 0
    by_condition: dict[str, int] = field(default_factory=dict)

    def record(self, hits: list[ShadowHit]) -> None:
        self.scored += 1
        if not hits:
            return
        self.utterances_with_hit += 1
        if any(h.would_change_grade for h in hits):
            self.would_change += 1
        for hit in hits:
            key = f"{hit.item_id}:{hit.condition}"
            self.by_condition[key] = self.by_condition.get(key, 0) + 1

    def summary(self) -> str:
        return (f"채점 {self.scored}건 · 그림자 매칭 {self.utterances_with_hit}건 "
                f"· 등급이 달라졌을 것 {self.would_change}건")


METER = ShadowMeter()


def unenforced_conditions(rubric: rubrics.Rubric) -> tuple[str, ...]:
    """강제 통로가 **없는** 조건 — 링크된 유형의 패턴이 안 덮는 것.

    ❗조건을 전부 세지 않는다. 링크가 있어도 **그 링크가 이 조건을 덮는다는 보장이 없고**,
    반대로 링크된 유형의 패턴과 거의 같은 조건은 이미 잡힌다. 후자를 세면 그림자 건수가
    부풀어 *"켜면 이만큼 잡힌다"* 가 거짓이 된다.
    """
    covered: list[str] = []
    types = {m.type_id: m for m in misconception.library()}
    for type_id in rubric.related_misconceptions:
        mtype = types.get(type_id)
        if mtype is not None:
            covered.extend(mtype.patterns)

    out: list[str] = []
    for condition in rubric.misconception_conditions:
        norm = textsim.normalize(condition)
        if any(textsim.containment(textsim.normalize(p), norm) >= THRESHOLD for p in covered):
            continue        # 이미 라이브러리가 잡는다
        out.append(condition)
    return tuple(out)


def probe(answer_text: str, rubric: rubrics.Rubric, grade: str) -> list[ShadowHit]:
    """발화가 **강제 통로 없는 조건**에 걸리는가. 판정은 안 바꾼다.

    ❗`grade` 는 **이미 나온 등급**이다. 여기서 등급을 만들지 않는다 — 이 함수가 판정에
    닿는 순간 P1(AI 는 측정, 룰은 결정)이 아니라 *"측정이 판정을 바꾼다"* 가 된다.
    """
    norm = textsim.normalize(answer_text)
    hits: list[ShadowHit] = []
    for condition in unenforced_conditions(rubric):
        score = textsim.containment(textsim.normalize(condition), norm)
        if score >= THRESHOLD:
            hits.append(ShadowHit(rubric.item_id, condition, round(score, 4),
                                  would_change_grade=grade != "U4"))
    return hits


def observe(answer_text: str, rubric: rubrics.Rubric, grade: str) -> list[ShadowHit]:
    """`probe` + 계량 + 로그. 채점 경로가 부르는 것은 이쪽이다.

    ❗**로그에 발화를 안 찍는다.** 조건 문면은 루브릭에서 오고 그건 공개 의무 대상이라
    남겨도 새로 드러나는 것이 없다 — 고객이 한 말은 다르다.
    """
    hits = probe(answer_text, rubric, grade)
    METER.record(hits)
    if hits:
        changed = [h for h in hits if h.would_change_grade]
        log.info(
            "F-DET-001 그림자 매칭: item_id=%s 조건=%d개 등급변경가능=%s (%s) — "
            "판정은 안 바꿨다. 켤지는 #284 (b) 에서 정한다",
            rubric.item_id, len(hits), bool(changed),
            " / ".join(h.condition for h in hits),
        )
    return hits
