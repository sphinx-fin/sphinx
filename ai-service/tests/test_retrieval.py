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
    """★ 항등식이 **조각별로** 성립한다 (P6 · 1절 F-EXT-002).

        "".join(pages[s.page].text[s.start:s.end] for s in spans) == chunk.text

    이것이 *"문서에 이렇게 적혀 있다"* 의 증명이다. 청크를 정규화하거나 재조립하면 깨지고,
    그러면 추출이 낸 `source_span` 이 원문을 안 가리킨다 — **추출이 통째로 무의미해진다.**

    스팬을 목록으로 바꾼 이유가 페이지 경계인데(모듈 docstring), **목록으로 바꾸면서
    항등식이 약해지지 않았다는 것**을 여기서 잰다.
    """
    doc = _doc(name)
    by_page = {p["page"]: p["text"] for p in doc["pages"]}
    chunks = retrieval.chunk_document(doc)
    assert chunks, "청크가 하나도 안 나왔다 — 경계 정규식이 아무것도 못 잡았나"

    broken = [
        [(s.page, s.start, s.end) for s in c.spans] for c in chunks
        if "".join(s.slice_of(by_page) for s in c.spans) != c.text
    ]
    assert not broken, f"조각을 이어붙인 것이 text 와 다르다: {broken[:3]}"


@pytest.mark.parametrize("name", CASES)
def test_some_chunks_cross_a_page_boundary(name: str) -> None:
    """★ 페이지 경계에서 갈린 문장이 **실제로 이어진다.**

    이 단정이 없으면 `_join_split_sentences` 가 아무것도 안 해도 전건 초록이다 —
    스팬 목록만 만들고 병합이 안 도는 상태가 그 모양이고, 그러면 이 변경이 무의미하다.

    계약 샘플 실측: 페이지 첫 청크 15개 중 10개가 문장 중간에서 시작했다.
    """
    chunks = retrieval.chunk_document(_doc(name))
    crossed = [c for c in chunks if c.crosses_pages]
    assert crossed, "페이지를 걸친 청크가 하나도 없다 — 병합이 안 돌았다"
    for c in crossed:
        assert len({s.page for s in c.spans}) == 2, "세 페이지를 걸치는 것은 지금 안 만든다"


@pytest.mark.parametrize("name", CASES)
def test_chunks_cover_the_document_without_gaps_in_between(name: str) -> None:
    """청크가 문서를 (겹침 빼고) 빠짐없이 덮는다.

    구멍이 있으면 **그 구간의 항목은 검색으로 못 찾는다.** 지금은 문서 전체를 프롬프트에
    넣어서 그 위험이 없는데, 검색으로 바꾸는 순간 구멍이 곧 미검출이 된다.
    """
    doc = _doc(name)
    chunks = retrieval.chunk_document(doc)
    for page in doc["pages"]:
        spans = sorted((s.start, s.end) for c in chunks for s in c.spans
                       if s.page == page["page"])
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
        def embed(self, texts, model=None, pii_scope=None):
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
    c = retrieval._chunk(1, 0, 5, "가 나, 다.")
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


# ── 리랭킹 ────────────────────────────────────────────────────────────────────
class _StubClient:
    """리랭커 스텁. 호출 여부와 넘긴 프롬프트를 기록한다."""

    def __init__(self, order=None, dropped=None, raise_error=False):
        self._order = [] if order is None else order
        self._dropped = [] if dropped is None else dropped
        self._raise = raise_error
        self.prompts: list[str] = []

    def embed(self, texts, model=None, pii_scope=None):
        return [[1.0, 0.0] for _ in texts]

    def complete_json(self, **kwargs):
        from app.llm_client import LlmError

        self.prompts.append(kwargs["prompt"])
        if self._raise:
            raise LlmError("스텁 실패")
        return retrieval.Reranked(order=self._order, dropped=self._dropped)


def _many_chunks(n: int = 6) -> list[retrieval.Chunk]:
    return [retrieval._chunk(1, i * 60, i * 60 + 60, f"조각{i} " + "가" * 50)
            for i in range(n)]


def test_rerank_is_skipped_when_candidates_fit() -> None:
    """★ 후보가 `top_n` 이하면 부르지 않는다.

    순위를 바꿀 여지가 없는데 부르면 **비결정성만 들인다** — `#281` 실측대로 이 단계는
    같은 입력에 같은 답을 안 낸다. 호출을 아끼는 것보다 그게 이유다.
    """
    client = _StubClient(order=[2, 1, 0])
    got = retrieval.rerank("질의", _many_chunks(3), client, top_n=3)
    assert got == [0, 1, 2]
    assert not client.prompts, "후보가 3개인데 리랭커를 불렀다"


