"""루브릭 후보 생성 (이슈 #474 ①). 소유: 윤지석

## 왜 있나 — 루브릭이 손으로 쓴 고정 파일이다

루브릭 17종은 사람이 문서를 읽고 쓴 것이다. `required_elements` 가 하나 빠지면

    고객이 그것을 몰라도 U1(이해) 이 나온다  →  게이트가 초록  →  아무도 모른다

**채점은 성공하고 미탐만 조용히 는다** — P5(0.2절)가 막으려는 모양이다. 실제로 빠져
있었다(`VAR-FEE-DEDUCTION` 의 펀드 층 비용, `#391`). `tools/find_coverage_gaps.py` 가
찾았지만 **사람이 그 도구를 기억해야만** 잡혔다(`#473`).

## ❗제안만 낸다 — 파일을 쓰지 않는다

승인 산출물은 `app/rubrics/*.yaml` **파일**이다. 루브릭을 런타임 저장소에 넣고 채점이
거기서 읽으면 `scoring.verify_rubric_clause_is_published` 가 **순환**한다(P4) — 근거로
적힌 조항을 그 자리에서 만든 기준과 대조하게 되고, 공개 의무(기획서 5절)와 재현성이
같이 무너진다. `#358` 에서 검색 스택을 채점 시점에 안 붙인 이유와 같은 자리다.

    생성   여기 (LLM + 검색)      자동
    승인   사람                   고르고 고친다
    산출   rubrics/<item>.yaml    파일 · 공개본 · 지금과 동일
    채점   rubrics.get()          **안 바뀐다 — 파일만 읽는다**

## ❗`u1_requires` 를 안 낸다

문턱은 문서에서 유도할 수 없는 **규범**이다. `#367` 이 그 필드를 만든 이유가
*"요소 수와 다를 수 있다"* 였고(`VAR-PARTIAL` 은 요소 2 · 문턱 1), 몇 개면 이해로 볼지는
파는 쪽이 정해서 공개하는 판단이다. 모델이 채우면 그 판단이 숨는다.

## 근거는 스팬을 든다 (P6)

문자열만 주면 승인하는 사람이 *"이게 정말 이 문서에 있나"* 를 눈으로 찾아야 한다.
`RiskItem.condition` 과 같은 규약으로 스팬을 실어, 화면이 원문에 표시하고 기계가
`pages[page].text[start:end] == text` 로 대조할 수 있게 한다.
"""
from __future__ import annotations

import logging
import re

from . import retrieval, rubrics, templates, textsim
from .llm_client import LlmClient
from .schemas import (
    ParsedDocument,
    RubricEvidence,
    RubricProposal,
    SourceSpan,
)
from pydantic import BaseModel, Field

log = logging.getLogger(__name__)

#: 후보가 기존 조항과 이만큼 겹치면 **이미 있는 것**으로 본다.
#:
#: `tools/find_coverage_gaps.COVERED_MIN`(0.25)보다 **높게** 잡는다. 목적이 반대다 —
#: 거기서는 *"이미 누가 덮고 있다"* 를 넓게 봐야 사각 목록이 조용하고, 여기서는
#: *"이건 새 조항이다"* 를 좁게 봐야 사람이 볼 후보가 진짜 새것이다.
#: 놓치는 쪽(새것을 기존으로 봄)이 실패 모드다.
ALREADY_COVERED = 0.45

#: 검색이 항목당 가져오는 청크 수. `tools/propose_rubric.py` 와 같은 값이다 —
#: 둘이 갈리면 도구로 본 후보와 화면이 받는 후보가 달라진다.
TOP_N = 3


class _Draft(BaseModel):
    """생성기 출력. **문서 문면에서 유도한 것만** 낸다."""

    required_elements: list[str] = Field(
        description="이해로 인정되려면 고객이 언급해야 하는 것. 문서 조항에서 유도한다"
    )
    misconception_conditions: list[str] = Field(
        default_factory=list, description="고객이 말하면 오해로 보는 것. 위 조항의 반대다"
    )
    evidence: list[str] = Field(
        default_factory=list,
        description="각 required_elements 의 근거가 된 문서 원문. 요약하지 말고 그대로",
    )


_SYSTEM = (
    "당신은 금융상품 공시문서에서 고객 이해도 채점 기준을 만드는 사람이다. "
    "문서에 적힌 것만 쓴다. 일반적인 금융 지식을 보태지 않는다. "
    "각 기준은 한 문장이고, 고객이 그것을 말했는지 사람이 판단할 수 있어야 한다."
)


