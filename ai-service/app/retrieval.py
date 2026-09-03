"""문서 검색 레이어 — 청킹 · BM25 · dense · RRF · 리랭킹. 소유: 윤지석

## 무엇에 쓰나 — 셋이 같은 모듈을 쓴다

    F-EXT-002 추출        문서 2만자에서 항목 조건 찾기 (지금은 문서 전체를 프롬프트에)
    커버리지 검사          어느 항목도 안 덮는 문면 찾기 (지금은 containment 하나)
    루브릭 생성            항목별 후보 조항 제시 → 사람 승인

**루브릭 생성이 이 모듈의 목표다.** 지금 루브릭 17종은 사람이 문서를 읽고 손으로 쓴 것이라,
빠뜨린 것이 있으면 **고객이 그것을 몰라도 U1(이해)이 나오고 게이트가 초록**이다. 실제로
빠져 있었다 — `tools/find_coverage_gaps.py` 가 `VAR-FEE-DEDUCTION` 에서 **펀드 층 비용
(특별계정 운용보수·증권거래비용·기초펀드보수)** 이 열거되지 않은 것을 찾았다.

## ❗채점 시점에는 쓰지 않는다

루브릭은 **파일로 고정된 채** 남는다. `verify_rubric_clause_is_published`(F-SCR-001)가
*"모델이 인용한 조항이 루브릭에 실재하는가"* 로 P4 를 강제하는데, 런타임에 검색해 오면
그 검사가 순환이 되어 무의미해진다. 공개 의무(루브릭 YAML)와 재현성도 같은 자리에 걸린다.

**바뀌는 것은 그 파일을 누가 쓰느냐다** — 사람이 타이핑하던 것을 검색이 후보로 내고 사람이
승인한다.

## 의존성을 안 늘린다

    dense    openai 임베딩 (이미 llm_client 가 쓰는 그 SDK)
    키워드    BM25 를 여기 구현한다 (문자 바이그램 기반 — textsim 과 같은 정규화)
    RRF      산수
    리랭킹    gpt-5-mini (팀 단일 모델 정책 · #266)

`numpy` 도 안 쓴다. 청크가 문서당 수백이라 순수 파이썬 코사인으로 충분하다 —
**문서 종수가 늘면 그때 재고 pgvector 를 본다**(그 판단은 강희진 영역인 영속 계층이다).

## 청킹이 지켜야 하는 것 — P6 항등식 (1절 F-EXT-002)

    pages[page].text[start:end] == condition.value_text

이것이 *"문서에 이렇게 적혀 있다"* 의 증명이다. **0.2절 P6 은 「재설명 생성 시」로 한정돼
있어 추출에는 안 걸린다** — 추출 쪽 근거는 1절 F-EXT-002 이고 그 절이 원문 인용을 요구한다
(`#308` 이 이 구별을 문면에 박았다).

그래서 **청크는 원문 오프셋을 들고 다닌다.** 청크를 정규화하거나 재조립하면 그 항등식이
깨지고 추출이 통째로 무의미해진다. `Chunk.text` 는 **원문에서 잘라낸 그대로**이고 정규화는
검색용 사본(`Chunk.norm`)에만 한다.

"""
from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass, field

from . import textsim

#: 청크 목표 길이. 조항 경계로 자르고 넘치면 이 크기로 슬라이딩한다.
#:
#: 공시문서의 조항이 대개 이 안에 들어간다(계약 샘플 실측: 문장 평균 60~120자, 조항 2~5문장).
#: 크게 잡으면 리랭커가 한 청크 안에서 무엇이 근거인지 못 가리고, 작게 잡으면 조건이
#: 청크를 걸쳐 **뜻이 뒤집힌다**(`#53` — "보호되지 않습니다" / "다만 …에 한하여 보호됩니다").
TARGET_CHARS = 500

#: 슬라이딩 겹침. 조건이 경계에 걸려도 한쪽 청크에는 온전히 들어가게 한다.
OVERLAP_CHARS = 120

#: 이보다 짧은 청크는 버린다 — 표 셀 조각이나 머리글이다.
MIN_CHUNK_CHARS = 20

#: RRF 상수. 관례값 60. **점수를 정규화하지 않는 것이 이 융합의 요점**이라
#: 이 숫자 하나 말고는 튜닝할 임계값이 없다(`#204`·`#127` 에서 배운 방향).
RRF_K = 60

#: 조항 머리 — 공시문서가 실제로 쓰는 구조. 「」 · "5." · "제3조" · "※"
_CLAUSE_HEAD = re.compile(r"(?m)^\s*(?:[「【]|※|\d+[.)]\s|제\s*\d+\s*조)")

#: 표 상단 단위 선언. `#204` 가 만난 그 구조 — 여기서 표가 시작한다.
_TABLE_HEAD = re.compile(r"\(\s*단위\s*[:：]")

