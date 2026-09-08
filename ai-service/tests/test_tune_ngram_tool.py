"""`tools/tune_ngram_threshold.py` 가 **실물 매처와 같은 답**을 내는지 (`#543` ①).

## 왜 이 파일이 있나

`scoring_thresholds.yaml` 의 `ngram_match.why` 가 *"실제 매처를 돌려도 51건 중 등급을
바꾼 횟수가 0"* 이라고 단정한다. 그 문장을 만든 도구가 **매처의 사본**이면 그 단정은
도구로 재현되지 않는다 — 그리고 그게 `#529` 가 규탄한 결함과 같은 모양이다.

사본을 지우는 것만으로는 부족하다. **다음 사람이 또 만든다.** 그래서 도구의 답과
프로덕션 경로(`match` → `apply_misconception_floor`)의 답을 매번 대조한다.

❗**LLM 을 안 부른다.** 3단계 극성 게이트는 이 대조에서 빠져 있고, 게이트는 후보를
빼기만 하므로 양쪽 모두 **상한**을 잰다 — 둘이 같은 상한을 내는지가 여기서 잴 것이다.
"""
from __future__ import annotations

import importlib.util
import pathlib

import pytest

from app import misconception, rubrics, scoring
from app.schemas import Evidence, Grade, Judgment

ROOT = pathlib.Path(__file__).resolve().parents[1]


