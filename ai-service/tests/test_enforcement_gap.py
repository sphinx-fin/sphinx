"""선언한 오해 중 **강제되는 것이 얼마인지**를 못박는다 (이슈 #284).

## 발단 — 미탐이 실물로 났다

`#279` 에서 `els-0028` 이 `U4 → U2` 로 내려갔다.

    발화   "지수만 안 빠지면 되는 거 아니에요? 회사가 망하는 건 별개 문제고요."
    항목   ELS-ISSUER-CREDIT-RISK

강희진·정세현이 **독립으로** 루브릭 문면을 대조해서 *"새 모델이 틀렸다"* 로 읽었다. 근거가
그 루브릭에 이미 있었다.

    misconception_conditions:
      - 기초자산만 오르면 안전하다      ← 발화가 거의 그대로다

**그런데 통과했다.** `apply_misconception_floor` 가 등급을 올리는 근거는
`related_misconceptions`(라이브러리 유형ID)뿐이고, `misconception_conditions` 는
프롬프트에 실리는 문면이라 **강제력이 없다.** 두 목록이 어디서도 대조되지 않았다.

## 이 파일이 잠그는 것

조건 문면 → 유형ID 대응은 **정적으로 계산할 수 없다.** 그래서 두 가지만 한다.

    ① 강제 통로가 아예 없는 루브릭을 전수로 고정한다        (사실이다. 계산이 아니다)
    ② 링크를 가진 루브릭 수를 고정한다                      (조용히 줄지 않게)

`#228` 의 `_CUE_UNREACHABLE` 과 같은 방식이다 — **지금 상태를 드러내고, 나빠지면 잡히게.**
좋아지면 그것도 잡힌다(목록에서 빼라고 말한다).

**예외로 안 만든 이유**는 `app/rubrics.enforcement_gaps` docstring 에 있다 — 유형 추가가
근거 자료를 요구해서(`misconceptions.yaml` 의 `source`) 기동을 막으면 고칠 사람이 확인할
방법도 없어진다.
"""
from __future__ import annotations

import logging
from pathlib import Path
from unittest.mock import patch

import pytest

from app import misconception, rubrics

RUBRIC_DIR = Path(rubrics.__file__).resolve().parent / "rubrics"

# ❗**모듈 수준에서 임포트한다.** `app.main` 을 테스트 함수 안에서 임포트하면
# `configure_logging()` 이 conftest 의 `_caplog_reaches_app_logger` **뒤에** 돌아서,
# fixture 가 `app.propagate` 를 아직 True 로 보고 핸들러를 안 붙인다 — 그 뒤에
# `propagate=False` 가 되면서 `caplog.records` 가 통째로 빈다(실제로 그랬다).
# 로그는 stderr 에 찍히는데 캡처만 안 되므로 **테스트가 거짓으로 실패한다.**
from app.main import _log_enforcement_gap

#: 강제 통로가 **없고 그것이 의도도 아닌** 루브릭. 지금은 없다.
#:
#: 처음에는 여기에 `VAR-PARTIAL-DEPOSIT-INSURANCE` 를 넣고 *"누가 봐도 M02 자리인데 링크가
#: 비어 있다 — 단순 누락으로 보인다"* 고 적었다. **틀렸다**(`#298` 리뷰). 그 파일 머리말이
#: 이유를 이미 적어 두고 있었다 — `#57` 로 M02 가 ELS 전용이 됐고 변액에서 *"예금자보호 되는
#: 줄"* 은 **부분적으로 참**이라 결정론 상향이 **오판**이었다.
#:
#: 그래서 그 항목은 **자기 루브릭 파일의 `unlinked_until`** 이 설명한다(`#284` (c)).
#: **빈 집합이 정상 상태다** —
#: 여기 뭐가 생기면 그건 진짜 사각이고, 의도라면 근거와 함께 그쪽 목록에 넣어야 한다.
_NO_ENFORCEMENT_PATH: set[str] = set()

#: 링크를 가진 루브릭 수 / 전체. 조용히 줄면 잡는다.
#:
#: ❗**이 비율 자체는 결함이 아니다.** 조건 하나에 유형 하나가 대응할 이유가 없고,
#: 라이브러리 9종이 46개 조건을 다 덮을 수도 없다. 못박는 이유는 **아무도 이 숫자를 모르는
#: 상태**가 `#284` 가 드러낸 것이기 때문이다.
_LINKED_RUBRICS = 16
_TOTAL_RUBRICS = 17