_SENTENCE_END = re.compile(r"(?<=[.。!?])\s+")


@dataclass(frozen=True)
class Chunk:
    """문서 한 조각. **원문 오프셋을 들고 다닌다** (P6 · 1절 F-EXT-002).

    항등식: `pages[page].text[start:end] == text`. 모듈 docstring 참고.
    """

    page: int
    start: int
    end: int
    text: str                       # 원문에서 잘라낸 그대로. 정규화하지 않는다
    kind: str = "prose"             # prose | table

    @property
    def norm(self) -> str:
        """검색용 사본. `textsim` 과 같은 정규화를 쓴다 — 두 벌이 되면 점수를 못 비교한다."""
        return textsim.normalize(self.text)


def chunk_document(doc: dict) -> list[Chunk]:
    """파싱된 문서 → 청크. 조항·표 경계를 먼저 보고 넘치면 문장 경계로 슬라이딩한다."""
    out: list[Chunk] = []
    for page in doc["pages"]:
        text: str = page["text"]
        for start, end in _segments(text):
            piece = text[start:end]
            if len(piece.strip()) < MIN_CHUNK_CHARS:
                continue
            out.append(Chunk(
                page=page["page"], start=start, end=end, text=piece,
                kind="table" if _TABLE_HEAD.search(piece) else "prose",
            ))
    return out


def _segments(text: str) -> list[tuple[int, int]]:
    """경계 오프셋 쌍. **오프셋으로만 다룬다** — 문자열을 만들면 항등식이 깨진다."""
    heads = [0] + [m.start() for m in _CLAUSE_HEAD.finditer(text)]
    heads += [m.start() for m in _TABLE_HEAD.finditer(text)]
    heads = sorted(set(h for h in heads if 0 <= h < len(text)))
    bounds = list(zip(heads, heads[1:] + [len(text)]))

    out: list[tuple[int, int]] = []
    for start, end in bounds:
        if end - start <= TARGET_CHARS:
            out.append((start, end))
            continue
        out.extend(_slide(text, start, end))
    return out


def _slide(text: str, start: int, end: int) -> list[tuple[int, int]]:
    """긴 구간을 문장 경계로 나눈다. 겹침을 둬서 조건이 경계에 잘리지 않게 한다."""
    cuts = [start] + [start + m.end() for m in _SENTENCE_END.finditer(text[start:end])]
    cuts.append(end)
    out: list[tuple[int, int]] = []
    lo = start
    for cut in cuts[1:]:
        if cut - lo >= TARGET_CHARS:
            out.append((lo, cut))
            lo = max(lo, cut - OVERLAP_CHARS)
    if end - lo >= MIN_CHUNK_CHARS:
        out.append((lo, end))
    return out


# ── 키워드 검색 (BM25) ────────────────────────────────────────────────────────
#: BM25 파라미터. 관례값이다 — 문서 종수가 2 종이라 튜닝할 근거가 없고,
#: **튜닝하면 그 숫자의 출처를 심사에서 설명해야 한다.** 관례값을 쓰고 출처를 적는다.
BM25_K1 = 1.5
BM25_B = 0.75


class Bm25:
    """문자 바이그램 BM25. `textsim` 과 같은 정규화를 쓴다.

    **왜 형태소가 아니라 바이그램인가** — `textsim` 이 세운 이유 그대로다. 외부 사전이
    없어 재현성이 있고, 조사·어미가 붙어도 어간 바이그램이 남는다. 여기서 다른 토큰화를
    쓰면 `containment` 점수와 비교가 안 되고, 이 모듈이 그 둘을 나란히 쓴다.
    """

    def __init__(self, chunks: list[Chunk]) -> None:
        self._docs = [Counter(textsim.bigrams(c.norm)) for c in chunks]
        self._len = [max(sum(d.values()), 1) for d in self._docs]
        self._avg = sum(self._len) / max(len(self._len), 1)
        df: Counter[str] = Counter()
        for d in self._docs:
            df.update(d.keys())
        n = max(len(self._docs), 1)
        self._idf = {
            t: math.log(1 + (n - c + 0.5) / (c + 0.5)) for t, c in df.items()
        }

    def rank(self, query: str) -> list[tuple[int, float]]:
        q = textsim.bigrams(textsim.normalize(query))
        scores: list[tuple[int, float]] = []
        for i, doc in enumerate(self._docs):
            s = 0.0
            for term in q:
                tf = doc.get(term, 0)
                if not tf:
                    continue
                denom = tf + BM25_K1 * (1 - BM25_B + BM25_B * self._len[i] / self._avg)
                s += self._idf.get(term, 0.0) * tf * (BM25_K1 + 1) / denom
            if s > 0:
                scores.append((i, s))
        return sorted(scores, key=lambda x: -x[1])


