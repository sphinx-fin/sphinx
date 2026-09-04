#!/usr/bin/env python3
"""문서에 있는데 **어느 항목에도 안 걸리는** 위험 문면을 찾는다. 소유: 윤지석

## 왜 — 지금 체인이 닫혀 있다

```
문서 13,233자  →  템플릿 13항목  →  루브릭 10종  →  채점
                    ↑ 닫힌 목록
```

**템플릿에 없는 항목은 추출도 안 되고 채점도 안 된다.** 재현율의 분모가 템플릿이라(이슈
#26) 분모 밖은 구조적으로 안 보인다. 그리고 루브릭의 `required_elements` 가 하나 빠지면

    고객이 그것을 몰라도 U1(이해) 이 나온다  →  게이트가 초록  →  아무도 모른다

**채점은 성공하고 미탐만 조용히 는다.** P5(0.2절 · 미탐 방지)가 막으려는 그 모양이다.

## 무엇을 하나 — 질문을 뒤집는다

추출은 *"이 항목의 근거가 문서 어디 있나"* 를 묻는다. 이 도구는 반대를 묻는다.

    "문서의 이 문면이 어느 항목에도 안 걸리는데, 중요한가"

❗**채점 경로를 건드리지 않는다.** 루브릭은 여전히 사람이 쓰고 공개되고 고정된다
(`verify_rubric_clause_is_published` 가 그것을 근거로 P4 를 강제한다 — 런타임에 검색해
오면 그 검사가 순환이 된다). 이건 **루브릭을 쓸 때 무엇을 빠뜨렸는지** 알려주는 도구다.

## ❗위험 어휘 필터를 뺐다 — 내 첫 설계에 순환이 있었다

처음에는 *"루브릭 `required_elements` 31개에서 위험 어휘를 유도해 필터로 쓴다"* 로 짰다.
하드코딩을 피한 것이라 맞다고 봤는데 **재보니 순환이었다.**

    위험 어휘를 앵커(루브릭)에서 유도한다
      → 그 어휘를 든 문장은 **당연히 앵커와 겹친다**
      → 두 필터가 같은 것을 재고 있다

실측이 그대로 나왔다.

    상품                    문장   겹침≤0.25   어휘통과   둘 다
    ELS                      90        28        45       0    ← 교집합이 0 이다
    VARIABLE_INSURANCE      130        73        69      29

**ELS 는 순환이 완전해서 사각을 하나도 못 냈다.** 필터가 그물을 찢은 것이고, 오늘 다른
PR 들에서 내가 지적한 바로 그 모양이다(`#304` 정규식 · `#352` 예외).

그래서 **필터를 버린다.** 문장이 90·130 개이므로 저겹침 순으로 정렬해 전부 보여주는 쪽이
낫다 — 사람이 훑을 수 있는 양이고, 무엇을 위험으로 볼지는 **사람이 판단할 것**이다.
도구가 그 판단을 대신하려다 판단을 지웠다.

## LLM 을 안 부른다

`textsim.containment` 만 쓴다. 검색 스택(청킹·하이브리드·리랭킹)을 붙일지 판단하려면
**먼저 진짜 빠진 것이 있는지** 알아야 하고, 그건 계산으로 초안이 나온다.

## 쓰기

    .venv/bin/python tools/find_coverage_gaps.py            # 두 상품 다
    .venv/bin/python tools/find_coverage_gaps.py ELS         # 하나만
    .venv/bin/python tools/find_coverage_gaps.py ELS --all   # 위험 어휘 필터 없이
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import rubrics, templates, textsim  # noqa: E402

CONTRACT_SAMPLES = Path(__file__).resolve().parents[2] / "contracts" / "samples"
SAMPLE_BY_PRODUCT = {
    "ELS": "parsed_els_sample.json",
    "VARIABLE_INSURANCE": "parsed_variable_sample.json",
}

#: 이 값 이상 겹치면 "그 항목이 이미 덮고 있다" 고 본다.
#:
#: `CUE_CONTAINMENT_MIN`(0.35, 추출의 좁히기 판별)보다 **낮게** 잡는다. 목적이 반대라서다 —
#: 추출은 *"이것이 그 항목의 조건이다"* 를 확정해야 하므로 높아야 하고, 여기는 *"이미 누가
#: 덮고 있다"* 를 보는 것이므로 **의심스러우면 덮은 것으로 처리**해야 오탐이 줄어든다.
#: 놓치는 쪽(사각인데 안 나온다)이 아니라 시끄러운 쪽(사각 아닌데 나온다)이 이 도구의
#: 실패 모드다 — 목록이 길면 아무도 안 본다.
COVERED_MIN = 0.25

#: 문장 하나로 볼 최소 길이. 이보다 짧으면 표 셀 조각이거나 머리글이다.
MIN_SENTENCE_CHARS = 20

_SENTENCE_END = re.compile(r"(?<=[.。!?])\s+|\n{2,}")


def anchors(product_type: str) -> list[tuple[str, str]]:
    """이미 덮고 있는 문면 — (출처, 문면). 템플릿 `cue` + 루브릭 조항 전부."""
    out: list[tuple[str, str]] = []
    for item in templates.get(product_type).items:
        out.append((f"template:{item.item_id}", f"{item.name} {item.cue}"))
    for item_id in rubrics.all_rubrics():
        rubric = rubrics.get(item_id)
        if rubric.product_type != product_type:
            continue
        for element in rubric.required_elements:
            out.append((f"rubric:{item_id}", element))
        for cond in rubric.misconception_conditions:
            out.append((f"rubric:{item_id}", cond))
    return out


def sentences(doc: dict) -> list[tuple[int, str]]:
    out: list[tuple[int, str]] = []
    for page in doc["pages"]:
        for raw in _SENTENCE_END.split(page["text"]):
            s = " ".join(raw.split())
            if len(s) >= MIN_SENTENCE_CHARS:
                out.append((page["page"], s))
    return out


def gaps(product_type: str, limit: float = COVERED_MIN):
    """(page, 문장, 최고 겹침, 그 출처) — 겹침이 낮은 것부터.

    **필터가 없다.** `limit` 아래를 전부 내고 겹침 순으로 정렬한다 — 무엇이 위험인지는
    사람이 판단하고, 도구는 *"아무도 안 덮고 있다"* 만 말한다(위 docstring 의 순환 참고).
    """
    doc = json.loads((CONTRACT_SAMPLES / SAMPLE_BY_PRODUCT[product_type]).read_text("utf-8"))
    anc = anchors(product_type)
    found = []
    for page, sentence in sentences(doc):
        best, src = 0.0, "-"
        for source, text in anc:
            score = textsim.containment(text, sentence)
            if score > best:
                best, src = score, source
        if best < limit:
            found.append((page, sentence, best, src))
    return sorted(found, key=lambda r: r[2])


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    products = args or list(SAMPLE_BY_PRODUCT)

    for product_type in products:
        found = gaps(product_type)
        doc = json.loads(
            (CONTRACT_SAMPLES / SAMPLE_BY_PRODUCT[product_type]).read_text("utf-8"))
        total = len(sentences(doc))
        draft = sum(1 for i in rubrics.all_rubrics()
                    if rubrics.get(i).product_type == product_type
                    and rubrics.get(i).is_draft)
        print("=" * 78)
        print(f"{product_type}  —  문장 {total}개 중 아무 항목도 안 덮는 것 {len(found)}개"
              f"   (루브릭 draft {draft}종)")
        print(f"  앵커 {len(anchors(product_type))}개 = 템플릿 cue + 루브릭 조항")
        print("=" * 78)
        for page, sentence, best, src in found:
            print(f"\n  p{page}  겹침 {best:.2f}  최근접 {src}")
            print(f"    {sentence[:160]}")
        if not found:
            print("\n  없음 — 모든 문장이 어느 항목엔가 걸린다")
        print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
