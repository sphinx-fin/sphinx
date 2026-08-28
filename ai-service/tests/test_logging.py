"""로그 설정. 소유: 윤지석

PR #113·#114 리뷰(정세현)에서 걸린 것을 고정한다. 두 PR 이 *"빈도를 로그로 본다"* 를
근거로 관측을 약속했는데 레포에 `basicConfig`·`dictConfig`·`setLevel` 이 하나도 없었다 —
**약속한 관측 경로가 아예 없었다.**

이건 조용한 실패의 한 형태다. 코드는 로그를 남기려 하고, 아무도 그것을 못 본다. 예외도
나지 않고 테스트도 통과한다(`caplog` 은 레벨을 직접 올려서 잡으므로 실제 실행과 다르다).
그래서 여기서는 `caplog` 대신 **실제 핸들러가 붙었는지**를 본다.
"""
from __future__ import annotations

import logging

import pytest

from app import config


@pytest.fixture(autouse=True)
def _restore_logger():
    logger = logging.getLogger(config.APP_LOGGER)
    before = (logger.level, list(logger.handlers), logger.propagate)
    config.settings.cache_clear()
    yield
    logger.setLevel(before[0])
    logger.handlers[:] = before[1]
    logger.propagate = before[2]
    config.settings.cache_clear()


def test_default_level_shows_our_observation_logs():
    """기본이 WARNING 이면 `log.info` 관측이 하나도 안 찍힌다 — 그게 원래 상태였다."""
    assert config.DEFAULT_LOG_LEVEL == "INFO"
    config.configure_logging()
    assert logging.getLogger(config.APP_LOGGER).isEnabledFor(logging.INFO)


def _our_handlers():
    """우리가 붙인 핸들러만. pytest 가 `app` 로거에 캡처 핸들러를 넷 붙인다."""
    return [h for h in logging.getLogger(config.APP_LOGGER).handlers
            if getattr(h, config.HANDLER_MARK, False)]


def test_app_logger_has_a_handler():
    """레벨만 올려도 핸들러가 없으면 아무 데도 안 나간다."""
    config.configure_logging()
    assert _our_handlers()


def test_handler_is_added_even_when_others_are_present():
    """★ `if not logger.handlers` 로 판단하면 남의 핸들러가 있을 때 우리 것을 안 붙인다.

    pytest 환경이 그 상황을 실제로 만든다(캡처 핸들러 4개). 운영에서는 uvicorn 이
    `uvicorn.*` 만 설정해 안 겹치지만, 조용히 안 붙는 경로를 남겨두지 않는다.
    """
    logger = logging.getLogger(config.APP_LOGGER)
    logger.handlers[:] = [logging.NullHandler()]     # 남의 핸들러만 있는 상태
    config.configure_logging()
    assert _our_handlers(), "남의 핸들러가 있으면 우리 것이 안 붙었다"


def test_repeated_calls_do_not_stack_handlers():
    """한 줄이 두 번 나오면 빈도 관측이 정확히 두 배로 틀린다."""
    for _ in range(3):
        config.configure_logging()
    assert len(_our_handlers()) == 1


def test_propagate_is_off_so_lines_are_not_doubled():
    """root 에도 핸들러가 있으면(uvicorn) 같은 줄이 두 번 찍힌다."""
    config.configure_logging()
    assert logging.getLogger(config.APP_LOGGER).propagate is False


def test_root_logger_is_not_touched():
    """uvicorn 이 root 에 자기 핸들러를 붙인다. 덮으면 access 로그 형식이 바뀐다."""
    root = logging.getLogger()
    before = (root.level, len(root.handlers))
    config.configure_logging()
    assert (root.level, len(root.handlers)) == before


def test_level_is_injectable(monkeypatch):
    monkeypatch.setenv(config.LOG_LEVEL_ENV, "warning")     # 소문자도 받는다
    config.settings.cache_clear()
    assert config.configure_logging() == "WARNING"
    assert not logging.getLogger(config.APP_LOGGER).isEnabledFor(logging.INFO)


def test_unknown_level_falls_back_to_the_default(monkeypatch):
    """`SPHINX_LOG_LEVEL=INFOO` 가 관측을 끄면 안 된다 — 기본값으로 내려간다.

    경고가 실제로 사람 눈에 닿는지는 `test_fallback_warning_goes_through_our_handler` 가
    본다. **`caplog` 으로 보지 않는 이유**: `propagate=False` 라 root 캡처 핸들러에 안 가고,
    그건 이 PR 이 의도한 동작이다(같은 줄이 두 번 찍히면 빈도 관측이 두 배로 틀린다).
    `caplog` 이 잡히는지로 검사하면 **그 의도와 반대 방향을 고정**하게 된다.
    """
    monkeypatch.setenv(config.LOG_LEVEL_ENV, "INFOO")
    config.settings.cache_clear()
    assert config.configure_logging() == config.DEFAULT_LOG_LEVEL


