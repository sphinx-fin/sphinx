"""루브릭 후보 생성 (이슈 #474 ① · F-SCR-001 계열). 소유: 윤지석

## 이 파일이 잠그는 것

    ① 제안만 낸다 — **파일을 쓰지 않는다**
    ② 근거에 스팬이 붙고 원문 대조가 성립한다 (P6)
    ③ ❗모델이 베낀 페이지 접두어 `(p8) ` 를 벗긴다 — 안 벗기면 스팬이 전건 0 이다
    ④ `u1_requires` 를 안 낸다 — 문턱은 문서에서 유도할 수 없는 **규범**이다 (#367)
    ⑤ 항목 하나가 죽어도 나머지를 내고, 죽은 것을 `warnings` 로 **노출**한다 (E-EXT-03)
    ⑥ 채점 경로가 이 모듈을 안 지난다 — 지나면 P4 가 순환한다

❗**실제 LLM 을 안 부른다.** 한 번 그래서 65초가 걸렸다(`#207`). 생성기는 스텁으로 대체하고
검색·스팬·대조 로직만 잰다.
"""
from __future__ import annotations

import pytest

from fastapi.testclient import TestClient

from app import rubricgen, textsim
from app.main import app
from app.llm_client import LlmError
from app.schemas import ParsedDocument


PAGE = (
    "⑧ 위 ⑥에 해당하지 않고, 최초기준가격의 45% 미만으로\n"
    "하락한 적이 있는 경우 만기상환금액은 다음과 같습니다.\n"
    "원금 × (만기평가가격/최초기준가격)\n"
    "이 경우 원금 손실이 발생합니다."
)


def _client() -> TestClient:
    """컨텍스트로 안 써도 되는 라우트 검사용 — lifespan 이 필요 없다."""
    return TestClient(app)


def _doc() -> ParsedDocument:
    return ParsedDocument.model_validate({
        "document_id": "d-1", "product_type": "ELS", "parser_version": "t",
        "pages": [{"page": 8, "text": PAGE, "char_count": len(PAGE)}],
        "tables": [], "parse_warnings": [],
    })


class _StubLlm:
    """생성기를 대체한다. `embed` 는 청크 수만큼 0 벡터를 준다."""

    def __init__(self, draft=None, raises: Exception | None = None):
        self._draft, self._raises = draft, raises
        self.calls = 0

    def embed(self, texts, **kwargs):
        return [[0.0, 0.0] for _ in texts]

    def complete_json(self, **kwargs):
        self.calls += 1
        if self._raises is not None:
            raise self._raises
        return self._draft


def _draft(evidence: list[str], required=None, misconception=None):
    return rubricgen._Draft(
        required_elements=required or ["만기평가일 기준 미만이면 손실이 발생한다"],
        misconception_conditions=misconception or [],
        evidence=evidence,
    )


# ── ② ③ 근거 스팬 ──────────────────────────────────────────────────────────────
def test_evidence_gets_a_span_that_resolves_to_the_source() -> None:
    """★ 근거에 스팬이 붙고 `pages[page].text[start:end]` 로 원문에 닿는다 (P6).

    문자열만 주면 승인하는 사람이 *"이게 정말 이 문서에 있나"* 를 눈으로 찾아야 한다.
    """
    quote = "원금 × (만기평가가격/최초기준가격)"
    llm = _StubLlm(_draft([quote]))

    out = rubricgen.propose_one("ELS-MATURITY-LOSS-CONDITION", _doc(), llm)

    assert out.evidence and out.evidence[0].spans, "근거에 스팬이 안 붙었다"
    joined = "".join(PAGE[s.start:s.end] for s in out.evidence[0].spans)
    assert textsim.normalize(quote) in textsim.normalize(joined), (
        "스팬이 가리키는 원문에 근거 문면이 없다 — 스팬이 엉뚱한 곳을 가리킨다"
    )


