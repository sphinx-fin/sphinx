"""**검토가 안 끝난 기준으로 채점하고 있다** — 그 사실을 못박는다. 소유: 윤지석 (이슈 #609 ①)

## 무엇이 문제인가

`status: confirmed` 는 *"근거자료 검토를 마쳤다"* 는 뜻으로 써 왔고 `Rubric.is_draft` 가 그
판별자다. **그런데 그 값을 읽는 코드가 채점 경로에 하나도 없다.**

    선언   app/rubrics.py  Rubric.is_draft
    호출   tools/find_coverage_gaps.py 하나 — app/ 안에서는 0건

그래서 draft 루브릭이 confirmed 와 **똑같이** 채점한다. `#475` 의 ⓐ 가 「승인 → draft 로
커밋 → 나중에 confirmed」 흐름인데, **그 흐름의 첫 단계가 이미 운영 채점에 들어가 있다.**

## 이 파일이 잠그는 것

`#284` 의 `test_enforcement_gap` 과 같은 방식이다 — 계산할 수 없는 것은 안 재고, **사실인
것만 전수로 고정해서 나빠지면 잡히게** 한다.

    ① draft 인 채로 채점되는 루브릭을 전수로 고정한다
    ② 채점 경로가 그 값을 여전히 안 본다는 것을 못박는다 — 보게 되는 순간 빨개진다

②가 「못 박기」다. `#613` 의 문맥 단정과 같은 모양이고, `xfail` 을 쓰지 않는 이유도 같다
(`no_skip.py` — `test_misconception_library_sources.py` 의 선례).
"""
from __future__ import annotations

import logging
from unittest.mock import patch

from app import rubrics

# ❗모듈 수준에서 임포트한다 — `test_enforcement_gap.py` 머리말의 caplog 함정과 같다.
from app.main import _log_draft_rubrics

#: 지금 **검토 전인데 채점에 쓰이는** 루브릭. **변액 7종 전부**다 (ELS 10종은 전부 confirmed).
#:
#: ❗「일부가 검토 전」과 「전부가 검토 전」은 화면에서 다른 이야기다(`#617` 리뷰, 정세현).
#: 항목마다 갈리면 배지가 맞고, 상품 전체가 그렇다면 **상품 단위 문면**이 맞을 수 있다 —
#: `#609` ① 의 ⓑ(`Judgment.rubric_status`)를 정할 때 이 차이가 걸린다.
#:
#: 늘면 *"검토 안 끝난 기준이 운영 채점에 더 들어왔다"* 는 뜻이고, 줄면 누가 검토를 마친
#: 것이다 — **둘 다 사건**이라 양방향으로 잡는다.
_DRAFT_IN_SCORING = {
    "VAR-EARLY-SURRENDER-RATIO",
    "VAR-FEE-DEDUCTION",
    "VAR-NOT-BANK-SAVINGS",
    "VAR-PARTIAL-DEPOSIT-INSURANCE",
    "VAR-PERFORMANCE-LINKED",
    "VAR-PRINCIPAL-LOSS",
    "VAR-SURRENDER-BELOW-PREMIUM",
}


def test_the_draft_set_is_exactly_what_we_measured() -> None:
    """★ 전수로 고정한다 — 채점은 계속 성공하므로 다른 어떤 테스트도 이걸 말해 주지 않는다."""
    drafts = set(rubrics.drafts_in_scoring())
    assert drafts == _DRAFT_IN_SCORING, (
        f"새로 draft: {sorted(drafts - _DRAFT_IN_SCORING)} · "
        f"확정됨(목록에서 빼라): {sorted(_DRAFT_IN_SCORING - drafts)}"
    )


