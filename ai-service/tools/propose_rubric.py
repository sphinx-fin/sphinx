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
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import rubricgen, rubrics, templates  # noqa: E402
from app.config import settings  # noqa: E402
from app.llm_client import LlmClient  # noqa: E402
from app.schemas import ParsedDocument  # noqa: E402

CONTRACT_SAMPLES = Path(__file__).resolve().parents[2] / "contracts" / "samples"
SAMPLE_BY_PRODUCT = {
    "ELS": "parsed_els_sample.json",
    "VARIABLE_INSURANCE": "parsed_variable_sample.json",
}

#: 생성은 `app/rubricgen.py` 가 한다 — **이 도구는 표시만 한다.**
#:
#: 예전에는 프롬프트·`_SYSTEM`·`ALREADY_COVERED`(0.45)·`_PAGE_MARK` 정규식·출력 모델이
#: 여기와 `app/rubricgen.py` 에 **두 벌** 있었다(`#476` 에서 내가 만들었고 리뷰 재촉
#: 코멘트에 스스로 적었다). 두 벌이면 갈렸을 때 **증상이 조용하다** — 이 도구로 본 후보와
#: 관리자 화면이 받는 후보가 달라지는데 **둘 다 그럴듯한 답을 낸다.**
#:
#: 그래서 후보 생성·근거 대조·겹침 판정을 전부 모듈에서 가져오고, 여기 남는 것은
#: **사람이 보는 문면**뿐이다. `tests/test_rubric_propose.py` 가 이 파일에 생성 코드가
#: 다시 생기면 문다.
def _load(product_type: str) -> ParsedDocument:
    """계약 샘플을 파스 출력으로 읽는다. **도구 전용 문서 출처다.**

    라우트는 요청이 준 `parsed_document` 를 쓴다(`#476`). 여기가 샘플을 읽는 것은
    개발용이고, 그 차이가 이 도구와 화면의 **유일한** 갈림이어야 한다.

    ❗**이 도구만 `extra="ignore"` 에 의존한다** (`#486` 리뷰, 정세현). 계약 샘플은
    `_source`·`_expected_risk_items` 를 들고 있는데(ADR-006), `ParsedDocument` 가
    `Strict`(=`extra="forbid"`)를 `model_config = ConfigDict(extra="ignore")` 로
    덮기 때문에 통과한다(`schemas.py:179`).

    그 줄이 사라지면 **라우트는 멀쩡하고 이 도구만 죽는다** — 요청이 주는
    `parsed_document` 에는 그 키가 없다. 그리고 **CI 가 이 도구를 실행하지 않으므로**
    회귀는 사람이 도구를 돌리는 날에나 보인다.
    """
    raw = json.loads((CONTRACT_SAMPLES / SAMPLE_BY_PRODUCT[product_type]).read_text("utf-8"))
    return ParsedDocument.model_validate(raw)


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
    doc = _load(product_type)
    # 문서 준비물은 **문서 단위**다 — 항목마다 다시 만들면 ELS 13항목에서 임베딩이
    # 450여 텍스트 · 왕복 13회가 된다 (`#476` 리뷰 ①).
    prep = rubricgen.prepare(doc, client)

    for item_id in item_ids:
        current = rubrics.get(item_id)
        out = rubricgen.propose_one(item_id, doc, client, prep=prep)

        print("=" * 78)
        print(f"{item_id}  —  {current.name}   [{current.status}]")
        # ❗**문서 전체**의 청크다 — 예전 문면("문맥 청크")은 그 항목이 실제로 검색해 온
        # 조각 수였는데, 준비물을 문서 단위로 공유하면서 세는 대상이 바뀌었다. 같은
        # 이름을 두면 다른 것을 재고도 안 보인다. 항목별 근거는 아래 스팬으로 나온다.
        print(f"  문서 청크 {len(prep.chunks)}개"
              f" (페이지 걸친 것 {sum(c.crosses_pages for c in prep.chunks)})")
        print("=" * 78)

        print("\n  현재 required_elements")
        for e in current.required_elements:
            print(f"    · {e}")

        print("\n  제안")
        for e in out.required_elements:
            covered, best, near = rubricgen.overlap_with_existing(e, current.required_elements)
            mark = f"기존과 {best:.2f}" if covered else "★새것"
            print(f"    {' ' if covered else '★'} {e}")
            print(f"        {mark}" + (f"  ← {near[:46]}" if covered else ""))

        if out.evidence:
            # ❗**스팬이 비면 원문에 없다는 뜻이다** — `rubricgen._locate` 가 요약을
            # 지어내지 않고 빈 목록을 낸다(P4 와 같은 방향). 화면도 같은 값을 받는다.
            print("\n  근거 (문서 원문)")
            for ev in out.evidence:
                where = f"p{ev.spans[0].page}" if ev.spans else "❗지어냄"
                print(f"    {'✅ ' + where if ev.spans else where} {ev.text[:106]}")
        print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
