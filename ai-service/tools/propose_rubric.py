#!/usr/bin/env python3
"""문서에서 루브릭 후보 조항을 낸다 — **사람이 승인해서 YAML 에 넣는다.** 소유: 윤지석

## 왜 — 루브릭이 하드코딩이라 빠뜨린 것이 안 보인다

루브릭 17종은 사람이 문서를 읽고 손으로 쓴 것이다. `required_elements` 가 하나 빠지면

    고객이 그것을 몰라도 U1(이해) 이 나온다  →  게이트가 초록  →  아무도 모른다

**채점은 성공하고 미탐만 조용히 는다.** P5(0.2절 · 미탐 방지)가 막으려는 모양이다.

실제로 빠져 있었다. `tools/find_coverage_gaps.py` 가 `VAR-FEE-DEDUCTION` 에서 찾은 것:

    루브릭  월공제액(위험보험료·계약체결비용·계약관리비용·보증비용) + 미상각신계약비
    문서    위 + **특별계정 운용보수 · 증권거래비용 · 기타비용 · 기초펀드보수**
            "…해당 특별계정(펀드) 적립액에서 차감되며 기준가격에 반영됩니다"

펀드 층 비용이 월공제액과 **다른 층**인데 루브릭에 없다. 이 도구는 그런 후보를 낸다.

## ❗채점 시점에 쓰지 않는다 — 루브릭은 파일로 고정된 채 남는다

`verify_rubric_clause_is_published`(F-SCR-001)가 *"모델이 인용한 조항이 루브릭에
실재하는가"* 로 P4 를 강제한다. 런타임에 검색해 오면 **그 검사가 순환이 되어 무의미해진다.**
공개 의무(루브릭 YAML)와 재현성도 같은 자리에 걸린다.

**바뀌는 것은 그 파일을 누가 쓰느냐다** — 사람이 타이핑하던 것을 검색이 후보로 내고
사람이 승인한다. 그래서 이 도구는 **YAML 을 고치지 않는다.** 후보를 찍고 끝난다.

## 왜 사람 승인이 필요한가 (자동 반영을 안 하는 이유)

리랭커가 비결정적이다(`#281`: `temperature` 는 정책 모델이 거부하고 `seed` 고정도
안 통한다). 그래서 **같은 문서에서 같은 후보가 나온다는 보장이 없다.** 자동 반영하면
루브릭이 회차마다 달라지고, 그러면 채점 기준이 흔들린다 — 문서 편집이 채점을 바꾸는
것과 같은 문제다.

사람이 승인하면 그 비결정성이 **후보 단계에서 멈춘다.** 승인된 루브릭은 여전히 고정이다.

## 쓰기

    .venv/bin/python tools/propose_rubric.py VAR-FEE-DEDUCTION
    .venv/bin/python tools/propose_rubric.py VARIABLE_INSURANCE --all
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import retrieval, rubrics, templates, textsim  # noqa: E402
from app.config import settings  # noqa: E402
from app.llm_client import LlmClient  # noqa: E402
from pydantic import BaseModel, Field  # noqa: E402

CONTRACT_SAMPLES = Path(__file__).resolve().parents[2] / "contracts" / "samples"
SAMPLE_BY_PRODUCT = {
    "ELS": "parsed_els_sample.json",
    "VARIABLE_INSURANCE": "parsed_variable_sample.json",
}

#: 후보가 기존 조항과 이만큼 겹치면 **이미 있는 것**으로 본다.
#:
#: `find_coverage_gaps.COVERED_MIN`(0.25)보다 **높게** 잡는다. 목적이 반대다 — 거기서는
#: *"이미 누가 덮고 있다"* 를 넓게 봐야 목록이 조용하고, 여기서는 *"이건 새 조항이다"* 를
#: 좁게 봐야 사람이 볼 후보가 진짜 새것이다. 놓치는 쪽(새것을 기존으로 봄)이 실패 모드다.
ALREADY_COVERED = 0.45


class Proposal(BaseModel):
    """생성기 출력. **문서 문면에서 유도한 것만** 낸다."""

    required_elements: list[str] = Field(
        description="이해로 인정되려면 고객이 언급해야 하는 것. 문서 조항에서 유도한다"
    )
    misconception_conditions: list[str] = Field(
        default_factory=list,
        description="고객이 말하면 오해로 보는 것. 위 조항의 반대다",
    )
    evidence: list[str] = Field(
        default_factory=list,
        description="각 required_elements 의 근거가 된 문서 원문. 요약하지 말고 그대로",
    )


def propose(item_id: str, product_type: str, client: LlmClient) -> tuple[Proposal, list[retrieval.Chunk]]:
    """검색으로 후보 청크를 모으고 조항 초안을 낸다."""
    item = next(i for i in templates.get(product_type).items if i.item_id == item_id)
    doc = json.loads((CONTRACT_SAMPLES / SAMPLE_BY_PRODUCT[product_type]).read_text("utf-8"))
    chunks = retrieval.chunk_document(doc)

    query = f"{item.name} {item.cue}"
    bm = retrieval.Bm25(chunks)
    dense = retrieval.Dense.embed(chunks, client)
    qvec = client.embed([query])[0]
    hits = retrieval.search(query, chunks, bm, dense, qvec, client=client, top_n=3)
    context = retrieval.with_neighbors(hits, chunks)

    listing = "\n\n".join(
        f"[{i}] (p{c.page}) {' '.join(c.text.split())}" for i, c in enumerate(context)
    )
    out = client.complete_json(
        prompt=(
            f"[항목]\n{item.item_id} — {item.name}\n{item.cue}\n\n"
            f"[문서 조각]\n{listing}\n\n"
            "위 조각에서 이 항목의 **채점 기준**을 만들라. 고객이 무엇을 말해야 이 항목을 "
            "이해한 것인가. 조각에 없는 것을 만들지 말고, `evidence` 에는 근거가 된 원문을 "
            "그대로 옮긴다. JSON만 출력한다."
        ),
        model_cls=Proposal,
        schema_name="Proposal",
        system=(
            "당신은 금융상품 공시문서에서 고객 이해도 채점 기준을 만드는 사람이다. "
            "문서에 적힌 것만 쓴다. 일반적인 금융 지식을 보태지 않는다. "
            "각 기준은 한 문장이고, 고객이 그것을 말했는지 사람이 판단할 수 있어야 한다."
        ),
        pii_scope="public_document",
    )
    return out, context


#: 모델이 베껴 오는 페이지 표시. **내가 프롬프트에 넣은 것이다.**
#:
#: 문맥을 `[0] (p12) 본문…` 으로 주니 모델이 `evidence` 에 `(p12) ` 를 그대로 붙여 왔고,
#: 그러면 원문 대조가 **전건 실패**한다(실측: 5/5 가 "지어냄" 으로 나왔다). 지어낸 것이
#: 아니라 내가 준 접두어다 — **그물이 내가 만든 노이즈에 걸린 것**이라 여기서 벗긴다.
#:
#: 접두어를 아예 안 주는 선택도 있는데, 사람이 후보를 볼 때 몇 페이지인지가 필요하다.
_PAGE_MARK = re.compile(r"^\s*\(?\s*p\s*\d+\s*\)?\s*")


def _is_verbatim(evidence: str, context: list[retrieval.Chunk]) -> bool:
    """근거가 문맥 원문에 실재하는가. **요약했으면 근거가 아니다.**

    F-EXT-002 가 인용을 원문에서 재계산하는 것과 같은 규칙이다 — 모델이 근거라고 말한
    것을 그대로 믿으면, 사람이 승인할 때 볼 것이 모델의 요약이 된다.
    """
    needle = textsim.normalize(_PAGE_MARK.sub("", evidence))
    return any(needle in c.norm for c in context) if needle else False


def _novelty(candidate: str, existing: tuple[str, ...]) -> tuple[bool, float, str]:
    """후보가 새것인가. (새것인가, 최고 겹침, 가장 가까운 기존 조항)"""
    best, near = 0.0, "-"
    for cur in existing:
        score = textsim.containment(cur, candidate)
        if score > best:
            best, near = score, cur
    return best < ALREADY_COVERED, best, near


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__.split("## 쓰기")[1])
        return 2

    target = args[0]
    if target in SAMPLE_BY_PRODUCT:
        product_type = target
        item_ids = [i.item_id for i in templates.get(product_type).items
                    if i.item_id in set(rubrics.all_rubrics())]
    else:
        product_type = rubrics.get(target).product_type
        item_ids = [target]

    client = LlmClient(settings())
    for item_id in item_ids:
        current = rubrics.get(item_id)
        out, context = propose(item_id, product_type, client)

        print("=" * 78)
        print(f"{item_id}  —  {current.name}   [{current.status}]")
        print(f"  문맥 청크 {len(context)}개"
              f" (페이지 걸친 것 {sum(c.crosses_pages for c in context)})")
        print("=" * 78)

        print("\n  현재 required_elements")
        for e in current.required_elements:
            print(f"    · {e}")

        print("\n  제안")
        for e in out.required_elements:
            new, best, near = _novelty(e, current.required_elements)
            mark = "★새것" if new else f"기존과 {best:.2f}"
            print(f"    {'★' if new else ' '} {e}")
            print(f"        {mark}" + (f"  ← {near[:46]}" if not new else ""))

        if out.evidence:
            print("\n  근거 (문서 원문)")
            for ev in out.evidence:
                found = _is_verbatim(ev, context)
                print(f"    {'✅' if found else '❗지어냄'} {ev[:110]}")
        print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