def test_the_no_enforcement_set_is_exactly_what_we_measured() -> None:
    """★ 강제 통로가 없는 루브릭을 전수로 고정한다.

    새로 늘면 **오해를 선언만 하고 강제는 안 하는 항목이 늘었다**는 뜻이다. 채점은 계속
    성공하므로 다른 어떤 테스트도 그것을 말해 주지 않는다.
    """
    gaps = set(rubrics.enforcement_gaps())
    assert gaps == _NO_ENFORCEMENT_PATH, (
        f"새로 통로 없음: {sorted(gaps - _NO_ENFORCEMENT_PATH)} · "
        f"해소됨(목록에서 빼라): {sorted(_NO_ENFORCEMENT_PATH - gaps)}"
    )


def test_the_gap_reports_the_conditions_not_just_the_item() -> None:
    """항목ID 만 알려주면 무엇이 강제 안 되는지 사람이 다시 찾아야 한다.

    지금은 진짜 사각이 0 이라 **합성 루브릭으로 잰다.** 실물이 없다고 이 경로를 안 재면
    다음에 사각이 생겼을 때 조건 문면이 빠져 있어도 아무도 모른다.
    """
    from app.rubrics import Rubric

    synthetic = Rubric(
        item_id="SYNTH-NO-LINK", product_type="ELS", name="합성", status="draft",
        required_elements=("가",), u1_requires=1,
        misconception_conditions=("전액 보호된다", "둘"),
        related_misconceptions=(), unlinked_until=None,
    )
    with patch.object(rubrics, "_all", lambda: {"SYNTH-NO-LINK": synthetic}):
        gaps = rubrics.enforcement_gaps()

    assert gaps == {"SYNTH-NO-LINK": ("전액 보호된다", "둘")}, (
        "항목ID 만 담고 조건을 버리면 로그에서 무엇이 강제 안 되는지 알 수 없다"
    )


def test_the_pending_exception_is_not_reported_as_a_gap() -> None:
    """★ `#298` 리뷰가 잡은 것 — 의도된 정정을 사각으로 세지 않는다.

    이 기능이 내는 경고는 지금 이것뿐이었다. **오탐 하나가 기동 로그에 상시로 서면 다음에
    진짜 사각이 생겨도 같은 줄로 보인다.** 그리고 확인받는 쪽이 M02 를 도로 링크할 수 있다 —
    `#57` 이 오판이라고 판정한 상향이 돌아온다.
    """
    assert "VAR-PARTIAL-DEPOSIT-INSURANCE" in rubrics.unlinked_until()
    assert "VAR-PARTIAL-DEPOSIT-INSURANCE" not in rubrics.enforcement_gaps()

    rubric = rubrics.get("VAR-PARTIAL-DEPOSIT-INSURANCE")
    assert rubric.misconception_conditions, "조건이 없으면 이 대조의 전제가 사라진다"
    assert not rubric.related_misconceptions, "링크가 생겼으면 예외 목록에서 빼야 한다"


def test_pending_exceptions_cite_their_reason() -> None:
    """★ 예외 목록을 **손으로 채울 수 없게** 한다.

    각 항목의 루브릭 파일에 그 근거 PR 번호가 실제로 적혀 있는지 대조한다. 없는데 적으면
    이 목록이 *"경고가 시끄러우니 끄는"* 통로가 된다 — `#204` 에서 `units` 선언을 계약
    샘플과 대조한 것과 같은 이유다.
    """
    import re
    from pathlib import Path

    root = Path(rubrics.__file__).resolve().parent / "rubrics"
    pending = rubrics.unlinked_until()
    # ❗**양성 대조** (`#395` 리뷰, 정세현). 목록이 비면 아래 루프가 **0 회 돌고 조용히
    # 통과한다** — 그러면 "예외가 하나도 없다" 와 "이 검사가 죽었다" 가 구분되지 않는다.
    # 기능이 은퇴하면 이 줄에서 실패하고, 그때 **합성으로 계속 지킬지**를 사람이 정한다
    # (아래 `test_the_pending_exception_machinery_works_without_a_real_case` 가 그 자리다).
    assert pending, (
        "unlinked_until 대상이 하나도 없다 — 이 검사가 아무것도 안 재고 있다. "
        "마지막 대상이 은퇴한 것이면 이 테스트를 합성 루브릭 쪽으로 옮긴다")
    for item_id, (why, until) in pending.items():
        assert until.strip(), f"{item_id}: 빼는 조건이 비어 있다 — 그러면 지우는 사건이 안 온다"
        path = root / f"{item_id}.yaml"
        assert path.exists(), f"{item_id}: 루브릭 파일이 없는데 예외 목록에 있다"
        head = path.read_text(encoding="utf-8")
        number = re.search(r"#(\d+)", why)
        assert number, f"{item_id}: 근거 문면에 PR 번호가 없다 — {why!r}"
        assert f"#{number.group(1)}" in head or f"PR {number.group(1)}" in head, (
            f"{item_id}: 루브릭 파일이 {why} 를 근거로 적고 있지 않다. "
            "판단이 파일에 없으면 예외가 아니라 경고를 끈 것이다"
        )


