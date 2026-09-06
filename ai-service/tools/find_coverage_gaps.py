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

## LLM 을 안 부른다 — 그리고 계산은 `app/coveragegap.py` 가 갖는다

겹침 계산만 쓴다(검색·리랭킹이 아니다). 그래서 결정론적이고 쿼터를 안 쓴다.

❗**이 파일은 표시만 한다** (`#486` 규율). 계산이 두 벌이면 갈렸을 때 증상이 조용하다 —
이 도구로 본 사각과 화면(`POST /internal/template/gaps`)이 받는 사각이 달라지는데
**둘 다 그럴듯하다.**

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

from app import coveragegap, rubrics  # noqa: E402
from app.schemas import ParsedDocument  # noqa: E402

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
#: 생성 로직은 `app/coveragegap.py` 가 갖는다 — **이 도구는 표시만 한다.**
#:
#: `#486` 에서 세운 규율이다. 두 벌이면 갈렸을 때 **증상이 조용하다** — 도구로 본 사각과
#: 화면(`POST /internal/template/gaps`)이 받는 사각이 달라지는데 **둘 다 그럴듯하다.**
#: `tests/test_coverage_gaps.py` 가 이 파일에 계산이 다시 생기면 문다.
#:
#: 도구 전용으로 남는 것은 **문서 출처 하나**다 — 라우트는 요청이 준 `parsed_document` 를
#: 쓰고, 여기는 개발용으로 계약 샘플을 읽는다.
COVERED_MIN = coveragegap.COVERED_MIN


def _load(product_type: str) -> ParsedDocument:
    """계약 샘플을 파스 출력으로 읽는다.

    ❗**이 도구만 `extra="ignore"` 에 의존한다**(`#486` 리뷰와 같은 자리). 계약 샘플은
    `_source`·`_expected_risk_items` 를 들고 있는데(ADR-006), `ParsedDocument` 가
    `Strict` 를 `model_config = ConfigDict(extra="ignore")` 로 덮어서 통과한다
    (`schemas.py`). 그 줄이 사라지면 **라우트는 멀쩡하고 이 도구만 죽는다.**
    """
    raw = json.loads((CONTRACT_SAMPLES / SAMPLE_BY_PRODUCT[product_type]).read_text("utf-8"))
    return ParsedDocument.model_validate(raw)


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    products = args or list(SAMPLE_BY_PRODUCT)

    for product_type in products:
        doc = _load(product_type)
        found = coveragegap.find_gaps(doc, product_type)
        total = len(coveragegap.sentences(doc))
        anchors = coveragegap.anchors(product_type)
        draft = sum(1 for r in rubrics.all_rubrics().values()
                    if r.product_type == product_type and r.is_draft)
        print("=" * 78)
        print(f"{product_type}  —  문장 {total}개 중 아무 항목도 안 덮는 것 {len(found)}개"
              f"   (루브릭 draft {draft}종)")
        print(f"  앵커 {len(anchors)}개 = 템플릿 cue + 루브릭 조항")
        print("=" * 78)
        for g in found:
            # ❗스팬을 같이 찍는다 — 사람이 원문에서 그 자리를 찾아야 한다(P6).
            print(f"\n  p{g.page} [{g.start}:{g.end}]  겹침 {g.best_overlap:.2f}  최근접 {g.covered_by}")
            print(f"    {g.text[:160]}")
        if not found:
            print("\n  없음 — 모든 문장이 어느 항목엔가 걸린다")
        print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