#: 모델이 베껴 오는 페이지 표시. **내가 프롬프트에 넣은 것이다.**
#:
#: 문맥을 `[0] (p12) 본문…` 으로 주니 모델이 `evidence` 에 `(p12) ` 를 그대로 붙여 왔고,
#: 그러면 원문 대조가 **전건 실패**한다 — `tools/propose_rubric.py` 가 실측으로 겪었고
#: (5/5 가 "지어냄" 으로 나왔다) 이 모듈을 처음 돌렸을 때 **내가 그 함정을 다시 밟았다**
#: (스팬 0/4). 지어낸 것이 아니라 내가 준 접두어다.
#:
#: 접두어를 아예 안 주는 선택도 있는데, 사람이 후보를 볼 때 몇 페이지인지가 필요하다.
_PAGE_MARK = re.compile(r"^\s*\(?\s*p\s*\d+\s*\)?\s*")


def _locate(text: str, chunks: list[retrieval.Chunk]) -> list[SourceSpan]:
    """근거 문자열이 나온 청크의 스팬을 찾는다. 못 찾으면 빈 목록이다.

    ❗**지어내지 않는다.** 모델이 문면을 요약해 오면 스팬이 안 붙는데, 그때 빈 목록을
    내는 것이 맞다 — 없는 근거를 있는 것처럼 만드는 것보다 **근거가 없다고 말하는 쪽**이
    안전하다(P4 가 근거 없는 판정을 무효로 두는 것과 같은 방향). 화면은 스팬이 없으면
    *"사람이 직접 찾아야 한다"* 로 읽으면 된다.

    `textsim.normalize` 가 공백을 지우므로 문맥을 `' '.join(split())` 로 준 것과
    원문의 줄바꿈이 서로 안 걸린다.
    """
    norm = textsim.normalize(_PAGE_MARK.sub("", text))
    if not norm:
        return []
    for c in chunks:
        if norm in textsim.normalize(c.text):
            return [SourceSpan(page=sp.page, start=sp.start, end=sp.end) for sp in c.spans]
    return []


def _already_covered(candidate: str, existing: tuple[str, ...]) -> bool:
    """기존 조항과 겹치는가. 사람이 새것과 헷갈리지 않게 갈라 준다."""
    # ❗**방향이 중요하다** — `containment(기존, 후보)` 다. `tools/propose_rubric._novelty`
    # 와 같은 방향이어야 도구로 본 것과 화면이 받는 것이 안 갈린다.
    #
    # 반대로 재면(후보가 기존에 얼마나 들어있나) **긴 후보가 짧은 기존 조항을 통째로
    # 포함해도 점수가 낮다** — 후보 바이그램의 대부분이 기존에 없으니까. 실측에서
    # `ELS-MATURITY-LOSS-CONDITION` 이 그랬다(기존 루브릭이 있는데 already_covered 0건).
    return any(textsim.containment(e, candidate) >= ALREADY_COVERED for e in existing)


def propose_one(
    item_id: str, doc: ParsedDocument, client: LlmClient
) -> RubricProposal:
    """항목 하나의 후보. 검색으로 근거 청크를 모으고 조항 초안을 낸다."""
    item = next(i for i in templates.get(doc.product_type).items if i.item_id == item_id)
    chunks = retrieval.chunk_document(doc.model_dump())

    query = f"{item.name} {item.cue}"
    bm = retrieval.Bm25(chunks)
    dense = retrieval.Dense.embed(chunks, client)
    qvec = client.embed([query])[0]
    hits = retrieval.search(query, chunks, bm, dense, qvec, client=client, top_n=TOP_N)
    context = retrieval.with_neighbors(hits, chunks)

    listing = "\n\n".join(
        f"[{i}] (p{c.page}) {' '.join(c.text.split())}" for i, c in enumerate(context)
    )
    draft = client.complete_json(
        prompt=(
            f"[항목]\n{item.item_id} — {item.name}\n{item.cue}\n\n"
            f"[문서 조각]\n{listing}\n\n"
            "위 조각에서 이 항목의 **채점 기준**을 만들라. 고객이 무엇을 말해야 이 항목을 "
            "이해한 것인가. 조각에 없는 것을 만들지 말고, `evidence` 에는 근거가 된 원문을 "
            "그대로 옮긴다. JSON만 출력한다."
        ),
        model_cls=_Draft,
        schema_name="RubricDraft",
        system=_SYSTEM,
        pii_scope="public_document",
    )
    if not isinstance(draft, _Draft):
        raise TypeError(f"생성기가 _Draft 가 아닌 것을 냈다: {type(draft).__name__}")

    existing: tuple[str, ...] = ()
    has_existing = False
    try:
        r = rubrics.get(item_id)
        existing = tuple(r.required_elements) + tuple(r.misconception_conditions)
        has_existing = True
    except Exception:  # noqa: BLE001 — 루브릭이 아직 없는 항목이 이 기능의 대상이다
        pass

    return RubricProposal(
        item_id=item.item_id,
        name=item.name,
        required_elements=list(draft.required_elements),
        misconception_conditions=list(draft.misconception_conditions),
        evidence=[RubricEvidence(text=t, spans=_locate(t, chunks)) for t in draft.evidence],
        already_covered=[c for c in draft.required_elements if _already_covered(c, existing)],
        has_existing_rubric=has_existing,
    )