def test_linked_rubric_count_does_not_shrink_quietly() -> None:
    """링크가 줄면 강제 범위가 줄어든 것이다 — 등급은 여전히 나오므로 안 보인다."""
    declared, linked, total = rubrics.enforcement_coverage()
    assert (linked, total) == (_LINKED_RUBRICS, _TOTAL_RUBRICS), (
        f"링크를 가진 루브릭 {linked}/{total} — 목록 기대값은 "
        f"{_LINKED_RUBRICS}/{_TOTAL_RUBRICS} 다. 줄었으면 왜인지 적고, 늘었으면 갱신하라"
    )
    assert declared > linked, (
        "선언된 조건이 링크 수보다 적으면 이 이슈의 전제가 바뀐 것이다 — 문면을 다시 봐라"
    )


def test_the_declared_conditions_outnumber_the_library() -> None:
    """★ `#284` 의 요지 — 선언이 라이브러리보다 훨씬 많다.

    이 부등식이 뒤집히는 날이 오면 (b)(유사도 매칭) 없이도 대부분이 강제된다는 뜻이라
    이 이슈의 성격이 바뀐다. 그때 이 테스트가 알려 준다.
    """
    declared, _, _ = rubrics.enforcement_coverage()
    library_size = len(misconception.library())
    assert declared > library_size * 3, (
        f"선언 {declared}개 · 라이브러리 {library_size}종 — 비율이 크게 바뀌었다"
    )


def test_startup_records_the_pending_exception_as_info(caplog) -> None:
    """의도된 예외는 `INFO` 로 남긴다 — 안 남기면 사각이 0 인 이유를 알 수 없다.

    `#121` 이 넣은 로그 레벨 설정 덕에 `INFO` 도 배포에서 보인다.
    """
    with caplog.at_level(logging.INFO, logger="app.main"):
        _log_enforcement_gap()

    assert logging.WARNING not in {r.levelno for r in caplog.records}, (
        "진짜 사각이 0 인데 경고가 있다 — 오탐이 상시로 서면 진짜 사각도 같은 줄로 보인다"
    )
    text = caplog.text
    assert "VAR-PARTIAL-DEPOSIT-INSURANCE" in text
    assert "아직" in text, "왜 세지 않는지 문면에 없으면 다음 사람이 누락으로 읽는다"
    assert "빼는 조건" in text, (
        "빼는 조건이 로그에 없으면 '영구히 이렇다' 로 읽힌다 — 지우는 사건이 안 온다"
    )
    assert "#284" in text, "출처가 없으면 이 로그가 왜 있는지 다음 사람이 모른다"


