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

## ❗3단계가 붙은 뒤로 임계 경로는 게이트를 지난다 — 약속을 낮춘다

전에는 *"데모 메인 시나리오(기획서 7-2 ③)의 발화가 2)까지에서 잡혀야 한다. LLM 응답에
의존하면 데모의 임계 경로가 비결정적이 된다"* 고 적혀 있었다. **이 층이 생기면서 그 문장이
거짓이 됐다**(`#397` 리뷰, 정세현). 데모 발화는 `pattern 1.0` 으로 잡히는데도 이제 게이트를
한 번 지나야 살아남는다.

`ngram` 단계에만 게이트를 걸어 결정론을 지키는 안을 재 봤고 **실측으로 반증됐다.**

    "…원금은 보장된다고 생각했는데 아니라면서요"      M01  pattern 1.00
    "…팔면 되는 거 아닌가요? 아니라고 하셨죠"         M09  pattern 1.00
    "낸 돈은 다 돌려받는 거죠? 아니라고 방금 들었어요"  M06  pattern 1.00
                                                    → 게이트가 셋 다 3/3 으로 뺀다

**부정하는 발화일수록 패턴을 축자로 담는다** — 부정이 어미에 붙으니 앞부분이 그대로 남는다.
즉 `pattern` 이 오탐이 제일 잘 나는 자리이고, 거기를 빼면 이 층을 만든 이유가 없어진다.

### 그래서 낮춘 자리를 명시한다

    실패는 후보를 남기는 쪽으로 떨어진다   예외 · 응답 타입 불일치 (P5 0.2절)
    틀린 판정에는 그 방어가 안 걸린다      그래서 아래를 실측으로 잰다

    게이트가 만든 미탐   dev set 후보 **10건** · 3회 반복 **30/30 keep**
                        데모 발화 4건도 3/3
                        ❗**표본이 10건이다.** `#283` 에서 11건으로 방법을 확정했다가
                          M11 에서 무너진 전례가 있다 — 이 숫자를 크게 읽지 않는다

❗**「지금 30/30」이지 결정론이 아니다.** 결정론을 요구하는 자리는 1·2단계다.

❗**공식 코퍼스에서는 이 층이 아예 안 돈다.** 합의 U4 19건(`eval/` 라벨, 리포트 4절의
`4/19` 와 같은 집합)을 1·2단계에 태우면 **후보가 0건**이다 — `pattern` 0 · `ngram` 0,
최고 점수 0.02~0.28 로 임계(`ngram_match` 0.62)에 못 미친다. 그러니 위 `30/30` 을
*"미탐 0"* 으로 읽으면 안 된다. **「잴 자리가 없다」이지 「안전하다」가 아니다** — dev set 은
패턴에서 유도된 표본이라 후보가 잘 생기는 쪽으로 치우친다.

그리고 그 0건은 이 파일보다 넓은 사실을 말한다. **강제되는 라이브러리 9종이 공식 코퍼스에서
한 번도 발동하지 않는다**(`MISCONCEPTION-DETECTION.md` 의 *"어휘 매칭 51건 중 발동 0건"* 과
같은 값). 즉 **오해 판정은 지금 전적으로 LLM 이 하고**, `apply_misconception_floor` 는 패턴을
그대로 담은 발화에서만 운다 — `#284` 가 가리키는 공백이 그것이다.

❗**현재 이 층은 실측 코퍼스에서 한 번도 안 돈다** — 1·2단계가 U4 19건에서 후보를 0건
만들기 때문이다(`#284`). **게이트의 위험도 효과도 아직 실측되지 않았다.** 라이브러리가
실제 화법을 물기 시작하면 그때 이 층이 처음으로 값을 갖는다 — 그때를 위해 지금 잠가 둔다
(`#397` 리뷰, 정세현).

원인은 임계값이 아니라 **패턴의 화법 폭**이다. 패턴이 데모 대본 한 줄에서 나왔고 조정례의
화법 폭이 아니라서, 같은 오해를 한 겹 돌려 말하면 떨어진다.

    데모 발화  "은행에서 파니까 원금은 보장되는 거죠"                      pattern 1.00
    els-0004   "그래도 은행 창구에서 파는 건데, 최소한 넣은 돈은 지켜 주는…"  ngram  0.179