# ── 융합 (RRF) ────────────────────────────────────────────────────────────────
def rrf(*rankings: list[tuple[int, float]], k: int = RRF_K) -> list[tuple[int, float]]:
    """Reciprocal Rank Fusion — **순위만 쓴다.**

    점수 정규화가 필요 없는 것이 요점이다. BM25(무한대)와 코사인(-1~1)을 같은 축에
    올리려면 스케일을 정해야 하는데 그 숫자는 근거가 없다. RRF 는 각 검색기의 **등수**만
    보므로 그 임계값이 아예 없어진다.

    ❗**F-DET-001(오해 탐지)에는 이것을 쓰지 않는다.** 거기서는 키워드·dense 가 **같은
    방향으로** 틀린다(둘 다 부정을 못 본다 — 실측). 같은 실패를 융합하면 같은 실패가
    나온다. 여기서 융합이 값을 하는 이유는 **두 검색기가 서로 다른 자리에서 죽기**
    때문이다 — 표 셀은 키워드가 0 이 되고(순수 수치라 자연어와 어휘가 안 겹친다),
    수치 조건은 dense 가 세부를 못 가른다.
    """
    fused: Counter[int] = Counter()
    for ranking in rankings:
        for rank, (idx, _) in enumerate(ranking, start=1):
            fused[idx] += 1.0 / (k + rank)
    return sorted(fused.items(), key=lambda x: -x[1])


@dataclass
class Hit:
    chunk: Chunk
    rrf: float
    bm25_rank: int | None = None
    dense_rank: int | None = None
    rerank: int | None = None
    why: str = field(default="")


# ── dense 검색 ────────────────────────────────────────────────────────────────
#: 임베딩 모델. **정책 모델과 따로 둔다** — `#266` 이 채팅 모델을 옮길 때 임베딩까지
#: 같이 옮길 이유가 없고, 폐쇄망 교체 때도 둘의 후보가 다르다(`bge-m3` 등).
EMBED_MODEL_ENV = "LLM_EMBED_MODEL"
DEFAULT_EMBED_MODEL = "text-embedding-3-small"


def cosine(a: list[float], b: list[float]) -> float:
    num = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return num / (na * nb) if na and nb else 0.0


class Dense:
    """청크 임베딩을 들고 질의와 코사인을 잰다.

    ❗**임베딩은 주제를 잡고 입장을 못 잡는다** — 부정은 벡터를 거의 안 옮긴다(F-DET-001
    실측: 오해 어미변형 0.560 < 정답 부정 0.621). 그래서 **여기서는 절대 유사도를 판정에
    쓰지 않는다.** RRF 에 넣을 *순위* 만 만들고, 최종 판단은 리랭커가 한다.

    미리 계산한 벡터를 파일로 받을 수 있게 한다 — 문서가 안 바뀌면 다시 부르지 않는다.
    """

    def __init__(self, vectors: list[list[float]]) -> None:
        self._vectors = vectors

    @classmethod
    def embed(cls, chunks: list[Chunk], client, model: str | None = None) -> "Dense":
        """❗**원문(`text`)을 넣는다 — `norm` 을 넣지 않는다.**

        처음에는 `c.norm or c.text` 로 짰고 두 가지가 틀렸다.

        **① PII 오탐.** `textsim.normalize` 가 공백·기호를 전부 지우므로 표의 수치가
        붙는다. 계약 샘플 p11 의 수익률 모의실험 표가 실물이다 —
        원문 `"1차 조기상환 550 41.41% 88.33%"` 는 통과하는데
        `norm` `"…상환5504141883322차…"` 이 **13자리 연속 숫자로 RRN 에 걸린다.**
        좁은 패턴(RRN·PHONE)은 `public_document` 범위에서도 검사하므로 여기서 막힌다.

        **② 임베딩에는 자연문이 필요하다.** `normalize` 는 **바이그램 대조용** 정규화다
        (`textsim` docstring: 조사·어미가 붙어도 어간 바이그램이 남게). 공백과 문장부호를
        지운 문자열은 문장 표현 모델이 학습한 분포가 아니다.

        **정규화는 목적별로 갈린다** — `numerics` 에 셋이 있고 `test_the_two_normalizations
        _disagree_on_purpose` 가 통일 시도를 막는다. 임베딩이 네 번째 목적이고, 그 목적의
        정규화는 **없음**이다.

        그래서 `norm` 은 BM25·containment 만 쓴다.
        """
        return cls(client.embed([c.text for c in chunks], model=model))

    def rank(self, query_vector: list[float]) -> list[tuple[int, float]]:
        scored = [(i, cosine(query_vector, v)) for i, v in enumerate(self._vectors)]
        return sorted(scored, key=lambda x: -x[1])