def test_startup_warns_when_a_real_gap_appears(caplog) -> None:
    """★ 진짜 사각이 생기면 경고가 선다 — 합성 루브릭으로 그 경로를 실제로 태운다.

    지금 사각이 0 이라 **이 경로가 아무 테스트도 안 타는 상태**가 되기 쉽다. 그러면 다음에
    사각이 생겨도 경고가 안 뜨는 것을 아무도 모른다.
    """
    from app.rubrics import Rubric

    synthetic = Rubric(
        item_id="SYNTH-NO-LINK", product_type="ELS", name="합성", status="draft",
        required_elements=("가",), u1_requires=1,
        misconception_conditions=("전액 보호된다",),
        related_misconceptions=(), unlinked_until=None,
    )
    with patch.object(rubrics, "_all", lambda: {"SYNTH-NO-LINK": synthetic}):
        with caplog.at_level(logging.INFO, logger="app.main"):
            _log_enforcement_gap()

    assert logging.WARNING in {r.levelno for r in caplog.records}, "사각인데 경고가 없다"
    text = caplog.text
    assert "SYNTH-NO-LINK" in text
    assert "전액 보호된다" in text, "조건 문면이 없으면 사람이 다시 찾아야 한다"
    # ❗**의도라면 어디에 적어야 하는지** 문면이 알려줘야 한다. 그 자리가 `#284` (c) 로
    # 옮겨졌다 — 전에는 `rubrics._UNLINKED_UNTIL`(파이썬 하드코딩)이었고 지금은 **루브릭
    # 파일의 `unlinked_until`** 이다. 안내가 옛 자리를 가리키면 사람이 그 dict 를 찾다가
    # 없는 것을 발견한다.
    assert "unlinked_until" in text, (
        "의도라면 어디에 적어야 하는지 문면이 알려줘야 한다 — 루브릭 파일의 unlinked_until"
    )
    assert "_UNLINKED_UNTIL" not in text, (
        "옛 자리(파이썬 하드코딩)를 가리킨다 — #284 (c) 로 루브릭 파일로 옮겼다"
    )


# ── `#298` 리뷰(정세현) C·B — 잡히지 않던 둘 ─────────────────────────────────
def test_startup_actually_calls_it_through_lifespan(caplog) -> None:
    """★ **C — 기동 경로를 실제로 태운다.**

    앞서 이 파일의 기동 테스트가 `_log_enforcement_gap()` 를 **직접 불렀다.** 그러면
    재는 것은 *"이 함수가 부르면 로그를 낸다"* 이고, ★ 로 적어 둔 주장(*"기동 로그에
    남는다"*)은 검증되지 않는다 — `lifespan` 에서 호출을 지워도 전건 초록이었다.

    **이 PR 의 주제가 그것이라 특히 걸린다** — *"선언했는데 강제 안 되는 것이 조용하다"* 를
    드러내려고 만든 기능이 조용히 빠질 수 있는 상태였다.

    `#252` 의 `UnfairSignalLogTest` 가 리스너를 직접 안 부르고 **실제 발행**을 쓴 이유가
    같다(결정 5.31) — *"직접 부르면 구독이 걸려 있는지를 안 재게 되는데, 이 기능이 없던
    이유가 정확히 그것이었다."*

    ❗`TestClient(app)` 를 **컨텍스트로** 써야 `lifespan` 이 돈다. 모듈 수준으로 두면
    안 돈다(`test_measurement_invalid_route.py` 가 그 형태다).
    """
    from fastapi.testclient import TestClient

    from app.main import app

    with caplog.at_level(logging.INFO, logger="app.main"), TestClient(app):
        pass

    text = caplog.text
    assert "F-DET-001 강제 범위" in text, (
        "lifespan 이 _log_enforcement_gap() 를 부르지 않는다 — 배포 기동 로그에 아무것도 "
        "안 남는데 직접 호출 테스트는 초록이다"
    )
    assert "VAR-PARTIAL-DEPOSIT-INSURANCE" in text


def test_a_rubric_without_conditions_is_not_a_gap(monkeypatch) -> None:
    """★ **B — 조건이 0개면 사각이 아니다.**

    `enforcement_gaps()` 의 `misconception_conditions and` 가드가 지키는 구별이다.

        선언했는데 강제 못 한다   ← 경고할 것
        선언한 것 자체가 없다     ← 경고하면 오탐

    지금 조건 0개 루브릭이 없어서 **가드를 빼도 전건 초록이었다.** 조건 없는 루브릭이
    들어오는 날 오탐 경고가 나고, 그건 이 PR 이 없애려던 그 상태다 — 오탐이 상시로 서면
    진짜 사각도 같은 줄로 보인다.
    """
    from app.rubrics import Rubric

    bare = Rubric(
        item_id="SYNTH-NO-CONDITIONS", product_type="ELS", name="합성", status="draft",
        required_elements=("가",), u1_requires=1,
        misconception_conditions=(), related_misconceptions=(), unlinked_until=None,
    )
    monkeypatch.setattr(rubrics, "_all", lambda: {"SYNTH-NO-CONDITIONS": bare})

    assert rubrics.enforcement_gaps() == {}, (
        "조건이 없는 루브릭을 사각으로 세면 오탐이다 — 강제할 것이 애초에 없다"
    )