`#377`(표본의 화법 폭이 좁아 종결어미가 라벨을 흘린다)과 **같은 뿌리, 반대 방향**이다.
임계값을 내리면 `#203` 이 잡은 오탐이 돌아오므로 답이 아니다 — 데이터는 정세현 소유이고
`#284` 로 갔다.

`escalate`는 라이브러리 데이터의 필드를 **읽어서** 판단한다. M08-TYING을 코드에
하드코딩하지 않는다 — 데이터 소유는 정세현이고, 유형이 늘어도 코드가 안 바뀌어야 한다.
"""
from __future__ import annotations

import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path

import yaml

import logging
from typing import Literal

from pydantic import BaseModel, Field

from . import textsim, thresholds
from .config import DATA_DIR_ENV, settings
from .schemas import PRODUCT_TYPES, MisconceptionMatch, MisconceptionResponse, Strict

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


class PolarityVerdict(Strict):
    """3단계 게이트의 출력. **측정값이다** — 판정은 게이트가 아니라 룰이 한다(P1).

    ❗**필드 순서가 곧 판정 절차다** (이슈 #503). `belief` 가 **먼저** 온다 — 구조화 출력은
    필드를 순서대로 채우므로, 모델이 *"고객이 지금 믿는 것"* 을 한 문장으로 먼저 적고 나서
    `holds` 를 정한다. 이 한 단계가 «예금자보호가 **안** 되는 상품이군요» 같은 **정정 발화**를
    오해로 읽는 것을 막는다 — 재설명 뒤 고객이 고쳐 말했는데 floor 가 U4 를 확정해
    빠져나오지 못하던 자리다(alpha 재현).

    ## 실측 — 무엇이 고쳤고 무엇이 아닌가 (2026-09-06 · gpt-5-mini)

        문제 발화 «아, 예금자보호가 안 되는 상품이군요. …원금을 못 받을 수 있다는 뜻으로 이해했습니다.»
        기대 holds=False.

        BaseModel · 프롬프트만        N=3   3/3 오판     ← 전
        Strict 만 (strict:true)       N=3   0/3   ❗N=10  6/10 오판   ← 3회는 운이었다
        Strict + belief 먼저 (이것)   N=10  0/10 오판  · 진짜 오해 대조 0/10 오판
        + 부정어 힌트 문장            N=10  0/10       ← belief 위에 아무것도 더하지 않는다

    ## 잔여 — 닫히지 않은 것 하나 (정직하게)

        진짜 오해 M11 반문형 «이것도 예금처럼 전액 보호되는 거 아닌가요»   기대 holds=True
          옛 게이트          10/10 오판(전부 뺀다 — 미탐)   ← #425 가 M11 을 링크할 때 게이트 없는
                                                        매처로만 재서 아무도 못 본 자리
          이것(belief)       6/10 오판                    ← 줄었지만 안 닫혔다 (P5 0.2절 방향)
          + 반문 지시(V4)    0/10 ❗대신 정정 발화 4/10 · M09 반문 8/10 → 총 12/80 (더 나쁘다)

    **반문(«~아닌가요»)을 주장으로 읽게 하는 지시는 실패를 옮길 뿐이었다.** 8케이스×10 총합으로
    V2 6/80 · V4 12/80. 남은 M11 반문형은 라이브러리에 명제형 `claim` 이 생기면 다시 잰다.

    **적은 표본으로 결정론을 주장하지 않는다** — Strict 만으로 닫혔다고 3회를 보고 적었다가
    10회에서 뒤집혔다. 남는 것은 `belief` 다. 프롬프트에 「동사 앞 부정」 지시를 더하는 것은
    실패를 옮길 뿐이었다(4/5 ↔ 4/5). 결함은 문면이 아니라 **판정 절차에 「지금 믿는 것」을
    말하는 단계가 없던 것**이다.

    `Strict` 인 것도 필요조건이다 — `BaseModel` 이면 `additionalProperties: false` 가 없어
    `llm_client` 가 `strict: true` 를 못 켜고(#490 과 같은 뿌리) 모델이 스키마를 최선노력으로만
    따른다. `tests/test_structured_output_strict.py` 의 표가 이 클래스를 `True` 로 잠근다.

    ❗**`belief` 는 고객 발화에서 유도된 문장이다 — 로그에 싣지 않는다** (#397 ① · P3).
    """

    belief: str = Field(description="고객이 «지금» 믿는 것 한 문장. 과거에 믿었다가 고쳐 말한 것은 뺀다")
    holds: bool
    polarity: Literal["positive", "negative", "neutral"]


_POLARITY_SYSTEM = (
    "당신은 금융 상담 발화의 **극성**만 판정한다. 주제가 같은지는 묻지 않는다.\n"
    "발화가 주어진 오해 문장과 **같은 것을 주장하면** holds=true,\n"
    "그 오해를 **부정하거나 범위를 제한하면** holds=false 다.\n"
    "부정·양보는 어미에 붙는다 — '~는 아니다', '~건 아니군요', '~만 된다' 는 부정이다.\n"
    "먼저 `belief` 에 **고객이 지금 믿는 것**을 한 문장으로 적는다 — 과거에 믿었다가 고쳐 말한 것"
    "('~인 줄 알았는데', '아, ~군요')은 빼고 지금 것만. 그 다음 그 문장이 오해 문장과 같은 "
    "주장인지로 holds 를 정한다."
)


@dataclass
class PolarityMeter:
    """3단계 게이트가 **무엇을 했는지**의 누적. 프로세스와 함께 사라진다 — 운영 관측값이다.

    ❗**발화를 안 담는다.** 유형ID 만 센다 — `#397` 리뷰 ①에서 로그의 발화를 뺀 것과 같은
    이유다(P3 · F-GTE-004 보존 정책 밖에 사본이 생긴다).

    ## 왜 필요한가 — `#160` 의 반대 방향이다

    `#160` 에서 세운 규칙이 *"조용히 등급만 바뀌면 감사 시점에 왜 U4 였는지 설명할 수 없다"*
    였다. **U4 가 「안 된」 경우에도 똑같다**(`#398` 리뷰, 정세현).

        floor 가 울면    reason 에 상향 사실 · misconception_type   → evidence 에 남는다
        게이트가 빼면    평범한 U1~U3 · 필드 없음 · INFO 한 줄       → 아무 데도 안 남는다

    로그는 evidence 가 아니다(ADR-003 — append-only · 해시 체인 · 정규화 직렬화). 그래서
    최소한 **계량기**는 있어야 한다. 집계·리포트는 `#326`·`#327` 에서 정세현이 붙인다.

    ## `not_run` 이 `dropped == 0` 과 다른 것이 요점이다

    `shadow.ShadowMeter.failed` 와 같은 자리다(결정 5.40 *"못 잰 값은 0 이 아니라 「모른다」로
    적는다"*). 게이트가 **조용히 영구히 안 도는** 경로가 둘 있는데(LLM 예외 · 클라이언트가
    `model_cls` 를 안 지킴) 둘 다 후보를 남기므로 **판정만 보면 게이트가 없는 것과 같다.**
    """

    asked: int = 0
    kept: int = 0
    dropped: int = 0
    #: ❗`holds=True` 인데 `polarity` 가 긍정이 아닌 자기모순. 실측에서 3회 중 1회 나왔다.
    contradicted: int = 0
    #: ❗**못 돈 건수.** 0 건과 「모른다」를 가르는 유일한 자리 — 위 docstring 참조.
    not_run: int = 0
    by_type: dict[str, int] = field(default_factory=dict)
    #: ❗**후보를 병렬로 확인하면서 필요해졌다** (이슈 #498). `+= 1` 은 원자적이지 않아
    #: 락 없이 여러 스레드가 동시에 세면 **건수가 샌다** — 그리고 그 샘은 조용하다.
    #: 비교·표시 대상이 아니라 `repr`·`==` 에서 뺀다.
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False, compare=False)

    def record_kept(self) -> None:
        with self._lock:
            self.asked += 1
            self.kept += 1

    def record_dropped(self, type_id: str, *, contradicted: bool) -> None:
        # ❗락은 **본문 전체**를 감싼다. 앞의 둘만 감싸면 `by_type` 의 read-modify-write 가
        # 밖에 남아 같은 유형 두 건이 동시에 들어올 때 하나가 사라진다 — 그리고 그 샘은
        # 합계(`dropped`)와 내역(`by_type`)이 **서로 안 맞는** 모양으로만 드러난다.
        with self._lock:
            self.asked += 1
            self.dropped += 1
            if contradicted:
                self.contradicted += 1
            self.by_type[type_id] = self.by_type.get(type_id, 0) + 1

    def record_not_run(self) -> None:
        """게이트가 못 돌았다. 후보는 남는다 — 판정만 보면 게이트가 없는 것과 같다."""
        with self._lock:
            self.asked += 1
            self.not_run += 1

    def snapshot(self) -> dict[str, object]:
        """지금 값. **락 안에서 통째로 뜬다** — 안 그러면 합계와 내역이 다른 순간의 것이 섞인다.

        `ConsistencyMeter.snapshot()` 과 같은 모양으로 둔다. 두 계량기가 다르게 읽히면
        소비자가 둘을 따로 배워야 한다.

        ❗**`by_type` 에 `M08-TYING` 이 들어온다.** 그 유형은 불공정영업 신호라 판매자
        화면에 보이면 안 된다(기획 7-4 · `#147`·`#159`·`#145`). 이 값을 화면에 싣는 쪽은
        `misconception_type` 과 **같은 경계**를 지나야 한다 — 그래서 이 요약은
        `/internal/*`(인증) 뒤에 두고 `/healthz`(무인증)에는 안 싣는다.
        """
        with self._lock:
            return {
                "asked": self.asked, "kept": self.kept, "dropped": self.dropped,
                "contradicted": self.contradicted, "not_run": self.not_run,
                "by_type": dict(self.by_type),
            }

    def summary(self) -> str:
        return (f"극성 게이트 {self.asked}건 · 남김 {self.kept}건 · 뺌 {self.dropped}건"
                f"(자기모순 {self.contradicted}건) · 못 돈 것 {self.not_run}건")


METER = PolarityMeter()


def _polarity_holds(client, misconception_text: str, utterance: str, type_id: str) -> bool:
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
            "F-DET-001 극성 게이트 실패: %s — 후보를 그대로 둔다(P5 0.2절). type_id=%s",
            type(exc).__name__, type_id,
        )
        METER.record_not_run()
        return True
    if not isinstance(verdict, PolarityVerdict):
        # ❗**타입이 다르면 게이트가 안 돈 것으로 본다.** 클라이언트가 `model_cls` 를
        # 안 지키는 경우다(테스트 스텁이 그렇다). 여기서 예외를 올리면 게이트가 채점을
        # 죽이는데, 게이트는 **과탐을 줄이는 장치이지 판정을 만드는 장치가 아니다**(P1).
        # 그래서 실패와 같은 방향으로 — 후보를 남긴다(P5 0.2절).
        log.info(
            "F-DET-001 극성 게이트가 %s 를 받았다 — PolarityVerdict 가 아니다. "
            "후보를 그대로 둔다. type_id=%s", type(verdict).__name__, type_id,
        )
        METER.record_not_run()
        return True

    holds = verdict.holds and verdict.polarity == "positive"
    if verdict.holds and not holds:
        log.info(
            "F-DET-001 극성 게이트 자기모순: holds=True 인데 polarity=%s — 뺀다. type_id=%s",
            verdict.polarity, type_id,
        )
    log.info(
        "F-DET-001 극성 게이트: type_id=%s holds=%s polarity=%s → %s",
        type_id, verdict.holds, verdict.polarity, holds,
    )
    if holds:
        METER.record_kept()
    else:
        METER.record_dropped(type_id, contradicted=verdict.holds)
    return holds


#: 극성 확인을 동시에 돌리는 워커 수 (이슈 #498).
#:
#: 한 답변의 **후보 수만큼** 호출이 필요하고 그 호출들은 서로 독립이다 — 같은 프롬프트
#: 구조에 다른 입력이라 순서를 지킬 이유가 없다. 순차로 두면 채점 본체(병렬, ~2~3초) 뒤에
#: 후보 수만큼 왕복이 **그대로 얹힌다.**
#:
#: ❗**호출 수는 안 바뀐다.** `#437`(투기적 재질의)은 등급을 보기 전에 던지느라 **버리는
#: 호출**이 생겨서 계량기가 그 낭비를 세야 했지만, 여기서는 필요한 N 건을 동시에 부를 뿐이다.
#: 그래서 *"쿼터가 왜 늘었나"* 라는 질문이 없고, 새 계량기도 안 만든다.
POLARITY_WORKERS = 4

_POLARITY_POOL = ThreadPoolExecutor(max_workers=POLARITY_WORKERS,
                                    thread_name_prefix="det-polarity")


def _polarity_parallel_enabled() -> bool:
    """후보를 동시에 확인할 것인가. **함수로 빼 둔 이유가 테스트다**(`#437` 과 같다).

    이 레포의 스텁 상당수가 호출 순서에 매여 있거나 호출 인자를 리스트에 모아 두고 그
    순서를 단정한다. 병렬로 돌면 그 순서가 회차마다 갈리는데, **운영에서는 후보들이 서로
    독립이라 없는 문제**이고 스텁의 성질이다. 그래서 `conftest.py` 가 이 함수를 덮어
    기본을 순차로 두고, 병렬 경로는 스레드 안전한 스텁으로 따로 잰다.
    """
    return True


def _verdicts(client, matches, text: str) -> list[bool]:
    """후보별 극성 판정. **입력 순서를 그대로 돌려준다.**

    `executor.map` 이 입력 순서대로 결과를 주므로 호출자가 `zip` 으로 다시 짝지어도
    후보와 판정이 안 섞인다.

    ❗**후보가 하나면 스레드를 안 쓴다.** 얻는 것이 없고 풀에 넣었다 꺼내는 값만 든다.

    ❗**여기서 예외가 새면 게이트가 채점을 죽인다.** `_polarity_holds` 는 스스로 전부
    잡아 `True` 로 떨어지지만(P5 0.2절), `executor.map` 은 안에서 난 예외를 **결과를 꺼낼
    때** 올리므로 그 계약이 나중에 깨지면 여기가 조용히 위험해진다. 게이트는 과탐을 줄이는
    장치이지 판정을 만드는 장치가 아니므로(P1) 한 겹 더 막는다.
    """
    def holds(match) -> bool:
        try:
            return _polarity_holds(client, match.matched_pattern, text, match.type_id)
        except Exception as exc:                    # noqa: BLE001 — 게이트가 채점을 죽이면 안 된다
            log.info("F-DET-001 극성 게이트가 예외로 끝났다: %s — 후보를 그대로 둔다(P5 0.2절). type_id=%s",
                     type(exc).__name__, match.type_id)
            METER.record_not_run()
            return True

    if len(matches) == 1 or not _polarity_parallel_enabled():
        return [holds(m) for m in matches]
    return list(_POLARITY_POOL.map(holds, matches))


def apply_polarity_gate(
    response: MisconceptionResponse, text: str, *, client
) -> MisconceptionResponse:
    """**3단계.** 후보 중 그 오해를 부정·제한하는 발화를 뺀다. 후보를 만들지 않는다.

    `stage` 는 그 후보를 **찾은** 단계 그대로 둔다 — 발견 경로와 확인 경로는 다른 사실이고,
    하나로 덮으면 재현성 논의에서 둘이 섞인다.

    ❗**`escalate` 를 다시 계산한다.** 게이트가 `M08-TYING` 후보를 빼면 그 신호도 같이
    사라져야 한다 — 안 그러면 *"오해는 없는데 꺾기 신호는 있다"* 가 되고, 그 상태는
    `F-GTE-003` 에서 설명할 수가 없다.
    """
    if not response.matches:
        return response
    kept = [m for m, holds in zip(response.matches, _verdicts(client, response.matches, text))
            if holds]
    if len(kept) == len(response.matches):
        return response
    log.info(
        "F-DET-001 극성 게이트가 후보 %d 건 중 %d 건을 뺐다",
        len(response.matches), len(response.matches) - len(kept),
    )
    return response.model_copy(update={
        "matches": kept,
        "escalate": any(_escalates(m.type_id) for m in kept),
    })


def match(text: str, product_type: str = "ELS", *, client=None) -> MisconceptionResponse:
    """발화 → 오해 유형 매칭(1·2단계). 유형별 최고점 1건만 남긴다.

    `client` 를 주면 이어서 3단계 게이트까지 돈다. 채점 경로는 **판정 직전**에
    `apply_polarity_gate` 를 따로 부른다 — 순서가 중요하다(아래).
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

    matches.sort(key=lambda m: m.score, reverse=True)
    escalate = any(_escalates(m.type_id) for m in matches)
    response = MisconceptionResponse(
        matches=matches,
        escalate=escalate,
        unclassified_candidate=(not matches) and near_miss,
    )
    if client is not None:
        response = apply_polarity_gate(response, text, client=client)
    return response


def _escalates(type_id: str) -> bool:
    """라이브러리의 escalate 필드를 읽는다 — 유형ID를 코드에 박지 않는다."""
    for mtype in library():
        if mtype.type_id == type_id:
            return mtype.escalate == "compliance"
    return False
