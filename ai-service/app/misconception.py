"""F-DET-001 오해 탐지. 소유: 윤지석 (라이브러리 데이터: 정세현)

기획서 5절 통제: **"라이브러리 기반이라 재현성이 확보되고, LLM은 변형된 표현을 커버하는
역할만 맡는다."** 그래서 단계를 셋으로 나눈다.

  1) pattern — 정규화 후 포함 검사. 완전 결정론적.
  2) ngram   — 문자 바이그램 포함도. 어미·조사 변형을 덮으면서 여전히 결정론적.
  3) llm     — 위 둘이 못 잡은 변형만. (미구현 — 임베딩 유사도로 붙일 예정)

데모 메인 시나리오(기획서 7-2 ③)의 발화가 2)까지에서 잡혀야 한다. LLM 응답에 의존하면
데모의 임계 경로가 비결정적이 된다.

`escalate`는 라이브러리 데이터의 필드를 **읽어서** 판단한다. M08-TYING을 코드에
하드코딩하지 않는다 — 데이터 소유는 정세현이고, 유형이 늘어도 코드가 안 바뀌어야 한다.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import yaml

from .schemas import MisconceptionMatch, MisconceptionResponse

LIBRARY_PATH = (
    Path(__file__).resolve().parents[2] / "data" / "misconception_library" / "misconceptions.yaml"
)

# 문자 바이그램 포함도 임계값. 이 숫자가 설명 가능한 형태로 드러나 있어야 한다
# (기획서 5절: 라이브러리 기반 재현성). 튜닝 시 dev set으로 재측정한다.
NGRAM_THRESHOLD = 0.62
# 후보 큐 적재 하한 — 임계값에 못 미치지만 무시하기엔 가까운 것
REVIEW_THRESHOLD = 0.45

_NOISE = re.compile(r"[^0-9가-힣a-zA-Z]+")


@dataclass(frozen=True)
class MisconceptionType:
    type_id: str
    label: str
    patterns: tuple[str, ...]
    products: tuple[str, ...]
    escalate: str | None
    source: str

    def applies_to(self, product_type: str) -> bool:
        return "ALL" in self.products or product_type in self.products


def _normalize(text: str) -> str:
    return _NOISE.sub("", text)


def _bigrams(text: str) -> set[str]:
    return {text[i:i + 2] for i in range(len(text) - 1)} or {text}


def _containment(pattern: str, text: str) -> float:
    """패턴의 바이그램이 발화에 얼마나 들어있는지. 발화가 길어도 페널티가 없다."""
    pb, tb = _bigrams(pattern), _bigrams(text)
    if not pb:
        return 0.0
    return len(pb & tb) / len(pb)


@lru_cache(maxsize=1)
def library() -> tuple[MisconceptionType, ...]:
    raw = yaml.safe_load(LIBRARY_PATH.read_text(encoding="utf-8")) or {}
    return tuple(
        MisconceptionType(
            type_id=entry["id"],
            label=entry.get("label", entry["id"]),
            patterns=tuple(entry.get("patterns") or ()),
            products=tuple(entry.get("products") or ("ALL",)),
            escalate=entry.get("escalate"),
            source=entry.get("source", ""),
        )
        for entry in (raw.get("types") or [])
    )


def library_version() -> int:
    raw = yaml.safe_load(LIBRARY_PATH.read_text(encoding="utf-8")) or {}
    return int(raw.get("version", 0))


def match(text: str, product_type: str = "ELS") -> MisconceptionResponse:
    """발화 → 오해 유형 매칭. 유형별 최고점 1건만 남긴다."""
    norm = _normalize(text)
    matches: list[MisconceptionMatch] = []
    near_miss = False

    for mtype in library():
        if not mtype.applies_to(product_type):
            continue
        best: MisconceptionMatch | None = None
        for pattern in mtype.patterns:
            npat = _normalize(pattern)
            if not npat:
                continue
            if npat in norm:
                score, stage = 1.0, "pattern"
            else:
                score, stage = _containment(npat, norm), "ngram"
            if score < NGRAM_THRESHOLD:
                near_miss = near_miss or score >= REVIEW_THRESHOLD
                continue
            if best is None or score > best.score:
                best = MisconceptionMatch(
                    type_id=mtype.type_id, label=mtype.label,
                    score=round(score, 4), matched_pattern=pattern, stage=stage,
                )
        if best is not None:
            matches.append(best)

    matches.sort(key=lambda m: m.score, reverse=True)
    escalate = any(_escalates(m.type_id) for m in matches)
    return MisconceptionResponse(
        matches=matches,
        escalate=escalate,
        unclassified_candidate=(not matches) and near_miss,
    )


def _escalates(type_id: str) -> bool:
    """라이브러리의 escalate 필드를 읽는다 — 유형ID를 코드에 박지 않는다."""
    for mtype in library():
        if mtype.type_id == type_id:
            return mtype.escalate == "compliance"
    return False