# ❗**부분적으로 참인 변액 발화** — 링크를 걸어도 되는지의 마지막 조건을 재는 모집단이다.
# 전부 **맞는 말**이라 오해로 잡히면 안 된다. `#57` 이 M02 에서 변액을 뺀 이유가 이것이고,
# `#395` 에서 M11 이 첫 두 조건을 채우고도 여기 걸렸다.
PARTIALLY_TRUE_VARIABLE = (
    "보험료 전액이 예금자보호 대상은 아니라고 하셨죠",
    "전액 보호되는 게 아니라 일부만 된다는 거네요",
    "예금처럼 전액 보호되는 건 아니군요",
    "최저사망지급금하고 특약만 1억까지 보호된다는 뜻이죠",
    "약관에서 정한 것만 예금자보호가 된다는 뜻이네요",
)


def _misfires_on_true_statements(match) -> dict[str, list[str]]:
    """맞는 말을 오해로 잡는 발화를 모은다. `match` 는 런타임 매처를 그대로 받는다.

    ❗**런타임이 쓰는 값을 잰다.** `textsim` 을 직접 불러 계산했을 때 `0.500` 이 나와서
    *"패턴을 줄이면 풀린다"* 로 읽었는데, 실물 매처는 `0.625` 였다(`#395` 리뷰). 유사도를
    여기서 다시 구현하면 그 차이가 그대로 그물의 구멍이 된다.
    """
    out: dict[str, list[str]] = {}
    for utterance in PARTIALLY_TRUE_VARIABLE:
        hits = [m.type_id for m in match(utterance, "VARIABLE_INSURANCE").matches]
        if hits:
            out[utterance] = hits
    return out


def test_unlinked_until_has_not_expired() -> None:
    """★ 만료 조건을 기계가 본다 (`#298` 리뷰 2번 · `#395` 리뷰로 조건이 셋이 됐다).

    `unlinked_until` 이 *"의도적으로 링크 없음"* 이면 **영구히 그렇다고 읽히고 지우는
    사건이 안 온다.** 결정 10.67(OIDC 이름 표기)에서 정리한 그 모양이다.

    ## ❗조건이 셋이다 — 전에는 둘만 봤고 그게 틀렸다

        (1) 변액을 덮는 예금자보호 유형이 생긴다
        (2) 그 유형이 인용 가능한 근거를 가진다              (결정 10.24)
        (3) 그 유형이 **부분적으로 참인 발화와 갈린다**      ← 이게 빠져 있었다

    `#395` 가 (1)(2) 를 채웠는데 **(3) 이 안 됐다.** 맞는 말을 한 고객이
    `ngram 0.65` 로 걸려 `U1 → U4` 가 되는 것을 실측했다 — `apply_misconception_floor`
    는 `stage` 를 안 읽으므로 `ngram` 단계도 그대로 확정이다.

    전 판은 (1) 만 보고 *"항목을 빼고 링크를 걸라"* 고 지시했다. **그대로 따랐으면
    `#57` 이 막은 실패를 유형 ID 만 바꿔 되살렸다.** 트립와이어가 울리는 것과 조치해도
    되는 것은 다르다.
    """
    covering = [
        m.type_id for m in misconception.library()
        if "DEPOSIT" in m.type_id
        and ("VARIABLE_INSURANCE" in m.products or "ALL" in m.products)
    ]
    if not covering:
        # (1) 미충족. `pytest.skip` 을 쓰지 않는다 — `ci.yml` 의 `no_skip.py` 가 skip 을
        # 실패로 바꾸므로, 아직 유형이 없는 **정상 상태**가 CI 빨강이 된다.
        return

    misfires = _misfires_on_true_statements(misconception.match)
    assert misfires, (
        f"{covering} 가 변액을 덮고, 부분적으로 참인 발화 {len(PARTIALLY_TRUE_VARIABLE)} 개에 "
        "오탐이 하나도 없다 — 조건 (1)(2)(3) 이 다 충족됐다. "
        "VAR-PARTIAL-DEPOSIT-INSURANCE.yaml 의 unlinked_until 을 빼고, "
        "그 루브릭에 related_misconceptions 를 걸고, 이 테스트를 지운다"
    )


