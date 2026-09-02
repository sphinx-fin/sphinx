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

from app import config, llm_client
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
                # `finish_reason` 을 실물처럼 담는다 — 프로바이더는 항상 보낸다.
                # 더미가 빠뜨리면 잘림 검사(#280)가 테스트에서만 안 도는 상태가 된다.
                choice = type("C", (), {"message": message, "finish_reason": "stop"})()
                return type("R", (), {"choices": [choice], "usage": None})()

        class _Chat:
            completions = _Completions()

        return type("Client", (), {"chat": _Chat()})()


def _stub_client(monkeypatch, *, content: str, finish_reason: str,
                 completion_tokens: int | None = None,
                 reasoning_tokens: int | None = None, **env: str):
    """응답 모양을 그대로 흉내내는 클라이언트. 잘림 검사(#280)용."""
    cfg = _settings(monkeypatch, LLM_API_KEY="test-key", **env)
    client = llm_client.LlmClient(cfg)

    detail = (type("D", (), {"reasoning_tokens": reasoning_tokens})()
              if reasoning_tokens is not None else None)
    usage = (type("U", (), {"completion_tokens": completion_tokens,
                            "completion_tokens_details": detail})()
             if completion_tokens is not None else None)

    class _Completions:
        def create(self, **kwargs):
            message = type("M", (), {"content": content})()
            choice = type("C", (), {"message": message, "finish_reason": finish_reason})()
            return type("R", (), {"choices": [choice], "usage": usage})()

    class _Chat:
        completions = _Completions()

    monkeypatch.setattr(client, "_openai", lambda: type("Client", (), {"chat": _Chat()})())
    return client


def _send(monkeypatch, caller_body: dict | None = None,
          seed: int | None = None, **env: str) -> dict:
    cfg = _settings(monkeypatch, LLM_API_KEY="test-key", **env)
    rec = _Recorder(cfg)
    rec.send(prompt="안녕하세요", extra_body=caller_body, seed=seed)
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


# ── 재현성: seed 고정과 잘림 구분 (이슈 #280) ─────────────────────────────────
#
# 같은 문서·같은 모델·같은 코드로 두 번 돌렸을 때 결과가 달랐다 — 추출 11/13 ↔ 13/13,
# P4 가드가 한 번은 터지고 한 번은 통과(#229 실측). 아무것도 고정하지 않고 있었다.
#
# ❗`temperature` 로는 못 잡는다. 정책 모델이 거부한다(실측):
#     temperature=0 → 400 "does not support 0 with this model. Only the default (1)..."
# 그래서 `seed` 로만 잡는다.
def test_seed_default_is_pinned(monkeypatch):
    kwargs = _send(monkeypatch)
    assert kwargs["seed"] == config.DEFAULT_SEED, "seed 를 안 보내면 실행마다 결과가 달라진다"


def test_seed_can_be_overridden(monkeypatch):
    kwargs = _send(monkeypatch, LLM_SEED="7")
    assert kwargs["seed"] == 7


def test_empty_seed_turns_it_off(monkeypatch):
    """❗빈 값과 미설정을 가른다 — 튜닝에서 표본을 여러 개 보려면 *끄는* 방법이 필요하다.

    `os.getenv(...) or DEFAULT` 로 쓰면 빈 값이 기본값으로 되살아나 끌 수 없다.
    `#198` 의 `_truthy` 가 `=0` 을 True 로 만들지 않게 한 것과 같은 결이다.
    """
    kwargs = _send(monkeypatch, LLM_SEED="")
    assert "seed" not in kwargs


def test_unparsable_seed_falls_back_and_warns(monkeypatch, caplog):
    """숫자가 아니면 조용히 끄지 않는다 — 끄면 재현성이 사라진 것을 아무도 모른다."""
    with caplog.at_level(logging.WARNING, logger="app.config"):
        kwargs = _send(monkeypatch, LLM_SEED="abc")
    assert kwargs["seed"] == config.DEFAULT_SEED
    assert any("LLM_SEED" in r.getMessage() for r in caplog.records), (
        "경고 없이 기본값으로 돌아가면 안 된다"
    )


def test_caller_seed_beats_the_setting(monkeypatch):
    """★ 호출자가 준 seed 가 설정을 이긴다.

    재판정(`scoring.MAX_SCORING_ATTEMPTS`)이 시도마다 다른 seed 를 줘야 하기 때문이다 —
    고정 seed 로 같은 프롬프트를 다시 물으면 같은 답이 와서 **재판정 자체가 무력해진다.**

    ## 왜 여기에도 두나 — `test_scoring` 만으로는 안 잡힌다

    `test_retry_varies_the_seed` 는 `SequenceLlm` 스텁을 쓴다. 그 스텁은
    `complete_json` 을 통째로 덮어써서 **`send()` 까지 오지 않는다.** 그래서 우선순위를
    되돌려도(설정이 호출자를 덮어쓰게) 그쪽은 초록이었다 — 변이 역검증에서 실제로 그랬다.
    호출자 값이 실제 요청에 실리는 것은 `send()` 에 닿는 여기서만 확인된다.
    """
    kwargs = _send(monkeypatch, seed=999)
    assert kwargs["seed"] == 999, kwargs.get("seed")
    assert kwargs["seed"] != config.DEFAULT_SEED


def test_caller_seed_none_falls_back_to_the_setting(monkeypatch):
    """seed 를 안 넘긴 호출은 설정값을 그대로 쓴다 — 대부분의 경로가 이쪽이다."""
    assert _send(monkeypatch)["seed"] == config.DEFAULT_SEED


def test_caller_seed_cannot_revive_a_disabled_seed(monkeypatch):
    """끈 상태(`LLM_SEED=`)에서 호출자가 `None` 을 주면 여전히 안 보낸다.

    `scoring._attempt_seed()` 가 그 경우 `None` 을 주도록 돼 있고, 여기서 되살아나면
    **끈 것을 코드가 되살리는** 것이 된다.
    """
    assert "seed" not in _send(monkeypatch, seed=None, LLM_SEED="")


def test_truncated_response_is_its_own_error(monkeypatch):
    """`finish_reason == "length"` 는 빈 응답이 아니라 **잘림**이다.

    추론 모델은 상한을 추론 토큰으로 먼저 소진하므로 `content` 가 빈 채로 length 가 온다.
    예전에는 그것이 `"LLM 빈 응답"` 으로 나왔고, 그러면 *"모델이 이상한 답을 했다"* 로
    읽힌다 — 실물은 *"우리가 상한을 너무 낮게 줬다"* 이고 고칠 곳이 반대편이다.

    그리고 잘림은 **재시도가 의미 없다** — 같은 입력이면 또 잘린다.
    """
    client = _stub_client(monkeypatch, content="", finish_reason="length",
                          completion_tokens=300, reasoning_tokens=300)
    with pytest.raises(llm_client.LlmTruncated) as exc:
        client.send(prompt="안녕하세요")
    assert "잘렸다" in str(exc.value)
    assert "추론=300" in str(exc.value), "무엇이 상한을 먹었는지 문면에 남아야 한다"


def test_empty_response_that_is_not_truncation_stays_distinct(monkeypatch):
    """잘림이 아닌 빈 응답은 그대로 LlmError — 두 원인을 뭉치지 않는다."""
    client = _stub_client(monkeypatch, content="", finish_reason="stop")
    with pytest.raises(llm_client.LlmError) as exc:
        client.send(prompt="안녕하세요")
    assert not isinstance(exc.value, llm_client.LlmTruncated)
    assert "finish_reason='stop'" in str(exc.value)