def test_rerank_drops_out_of_range_indices_from_the_model() -> None:
    """★ 모델이 낸 번호를 그대로 안 믿는다 — 인덱스는 우리 값이다.

    범위 밖 번호를 그대로 쓰면 `IndexError` 로 검색이 죽거나 남의 청크를 가리킨다.
    `_pin_item_id`·`_drop_llm_misconception_type`(F-SCR-001)과 같은 층의 규칙이다.
    """
    client = _StubClient(order=[99, 1, -3, 1, 0])
    got = retrieval.rerank("질의", _many_chunks(6), client, top_n=3)
    assert got == [1, 0], f"범위 밖·중복을 안 걸렀다: {got}"


def test_rerank_falls_back_to_fused_order_when_the_model_fails(caplog) -> None:
    """★ 리랭커가 죽으면 **RRF 순위로 되돌아간다** — 검색이 같이 죽지 않는다.

    이 단계는 순위를 *개선* 하는 것이고 만드는 것이 아니다. 그리고 **조용히 되돌아가지
    않는다** — 로그가 없으면 리랭킹이 꺼진 채로 도는 것을 아무도 모른다(`#238` 이
    폴백 로그를 넣은 것과 같은 이유).
    """
    import logging

    client = _StubClient(raise_error=True)
    with caplog.at_level(logging.WARNING, logger="app.retrieval"):
        got = retrieval.rerank("질의", _many_chunks(6), client, top_n=3)

    assert got == [0, 1, 2], "융합 순위로 안 돌아갔다"
    assert any("리랭커 실패" in r.getMessage() for r in caplog.records), (
        "조용히 폴백했다 — 리랭킹이 꺼진 것을 알 수 없다"
    )


def test_rerank_prompt_separates_topic_from_condition() -> None:
    """프롬프트가 **주제 유사와 조건 실재를 가르라고** 말해야 한다.

    이 단계가 존재하는 이유가 그 구별이다 — BM25 는 어휘를, dense 는 주제를 재고 둘 다
    *"이 문면이 이 항목의 조건인가"* 를 못 본다(F-DET-001 실측: 임베딩이 오해 문장과 그
    부정을 0.560 vs 0.621 로 뒤집어 놨다).
    """
    client = _StubClient(order=[0])
    retrieval.rerank("원금손실 조건", _many_chunks(6), client)
    prompt = client.prompts[0]
    assert "조건이 실제로 적힌" in prompt
    assert "주제가 비슷한 것과 조건이 적힌 것은 다르다" in prompt


def test_search_works_without_a_client() -> None:
    """★ 리랭킹 없이도 돌아야 한다.

    `recall@k` 측정과 테스트가 LLM 없이 돌아야 하고, 리랭커가 정책·쿼터에 걸리는 날에도
    검색은 돌아야 한다. `client=None` 이면 RRF 까지다.
    """
    chunks = _many_chunks(6)
    bm = retrieval.Bm25(chunks)
    dense = retrieval.Dense([[1.0, 0.0]] * 6)
    hits = retrieval.search("조각0", chunks, bm, dense, [1.0, 0.0], client=None, top_n=3)
    assert hits and all(h.why == "rrf" for h in hits)
    assert all(h.rerank is None for h in hits)


def test_hits_carry_which_retriever_found_them() -> None:
    """어느 검색기가 찾았는지가 `Hit` 에 남아야 한다 — 안 남으면 왜 이 순위인지 못 짚는다."""
    chunks = _many_chunks(6)
    hits = retrieval.search("조각1", chunks, retrieval.Bm25(chunks),
                            retrieval.Dense([[1.0, 0.0]] * 6), [1.0, 0.0], client=None)
    assert any(h.bm25_rank is not None for h in hits)
    assert any(h.dense_rank is not None for h in hits)


# ── 이웃 포함 (#283 ② — 항등식 제약 안에서의 정답) ────────────────────────────
def test_neighbors_bring_the_adjacent_chunk() -> None:
    """★ 정답이 적중 청크 **바로 옆**에 있을 때 구제한다.

    `ELS-EARLY-REDEMPTION-CONDITION` 이 실물이다 — 정답이 청크30 에 **있는데** 리랭커가
    31·32·33 을 위로 뽑았다(거리 1). 순위 문제이고, 이웃을 내면 30 이 따라온다.
    """
    chunks = _many_chunks(8)
    hits = [retrieval.Hit(chunk=chunks[4], rrf=1.0)]
    got = retrieval.with_neighbors(hits, chunks, span=1)
    assert [chunks.index(c) for c in got] == [3, 4, 5]