# ── `#284` (c) — 의도가 루브릭 파일에 있다 ───────────────────────────────────
def test_the_intent_lives_in_the_rubric_file_not_in_code() -> None:
    """★ 링크가 빈 이유가 **파일**에서 온다. 파이썬 하드코딩이 없다.

    ❗전에는 같은 내용이 두 곳에 있었다 — `rubrics._UNLINKED_UNTIL`(기계가 읽는 dict)과
    그 루브릭 YAML 의 **주석**(사람이 읽는 문면). **두 벌이면 갈린다.**

    정세현이 `#284` 에서 그 자리를 짚었다: *"루브릭에 명시하고 로딩 시점에 대조하면
    빈 목록이 「빠뜨림」이 아니라 「의도」라고 파일이 스스로 말한다."*
    """
    assert not hasattr(rubrics, "_UNLINKED_UNTIL"), (
        "파이썬 하드코딩이 남아 있다 — 의도는 루브릭 파일이 말한다(#284 (c))")
    got = rubrics.unlinked_until()
    assert got, "unlinked_until 을 하나도 못 읽었다 — 파일에서 읽는지 확인한다"
    for item_id, (reason, until) in got.items():
        text = (RUBRIC_DIR / f"{item_id}.yaml").read_text(encoding="utf-8")
        assert "unlinked_until:" in text, f"{item_id}: 값이 파일에 없다"
        assert reason and until, f"{item_id}: reason·until 이 비었다"


def test_a_rubric_cannot_claim_both(tmp_path, monkeypatch) -> None:
    """★ 링크가 **있는데** `unlinked_until` 도 있으면 로딩 시점에 터진다.

    둘 다 두면 **어느 쪽이 참인지 알 수 없다.** 한쪽만 보는 검사로는 이 상태가 조용하다.
    """
    (tmp_path / "X.yaml").write_text(
        "item_id: X\nproduct_type: ELS\nrequired_elements:\n  - 가\nu1_requires: 1\n"
        "misconception_conditions:\n  - 나\n"
        "related_misconceptions:\n  - M01-PRINCIPAL-GUARANTEE\n"
        "unlinked_until:\n  reason: 왜\n  until: 언제\n", encoding="utf-8")
    monkeypatch.setattr(rubrics, "RUBRIC_DIR", tmp_path)
    rubrics._all.cache_clear()
    with pytest.raises(ValueError, match="unlinked_until"):
        rubrics.all_rubrics()
    rubrics._all.cache_clear()


@pytest.mark.parametrize("missing", ["reason", "until"])
def test_a_half_written_unlinked_until_is_refused(tmp_path, monkeypatch, missing) -> None:
    """★ `until` 이 없으면 **지우는 사건이 안 온다**(결정 10.67). 둘 다 요구한다."""
    keys = {"reason": "왜", "until": "언제"}
    del keys[missing]
    body = "\n".join(f"  {k}: {v}" for k, v in keys.items())
    (tmp_path / "X.yaml").write_text(
        "item_id: X\nproduct_type: ELS\nrequired_elements:\n  - 가\nu1_requires: 1\n"
        f"misconception_conditions:\n  - 나\nunlinked_until:\n{body}\n", encoding="utf-8")
    monkeypatch.setattr(rubrics, "RUBRIC_DIR", tmp_path)
    rubrics._all.cache_clear()
    with pytest.raises(ValueError, match="unlinked_until"):
        rubrics.all_rubrics()
    rubrics._all.cache_clear()


def test_the_condition_counts_add_up() -> None:
    """★ 조건 단위 셈이 선언 총수와 맞는다 — `#284` 가 물은 *"46개 중 무엇이"*.

    ❗`enforcement_coverage()` 는 **루브릭** 단위라 그 질문에 답하지 못한다. 링크가
    하나라도 있으면 그 루브릭의 조건 전부가 「링크 있음」으로 세어진다.
    """
    declared, _, _ = rubrics.enforcement_coverage()
    enforced, advisory, explained = rubrics.condition_enforcement()
    assert enforced + advisory == declared, (
        f"조건 단위 합 {enforced}+{advisory} ≠ 선언 {declared} — 세는 자리가 갈렸다")
    assert explained == advisory, (
        f"권고만 {advisory}개 중 이유가 적힌 것이 {explained}개 — 나머지는 "
        "unlinked_until 이 없는 채로 조건만 선언돼 있다")


def test_the_startup_log_reports_condition_level(caplog) -> None:
    """★ 기동 로그가 **조건 단위**를 찍는다. 루브릭 단위만 찍으면 그 질문에 답이 없다."""
    import logging
    from app.main import _log_enforcement_gap
    with caplog.at_level(logging.INFO, logger="app.main"):
        _log_enforcement_gap()
    assert "조건 단위" in caplog.text
    assert "권고만" in caplog.text, "「권고만」이 몇 개인지가 이 로그의 요점이다"