def test_the_page_prefix_the_prompt_itself_added_is_stripped() -> None:
    """❗**모델이 베끼는 `(p8) ` 는 내가 프롬프트에 넣은 것이다.**

    문맥을 `[0] (p8) 본문…` 으로 주니 모델이 `evidence` 에 그대로 붙여 왔고, 안 벗기면
    원문 대조가 **전건 실패**한다. `tools/propose_rubric.py` 가 실측으로 겪었고
    (5/5 "지어냄"), 이 모듈을 처음 돌렸을 때 **같은 함정을 다시 밟았다**(스팬 0/4).

    지어낸 것과 접두어가 붙은 것은 다르다 — 그물이 그것을 못 가르면 진짜 지어낸 근거를
    찾을 수 없다.
    """
    quote = "이 경우 원금 손실이 발생합니다."
    with_prefix = _StubLlm(_draft([f"(p8) {quote}"]))
    without = _StubLlm(_draft([quote]))

    a = rubricgen.propose_one("ELS-MATURITY-LOSS-CONDITION", _doc(), with_prefix)
    b = rubricgen.propose_one("ELS-MATURITY-LOSS-CONDITION", _doc(), without)

    assert a.evidence[0].spans, "접두어가 붙은 근거의 스팬이 비었다 — 접두어를 안 벗겼다"
    assert [s.model_dump() for s in a.evidence[0].spans] == \
           [s.model_dump() for s in b.evidence[0].spans], "접두어 유무로 스팬이 달라졌다"


def test_a_summarised_evidence_gets_no_span_instead_of_a_made_up_one() -> None:
    """❗요약해 온 근거에는 **스팬을 안 붙인다.**

    없는 근거를 있는 것처럼 만드는 것보다 *"근거가 없다"* 고 말하는 쪽이 안전하다 —
    P4 가 근거 없는 판정을 무효로 두는 것과 같은 방향이다.
    """
    llm = _StubLlm(_draft(["만기에 손실이 날 수 있다는 취지의 설명"]))   # 원문에 없는 요약

    out = rubricgen.propose_one("ELS-MATURITY-LOSS-CONDITION", _doc(), llm)

    assert out.evidence[0].spans == [], "원문에 없는 근거에 스팬이 붙었다"


# ── ④ 문턱을 안 낸다 ───────────────────────────────────────────────────────────
def test_the_proposal_does_not_carry_a_u1_threshold() -> None:
    """★ `u1_requires` 는 문서에서 유도할 수 없는 **규범**이다 (#367).

    `#367` 이 그 필드를 만든 이유가 *"요소 수와 다를 수 있다"* 였고(`VAR-PARTIAL` 은
    요소 2 · 문턱 1), 몇 개면 이해로 볼지는 파는 쪽이 정해서 공개하는 판단이다.
    모델이 채우면 그 판단이 숨는다.
    """
    from app.schemas import RubricProposal

    assert "u1_requires" not in RubricProposal.model_fields, (
        "후보가 U1 문턱을 들고 있다 — 규범을 모델이 정하게 된다"
    )
    assert "u1_requires" not in rubricgen._Draft.model_fields


# ── 기존 조항과의 대조 ─────────────────────────────────────────────────────────
def test_a_candidate_that_subsumes_an_existing_clause_is_marked_covered() -> None:
    """❗**방향이 중요하다** — `containment(기존, 후보)` 다.

    반대로 재면 **긴 후보가 짧은 기존 조항을 통째로 포함해도 점수가 낮다**(후보
    바이그램의 대부분이 기존에 없으니까). 실측에서 기존 루브릭이 있는 항목의
    `already_covered` 가 0건이었다.
    """
    from app import rubrics

    existing = rubrics.get("ELS-MATURITY-LOSS-CONDITION").required_elements[0]

    # ❗**후보 길이가 방향을 가르는 조건이다.** 짧은 후보(기존 + 15자)로는 두 방향이
    # 둘 다 임계 위라(1.00 · 0.59) 뒤집어도 안 물린다 — 처음에 그렇게 썼고 변조가
    # 통과했다. 실제 모델이 내는 길이(160자)에서 갈린다: 1.00 ↔ 0.27.
    #
    # 「그물이 무는가」와 「무엇을 무는가」는 다르다 — 표본을 실물 길이로 맞춰야
    # 이 단정이 방향을 잠근다.
    candidate = (
        "만기평가일까지 어느 기초자산이라도 최초기준가격의 45% 미만으로 하락한 적이 있고, "
        "만기평가일에 최저 기초자산의 만기평가가격이 최초기준가격의 70% 미만이면 "
        f"{existing} 라고 설명할 것. 만기상환금액은 원금×(만기평가가격/최초기준가격)으로 결정된다"
    )
    llm = _StubLlm(_draft([], required=[candidate]))

    out = rubricgen.propose_one("ELS-MATURITY-LOSS-CONDITION", _doc(), llm)

    assert out.already_covered, (
        "기존 조항을 통째로 품은 후보가 새것으로 나왔다 — containment 방향이 뒤집혔는지 본다"
    )
    assert out.has_existing_rubric is True


