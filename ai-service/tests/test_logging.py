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


def test_unknown_level_falls_back_loudly(monkeypatch, caplog):
    """`SPHINX_LOG_LEVEL=INFOO` 같은 오타가 관측을 끄는데 그게 안 보이면 안 된다."""
    monkeypatch.setenv(config.LOG_LEVEL_ENV, "INFOO")
    config.settings.cache_clear()
    with caplog.at_level(logging.WARNING, logger="app.config"):
        assert config.configure_logging() == config.DEFAULT_LOG_LEVEL
    assert any(config.LOG_LEVEL_ENV in r.message for r in caplog.records), caplog.text


def test_entrypoint_configures_logging_on_import():
    """lifespan 이 아니라 모듈 수준이다 — `TestClient(app)` 을 context manager 로 쓰지
    않으면 lifespan 이 돌지 않고, 그러면 테스트에서 관측이 꺼진다."""
    from pathlib import Path

    source = (Path(__file__).resolve().parents[1] / "app" / "main.py").read_text(encoding="utf-8")
    assert "configure_logging()" in source


def test_healthz_reports_the_level():
    """배포 후 "관측이 켜져 있나" 를 물을 수 있어야 한다."""
    from fastapi.testclient import TestClient

    from app.main import app

    assert TestClient(app).get("/healthz").json()["log_level"] == config.settings().log_level
