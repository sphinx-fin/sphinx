"""모델 정책과 사고 예산이 **한 곳에서** 정해지고, 실제로 요청에 실린다.

`gemini-3.5-flash-lite` → `gpt-5-mini` 전환에서 나온 파일이다. 전환 자체는 상수 세 개를
고치는 일이지만, 두 가지가 조용히 틀릴 수 있어서 잠근다.

1. **정책 경고 문면이 상수와 갈린다.** 경고가 "flash-lite" 를 하드코딩하고 있었다 —
   정책을 바꿔도 경고는 옛 모델 이름을 말한다. 성능 수치의 출처를 알려고 있는 장치가
   틀린 출처를 말하면 없느니만 못하다.
2. **사고 예산이 요청에서 빠진다.** `reasoning_effort` 를 안 보내면 gpt-5-mini 가
   호출당 사고토큰 1,024개를 태운다(실측 20.3초 → minimal 은 0개 · 2.4초, 등급 동일).
   빠져도 결과가 맞게 나오므로 **테스트로는 안 보이고 요금으로만 보인다.**
"""
from __future__ import annotations

import logging
from typing import Any

import pytest

from app import config
from app.llm_client import LlmClient


@pytest.fixture(autouse=True)
def _clear_settings_cache():
    config.settings.cache_clear()
    yield
    config.settings.cache_clear()


def _settings(monkeypatch, **env: str) -> config.Settings:
    for key in ("LLM_MODEL", config.REASONING_EFFORT_ENV):
        monkeypatch.delenv(key, raising=False)
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    return config.settings()


# ── 정책 ─────────────────────────────────────────────────────────────────────
def test_the_default_model_satisfies_its_own_policy() -> None:
    """기본값이 정책을 어기면 아무 설정 없이 켜기만 해도 경고가 뜬다."""
    assert config.MODEL_POLICY_SUBSTRING in config.DEFAULT_MODEL


def test_off_policy_model_warns(monkeypatch, caplog) -> None:
    with caplog.at_level(logging.WARNING, logger="app.config"):
        s = _settings(monkeypatch, LLM_MODEL="gemini-3.5-flash-lite")
    assert s.llm_model == "gemini-3.5-flash-lite"
    assert "모델 정책 위반" in caplog.text


def test_the_warning_names_the_policy_it_read(monkeypatch, caplog) -> None:
    """★ 경고 문면이 상수에서 나온다 — 하드코딩하면 정책을 바꿔도 옛말을 한다."""
    monkeypatch.setattr(config, "MODEL_POLICY_SUBSTRING", "some-other-model")
    with caplog.at_level(logging.WARNING, logger="app.config"):
        _settings(monkeypatch, LLM_MODEL="gpt-5-mini")
    assert "some-other-model" in caplog.text, caplog.text


def test_on_policy_model_is_silent(monkeypatch, caplog) -> None:
    with caplog.at_level(logging.WARNING, logger="app.config"):
        _settings(monkeypatch, LLM_MODEL=config.DEFAULT_MODEL)
    assert "모델 정책 위반" not in caplog.text


# ── 사고 예산 ─────────────────────────────────────────────────────────────────
class _Recorder(LlmClient):
    """요청 kwargs 만 붙잡는다. 네트워크로 안 나간다."""

    def __init__(self, cfg: config.Settings) -> None:
        super().__init__(cfg)
        self.kwargs: dict[str, Any] = {}

    def _openai(self) -> Any:  # noqa: D102
        recorder = self

        class _Completions:
            def create(self, **kwargs: Any) -> Any:
                recorder.kwargs = kwargs
                message = type("M", (), {"content": '{"ok": true}'})()
                choice = type("C", (), {"message": message})()
                return type("R", (), {"choices": [choice]})()

        class _Chat:
            completions = _Completions()

        return type("Client", (), {"chat": _Chat()})()


def _send(monkeypatch, caller_body: dict | None = None, **env: str) -> dict:
    cfg = _settings(monkeypatch, LLM_API_KEY="test-key", **env)
    rec = _Recorder(cfg)
    rec.send(prompt="안녕하세요", extra_body=caller_body)
    return rec.kwargs


def test_reasoning_effort_rides_on_every_call(monkeypatch) -> None:
    """★ 라우트가 아무것도 안 해도 실린다 — 새 라우트에서 빠지지 않게."""
    kwargs = _send(monkeypatch)
    assert kwargs["extra_body"]["reasoning_effort"] == config.DEFAULT_REASONING_EFFORT


def test_the_caller_wins(monkeypatch) -> None:
    """호출자가 사고 예산을 실험할 수 있어야 한다 — 설정이 덮어쓰면 못 한다."""
    kwargs = _send(monkeypatch, {"reasoning_effort": "high"})
    assert kwargs["extra_body"]["reasoning_effort"] == "high"


def test_other_extra_body_keys_survive(monkeypatch) -> None:
    kwargs = _send(monkeypatch, {"safety_settings": []})
    assert kwargs["extra_body"]["safety_settings"] == []
    assert kwargs["extra_body"]["reasoning_effort"] == config.DEFAULT_REASONING_EFFORT


def test_empty_setting_sends_nothing(monkeypatch) -> None:
    """이 파라미터를 모르는 프로바이더로 옮길 때 **코드가 아니라 설정으로** 끈다."""
    kwargs = _send(monkeypatch, **{config.REASONING_EFFORT_ENV: ""})
    assert "extra_body" not in kwargs, kwargs.get("extra_body")
