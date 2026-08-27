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

from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import yaml

from . import textsim
from .config import DATA_DIR_ENV, settings
from .schemas import PRODUCT_TYPES, MisconceptionMatch, MisconceptionResponse

#: 라이브러리 파일의 `data_dir` 기준 상대 위치. 절대경로를 여기 박지 않는다 —
#: 결정로그 10.7. 디렉토리는 `SPHINX_DATA_DIR` 로 주입한다.
LIBRARY_RELPATH = Path("misconception_library") / "misconceptions.yaml"


class MisconceptionLibraryMissing(FileNotFoundError):
    """라이브러리 파일이 없다. 컨테이너에서 `data/` 를 안 마운트한 경우가 이것이다."""


def library_path() -> Path:
    """오해 라이브러리 경로. 상수가 아니라 함수인 이유는 `SPHINX_DATA_DIR` 주입 때문이다.

    상수로 두면 import 시점에 값이 굳어 환경변수를 나중에 넣어도 반영되지 않는다. 테스트가
    경로를 갈아끼울 수 없다는 뜻이기도 하다.
    """
    return settings().data_dir / LIBRARY_RELPATH

# 문자 바이그램 포함도 임계값. 이 숫자가 설명 가능한 형태로 드러나 있어야 한다
# (기획서 5절: 라이브러리 기반 재현성). 튜닝 시 dev set으로 재측정한다.
NGRAM_THRESHOLD = 0.62
# 후보 큐 적재 하한 — 임계값에 못 미치지만 무시하기엔 가까운 것
REVIEW_THRESHOLD = 0.45

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


#: 정규화·바이그램·포함도는 `textsim` 한 벌만 쓴다 — F-SCR-001 복창 판정이 같은 계산을
#: 쓰므로 두 곳에서 따로 정규화하면 임계값을 서로 비교할 수 없게 된다.
_normalize = textsim.normalize
_bigrams = textsim.bigrams
_containment = textsim.containment


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


def _read_library() -> dict:
    path = library_path()
    if not path.is_file():
        raise MisconceptionLibraryMissing(
            f"오해 라이브러리가 없다: {path}  "
            f"({DATA_DIR_ENV} 로 data 디렉토리를 지정한다. 지금 값: {settings().data_dir})"
        )
    return yaml.safe_load(path.read_text(encoding="utf-8")) or {}


@lru_cache(maxsize=1)
def library() -> tuple[MisconceptionType, ...]:
    """파일을 읽고 **읽은 자리에서 검증한다.**

    `assert_products_are_canonical` 은 docstring 이 *"로딩 시점에 터뜨린다"* 고 적었지만
    실제로는 테스트에서만 불렸다 — 즉 테스트를 안 돌린 환경에서는 그 조용한 실패가 그대로
    남았다. 검증을 로더 안으로 옮겨 문면과 동작을 맞춘다(PR #113 리뷰에서 스스로 찾은 것).
    """
    raw = _read_library()
    types = tuple(
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
    _assert_products_are_canonical(types)
    return types


def assert_products_are_canonical() -> None:
    """공개 진입점. `library()` 가 로딩 때 이미 부르므로 여기는 명시적 재확인용이다."""
    _assert_products_are_canonical(library())


def _assert_products_are_canonical(types: tuple[MisconceptionType, ...]) -> None:
    """라이브러리의 products 값이 계약(PRODUCT_TYPES)을 벗어나지 않는지 확인한다.

    값이 어긋나면 match()가 예외도 로그도 없이 빗나가고 해당 상품 오해가 하나도 잡히지
    않는다. 그 조용한 실패를 막기 위해 로딩 시점에 터뜨린다.

    `library()` 안에서 불리므로 여기서 `library()` 를 부르면 재귀가 된다 — 파싱된 튜플을
    인자로 받는다.
    """
    allowed = set(PRODUCT_TYPES) | {"ALL"}
    for mtype in types:
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
    return int(_read_library().get("version", 0))


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
