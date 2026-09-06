"""커버리지 사각 — 체인의 첫 칸 (이슈 #474 1번). 소유: 윤지석

`#476` 이 «항목 → 조항» 을 열었지만 그 입력인 **항목 목록은 사람이 쓴다.** 템플릿에 없는
항목은 추출도 채점도 안 되고, 재현율의 분모가 템플릿이라(`#26`) **분모 밖은 구조적으로
안 보인다.** 이 층이 그 반대를 묻는다 — *"이 문면이 어느 항목에도 안 걸리나."*

❗**「항목을 제안」하지 않는다.** 도구가 위험 어휘 필터로 그 판단을 대신하려다 순환을
겪었다(어휘를 앵커에서 유도 → ELS 교집합 0 → 사각 0건). 무엇이 위험인지는 사람이 정한다.
"""
from __future__ import annotations

import json
from pathlib import Path

from fastapi.testclient import TestClient

from app import coveragegap, rubricgen
from app.main import app
from app.schemas import ParsedDocument

SAMPLES = Path(__file__).resolve().parents[2] / "contracts" / "samples"


def _doc(name: str = "parsed_els_sample.json") -> ParsedDocument:
    return ParsedDocument.model_validate(json.loads((SAMPLES / name).read_text("utf-8")))


def _post(doc: ParsedDocument, **kw) -> dict:
    with TestClient(app) as c:
        r = c.post("/internal/template/gaps",
                   json={"parsed_document": doc.model_dump(mode="json"), **kw})
        assert r.status_code == 200, r.text
        return r.json()


# ── P6 — 스팬이 원문을 가리킨다 ──────────────────────────────────────────────
def test_every_gap_span_resolves_to_its_own_text() -> None:
    """★ `pages[page].text[start:end] == text` (P6 · 1절).

    ❗**첫 판이 이걸 깰 뻔했다.** 도구가 문장을 `" ".join(s.split())` 로 접어서 내는데,
    그대로 옮기면 스팬이 가리키는 원문과 글자가 달라진다 — 화면이 하이라이트하면 밀린다.
    접는 것은 대조할 때만 하고(`textsim` 안) 내보내는 값은 원문 슬라이스다.
    """
    for name in ("parsed_els_sample.json", "parsed_variable_sample.json"):
        doc = _doc(name)
        body = _post(doc)
        pages = {p.page: p.text for p in doc.pages}
        for g in body["gaps"]:
            assert pages[g["page"]][g["start"]:g["end"]] == g["text"], (
                f"{name} p{g['page']} [{g['start']}:{g['end']}] 스팬이 본문과 다르다"
            )
        assert body["gaps"], f"{name} 사각이 0건이면 위 루프가 0회 돈다(빈 모집단)"


# ── 분모 ────────────────────────────────────────────────────────────────────
def test_the_denominators_are_served() -> None:
    """❗사각 0건이 「깨끗하다」인지 「문장을 못 읽었다」인지 갈라야 한다.

    `R-00` 이 분모를 지키는 것과 같은 이유다. 둘 다 응답에 싣는다.
    """
    body = _post(_doc())
    assert body["sentences_scanned"] > 0
    assert body["anchors_used"] > 0
    assert len(body["gaps"]) <= body["sentences_scanned"]


def test_the_denominator_comes_from_the_scan_not_a_second_count() -> None:
    """★ 라우트가 분모를 **다시 세지 않는다** (`#499` 리뷰, 정세현).

    ❗**값으로는 못 잰다** — `sentences()` 가 순수 함수라 다시 불러도 오늘은 같은 수가
    나온다. 그래서 라우트가 `scan()` 이 «낸» 값을 그대로 싣는지를 직접 잰다. `#449` 에서
    「결과」와 「자리」를 갈라 잰 것과 같은 모양이다.

    이게 왜 중요한가: `find_gaps` 가 나중에 문장을 걸러내면 **분모만 옛 값으로 남고 그
    사실이 안 보인다** — 사각 0건인데 분모가 91 로 찍히면 *"91개를 다 봤는데 깨끗하다"*
    로 읽힌다.
    """
    from app import routes

    original = coveragegap.scan
    fake = coveragegap.Scan(gaps=[], sentences_scanned=7, anchors_used=3)
    routes.coveragegap.scan = lambda *a, **k: fake
    try:
        body = _post(_doc())
    finally:
        routes.coveragegap.scan = original

    assert body["sentences_scanned"] == 7, "라우트가 문장 수를 다시 센다"
    assert body["anchors_used"] == 3, "라우트가 앵커 수를 다시 센다"


