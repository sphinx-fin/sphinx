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

from app import misconception, rubrics

# ❗**모듈 수준에서 임포트한다.** `app.main` 을 테스트 함수 안에서 임포트하면
# `configure_logging()` 이 conftest 의 `_caplog_reaches_app_logger` **뒤에** 돌아서,
# fixture 가 `app.propagate` 를 아직 True 로 보고 핸들러를 안 붙인다 — 그 뒤에
# `propagate=False` 가 되면서 `caplog.records` 가 통째로 빈다(실제로 그랬다).
# 로그는 stderr 에 찍히는데 캡처만 안 되므로 **테스트가 거짓으로 실패한다.**
from app.main import _log_enforcement_gap

#: 오해 조건을 선언했는데 **라이브러리 링크가 하나도 없는** 루브릭.
#:
#: 이 항목의 조건은 **모델이 놓치면 아무 일도 안 일어난다** — 결정론적 U4 상향이 존재하지
#: 않는다. `VAR-PARTIAL-DEPOSIT-INSURANCE` 의 첫 조건(*"예금처럼 전액 보호된다"*)은
#: 누가 봐도 `M02-DEPOSIT-INSURANCE` 자리인데 링크가 비어 있다 — 단순 누락으로 보이고
#: `#284` 에서 정세현에게 확인 요청했다.
#:
#: **변액 예금자보호는 데모 시나리오에 들어간다.** 그래서 이 한 건은 리허설 전에도 값이 있다.
_NO_ENFORCEMENT_PATH = {
    "VAR-PARTIAL-DEPOSIT-INSURANCE",
}

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
    """항목ID 만 알려주면 무엇이 강제 안 되는지 사람이 다시 찾아야 한다."""
    conditions = rubrics.enforcement_gaps()["VAR-PARTIAL-DEPOSIT-INSURANCE"]
    assert "예금처럼 전액 보호된다" in conditions
    assert len(conditions) == 3


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


def test_startup_warns_about_the_gap(caplog) -> None:
    """★ 기동 로그에 남는다 — 배포된 컨테이너에서 이걸 볼 수 있어야 한다.

    `#121` 이 넣은 로그 레벨 설정 덕에 `INFO` 도 배포에서 보인다. 통로 없음은 `WARNING`
    이고 비율은 `INFO` 다 — 앞은 사실이고 뒤는 판단 재료라 레벨을 가른다.
    """
    with caplog.at_level(logging.INFO, logger="app.main"):
        _log_enforcement_gap()

    levels = {r.levelno for r in caplog.records}
    assert logging.WARNING in levels, "통로 없는 루브릭이 있는데 경고가 없다"
    assert logging.INFO in levels, "비율이 안 남으면 판단 재료가 없다"

    text = caplog.text
    assert "VAR-PARTIAL-DEPOSIT-INSURANCE" in text
    assert "예금처럼 전액 보호된다" in text, "조건 문면이 로그에 없으면 다시 찾아야 한다"
    assert "#284" in text, "출처가 없으면 이 경고가 왜 있는지 다음 사람이 모른다"