def test_the_split_is_by_product_not_by_item() -> None:
    """★ **상품별 구성을 못박는다** — 「일부가 검토 전」과 「전부가 검토 전」은 다른 이야기다.

    ❗내가 위 목록 주석에 *"변액 10종 중 7종"* 이라고 적었었다. **틀렸다**(`#617` 리뷰,
    정세현) — ELS 10 · 변액 7 인데 둘을 뒤섞었고, 실제는 **변액 7종 전부**다. 숫자를 주석에만
    적으면 그런 식으로 낡는다. 그래서 그 문장을 여기서 **검사로 만든다.**

        ELS      10종 · draft 0종      데모가 도는 상품이다
        변액       7종 · draft 7종      전부다

    이 구별이 `#609` ① 의 ⓑ 설계에 걸린다 — 항목마다 갈리면 배지가 맞고, 상품 전체가
    그렇다면 **상품 단위 문면**이 맞을 수 있다. 그리고 `_DRAFT_IN_SCORING` 집합만으로는
    이 사실이 안 잡힌다: 변액 루브릭이 confirmed 로 하나 늘어도 그 집합은 그대로다.
    """
    by_type: dict[str, list[str]] = {}
    for item_id, r in rubrics.all_rubrics().items():
        by_type.setdefault(r.product_type, []).append(item_id)
    drafts = set(rubrics.drafts_in_scoring())

    els = set(by_type["ELS"])
    var = set(by_type["VARIABLE_INSURANCE"])

    assert not (drafts & els), (
        f"ELS 루브릭이 검토 전이다: {sorted(drafts & els)} — **데모가 ELS 로 돈다**")
    assert drafts == var, (
        f"「변액 전부가 검토 전」이 더 이상 참이 아니다. 검토 끝남: {sorted(var - drafts)} · "
        f"변액 밖에서 새로 draft: {sorted(drafts - var)}. 위 목록 주석과 #617·#609 의 "
        f"문면을 같이 고친다"
    )


def test_scoring_cannot_tell_the_two_apart() -> None:
    """★ **못 박기** — 같은 루브릭을 draft 로 두든 confirmed 로 두든 채점 입력이 **글자까지
    같다.**

    텍스트로 «`is_draft` 호출 0건» 을 세면 주석 한 줄에도 걸린다(실제로 그랬다). 재야 하는
    것은 호출 여부가 아니라 **동작**이다 — 채점 프롬프트가 두 상태에서 갈리는가.

    ❗여기가 빨개지면 **고쳐진 것이다.** 그때 할 일은 이 테스트를 지우고, `#609` ① 의 갈래가
    실제로 무엇을 하는지 재는 단정으로 바꾸는 것이다.

    `xfail` 을 쓰지 않는다 — `no_skip.py` 가 skip 을 실패로 바꾼다
    (`test_misconception_library_sources.py` 의 선례).
    """
    import dataclasses

    from app import scoring
    from app.schemas import Condition, RiskItem, SourceSpan

    draft = rubrics.get("VAR-FEE-DEDUCTION")
    assert draft.is_draft, "이 테스트의 전제가 바뀌었다 — 위 목록도 같이 본다"
    confirmed = dataclasses.replace(draft, status="confirmed")

    item = RiskItem(
        item_id=draft.item_id, product_id="doc-x", name=draft.name, importance="required",
        status="extracted",
        condition=Condition(value_text="원문",
                            source_span=SourceSpan(page=1, start=0, end=2)),
    )
    args = (item, "질문", "답변")
    assert scoring.build_prompt(draft, *args) == scoring.build_prompt(confirmed, *args), (
        "채점 입력이 갈리기 시작했다 — 좋은 소식이다. 이 테스트를 지우고 그 동작을 "
        "재는 단정으로 바꾼다(#609 ①)"
    )


def test_startup_says_we_are_scoring_with_unreviewed_rubrics(caplog) -> None:
    """기동이 그 사실을 말한다 — 지금 이 사실이 보이는 자리가 여기뿐이다."""
    with caplog.at_level(logging.INFO, logger="app.main"):
        _log_draft_rubrics()

    assert logging.WARNING in {r.levelno for r in caplog.records}, (
        "검토 전 기준으로 채점 중인데 경고가 없다")
    text = caplog.text
    assert "7/17" in text, f"비율이 없으면 조용히 늘어도 모른다. 문면: {text}"
    assert "VAR-FEE-DEDUCTION" in text, "항목을 안 적으면 사람이 다시 찾아야 한다"
    assert "#609" in text, "출처가 없으면 이 로그가 왜 있는지 다음 사람이 모른다"


def test_startup_still_speaks_when_nothing_is_draft(caplog) -> None:
    """❗**좋아져도 말한다.** 줄이 사라지면 「검사가 사라진 것」과 구별되지 않는다.

    `#284` 가 링크 17/17 이 된 뒤에도 강제 범위를 계속 찍는 것과 같은 판단이다.
    """
    confirmed = {
        i: r for i, r in rubrics.all_rubrics().items() if not r.is_draft
    }
    with patch.object(rubrics, "_all", lambda: confirmed):
        with caplog.at_level(logging.INFO, logger="app.main"):
            _log_draft_rubrics()

    assert logging.WARNING not in {r.levelno for r in caplog.records}, (
        "draft 가 0 인데 경고가 선다 — 오탐이 상시로 서면 진짜 경고도 같은 줄로 보인다")
    assert "검토 전 기준 0종" in caplog.text, caplog.text