def test_entrypoint_configures_logging_on_import():
    """lifespan 이 아니라 모듈 수준이다 — `TestClient(app)` 을 context manager 로 쓰지
    않으면 lifespan 이 돌지 않고, 그러면 테스트에서 관측이 꺼진다."""
    from pathlib import Path

    source = (Path(__file__).resolve().parents[1] / "app" / "main.py").read_text(encoding="utf-8")
    assert "configure_logging()" in source


def test_healthz_reports_the_applied_level_not_the_requested_one(monkeypatch):
    """★ 오타를 낸 사람이 `/healthz` 에서 자기 오타를 되돌려받으면 안 된다.

    `WARNING` 을 치려다 `WARNIG` 를 쳤다면 실제로는 INFO 로 도는데 화면은 *"WARNIG 로
    설정됨"* 이다 — 관측이 켜져 있는지 묻는 유일한 창구가 사실이 아닌 값을 말한다
    (PR #121 리뷰, 정세현 실측).

    **기대값을 하드코딩한다.** 이전 버전은 `settings().log_level` 과 대조해서 양쪽이 같은
    출처였고, 그래서 둘이 같이 틀려도 초록이었다.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    monkeypatch.setenv(config.LOG_LEVEL_ENV, "INFOO")
    config.settings.cache_clear()
    config.configure_logging()

    body = TestClient(app).get("/healthz").json()
    assert body["log_level"] == "INFO", "적용되지 않은 값을 보고한다"
    assert body["log_level_requested"] == "INFOO", "요청 원본도 함께 보여야 진단이 된다"


def test_healthz_level_matches_the_logger_not_the_setting(monkeypatch):
    """출처가 로거여야 한다 — 실제로 필터링에 쓰이는 값과 어긋날 수 없다."""
    import logging as _logging

    from fastapi.testclient import TestClient

    from app.main import app

    monkeypatch.setenv(config.LOG_LEVEL_ENV, "ERROR")
    config.settings.cache_clear()
    config.configure_logging()
    assert TestClient(app).get("/healthz").json()["log_level"] == "ERROR"
    assert _logging.getLogger(config.APP_LOGGER).getEffectiveLevel() == _logging.ERROR


def test_numeric_levels_are_accepted(monkeypatch):
    """파이썬 로깅은 숫자 레벨을 정식으로 받는다. 안 받으면 오타와 같은 경고를 받고,
    그러면 그 경고가 두 가지 뜻을 갖는다(PR #121 리뷰)."""
    import logging as _logging

    monkeypatch.setenv(config.LOG_LEVEL_ENV, "10")
    config.settings.cache_clear()
    config.configure_logging()
    assert _logging.getLogger(config.APP_LOGGER).getEffectiveLevel() == _logging.DEBUG


def test_out_of_range_numeric_level_falls_back(monkeypatch):
    """`0` 은 NOTSET 이라 상속으로 새고, 범위를 벗어난 값도 오타와 같다."""
    for bad in ("0", "999"):
        monkeypatch.setenv(config.LOG_LEVEL_ENV, bad)
        config.settings.cache_clear()
        assert config.configure_logging() == config.DEFAULT_LOG_LEVEL, bad


def test_notset_by_name_is_refused_like_the_number(monkeypatch):
    """★ 숫자 `0` 을 막고 이름 `NOTSET` 을 통과시키면 같은 상태로 가는 문이 남는다.

    `getattr(logging, "NOTSET")` 이 `0` 이고 `isinstance(0, int)` 가 참이라 유효한 레벨로
    받아졌다(PR #121 리뷰 2차, 정세현 실측). 결과가 오타보다 나쁘다 — `app` 로거 레벨이
    `0` 이면 `getEffectiveLevel()` 이 root 로 상속돼 `WARNING` 이 되고 `log.info` 관측이
    전부 꺼지는데 **경고가 하나도 안 난다.**

    그래서 두 가지를 같이 본다: 폴백했는가(반환값), 그리고 **실제로 관측이 살아 있는가**
    (실효 레벨). 반환값만 보면 상속이 남아도 초록일 수 있다.
    """
    monkeypatch.setenv(config.LOG_LEVEL_ENV, "NOTSET")
    config.settings.cache_clear()
    assert config.configure_logging() == config.DEFAULT_LOG_LEVEL

    logger = logging.getLogger(config.APP_LOGGER)
    assert logger.level != logging.NOTSET, "레벨이 0 이면 root 로 상속된다"
    assert logger.getEffectiveLevel() == getattr(logging, config.DEFAULT_LOG_LEVEL)


def test_fallback_warning_goes_through_our_handler(monkeypatch, capsys):
    """경고를 핸들러 붙이기 전에 내면 `logging.lastResort` 로 나가 포맷이 없다.

    관측을 켜는 함수가 자기 경고를 관측 밖으로 내보내면 안 된다(PR #121 리뷰, 정세현 실측).
    """
    import logging as _logging

    logger = _logging.getLogger(config.APP_LOGGER)
    logger.handlers[:] = []
    monkeypatch.setenv(config.LOG_LEVEL_ENV, "INFOO")
    config.settings.cache_clear()
    config.configure_logging()

    captured = capsys.readouterr().err
    assert config.LOG_LEVEL_ENV in captured
    assert "WARNING" in captured, f"포맷 없는 lastResort 출력이다: {captured!r}"
