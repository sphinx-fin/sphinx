"""검색 레이어 — 청킹·BM25·RRF·dense. 소유: 윤지석

이 모듈이 셋에 쓰인다(추출 개선 · 커버리지 검사 · 루브릭 생성). **채점에는 쓰지 않는다** —
런타임에 루브릭을 검색해 오면 `verify_rubric_clause_is_published` 가 순환이 된다.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app import retrieval, textsim

SAMPLES = Path(__file__).resolve().parents[2] / "contracts" / "samples"
CASES = ("parsed_els_sample.json", "parsed_variable_sample.json")


def _doc(name: str) -> dict:
    return json.loads((SAMPLES / name).read_text(encoding="utf-8"))


# ── 청킹 — P6 항등식이 이 모듈의 계약이다 ─────────────────────────────────────
@pytest.mark.parametrize("name", CASES)
def test_chunks_keep_the_source_offsets(name: str) -> None:
    """★ **`pages[page].text[start:end] == chunk.text`** (P6 · 1절 F-EXT-002).

    이것이 *"문서에 이렇게 적혀 있다"* 의 증명이다. 청크를 정규화하거나 재조립하면 이
    항등식이 깨지고, 그러면 추출이 낸 `source_span` 이 원문을 안 가리킨다 — **추출이
    통째로 무의미해진다.** 이 모듈에서 제일 먼저 깨지기 쉬운 자리라 전건으로 잰다.
    """
    doc = _doc(name)
    by_page = {p["page"]: p["text"] for p in doc["pages"]}
    chunks = retrieval.chunk_document(doc)
    assert chunks, "청크가 하나도 안 나왔다 — 경계 정규식이 아무것도 못 잡았나"

    broken = [
        (c.page, c.start, c.end) for c in chunks
        if by_page[c.page][c.start:c.end] != c.text
    ]
    assert not broken, f"오프셋이 원문과 안 맞는 청크: {broken[:5]}"


@pytest.mark.parametrize("name", CASES)
def test_chunks_cover_the_document_without_gaps_in_between(name: str) -> None:
    """청크가 문서를 (겹침 빼고) 빠짐없이 덮는다.

    구멍이 있으면 **그 구간의 항목은 검색으로 못 찾는다.** 지금은 문서 전체를 프롬프트에
    넣어서 그 위험이 없는데, 검색으로 바꾸는 순간 구멍이 곧 미검출이 된다.
    """
    doc = _doc(name)
    for page in doc["pages"]:
        spans = sorted((c.start, c.end) for c in retrieval.chunk_document(doc)
                       if c.page == page["page"])
        if not spans:
            continue
        covered = 0
        cursor = 0
        for start, end in spans:
            if end > cursor:
                covered += end - max(start, cursor)
                cursor = end
        text_len = len(page["text"].rstrip())
        assert covered >= text_len * 0.9, (
            f"p{page['page']} 의 {covered}/{text_len} 만 덮인다 — 청크 사이에 구멍이 있다"
        )


def test_short_fragments_are_dropped() -> None:
    doc = {"pages": [{"page": 1, "text": "짧다\n\n" + "가" * 50}]}
    chunks = retrieval.chunk_document(doc)
    assert all(len(c.text.strip()) >= retrieval.MIN_CHUNK_CHARS for c in chunks)


# ── ❗dense 는 원문을 임베딩한다 — 내가 만든 버그의 회귀 가드 ────────────────
def test_dense_embeds_raw_text_not_the_normalized_copy() -> None:
    """★ 처음에 `c.norm or c.text` 로 짰고 **두 가지가 틀렸다.**

    ① **PII 오탐.** `textsim.normalize` 가 공백·기호를 지우므로 표의 수치가 붙는다.
       계약 샘플 p11 수익률 모의실험 표가 실물이다 — 원문은 통과하는데 `norm` 은
       13자리 연속 숫자가 되어 **RRN 으로 걸린다**(좁은 패턴은 `public_document`
       범위에서도 검사한다). 즉 정상 문서의 임베딩이 막혔다.
    ② **임베딩에는 자연문이 필요하다.** `normalize` 는 바이그램 대조용 정규화다.

    **정규화는 목적별로 갈린다** — `numerics` 에 셋이 있고, 임베딩이 네 번째 목적이며
    그 목적의 정규화는 **없음**이다.
    """
    seen: list[list[str]] = []

    class _Stub:
        def embed(self, texts, model=None):
            seen.append(list(texts))
            return [[1.0, 0.0] for _ in texts]

    doc = {"pages": [{"page": 1, "text": "가" * 30 + " 41.41% 88.33% " + "나" * 30}]}
    chunks = retrieval.chunk_document(doc)
    retrieval.Dense.embed(chunks, _Stub())

    assert seen, "임베딩 호출이 없었다"
    for text, chunk in zip(seen[0], chunks):
        assert text == chunk.text, "정규화 사본을 넣고 있다 — 위 docstring 참고"
        assert " " in text or len(text) < 3, "공백이 사라졌다 = normalize 를 지났다"


def test_norm_is_the_same_normalization_as_textsim() -> None:
    """BM25·containment 가 `textsim` 과 다른 정규화를 쓰면 점수를 서로 비교할 수 없다."""
    c = retrieval.Chunk(page=1, start=0, end=5, text="가 나, 다.")
    assert c.norm == textsim.normalize(c.text)


# ── RRF — 점수가 아니라 순위를 쓴다 ───────────────────────────────────────────
def test_rrf_uses_ranks_not_scores() -> None:
    """★ 점수 스케일을 정하지 않는 것이 이 융합의 요점이다.

    BM25 는 위가 없고 코사인은 -1~1 이다. 같은 축에 올리려면 정규화 상수를 정해야 하는데
    **그 숫자에 근거가 없다.** RRF 는 등수만 보므로 그 임계값이 아예 없어진다.

    점수를 1000 배로 키워도 답이 같아야 한다 — 같지 않으면 점수를 보고 있는 것이다.
    """
    a = [(0, 9.9), (1, 0.2)]
    b = [(1, 0.9), (0, 0.1)]
    scaled_a = [(i, s * 1000) for i, s in a]

    assert [i for i, _ in retrieval.rrf(a, b)] == [i for i, _ in retrieval.rrf(scaled_a, b)]


def test_rrf_ranks_a_doc_found_by_both_above_one_found_by_one() -> None:
    both = [(7, 1.0)]
    only = [(8, 99.0)]
    fused = dict(retrieval.rrf(both + only, both))
    assert fused[7] > fused[8], "두 검색기가 다 찾은 것이 위여야 한다"


def test_rrf_is_empty_for_no_rankings() -> None:
    assert retrieval.rrf() == []


# ── BM25 ──────────────────────────────────────────────────────────────────────
def test_bm25_finds_the_chunk_that_shares_wording() -> None:
    doc = {"pages": [{"page": 1, "text":
        "원금손실이 발생할 수 있습니다. 투자원금 전액을 잃을 수 있습니다.\n\n"
        + "기초자산은 코스피200 지수입니다. 지수는 매일 종가로 산출됩니다."}]}
    chunks = retrieval.chunk_document(doc)
    ranked = retrieval.Bm25(chunks).rank("투자원금의 손실이 발생할 수 있음")
    assert ranked, "아무것도 못 찾았다"
    assert "원금손실" in chunks[ranked[0][0]].text


def test_bm25_returns_nothing_for_an_unrelated_query() -> None:
    doc = {"pages": [{"page": 1, "text": "가" * 60}]}
    chunks = retrieval.chunk_document(doc)
    assert retrieval.Bm25(chunks).rank("zzz 전혀 다른 말") == []


def test_long_sections_overlap_so_a_condition_is_never_split() -> None:
    """★ 겹침이 없으면 경계에 걸친 조건이 **양쪽 청크에서 다 불완전**해진다.

    `#53` 이 그 실물이다 — `"…보호되지 않습니다."` 와 `"다만, … 에 한하여 보호됩니다"` 가
    갈리면 **뜻이 뒤집힌다.** 그 두 문장이 청크 경계에 놓이면 어느 쪽을 검색해도 반대
    뜻으로 읽힌다.

    ❗**이 단정이 없으면 `OVERLAP_CHARS = 0` 변조가 전건 초록이었다**(실측). 겹침을
    상수로만 두면 그것이 실제로 쓰이는지 아무도 안 잰다.
    """
    tail = "다만, 약관에서 정한 최저사망지급금에 한하여 보호됩니다."
    body = ("이 계약은 예금자보호법에 따라 보호되지 않습니다. " * 40) + tail
    chunks = retrieval.chunk_document({"pages": [{"page": 1, "text": body}]})

    assert len(chunks) > 1, "이 길이면 슬라이딩이 돌아야 한다 — 안 돌면 이 대조가 무의미하다"
    starts = [c.start for c in chunks]
    ends = [c.end for c in chunks]
    overlaps = [ends[i] - starts[i + 1] for i in range(len(chunks) - 1)]
    assert all(o > 0 for o in overlaps), (
        f"청크가 안 겹친다: {overlaps} — 경계에 걸친 조건이 양쪽에서 다 불완전해진다"
    )
    assert any(tail in c.text for c in chunks), (
        "'다만 …' 절이 어느 청크에도 온전히 없다 — 뜻이 뒤집힌 채로 검색된다"
    )
