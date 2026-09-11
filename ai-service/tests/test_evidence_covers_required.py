"""인용이 루브릭 **필수요소의 근거를 덮는가**. 소유: 윤지석 (이슈 #456)

## 왜 이 파일이 있나

P6 항등식(`pages[page].text[start:end] == value_text`)은 *"인용이 원문과 같은가"* 를 잰다.
**«필요한 것을 덮는가» 는 아무도 안 쟀다.**

`ELS-MATURITY-LOSS-CONDITION` 의 루브릭이 `u1_requires: 2` 로 두 요소를 요구하는데, 그
근거가 원문 ⑧ 안에서 **조건절과 결론에 갈려 있다.** 모델이 어느 쪽을 인용하든 P6 를
통과하므로 같은 문서·같은 프롬프트에서 회차마다 다른 답이 나왔고 **여섯 회차에 세 답이
전부 초록**이었다(`#456`).

    #452 후 재생성   [ 827,  982)  조건만    → 필수요소 ②의 근거 없음
    alpha 스냅샷      [1038, 1077)  결론만    → 필수요소 ①의 근거 없음
    #477 재생성       [ 986, 1004)  결론 18자  ← 커밋돼 있던 것
    실추출            ⑧ 전문        둘 다

`[상품 조건 원문]` 으로 채점 프롬프트에 실리는 것이 이 `value_text` 라, 반쪽이면 고객이
정확히 말해도 채점자가 대조할 원문이 없다 — `u1_requires: 2` 를 채우는 U1 은 근거가 아니라
모델의 사전지식이다.

## 무엇을 잠그나

`extraction._widen_to_cover` 가 그 변동을 **결정론으로** 없앤다. 이 파일은 그 결과를 잰다 —
**LLM 없이, 커밋된 문맥과 원문만 보고.**
"""
from __future__ import annotations

import dataclasses
import json
import pathlib

import pytest

from app import extraction, parsing, templates

REPO = pathlib.Path(__file__).resolve().parents[2]
PARSED = REPO / "contracts" / "samples" / "parsed_els_sample.json"
CONTEXT = REPO / "eval" / "data" / "context" / "els.json"


def _doc() -> dict:
    return json.loads(PARSED.read_text(encoding="utf-8"))


def _item(product: str, item_id: str) -> templates.TemplateItem:
    return [i for i in templates.get(product).items if i.item_id == item_id][0]


def _declared() -> list[templates.TemplateItem]:
    out = [i for t in ("ELS", "VARIABLE_INSURANCE")
           for i in templates.get(t).items if i.evidence_must_cover]
    assert out, ("evidence_must_cover 를 선언한 항목이 하나도 없다 — 규약이 사라졌으면 "
                 "이 파일도 같이 지운다. 안 그러면 0건을 검사하고 조용히 통과한다")
    return out


# ── 커밋된 문맥이 규약을 지킨다 ────────────────────────────────────────────────
@pytest.mark.xfail(
    strict=True,
    reason="커밋된 문맥이 아직 넓힘 전 회차다(결론 18자) — #409 가 재생성할 때 초록이 된다",
)
def test_the_committed_context_covers_every_required_element() -> None:
    """★ **커밋된 추출 산출물**이 선언된 조각을 다 덮는다 (이슈 #456).

    ❗여기가 이 이슈의 본체다. `eval/data/context/els.json` 이 채점 표본의 `[상품 조건 원문]`
    이고, 그것이 반쪽이면 그 표본으로 잰 수치가 근거 없이 나온다.

    ## ❗지금은 `xfail(strict)` 다 — 이 PR 이 문맥을 재생성하지 않기 때문이다

    `build_context.py` 를 돌리면 `built_at` 이 바뀌고 그 순간
    `eval/tests/test_model_output_matches_context.py` 가 **빨개진다** — 그 파일이 `#456` 을
    이름으로 예고해 둔 자리다. 초록으로 만들려면 채점 91회를 다시 돌려야 하고, 그러면 대표
    수치(QWK)가 이 PR 에서 바뀐다. `#409` 가 그 배치를 들고 있고 *"91회는 두 번 돌릴 값이
    아니다"* 가 그쪽 판단이므로, **재생성은 거기서 한 번에** 한다.

    ❗**`strict=True` 인 이유**: 재생성이 끝나면 이 단정이 통과하는데, 그때 `xpass` 로 조용히
    지나가면 이 표시가 영원히 남는다. strict 면 **초록이 되는 순간 빨개져서** 이 데코레이터를
    걷으라고 말한다 — 「고쳤는데 아무도 안 걷는 표시」를 안 만든다.

    걷는 조건은 하나다 — `eval/data/context/els.json` 이 넓힘 뒤 추출로 재생성되는 것.
    """
    ctx = json.loads(CONTEXT.read_text(encoding="utf-8"))
    items = ctx["risk_items"]

    for item in _declared():
        got = items.get(item.item_id)
        if got is None:
            pytest.skip(f"{item.item_id} 가 문맥에 없다 — 표본 구성이 바뀐 것이다")
        text = got["condition"]["value_text"]
        missing = [p for p in item.evidence_must_cover if p not in text]
        assert not missing, (
            f"{item.item_id}: 인용이 필수요소의 근거를 덜 덮는다 — 빠진 조각 {missing}. "
            f"루브릭이 u1_requires 로 요구하는 요소의 근거가 원문에서 갈려 있는데 인용이 "
            f"한쪽만 집었다는 뜻이다(이슈 #456). 인용: {text[:60]!r}"
        )