def test_no_anchors_makes_every_sentence_a_gap() -> None:
    """★ 앵커가 비면 **모든 문장이 사각**이 된다 — 그래서 `anchors_used` 를 같이 낸다.

    `#396` 에서 밟은 「빈 모집단이 단정을 참으로 만든다」의 반대편이다. 여기서는 빈 모집단이
    결과를 **뒤집는다** — 아무것도 안 덮으니 전부 사각이고, 그것을 「사각이 65건이나 된다」로
    읽으면 정반대 결론이 난다.
    """
    doc = _doc()
    real = coveragegap.find_gaps(doc, "ELS")
    every = coveragegap.find_gaps_with_anchors(doc, [])
    assert len(every) == len(coveragegap.sentences(doc))
    assert len(real) < len(every), "앵커가 아무것도 안 덮고 있다"


# ── 무엇을 내는가 ───────────────────────────────────────────────────────────
def test_anchors_come_from_both_sources() -> None:
    """★ 템플릿 cue **와** 루브릭 조항이 둘 다 앵커다.

    ❗한쪽만 쓰면 조용히 시끄러워진다 — 루브릭을 빼면 **이미 조항이 있는 문면**이 사각으로
    올라오고, 템플릿을 빼면 `recommended` 6개(루브릭이 없는 항목 — `#435`)가 덮는 문면이
    전부 올라온다. 둘 다 「틀린 목록」이 아니라 「긴 목록」이라, 이 층의 실패 모드
    (*목록이 길면 아무도 안 본다*)로 조용히 빠진다.

    변조로 확인했다 — 루브릭 앵커를 지워도 합성 문장은 템플릿 cue 가 덮어서 **다른
    테스트가 아무것도 안 물었다.** 그래서 구성을 직접 잰다.
    """
    from app import rubrics

    anc = coveragegap.anchors("ELS")
    texts = {text for _, text in anc}
    sources = {s.split(":", 1)[0] for s, _ in anc}
    assert sources == {"template", "rubric"}, sources

    # ❗**출처 이름만 세면 안 된다.** 첫 변조가 `required_elements` 만 지웠는데
    #   `misconception_conditions` 가 남아 `rubric:` 접두어가 그대로였다 — 그물이
    #   *"루브릭 조항이 앵커다"* 를 재는 척하고 **접두어의 존재**를 재고 있었다.
    #   축자 문면으로 양쪽을 따로 잠근다.
    rubric = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING")
    assert rubric.required_elements[0] in texts, "required_elements 가 앵커에 없다"
    assert rubric.misconception_conditions[0] in texts, "misconception_conditions 가 앵커에 없다"

    item = next(i for i in __import__("app.templates", fromlist=["get"]).get("ELS").items)
    assert any(item.cue in text for text in texts), "템플릿 cue 가 앵커에 없다"


def test_gaps_are_sorted_least_covered_first() -> None:
    """사람이 위에서부터 읽는다 — 가장 안 덮인 것이 먼저다."""
    scores = [g["best_overlap"] for g in _post(_doc())["gaps"]]
    assert scores == sorted(scores)


def test_a_sentence_an_anchor_covers_is_not_a_gap() -> None:
    """★ 루브릭 조항을 그대로 담은 문장은 사각이 아니다 — 합성으로 잰다.

    실물에만 기대면 「덮은 것을 걸러낸다」를 **직접** 재지 못한다(사각으로 안 나온 것이
    덮여서인지 짧아서인지 모른다).
    """
    from app import rubrics
    clause = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING").required_elements[0]
    sentence = f"{clause}. 이 문장은 그 조항을 그대로 담고 있다."
    doc = ParsedDocument.model_validate({
        "document_id": "synth", "product_type": "ELS", "parser_version": "0.0.0",
        "pages": [{"page": 1, "text": sentence}],
    })
    assert not coveragegap.find_gaps(doc, "ELS"), f"덮인 문장이 사각으로 나왔다: {clause!r}"

    unrelated = "오늘 점심은 김치찌개였고 국물이 조금 짰다는 이야기를 적어 둔다."
    other = ParsedDocument.model_validate({
        "document_id": "synth", "product_type": "ELS", "parser_version": "0.0.0",
        "pages": [{"page": 1, "text": unrelated}],
    })
    assert coveragegap.find_gaps(other, "ELS"), "❗양성 대조 — 아무 문장도 사각이 아니면 위가 무의미하다"


def test_short_fragments_are_not_sentences() -> None:
    """표 셀 조각·머리글이 목록을 채우면 사람이 안 본다 (이 층의 실패 모드)."""
    doc = ParsedDocument.model_validate({
        "document_id": "synth", "product_type": "ELS", "parser_version": "0.0.0",
        "pages": [{"page": 1, "text": "45%\n\n85%\n\n" + "가" * coveragegap.MIN_SENTENCE_CHARS}],
    })
    assert len(coveragegap.sentences(doc)) == 1


