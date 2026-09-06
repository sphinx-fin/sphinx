"""루브릭 후보 생성기 — 사람 승인 전 단계. 소유: 윤지석

❗**여기서 재는 로직은 이제 `app/rubricgen.py` 에 산다.** `tools/propose_rubric.py` 가
그것을 부르고 표시만 한다 — 예전에는 두 벌이었고, 갈리면 **도구로 본 후보와 관리자
화면이 받는 후보가 달라지는데 둘 다 그럴듯했다.** 파일 이름은 그대로 둔다: 이 테스트가
지키는 성질(요약은 근거가 아니다 · 재진술은 새것이 아니다 · 두 임계값은 목적이 반대다)은
도구가 사람에게 보여 주는 것 그대로이고, **어느 모듈이 계산하느냐는 그 성질과 무관**하다.
`tools/` 쪽에 다시 생성 코드가 생기면 `test_rubric_propose.py` 가 문다.

`tools/propose_rubric.py` 는 **YAML 을 고치지 않는다.** 후보를 찍고 끝난다 —
리랭커가 비결정적이라(`#281`) 자동 반영하면 루브릭이 회차마다 달라지고, 그러면 채점
기준이 흔들린다. 사람이 승인하면 그 비결정성이 후보 단계에서 멈춘다.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))

import propose_rubric as PR  # noqa: E402  — 도구가 임포트되는 것 자체가 대조다

from app import retrieval, rubricgen  # noqa: E402


def _ctx(*texts: str) -> list[retrieval.Chunk]:
    return [retrieval._chunk(1, i * 200, i * 200 + len(t), t) for i, t in enumerate(texts)]


def _verbatim(evidence: str, ctx: list[retrieval.Chunk]) -> bool:
    """근거가 문맥 원문에 실재하는가.

    `rubricgen._locate` 는 **스팬**을 낸다 — 못 찾으면 빈 목록이다. 그 「비어 있음」이
    곧 *"원문에 없다"* 이고, 화면도 같은 값을 받는다(`RubricEvidence.spans`).
    """
    return bool(rubricgen._locate(evidence, ctx))


# ── 근거 대조 — 모델 출력을 그대로 안 믿는다 ─────────────────────────────────
def test_evidence_must_exist_in_the_context_verbatim() -> None:
    """★ 요약한 근거는 근거가 아니다.

    F-EXT-002 가 인용을 원문에서 재계산하는 것과 같은 규칙이다 — 모델이 근거라고 말한
    것을 그대로 믿으면, **사람이 승인할 때 보는 것이 모델의 요약**이 된다.
    """
    ctx = _ctx("특별계정운용보수, 증권거래비용 및 기타비용은 매일 차감되어 기준가격에 반영됩니다.")
    assert _verbatim("특별계정운용보수, 증권거래비용 및 기타비용은 매일 차감되어", ctx)
    assert not _verbatim("펀드 관련 비용이 여러 층에서 빠진다", ctx), (
        "요약을 근거로 통과시켰다"
    )


def test_the_page_marker_i_injected_is_stripped_before_the_check() -> None:
    """★ **내가 만든 노이즈에 그물이 걸리던 것을 막는다.**

    문맥을 `[0] (p12) 본문…` 으로 주니 모델이 `evidence` 에 `(p12) ` 를 그대로 붙여 왔고,
    원문 대조가 **전건 실패**했다(실측 5/5 "지어냄"). 지어낸 것이 아니라 내가 준 접두어다.

    ❗접두어를 안 주는 선택도 있었는데, 사람이 후보를 볼 때 몇 페이지인지가 필요하다.
    그래서 주고, 대조에서 벗긴다.
    """
    ctx = _ctx("월공제액은 보험료에서 차감됩니다.")
    for prefixed in ("(p12) 월공제액은 보험료에서 차감됩니다.",
                     "p12 월공제액은 보험료에서 차감됩니다.",
                     "  ( p 3 ) 월공제액은 보험료에서 차감됩니다."):
        assert _verbatim(prefixed, ctx), f"접두어를 못 벗겼다: {prefixed!r}"


def test_empty_evidence_is_not_verbatim() -> None:
    """빈 근거가 통과하면 `evidence` 를 비워 내는 것이 제일 쉬운 통과 경로가 된다."""
    assert not _verbatim("", _ctx("아무 문면"))
    assert not _verbatim("(p1)", _ctx("아무 문면"))


# ── 새것 판정 ─────────────────────────────────────────────────────────────────
def test_novelty_flags_a_genuinely_new_clause() -> None:
    """★ 검증 케이스 — `VAR-FEE-DEDUCTION` 의 펀드 층 비용.

    `find_coverage_gaps` 가 찾은 사각이고 생성기가 독립적으로 같은 것을 냈다. **그것이
    "새것" 으로 나와야** 사람이 승인 대상으로 본다.
    """
    existing = (
        "월공제액(위험보험료·계약체결비용·계약관리비용·보증비용)이 보험료에서 차감됨",
        "미상각신계약비(해약공제액)가 추가로 차감됨",
    )
    covered, score, _ = rubricgen.overlap_with_existing(
        "특별계정운용보수, 증권거래비용 및 기타 펀드관련 제반비용이 매일 차감되어 "
        "기준가격에 반영된다는 것", existing)
    assert not covered, f"펀드 층 비용을 기존과 같다고 봤다 (겹침 {score:.2f})"


def test_novelty_recognizes_a_restatement_of_an_existing_clause() -> None:
    """기존 조항을 말만 바꾼 것은 새것이 아니다 — 목록이 길면 사람이 안 본다."""
    existing = ("월공제액(위험보험료·계약체결비용·계약관리비용·보증비용)이 보험료에서 차감됨",)
    covered, score, near = rubricgen.overlap_with_existing(
        "월공제액은 위험보험료·계약체결비용·계약관리비용 및 보증비용 등을 포함한다", existing)
    assert covered, f"재진술을 새것으로 봤다 (겹침 {score:.2f})"
    assert near == existing[0]


def test_novelty_threshold_is_stricter_than_the_coverage_tool() -> None:
    """★ 두 임계값이 **목적이 반대라서** 다르다.

        find_coverage_gaps.COVERED_MIN = 0.25   "이미 누가 덮고 있다" 를 넓게 본다
        rubricgen.ALREADY_COVERED      = 0.45   "이건 새 조항이다" 를 좁게 본다

    전자는 목록이 시끄러운 것이 실패 모드이고, 후자는 **새것을 기존으로 보고 놓치는 것**이
    실패 모드다. 한 상수로 합치려는 시도를 여기서 막는다 — `numerics` 의 세 정규화와
    같은 결이다.
    """
    from find_coverage_gaps import COVERED_MIN

    assert rubricgen.ALREADY_COVERED > COVERED_MIN, (
        "새것 판정이 커버리지 판정보다 느슨하면, 커버리지가 사각이라 부른 것을 생성기가 "
        "기존으로 보고 버린다 — 두 도구가 서로를 무력화한다"
    )
