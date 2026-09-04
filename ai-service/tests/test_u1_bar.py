"""U1 문턱이 **파일마다 적혀 있고 프롬프트까지 간다**. 소유: 윤지석 (F-SCR-001)

## 왜 생겼나

`u1_requires` 가 없던 동안 U1↔U2 는 *"필수 요소를 자기 말로 설명했다"* 라는 **홀리스틱
판단**이었고, 프롬프트의 *"애매하면 U2"* 는 기준이 아니라 **동점 처리 규칙**이었다.

F-CMN-003 실측(`#357`)이 그 대가를 숫자로 보여 준다.

    사람 ↔ 사람 상한   QWK 0.769 · 일치율 72.9%      ← 같은 루브릭을 든 둘이 27% 에서 갈렸다
    불일치 19건 중      U1↔U2 가 9건 (47%)            ← 리포트 1절 혼동행렬
    모델 ↔ 사람        0.706 / 0.682  (상한의 92% / 89%)

**모델이 천장을 못 넘은 게 아니라 정답이 정해져 있지 않았다.** 모델을 고쳐도 0.769 위로는
의미가 없다 — 먼저 사람끼리 갈리는 자리를 줄여야 한다.

## 이 파일이 잡는 것

    (가)  17개 루브릭이 전부 문턱을 **적었다**            — 기본값으로 안 떨어진다
    (나)  문턱이 요소 수와 어긋나지 않는다                 — 1 ≤ bar ≤ len
    (다)  프롬프트가 그 값을 **실제로 읽는다**             — 안 읽으면 아무 일도 안 난다

(다)가 제일 중요하다. `#310` 이 `product_type` 에서 잡은 것이 정확히 *"아무도 안 읽는
필드는 조용히 낡는다"* 였다.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

from app import rubrics, scoring
from app.schemas import Condition, RiskItem, SourceSpan

RUBRIC_DIR = Path(rubrics.__file__).resolve().parent / "rubrics"


@pytest.fixture(autouse=True)
def _isolate_rubric_cache():
    """❗**실패한 테스트가 캐시를 남기면 뒤가 통째로 오염된다.**

    아래 두 테스트가 `RUBRIC_DIR` 을 tmp 로 갈아 끼우는데, 본문 안에서 `cache_clear()` 를
    부르면 **그 줄에 닿기 전에 실패했을 때** tmp 루브릭이 캐시에 남는다. 실제로 그랬다 —
    변이 하나를 걸었더니 원인은 5건인데 **40건이 빨개졌다**(`#TODO` 이 PR 작업 중 실측).

    원인 수와 빨간 수가 다르면 진단이 한 칸 더 걸린다. 정리를 fixture 로 옮긴다.
    """
    rubrics._all.cache_clear()
    yield
    rubrics._all.cache_clear()


def _item(rubric: rubrics.Rubric) -> RiskItem:
    return RiskItem(
        item_id=rubric.item_id, product_id="p", name=rubric.name,
        importance="required", status="extracted",
        condition=Condition(value_text="조건 원문",
                            source_span=SourceSpan(page=1, start=0, end=4)),
    )


# ── (가) 전부 적었다 ─────────────────────────────────────────────────────────
def test_every_rubric_states_its_bar() -> None:
    """★ 공회전 방지 겸 전수 — 루브릭이 하나도 없으면 아래가 아무것도 안 잰다."""
    all_r = rubrics.all_rubrics()
    assert all_r, "루브릭을 하나도 못 읽었다"
    assert all(isinstance(r.u1_requires, int) for r in all_r.values())


@pytest.mark.parametrize("path", sorted(RUBRIC_DIR.glob("*.yaml")), ids=lambda p: p.stem)
def test_the_yaml_file_itself_names_the_bar(path: Path) -> None:
    """★ **파일 문면에** 적혀 있다 — 루브릭은 공개 의무 대상이라 읽는 사람이 봐야 한다.

    로더가 값을 만들어 낼 수 있으면 파일만 봐서는 문턱을 모른다.
    """
    assert re.search(r"^u1_requires:\s*\d+\s*$", path.read_text(encoding="utf-8"), re.M), (
        f"{path.name}: u1_requires 줄이 없다 — 필수 요소 중 몇 개를 충족해야 U1 인지 "
        "파일이 스스로 말해야 한다"
    )


def test_a_missing_bar_is_refused_at_load(tmp_path, monkeypatch) -> None:
    """★ 안 적으면 **로딩 시점에** 터진다. 조용한 기본값이 없다.

    `product_type` 이 세운 규약과 같다(`#310`) — *"안 적으면 기본값"* 이 참이 되는 자리를
    남기지 않는다. 남기면 이 필드가 생기기 전 상태가 파일마다 숨는다.
    """
    (tmp_path / "X.yaml").write_text(
        "item_id: X\nproduct_type: ELS\nrequired_elements:\n  - 가\n  - 나\n",
        encoding="utf-8")
    monkeypatch.setattr(rubrics, "RUBRIC_DIR", tmp_path)
    with pytest.raises(ValueError, match="u1_requires"):
        rubrics.all_rubrics()


@pytest.mark.parametrize("bad", ["0", "3", "true", "'2'"])
def test_a_bar_outside_the_element_count_is_refused(tmp_path, monkeypatch, bad) -> None:
    """★ 요소가 2개인데 3을 적거나 0을 적으면 도달 불가능한 등급이 생긴다.

    `true` 는 파이썬에서 `1` 로 통하므로 따로 막는다 — `isinstance(True, int)` 가 참이다.
    """
    (tmp_path / "X.yaml").write_text(
        f"item_id: X\nproduct_type: ELS\nrequired_elements:\n  - 가\n  - 나\n"
        f"u1_requires: {bad}\n", encoding="utf-8")
    monkeypatch.setattr(rubrics, "RUBRIC_DIR", tmp_path)
    with pytest.raises(ValueError, match="u1_requires"):
        rubrics.all_rubrics()


# ── (나) 요소 수와 어긋나지 않는다 ───────────────────────────────────────────
@pytest.mark.parametrize("item_id", sorted(rubrics.all_rubrics()))
def test_the_bar_fits_the_elements(item_id: str) -> None:
    r = rubrics.all_rubrics()[item_id]
    assert 1 <= r.u1_requires <= len(r.required_elements), (
        f"{item_id}: 문턱 {r.u1_requires} · 요소 {len(r.required_elements)}개")


# ── (다) 프롬프트가 실제로 읽는다 ────────────────────────────────────────────
@pytest.mark.parametrize("item_id", sorted(rubrics.all_rubrics()))
def test_the_prompt_carries_the_bar(item_id: str) -> None:
    """★ **값이 프롬프트 문면에 실린다.** 안 실리면 모델은 문턱을 모르고 예전과 같다."""
    r = rubrics.all_rubrics()[item_id]
    prompt = scoring.build_prompt(r, _item(r), "질문", "답변")
    line = next((l for l in prompt.splitlines() if "U1 문턱" in l), None)
    assert line is not None, f"{item_id}: 프롬프트에 U1 문턱 줄이 없다"
    assert re.search(rf"{len(r.required_elements)}개 중 {r.u1_requires}개", line), (
        f"{item_id}: 문턱 줄이 루브릭 값과 다르다 — {line!r}")


def test_the_bar_line_moves_with_the_rubric() -> None:
    """★ 문면이 **상수가 아니라 루브릭에서** 나온다 — 박아 두면 값을 바꿔도 옛말을 한다.

    형제 검사와 같은 방식이다(`test_the_warning_names_the_policy_it_read`).
    """
    a = rubrics.get("ELS-NO-LISTING")                 # 2/2
    b = rubrics.get("VAR-PARTIAL-DEPOSIT-INSURANCE")  # 1/2
    pa = scoring.build_prompt(a, _item(a), "q", "a")
    pb = scoring.build_prompt(b, _item(b), "q", "a")
    assert "2개 중 2개" in pa and "2개 중 1개" in pb, "두 문턱이 같은 문면으로 나온다"


def test_the_instruction_and_the_value_sit_in_their_own_sections() -> None:
    """★ **지시는 system, 값은 user** — 둘 중 하나만 있으면 문턱이 작동하지 않는다.

    지시(*"세어서 정한다"*)가 없으면 문턱 줄은 **떠 있는 숫자**가 되고, 값이 없으면
    지시가 가리킬 것이 없다. 자리를 갈라 재는 이유는 한쪽만 옮겨도 잡히게 하려는 것이다.
    """
    system, template = scoring._prompt_sections()
    assert "세어서 정한다" in system, "등급 정의(system)에 세라는 지시가 있어야 한다"
    assert "U1 문턱" in system, "등급 정의가 문턱을 이름으로 가리켜야 한다"
    assert "{u1_requires}" in template, "user 템플릿이 루브릭 값을 받아야 한다"
    assert "{element_count}" in template