def _tool():
    path = ROOT / "tools" / "tune_ngram_threshold.py"
    spec = importlib.util.spec_from_file_location("tune_ngram_threshold", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def tool():
    return _tool()


@pytest.fixture(scope="module")
def loaded(tool):
    corpus, consensus, model = tool.load()
    keys = [k for k in sorted(consensus) if k in corpus]
    return corpus, consensus, model, keys


def _floor_fires(corpus: dict, key: tuple[str, str]) -> bool:
    """**프로덕션 경로 그대로.** 도구를 안 쓴다 — 그게 이 대조의 요점이다."""
    record = corpus[key]
    rubric = rubrics.get(record["item_id"])
    matched = misconception.match(record["utterance"], record["product_type"])
    before = Judgment(
        item_id=record["item_id"], grade=Grade.U1, confidence=0.9,
        evidence=Evidence(utterance_quote=record["utterance"][:20], rubric_clause="c"),
        reason="(대조용)",
    )
    after = scoring.apply_misconception_floor(before, matched, rubric)
    return after.grade != before.grade


def test_the_tool_agrees_with_the_production_floor(tool, loaded) -> None:
    """★ **도구의 점수와 floor 의 발동이 같은 답이어야 한다.**

    도구가 내는 것은 «related 유형의 최고 포함도» 이고, floor 가 하는 것은 «그 점수가
    문턱을 넘으면 U4 로 올린다» 다. 둘이 갈리면 `why` 의 단정이 거짓이 된다.
    """
    corpus, _, _, keys = loaded
    disagreements = []
    for key in keys:
        score, _, _ = tool.best_related(corpus, key)
        expected = score >= misconception.NGRAM_THRESHOLD
        if _floor_fires(corpus, key) != expected:
            disagreements.append(f"{key} 도구 {score:.3f} · floor {not expected}")
    assert not disagreements, (
        "도구와 프로덕션 경로가 갈렸다 — 도구가 매처의 사본이 됐다:\n  "
        + "\n  ".join(disagreements)
    )


def test_the_agreement_is_not_vacuous(tool, loaded, monkeypatch) -> None:
    """★ **현행 문턱에서는 아무것도 안 걸린다** — 그래서 위 대조는 「둘 다 거짓」만 잰다.

    ❗그 상태로 두면 사본이 다시 생겨도 위 단정이 초록이다(`wilson(0,0)` 과 같은 모양).
    문턱을 내려 **실제로 발동하는 모집단**을 만들고 거기서 대조한다.
    """
    corpus, _, _, keys = loaded

    monkeypatch.setattr(misconception, "NGRAM_THRESHOLD", 0.30)
    fired = [k for k in keys if _floor_fires(corpus, k)]
    assert fired, "0.30 에서도 발동이 0건이다 — 이 대조가 아무것도 안 재고 있다"

    for key in fired:
        score, _, _ = tool.best_related(corpus, key)
        assert score >= 0.30, f"{key}: floor 는 울었는데 도구 점수가 {score:.3f} 다"


def test_the_tool_does_not_reimplement_the_matcher(tool) -> None:
    """★ **사본이 다시 생기면 빨강.**

    `best_related` 가 `match()` 를 부르지 않고 패턴을 직접 순회하면 이 단정이 운다.
    지우는 것만으로는 다음 사람이 또 만든다 — `#543` 이 그 이유로 열린 이슈다.
    """
    import inspect

    source = inspect.getsource(tool.best_related)
    assert "misconception.match(" in source, "실물 매처를 안 부른다 — 사본이 돌아왔다"
    for copied in ("_containment", "for pattern in", "library()"):
        assert copied not in source, f"매처를 직접 순회한다: {copied!r}"


def test_the_related_filter_comes_from_the_rubric(tool, loaded) -> None:
    """`related` 는 floor 가 보는 것과 **같은 출처**여야 한다.

    floor 는 `rubric.related_misconceptions` 를 본다(`scoring.py:862`). 도구가 다른
    자리에서 목록을 만들면 두 규칙이 갈리고, 갈린 뒤에는 어느 쪽이 참인지 알 수 없다.
    """
    corpus, _, _, keys = loaded
    key = keys[0]
    item_id = corpus[key]["item_id"]
    declared = set(rubrics.get(item_id).related_misconceptions)

    _, type_id, _ = tool.best_related(corpus, key)
    if type_id is not None:
        assert type_id in declared, f"{type_id} 가 루브릭 related 밖이다"


# ── 조건 줄이 이 측정이 읽은 행만 말한다 (#543 ⓐⓑⓒⓓ) ──────────────────────────
def test_provenance_covers_only_the_rows_this_run_read(tool, loaded) -> None:
    """★ 조회한 행의 판만 찍는다 — 파일 전체가 아니다.

    안 쓴 행이 다른 판이면 «이 측정이 무엇으로 잰 값인가» 가 거짓이 된다.
    """
    _, _, model, keys = loaded
    line = tool.provenance(model, keys[:1])

    only = model[keys[0]]["prompt_version"]
    assert only in line
    others = {row["prompt_version"] for row in model.values()} - {only}
    for other in others:
        assert other not in line, f"안 읽은 행의 판이 실렸다: {other}"


def test_provenance_names_the_model_too(tool, loaded) -> None:
    """판만 적으면 «어느 모델의 v3 인가» 가 빠진다 — `#266` 이 모델을 통째로 옮겼다."""
    _, _, model, keys = loaded
    line = tool.provenance(model, keys)
    assert model[keys[0]]["model"] in line, line


def test_a_missing_prompt_version_stops_the_run(tool) -> None:
    """★ **무르게 실패하지 않는다.** 조용히 빠지면 「판이 없는 파일」과 구별이 안 된다."""
    model = {("s", "i"): {"grade": "U1", "model": "m"}}      # prompt_version 없음
    with pytest.raises(SystemExit) as caught:
        tool.provenance(model, [("s", "i")])
    assert "prompt_version" in str(caught.value)


def test_versions_sort_by_number_not_by_text(tool) -> None:
    """`v10` 이 `v2` 앞에 오면 조건 줄이 최신 판을 가운데 묻는다."""
    assert tool._natural_sorted({"F-SCR-001_v10", "F-SCR-001_v2", "F-SCR-001_v9"}) == [
        "F-SCR-001_v2", "F-SCR-001_v9", "F-SCR-001_v10"
    ]