def test_the_span_still_resolves_to_the_source() -> None:
    """넓힌 뒤에도 P6 항등식이 참이다 — 넓힘이 원문 밖을 만들지 않는다."""
    ctx = json.loads(CONTEXT.read_text(encoding="utf-8"))
    doc = _doc()

    for item in _declared():
        got = ctx["risk_items"].get(item.item_id)
        if got is None:
            continue
        span, text = got["condition"]["source_span"], got["condition"]["value_text"]
        page = parsing.page_text(doc, span["page"])
        assert page[span["start"]:span["end"]] == text, (
            f"{item.item_id}: P6 항등식이 깨졌다 — 넓힘이 스팬과 문면을 갈라 놨다")


# ── 넓힘이 회차에 안 흔들린다 ──────────────────────────────────────────────────
@pytest.mark.parametrize("start,end,what", [
    (986, 1004, "결론만 — #477 이 커밋한 회차"),
    (827, 982, "조건만 — #452 후 재생성"),
    (807, 1004, "전문 — 실추출"),
    (966, 1004, "수식만"),
    (991, 992, "한 글자"),
])
def test_every_partial_quote_widens_to_the_same_span(start: int, end: int, what: str) -> None:
    """★ **어느 조각을 집든 같은 답이 나온다** — 회차 변동이 없어지는 자리다.

    ❗프롬프트로는 이걸 못 만든다. 모델은 인용만 내고 스팬은 `_widen_to_cover` 가 정하므로,
    문면을 다듬어도 «어느 조각을 인용하나» 는 회차마다 흔들린다. 그래서 뒤에서 맞춘다.
    """
    doc = _doc()
    item = _item("ELS", "ELS-MATURITY-LOSS-CONDITION")
    text = parsing.page_text(doc, 8)

    span = {"match": "exact", "value_text": text[start:end],
            "source_span": {"page": 8, "start": start, "end": end}}
    out = extraction._widen_to_cover(span, item, doc, [])

    got = (out["source_span"]["start"], out["source_span"]["end"])
    assert got == (807, 1004), f"{what}: ⑧ 전문으로 안 모였다 — {got}"
    assert all(p in out["value_text"] for p in item.evidence_must_cover)


def test_an_item_without_the_declaration_is_untouched() -> None:
    """❗**옵트인이다** — 선언 안 한 항목은 스팬이 그대로다.

    이 단정이 없으면 넓힘이 전 항목에 걸려도 아무도 모른다. `ELS-KNOCKIN-BARRIER` 는 필수
    요소 둘이 **조건절 안에 다 있어서** 이 규약이 필요 없는 항목이다(`#456` 본문).
    """
    doc = _doc()
    item = _item("ELS", "ELS-KNOCKIN-BARRIER")
    assert not item.evidence_must_cover, "이 테스트의 전제가 바뀌었다"

    span = {"match": "exact", "value_text": parsing.page_text(doc, 8)[900:950],
            "source_span": {"page": 8, "start": 900, "end": 950}}
    warnings: list = []
    out = extraction._widen_to_cover(span, item, doc, warnings)

    assert out["source_span"] == {"page": 8, "start": 900, "end": 950}
    assert warnings == []


def test_widening_is_announced() -> None:
    """넓혔으면 경고로 말한다 — 은폐하지 않는다(E-EXT-03).

    ❗조용히 넓히면 «모델이 ⑧ 전문을 인용했다» 로 읽힌다. 실제로는 우리가 뒤에서 맞춘
    것이고, 그 사실이 산출물에 남아야 다음 사람이 이 규약을 찾는다.
    """
    doc = _doc()
    item = _item("ELS", "ELS-MATURITY-LOSS-CONDITION")
    text = parsing.page_text(doc, 8)

    warnings: list = []
    extraction._widen_to_cover(
        {"match": "exact", "value_text": text[986:1004],
         "source_span": {"page": 8, "start": 986, "end": 1004}}, item, doc, warnings)
    assert [w.code for w in warnings] == ["EVIDENCE_WIDENED"]

    # 이미 덮고 있으면 안 넓히고 말도 안 한다 — 경고가 배경이 되지 않게
    quiet: list = []
    extraction._widen_to_cover(
        {"match": "exact", "value_text": text[807:1004],
         "source_span": {"page": 8, "start": 807, "end": 1004}}, item, doc, quiet)
    assert quiet == []


def test_a_missing_piece_is_announced_and_the_rest_still_widens() -> None:
    """❗**그 페이지에 없는 조각**은 조용히 넘기지 않는다 (E-EXT-03).

    선언이 낡거나(원문 판이 바뀜) 오타면 넓힘이 **반쪽으로 성공**한다 — 스팬은 늘어나는데
    덮으라고 적은 것 하나가 빠진 채다. 경고가 없으면 그 상태가 산출물에서 정상과 구별되지
    않는다. `#456` 이 고치려는 것이 정확히 «반쪽인데 초록» 이라, 이 규약 자신이 같은 모양을
    만들면 안 된다.

    남은 조각으로는 **계속 넓힌다** — 하나가 안 맞는다고 나머지 근거까지 버릴 이유가 없다.
    """
    doc = _doc()
    item = dataclasses.replace(
        _item("ELS", "ELS-MATURITY-LOSS-CONDITION"),
        evidence_must_cover=("최초기준가격의", "이 페이지에 없는 문면"),
    )
    text = parsing.page_text(doc, 8)

    warnings: list = []
    out = extraction._widen_to_cover(
        {"match": "exact", "value_text": text[986:1004],
         "source_span": {"page": 8, "start": 986, "end": 1004}}, item, doc, warnings)

    assert [w.code for w in warnings] == ["EVIDENCE_PIECE_MISSING", "EVIDENCE_WIDENED"]
    assert "이 페이지에 없는 문면" in warnings[0].message
    assert warnings[0].item_id == "ELS-MATURITY-LOSS-CONDITION"
    assert "최초기준가격의" in out["value_text"], "찾은 조각으로는 계속 넓힌다"