# ── 조건 (3) 검사와 예외 기구를 **실물 없이도** 태운다 ────────────────────────
#
# ❗둘 다 「실물이 없으면 경로가 안 돌아 조용히 통과」를 막는 자리다. `#395` 리뷰에서
# `test_pending_exceptions_cite_their_reason` 이 정확히 그 모양이었고(목록이 비면 루프가
# 0 회), 같은 함정이 `test_unlinked_until_has_not_expired` 의 (3) 검사에도 있다 —
# 덮는 유형이 없으면 `return` 으로 빠져서 **(3) 을 재는 코드가 한 번도 안 돈다.**
class _StubMatch:
    def __init__(self, type_ids): self.matches = [_StubHit(t) for t in type_ids]


class _StubHit:
    def __init__(self, type_id): self.type_id = type_id


def test_condition_three_catches_a_type_that_misfires_on_true_statements() -> None:
    """★ 맞는 말을 잡는 유형이면 `_misfires_on_true_statements` 가 반드시 잡는다."""
    def always(_utterance, _product):
        return _StubMatch(["M99-SYNTHETIC"])

    # ❗**모집단부터 잰다.** 이게 없으면 `PARTIALLY_TRUE_VARIABLE` 를 비우는 변조가
    # 전건 초록으로 지나간다 — `{} == set(())` 이라 아래 단정이 참이 된다(실측으로 확인).
    # 그물은 모집단이 맞아야 한다.
    assert len(PARTIALLY_TRUE_VARIABLE) >= 3, (
        "부분적으로 참인 발화가 3 개 미만이다 — 조건 (3) 이 사실상 안 재진다")

    misfires = _misfires_on_true_statements(always)
    assert misfires, "다 잡는 매처인데 오탐이 0 이다 — 검사가 안 돌았다"
    assert set(misfires) == set(PARTIALLY_TRUE_VARIABLE), (
        "부분적으로 참인 발화를 다 잡는 유형인데 오탐으로 안 세었다 — "
        "이 검사가 죽으면 링크해도 되는지를 아무도 안 재게 된다")


def test_condition_three_passes_a_type_that_separates() -> None:
    """★ 갈라내는 유형이면 빈 dict — 그때가 링크를 걸어도 되는 때다.

    이 양성/음성 한 쌍이 있어야 *"오탐이 없다"* 와 *"검사가 안 돈다"* 가 구분된다.
    """
    def never(_utterance, _product):
        return _StubMatch([])

    assert _misfires_on_true_statements(never) == {}, (
        "아무것도 안 잡는 매처인데 오탐을 셌다 — 검사가 거꾸로다")


def test_the_pending_exception_machinery_works_without_a_real_case() -> None:
    """★ `unlinked_until` **기구**는 실물 대상이 0 이어도 살아 있어야 한다.

    `test_pending_exceptions_cite_their_reason` 은 실물 목록을 보고, 그 목록이 비면
    양성 대조에서 실패한다. 그때 *"기능을 같이 은퇴시킬 것인가"* 를 정하게 되는데
    (`#395` 리뷰, 정세현), **판단이 오기 전까지 기구가 조용히 썩지 않게** 여기서 합성으로
    태운다. `enforcement_gaps` 가 이 예외를 사각으로 세지 않는 것도 같이 잰다.
    """
    from app.rubrics import Rubric

    synthetic = Rubric(
        item_id="SYNTH-PENDING", product_type="ELS", name="합성", status="draft",
        required_elements=("가",), u1_requires=1,
        misconception_conditions=("전액 보호된다",),
        related_misconceptions=(),
        unlinked_until=("PR #57 근거", "유형이 생기고 부분적으로 참인 발화와 갈릴 때"),
    )
    with patch.object(rubrics, "_all", lambda: {"SYNTH-PENDING": synthetic}):
        assert rubrics.unlinked_until() == {
            "SYNTH-PENDING": ("PR #57 근거", "유형이 생기고 부분적으로 참인 발화와 갈릴 때")
        }, "파일에서 읽은 값을 그대로 못 돌려준다"
        assert rubrics.enforcement_gaps() == {}, (
            "의도된 예외를 사각으로 세면 진짜 사각이 그 줄에 묻힌다(#298 리뷰)")
