"""F-DET-001 오해 탐지. 소유: 윤지석 (라이브러리 데이터: 정세현)

기획서 5절 통제: **"라이브러리 기반이라 재현성이 확보되고, LLM은 변형된 표현을 커버하는
역할만 맡는다."** 그래서 단계를 셋으로 나눈다.

  1) pattern — 정규화 후 포함 검사. 완전 결정론적.
  2) ngram   — 문자 바이그램 포함도. 어미·조사 변형을 덮으면서 여전히 결정론적.
  3) llm     — **극성 게이트.** 위 둘이 만든 후보 중 「그 오해를 부정·제한하는 발화」를
               떨어뜨린다. 후보를 **만들지 않고 지우기만 한다.**

❗**3단계는 「변형을 더 잡는 층」이 아니라 「잘못 잡은 것을 빼는 층」이다.** docstring 이
전에 *"임베딩 유사도로 붙일 예정"* 이라고 적었는데 **실측으로 반증됐다**(아래).

## 왜 임베딩이 아닌가 — 세 번째 같은 실패다

한국어는 부정·양보가 **어미에 붙는다.** 그래서 오해와 그 부정은 주제가 같고, 주제를 재는
수단은 전부 같은 곳에서 무너진다.

    #203  n-gram        어간까지만 자른 조각이 오해와 부정을 못 가른다
    #283  임베딩 코사인  부정이 벡터를 안 옮긴다 — M09 에서 정답(0.621)이 어미변형(0.560)보다 높다
    9/5   대조 앵커      M09 는 갈리는데(간격 +0.104) **M11 은 겹친다(간격 -0.006)**

**대조 앵커가 M09 에서 된 이유는 오해와 정답의 *내용* 이 달라서였다**(팔 수 있다 ↔ 상장이
안 됐다). M11 은 **같은 명제의 부정만** 다르다 — `전액 보호된다` ↔ `전액 보호되는 건 아니다`.
그때는 pro·anti 앵커가 둘 다 같은 주제라 margin 이 잡음(±0.05)이 된다.

→ **주제를 재는 어떤 수단으로도 극성은 안 갈린다.** 극성을 직접 묻는 수밖에 없다.

## 실측 (2026-09-05 · gpt-5-mini · 3회 반복)

    대조 앵커  M11 부호 오류 2/5 · 간격 -0.006     ← 임계값이 존재할 수 없다
    극성 게이트 9/9 · 3회 전부 동일               ← M11 부정 4건 · M09 부정 1건 포함

## 안전 방향 — 실패하면 후보를 남긴다

게이트가 못 돌면(LLM 장애·미설정) **후보를 그대로 둔다.** 지우는 쪽이 미탐이고 P5(0.2절)가
미탐을 과탐보다 비싸게 다룬다 — 게이트는 과탐을 줄이는 장치이지 판정을 만드는 장치가 아니다.
`client=None` 이면 3단계가 아예 안 돈다(기존 동작 그대로).

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

import logging
from typing import Literal

from pydantic import BaseModel

from . import textsim, thresholds
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

# ❗**값은 `scoring_thresholds.yaml` 에서 온다.** 여기 숫자를 적지 않는다 — 그 파일이
# 「무엇에 반응하는가 · 왜 이 값인가」를 같이 들고 있고, 그게 있어야 심사에서 설명이 되고
# 튜닝이 코드 변경이 아니게 된다(기획서 5절: 라이브러리 기반 재현성).
log = logging.getLogger(__name__)

NGRAM_THRESHOLD = thresholds.get("ngram_match")
REVIEW_THRESHOLD = thresholds.get("ngram_review")

#: 오해 유형의 근거 종류. 기획서 5절은 "실제로 분쟁까지 간 오해만 라이브러리에 들어간다.
#: 근거가 조정례와 검사결과라는 뜻이다"라고 못박는다. 나머지 값은 그 두 종류가 아니므로
#: 감사 시점에 구분돼야 한다 — `is_dispute_grounded` 가 그 경계다.
#:
#: `product_document` 는 **상품문서 조항 자신이 근거인 경우**다(이슈 #148, 정세현 요청).
#: 결정 3.17(*"근거 없는 유형은 개수를 맞추려고 남기지 않는다"*)이 묻는 것은 *"왜 이 문장이
#: 오해인가"* 에 답할 수 있느냐인데, 이 계열은 답이 있다 — **원문이 정면으로 반대로 적고
#: 있다.**
#:
#:     M10 후보  "주식처럼 팔 수 있는 거 아닌가요"
#:     원문      "본 증권은 상장하지 않을 예정이므로"   (p14 560~579)
#:     M09 후보  "급하면 중간에 빼면 되는 거죠 …"
#:     원문      "실제 중도상환금액은 상기한 공정가액(기준가)의 95% 이상"   (p13 414~447)
#:
#: 세 값이 전부 **외부 증거**라 문서 자신을 표현할 칸이 없었다. `M07` 을 뺀 것과 상황이
#: 다르다 — 그건 *"이 말이 오해라는 근거"* 가 아예 없었고, 이쪽은 조정례보다 오히려 직접적인
#: 근거다. 그래도 조정례와 같은 층으로 올리지는 않는다(`is_dispute_grounded` 에 안 넣는다).
SOURCE_TYPES = ("dispute_case", "inspection", "proposal_example", "product_document")


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
        """분쟁조정례·검사결과에 근거하는가 (기획서 5절).

        `product_document` 는 여기 안 넣는다 — 기획서 5절이 말하는 근거는 조정례·검사결과이고,
        상품문서 조항은 인용 가능하지만 그 두 종류가 아니다(이슈 #148). 심사에서 *"분쟁까지
        간 오해만 들어간다"* 를 주장할 때 셀 수 있는 것은 이 속성이 참인 것뿐이다.
        """
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


class PolarityVerdict(BaseModel):
    """3단계 게이트의 출력. **측정값이다** — 판정은 게이트가 아니라 룰이 한다(P1)."""

    holds: bool
    polarity: Literal["positive", "negative", "neutral"]


_POLARITY_SYSTEM = (
    "당신은 금융 상담 발화의 **극성**만 판정한다. 주제가 같은지는 묻지 않는다.\n"
    "발화가 주어진 오해 문장과 **같은 것을 주장하면** holds=true,\n"
    "그 오해를 **부정하거나 범위를 제한하면** holds=false 다.\n"
    "부정·양보는 어미에 붙는다 — '~는 아니다', '~건 아니군요', '~만 된다' 는 부정이다."
)


def _polarity_holds(client, misconception_text: str, utterance: str) -> bool:
    """이 발화가 그 오해를 **실제로 담고 있는가**. 못 물으면 `True`(후보 유지).

    ❗**실패는 후보를 남기는 쪽으로 떨어진다.** 지우면 미탐이고 P5(0.2절)가 미탐을 과탐보다
    비싸게 다룬다. 게이트는 과탐을 줄이는 장치이지 판정을 만드는 장치가 아니다.

    ## ❗두 필드가 합의할 때만 참으로 본다

    `holds` 하나만 보면 **비결정적이었다.** 같은 입력 3회에 1회가 갈렸고, 갈린 회차의 출력이
    `polarity="negative"` 인데 `holds=True` 라는 **자기모순**이었다(2026-09-05 실측).

        holds 만                  1/9 · 0/9 · 0/9      ← 회차마다 다르다
        holds AND polarity        0/9 · 0/9 · 0/9      ← 3회 동일

    같은 호출 안의 두 출력이 어긋나면 그 판정은 못 쓴다 — *"모델 출력을 그대로 믿지 않는다"*
    를 **한 응답 안에서** 적용하는 자리다. 합의를 요구하면 어긋난 회차가 `False` 로 떨어지고,
    `False` 는 후보를 빼는 쪽이라 **과탐을 줄이는 이 게이트의 방향과 같다.**

    덕분에 **오해 문장을 따로 데이터로 만들 필요가 없다** — 매칭된 패턴을 그대로 쓴다.
    명제형 문장을 쓰면 `holds` 만으로도 3회 0/9 였지만, 그건 라이브러리에 `claim` 필드를
    새로 만들어야 하고 그 파일은 정세현 소유다.
    """
    try:
        verdict = client.complete_json(
            prompt=f"오해 문장: {misconception_text}\n발화: {utterance}",
            model_cls=PolarityVerdict, schema_name="PolarityVerdict",
            system=_POLARITY_SYSTEM,
        )
    except Exception as exc:                       # LLM 계열 예외를 여기서 좁히지 않는다
        log.info(
            "F-DET-001 극성 게이트 실패: %s — 후보를 그대로 둔다(P5 0.2절). 발화=%r",
            type(exc).__name__, utterance[:40],
        )
        return True
    holds = verdict.holds and verdict.polarity == "positive"
    if verdict.holds and not holds:
        log.info(
            "F-DET-001 극성 게이트 자기모순: holds=True 인데 polarity=%s — 뺀다. 발화=%r",
            verdict.polarity, utterance[:40],
        )
    log.info(
        "F-DET-001 극성 게이트: holds=%s polarity=%s → %s. 발화=%r",
        verdict.holds, verdict.polarity, holds, utterance[:40],
    )
    return holds


def match(text: str, product_type: str = "ELS", *, client=None) -> MisconceptionResponse:
    """발화 → 오해 유형 매칭. 유형별 최고점 1건만 남긴다.

    `client` 를 주면 **3단계 극성 게이트**가 돈다 — 1·2단계가 만든 후보 중 그 오해를
    부정·제한하는 발화를 떨어뜨린다. 안 주면 3단계가 아예 안 돈다(기존 동작).

    **게이트는 후보를 만들지 않는다.** `stage` 는 그 후보를 **찾은** 단계 그대로 둔다 —
    발견 경로와 확인 경로는 다른 사실이고, 하나로 덮으면 재현성 논의에서 둘이 섞인다.
    """
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

    if client is not None and matches:
        kept = [
            m for m in matches
            if _polarity_holds(client, m.matched_pattern, text)
        ]
        if len(kept) != len(matches):
            log.info(
                "F-DET-001 극성 게이트가 후보 %d 건 중 %d 건을 뺐다",
                len(matches), len(matches) - len(kept),
            )
        matches = kept

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