# ── 임계값이 둘인 이유 ──────────────────────────────────────────────────────
def test_the_two_thresholds_stay_opposite() -> None:
    """★ `COVERED_MIN`(0.25) < `ALREADY_COVERED`(0.45) — **목적이 반대다.**

    여기는 *"이미 누가 덮고 있다"* 를 넓게 봐야 목록이 조용하고, `rubricgen` 은
    *"이건 새 조항이다"* 를 좁게 봐야 후보가 진짜 새것이다. 합치면 **커버리지가 사각이라
    부른 것을 생성기가 기존으로 보고 버려 두 도구가 서로를 무력화한다.**
    """
    assert coveragegap.COVERED_MIN < rubricgen.ALREADY_COVERED


# ── 안 하는 것 ──────────────────────────────────────────────────────────────
def test_this_layer_calls_no_llm() -> None:
    """★ 결정론적이다 — 쿼터를 안 쓰고 스텁 없이 테스트된다.

    `#476` 은 검색·리랭킹으로 조항을 뽑지만 이 층은 `textsim` 뿐이다. LLM 이 들어오면
    같은 문서가 회차마다 다른 사각을 내고, 그러면 **사람이 승인한 목록을 재현할 수 없다.**
    """
    src = (Path(__file__).resolve().parents[1] / "app" / "coveragegap.py").read_text("utf-8")
    body = "\n".join(l for l in src.splitlines() if not l.lstrip().startswith(("#", '"')))
    for forbidden in ("complete_json", "LlmClient", "client.embed", "retrieval."):
        assert forbidden not in body, f"이 층이 LLM 을 부른다: {forbidden}"
    assert "textsim.containment" in body, "양성 대조 — 대조를 실제로 하고 있다"


def test_it_does_not_guess_importance_or_item_id() -> None:
    """★ `importance`·`item_id` 를 **안 낸다** — 규범이다.

    결정 10.1 이 `importance` 에 **근거**를 요구하고(*"중요해 보인다로 required 를 늘리면
    루브릭 분모가 커진다"*), `item_id` 는 ADR-006 정본을 따른다. `#476` 이 `u1_requires` 를
    안 내는 것과 같은 규율이다.
    """
    from app.schemas import CoverageGap
    fields = set(CoverageGap.model_fields)
    assert not (fields & {"importance", "item_id", "name", "cue"}), fields


def test_an_unknown_product_type_is_422() -> None:
    with TestClient(app) as c:
        r = c.post("/internal/template/gaps",
                   json={"parsed_document": _doc().model_dump(mode="json"),
                         "product_type": "NO-SUCH-PRODUCT"})
    assert r.status_code == 422


# ── 두 벌 금지 (`#486` 과 같은 규율) ─────────────────────────────────────────
#
# `#476` 에서 도구를 복사해 모듈을 만들었다가 `#486` 으로 걷었다. **같은 실수를 여기서
# 반복하지 않는다** — 두 벌이면 갈렸을 때 증상이 조용하다(도구로 본 사각과 화면이 받는
# 사각이 달라지는데 둘 다 그럴듯하다).
TOOL = Path(__file__).resolve().parents[1] / "tools" / "find_coverage_gaps.py"


def test_the_tool_does_not_hold_a_second_implementation() -> None:
    import ast

    src = TOOL.read_text(encoding="utf-8")
    # ❗**산문을 빼고 «코드» 만 본다.** 첫 판이 `"containment"` 를 찾다가 **모듈 docstring** 에
    #   걸렸다 — 주석만 걷고 docstring 은 안 걷어서다. `#493` 의 `unlink` ↔ `unlinked_until`
    #   과 같은 모양이다: 그물이 자기 파일의 «설명» 을 물었다.
    tree = ast.parse(src)
    if tree.body and isinstance(tree.body[0], ast.Expr) and isinstance(tree.body[0].value, ast.Constant):
        tree.body = tree.body[1:]
    body = ast.unparse(tree)

    for name in ("COVERED_MIN =", "MIN_SENTENCE_CHARS", "_SENTENCE_END", "textsim."):
        assert f"{name} 0" not in body and f"{name} 2" not in body, name
    assert "def anchors(" not in body and "def sentences(" not in body, (
        "도구가 계산을 다시 갖는다 — app.coveragegap 를 쓴다"
    )
    assert "containment" not in body, "도구가 겹침을 따로 잰다"

    # ❗양성 대조 — 「없다」만 재면 도구가 통째로 비어도 통과한다 (`#396` 자리).
    assert "coveragegap.find_gaps(" in body
    assert "coveragegap.anchors(" in body
    assert "coveragegap.sentences(" in body


def test_the_tool_reuses_the_module_threshold() -> None:
    """도구의 `COVERED_MIN` 이 모듈에서 온다 — 값을 손으로 적으면 갈린다."""
    import sys
    sys.path.insert(0, str(TOOL.parent))
    import find_coverage_gaps
    assert find_coverage_gaps.COVERED_MIN is coveragegap.COVERED_MIN