def test_neighbors_are_deduplicated_and_ordered() -> None:
    """중복을 지우고 문서 순서로 낸다.

    같은 문면이 프롬프트에 두 번 들어가면 모델이 그것을 강조로 읽는다. 그리고 순서가
    문서 순이 아니면 조항의 앞뒤가 뒤바뀐 채로 들어간다.
    """
    chunks = _many_chunks(8)
    hits = [retrieval.Hit(chunk=chunks[4], rrf=1.0),
            retrieval.Hit(chunk=chunks[5], rrf=0.9),
            retrieval.Hit(chunk=chunks[1], rrf=0.8)]
    got = [chunks.index(c) for c in retrieval.with_neighbors(hits, chunks, span=1)]
    assert got == sorted(set(got)), f"중복이거나 순서가 흐트러졌다: {got}"
    assert got == [0, 1, 2, 3, 4, 5, 6]


def test_neighbors_do_not_run_off_the_ends() -> None:
    chunks = _many_chunks(3)
    for i in (0, 2):
        got = retrieval.with_neighbors([retrieval.Hit(chunk=chunks[i], rrf=1.0)], chunks, span=1)
        assert all(c in chunks for c in got)


def test_neighbors_cannot_reach_a_far_answer() -> None:
    """★ **이웃 포함이 페이지 경계 문제를 구제하지 못한다** — 그 사실을 못 박는다.

    `ELS-ISSUER-CREDIT-RISK` 는 정답 청크 45, 가져온 것 6 — **거리 39** 다. 정답 청크가
    문장 중간에서 시작해서(`"따른 위험 파생상품적 성격을…"`, 그 문장은 앞 페이지에서
    시작했다) 질의와 어휘·주제가 둘 다 안 맞는다.

    반경을 늘려 39 를 덮으려면 청크 79개를 넣는 것이고 **그러면 문서 전체와 같다** —
    이 모듈이 존재하는 이유가 없어진다. 그래서 이 한계를 늘리는 것으로 풀지 않는다.
    """
    chunks = _many_chunks(50)
    # ❗기본 상수를 쓴다 — `span=1` 을 명시하면 `NEIGHBOR_SPAN` 을 40 으로 키우는 변조가
    # 이 테스트를 안 지나간다(실측으로 그랬다). 그물은 **런타임이 쓰는 값**을 재야 한다.
    got = retrieval.with_neighbors([retrieval.Hit(chunk=chunks[6], rrf=1.0)], chunks)
    assert retrieval.NEIGHBOR_SPAN <= 3, (
        f"NEIGHBOR_SPAN={retrieval.NEIGHBOR_SPAN} — 반경을 키워 먼 정답을 덮으려 하고 있다"
    )
    assert chunks[45] not in got, (
        "반경을 키워 거리 39 를 덮으려 하고 있다 — 그러면 문서 전체를 넣는 것과 같다. "
        "페이지 경계는 청크가 스팬 목록을 들거나 파서가 문장을 이어야 풀린다"
    )


def test_dense_declares_the_public_document_scope() -> None:
    """★ `Dense.embed` 가 `pii_scope="public_document"` 를 **명시로** 넘긴다.

    ❗기본값에 기대면 안 된다(`#358` 리뷰, 강희진). `llm_client.embed` 의 기본값은
    다른 두 출구와 같은 `customer` 이고, **넓은 검사(EMAIL·CARD·ACCOUNT)를 끄는 것은
    그럴 이유를 아는 호출부가 명시한다.**

    실패 방향이 뒤집힌 자리다 — 예전 기본값(`public_document`)이면 나중에 고객 발화를
    임베딩하며 인자를 빼먹은 사람이 **조용히 약한 검사로** 내보낸다. 지금은 빼먹으면
    엄격한 쪽으로 죽는다.

    이 단정이 없으면 호출부에서 인자를 지워도 초록이다(스텁이 `pii_scope` 를 받고
    무시한다).
    """
    seen: list[str | None] = []

    class _Stub:
        def embed(self, texts, model=None, pii_scope=None):
            seen.append(pii_scope)
            return [[1.0, 0.0] for _ in texts]

    doc = {"pages": [{"page": 1, "text": "원금이 보장되지 않습니다. " * 8}]}
    retrieval.Dense.embed(retrieval.chunk_document(doc), _Stub())
    assert seen == ["public_document"], seen


def test_the_three_exits_share_one_default_scope() -> None:
    """★ 세 출구의 기본 `pii_scope` 가 같다 — 하나만 약하면 그게 새는 자리가 된다.

    `pii.assert_clean` 자체 기본값과도 맞춘다. 이 표가 갈리면 **어느 것이 규약인지**
    다음 사람이 알 수 없다.
    """
    import inspect

    from app import llm_client, pii

    defaults = {
        name: inspect.signature(getattr(llm_client.LlmClient, name))
        .parameters["pii_scope"].default
        for name in ("send", "complete_json", "embed")
    }
    defaults["pii.assert_clean"] = (
        inspect.signature(pii.assert_clean).parameters["scope"].default
    )
    assert set(defaults.values()) == {"customer"}, defaults