# ── ⑤ 실패를 노출한다 ──────────────────────────────────────────────────────────
def test_a_failing_item_is_reported_not_hidden() -> None:
    """❗항목 하나가 죽어도 나머지를 내고 **죽은 것을 `warnings` 로 노출**한다.

    조용히 빠지면 사람이 *"이 항목은 후보가 없구나"* 와 *"이 항목이 죽었구나"* 를 못
    가른다 — `E-EXT-03`(추출 실패를 은폐하지 않는다)과 같은 규약이다.
    """
    import app.routes as routes

    calls: list[str] = []
    original = routes.rubricgen.propose_one          # ❗패치 **전에** 잡는다

    def flaky(item_id, doc, client_):
        # 패치 뒤에 `rubricgen.propose_one` 을 부르면 자기 자신이라 재귀한다 —
        # 실제로 그렇게 썼다가 RecursionError 가 502 로 나왔다.
        calls.append(item_id)
        if item_id == "ELS-NO-DEPOSIT-INSURANCE":
            raise LlmError("모델이 죽었다")
        return original(item_id, doc, _StubLlm(_draft(["이 경우 원금 손실이 발생합니다."])))

    routes.rubricgen.propose_one = flaky
    try:
        r = _client().post("/internal/rubric/propose", json={
            "parsed_document": _doc().model_dump(mode="json"),
            "item_ids": ["ELS-MATURITY-LOSS-CONDITION", "ELS-NO-DEPOSIT-INSURANCE"],
        })
    finally:
        routes.rubricgen.propose_one = original

    assert r.status_code == 200
    body = r.json()
    assert [p["item_id"] for p in body["proposals"]] == ["ELS-MATURITY-LOSS-CONDITION"]
    assert any("ELS-NO-DEPOSIT-INSURANCE" in w for w in body["warnings"]), \
        "죽은 항목이 어디에도 안 남았다"
    assert len(calls) == 2, "하나가 죽자 나머지를 안 돌았다"


def test_an_unknown_item_is_a_warning_not_a_crash() -> None:
    """템플릿에 없는 항목을 달라고 하면 경고로 알린다 — 500 이 아니다."""
    r = _client().post("/internal/rubric/propose", json={
        "parsed_document": _doc().model_dump(mode="json"),
        "item_ids": ["NOPE-NOT-AN-ITEM"],
    })
    assert r.status_code == 200
    assert r.json()["proposals"] == []
    assert any("NOPE-NOT-AN-ITEM" in w for w in r.json()["warnings"])


# ── ⑥ 채점이 이 경로를 안 지난다 ───────────────────────────────────────────────
def test_scoring_does_not_import_the_generator() -> None:
    """★ **채점이 루브릭을 만들면 P4 가 순환한다.**

    `verify_rubric_clause_is_published` 는 근거로 적힌 조항이 **공개된 루브릭**에 있는지
    본다. 채점이 그 자리에서 기준을 만들면, 만든 기준과 대조하는 것이라 의무가 형식만
    남는다. 공개 의무(기획서 5절)·재현성도 같은 자리다 — `#358` 에서 검색 스택을 채점
    시점에 안 붙인 이유가 이것이다.

    ❗`retrieval` 까지 같이 본다. 생성기를 안 불러도 검색을 직접 쓰면 같은 일이 된다.
    """
    import pathlib

    src = pathlib.Path(rubricgen.__file__).with_name("scoring.py").read_text("utf-8")
    for banned in ("rubricgen", "retrieval"):
        assert banned not in src, (
            f"scoring.py 가 {banned} 를 쓴다 — 채점 시점에 루브릭을 만들면 "
            "verify_rubric_clause_is_published 가 순환한다 (P4)"
        )
    assert "verify_rubric_clause_is_published" in src, \
        "양성 대조 — 이 파일을 읽고 있는 것이 맞는지"
