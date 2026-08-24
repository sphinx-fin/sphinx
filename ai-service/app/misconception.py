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

from .schemas import PRODUCT_TYPES, MisconceptionMatch, MisconceptionResponse

LIBRARY_PATH = (
    Path(__file__).resolve().parents[2] / "data" / "misconception_library" / "misconceptions.yaml"
)

# 문자 바이그램 포함도 임계값. 이 숫자가 설명 가능한 형태로 드러나 있어야 한다
# (기획서 5절: 라이브러리 기반 재현성). 튜닝 시 dev set으로 재측정한다.
NGRAM_THRESHOLD = 0.62
# 후보 큐 적재 하한 — 임계값에 못 미치지만 무시하기엔 가까운 것
REVIEW_THRESHOLD = 0.45

_NOISE = re.compile(r"[^0-9가-힣a-zA-Z]+")


#: 오해 유형의 근거 종류. 기획서 5절은 "실제로 분쟁까지 간 오해만 라이브러리에 들어간다.
#: 근거가 조정례와 검사결과라는 뜻이다"라고 못박는다. proposal_example은 그 두 종류가
#: 아니므로 감사 시점에 구분돼야 한다.
SOURCE_TYPES = ("dispute_case", "inspection", "proposal_example")


@dataclass(frozen=True)
class SourceRef:
    """근거 출처. 라이브러리가 str 한 줄이던 시절 형태도 받는다(전환기)."""

    type: str | None
    ref: str

    @property
    def is_citable(self) -> bool:
        """판정 근거로 인용 가능한가. TODO 문자열과 빈 값은 아니다."""
        return bool(self.ref) and "TODO" not in self.ref

    @property
    def is_dispute_grounded(self) -> bool:
        """분쟁조정례·검사결과에 근거하는가 (기획서 5절)."""
        return self.type in ("dispute_case", "inspection") and self.is_citable


@dataclass(frozen=True)
class MisconceptionType:
    type_id: str
    label: str
    patterns: tuple[str, ...]
    products: tuple[str, ...]
    escalate: str | None
    source: SourceRef

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


def _parse_source(entry: dict) -> SourceRef:
    """`source`가 문자열이든 매핑이든 받는다.

    라이브러리가 매핑 형태로 바뀔 때(정세현) str 자리에 dict가 조용히 앉는 것을 막는다 —
    타입 에러도 로그도 없이 근거 표시만 망가지는, assert_products_are_canonical()로 막은
    것과 같은 실패 양식이다. 알 수 없는 type은 로딩 시점에 터뜨린다.
    """
    raw = entry.get("source")
    if raw is None:
        return SourceRef(type=None, ref="")
    if isinstance(raw, str):
        return SourceRef(type=None, ref=raw)
    if isinstance(raw, dict):
        stype = raw.get("type")
        if stype is not None and stype not in SOURCE_TYPES:
            raise ValueError(
                f"{entry.get('id')}: 알 수 없는 source.type {stype!r}. 허용: {list(SOURCE_TYPES)}"
            )
        return SourceRef(type=stype, ref=str(raw.get("ref", "")))
    raise ValueError(f"{entry.get('id')}: source 형태를 알 수 없다: {type(raw).__name__}")


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
            source=_parse_source(entry),
        )
        for entry in (raw.get("types") or [])
    )


def assert_products_are_canonical() -> None:
    """라이브러리의 products 값이 계약(PRODUCT_TYPES)을 벗어나지 않는지 확인한다.

    값이 어긋나면 match()가 예외도 로그도 없이 빗나가고 해당 상품 오해가 하나도 잡히지
    않는다. 그 조용한 실패를 막기 위해 로딩 시점에 터뜨린다.
    """
    allowed = set(PRODUCT_TYPES) | {"ALL"}
    for mtype in library():
        unknown = set(mtype.products) - allowed
        if unknown:
            raise ValueError(
                f"{mtype.type_id}: 계약 밖의 product 값 {sorted(unknown)}. "
                f"허용: {sorted(allowed)}"
            )


def types_without_citable_source() -> tuple[str, ...]:
    """근거를 인용할 수 없는 유형 목록.

    apply_misconception_floor()가 이 유형의 매칭만으로 U4를 확정하면 근거 없이 등급을
    올리는 것이 된다. 지금은 목록을 노출만 하고 막지는 않는다 — 정세현이 조정례 번호를
    채우는 중이고, 여기서 예외를 던지면 라이브러리가 아예 로드되지 않는다.
    """
    return tuple(m.type_id for m in library() if not m.source.is_citable)


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
