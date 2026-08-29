"""`/internal/*` 공유 시크릿. 소유: 윤지석 (결정 10.4 · 이슈 #41 ③)

**네트워크 격리가 1차 방어이고 이건 2차다.** compose network 설정 실수 한 번으로 전체가
열리는 구조를 피한다.

왜 필요한가: `ai-service` 는 브라우저가 직접 부르지 않고 Spring 만 부른다는 전제 위에 P3 가
서 있다 — *"고객 텍스트가 나가는 유일한 경로는 `PiiGateway.mask()` → `AiServiceClient`"*.
`:8100` 이 퍼블릭 서브넷에 뜨면 **그 유일한 경로가 유일하지 않게 된다.** 입구 PII 재검사는
마스킹 누락을 막는 방어선이지 접근 통제가 아니다.
"""
from __future__ import annotations

import logging

import pytest
from fastapi.testclient import TestClient

from app import config, main

TOKEN = "s3cr3t-demo-token"
PROBE = {"text": "은행에서 파는 거니까 원금은 지켜지는 거죠?", "product_type": "ELS"}


@pytest.fixture(autouse=True)
def _clear():
    config.settings.cache_clear()
    yield
    config.settings.cache_clear()


@pytest.fixture
def authed(monkeypatch):
    monkeypatch.setenv(config.INTERNAL_TOKEN_ENV, TOKEN)
    config.settings.cache_clear()
    return TestClient(main.app)


def test_internal_route_rejects_missing_header(authed):
    r = authed.post("/internal/misconception", json=PROBE)
    assert r.status_code == 401
    assert main.INTERNAL_TOKEN_HEADER in r.json()["detail"]


def test_internal_route_rejects_wrong_token(authed):
    r = authed.post("/internal/misconception", json=PROBE,
                    headers={main.INTERNAL_TOKEN_HEADER: "wrong"})
    assert r.status_code == 401


def test_internal_route_accepts_correct_token(authed):
    r = authed.post("/internal/misconception", json=PROBE,
                    headers={main.INTERNAL_TOKEN_HEADER: TOKEN})
    assert r.status_code == 200
    assert r.json()["matches"], "인증은 통과했는데 판정이 안 나왔다"


def test_healthz_is_reachable_without_a_token(authed):
    """헬스체크가 토큰을 알아야 하면 오케스트레이터가 시크릿을 들고 있어야 한다 —
    시크릿이 사는 곳을 늘리지 않는다."""
    r = authed.get("/healthz")
    assert r.status_code == 200
    assert r.json()["internal_auth"] == "enabled"


def test_healthz_never_leaks_the_token(authed):
    """LLM 키를 안 내는 것과 같은 규칙 — 켜졌는지만 낸다."""
    body = authed.get("/healthz").text
    assert TOKEN not in body


def test_auth_is_off_by_default_but_reported(monkeypatch):
    """목 개발 중이라 기본은 꺼짐이다. **꺼진 것이 조용하면 안 된다.**"""
    monkeypatch.delenv(config.INTERNAL_TOKEN_ENV, raising=False)
    config.settings.cache_clear()
    client = TestClient(main.app)
    assert client.post("/internal/misconception", json=PROBE).status_code == 200
    assert client.get("/healthz").json()["internal_auth"] == "disabled"


def test_startup_warns_when_auth_is_off(monkeypatch, caplog):
    monkeypatch.delenv(config.INTERNAL_TOKEN_ENV, raising=False)
    monkeypatch.delenv(config.REQUIRE_INTERNAL_AUTH_ENV, raising=False)
    config.settings.cache_clear()
    with caplog.at_level(logging.WARNING, logger="app.main"):
        main._check_internal_auth()
    assert any("무인증" in r.message for r in caplog.records), caplog.text


def test_startup_fails_when_auth_is_required_but_missing(monkeypatch):
    """배포에서 무인증으로 뜨는 것을 막는다 — 경고가 아니라 기동 실패다."""
    monkeypatch.delenv(config.INTERNAL_TOKEN_ENV, raising=False)
    monkeypatch.setenv(config.REQUIRE_INTERNAL_AUTH_ENV, "1")
    config.settings.cache_clear()
    with pytest.raises(RuntimeError, match=config.INTERNAL_TOKEN_ENV):
        main._check_internal_auth()


@pytest.mark.parametrize("value,expected", [
    ("1", True), ("true", True), ("YES", True), ("on", True),
    ("0", False), ("false", False), ("", False), ("아무거나", False),
])
def test_require_flag_reads_falsy_strings_as_false(monkeypatch, value, expected):
    """★ `bool(os.getenv(...))` 로 두면 `=0` 이 **참**이 된다 — 끄려고 적은 값이 켜는 결과."""
    monkeypatch.setenv(config.REQUIRE_INTERNAL_AUTH_ENV, value)
    config.settings.cache_clear()
    assert config.settings().require_internal_auth is expected


def test_non_ascii_token_is_rejected_at_configuration(monkeypatch):
    """★ 실측으로 잡은 것 — 한글 토큰을 넣으면 401 이 아니라 **500** 이었다.

    `hmac.compare_digest` 가 비ASCII 문자열에 `TypeError` 를 낸다. 그리고 HTTP 헤더 값은
    규격상 ASCII 라, 비ASCII 토큰은 전송 중 값이 달라져 *"맞게 넣었는데 401"* 이 된다.
    설정 시점에 터뜨려 그 경로를 아예 없앤다.
    """
    monkeypatch.setenv(config.INTERNAL_TOKEN_ENV, "한글토큰")
    config.settings.cache_clear()
    with pytest.raises(ValueError, match="ASCII"):
        config.settings()


def test_header_comparison_survives_non_ascii_bytes(monkeypatch):
    """헤더 값은 통제 밖이다 — 이상한 바이트 하나가 401 이 아니라 500 을 만들면 안 된다.

    HTTP 클라이언트(httpx)는 비ASCII 헤더를 아예 못 보내지만, 헤더는 전송 계층에서 **바이트**
    이고 서버가 latin-1 로 디코드한다. curl 이나 악의적 클라이언트는 임의 바이트를 실을 수
    있으므로 ASGI 층에서 직접 넣어 확인한다.

    이 경로가 실측으로 500 을 냈다 — `hmac.compare_digest` 가 비ASCII 문자열에 `TypeError`
    를 낸다. 바이트 비교로 바꿔 닫았다.
    """
    import asyncio

    monkeypatch.setenv(config.INTERNAL_TOKEN_ENV, TOKEN)
    config.settings.cache_clear()

    sent: list[dict] = []

    async def receive():
        return {"type": "http.request", "body": b"{}", "more_body": False}

    async def send(message):
        sent.append(message)

    async def never_called(scope, receive, send):
        raise AssertionError("인증을 통과했다 — 틀린 토큰인데")

    middleware = main.InternalAuthMiddleware(never_called)
    scope = {
        "type": "http",
        "path": "/internal/misconception",
        "method": "POST",
        "headers": [(main.INTERNAL_TOKEN_HEADER.encode(), b"caf\xe9")],   # latin-1 é
    }
    asyncio.run(middleware(scope, receive, send))
    assert sent[0]["status"] == 401, f"500 이면 비교가 터진 것이다: {sent[0]}"


def test_auth_runs_before_pii_guard(authed):
    """인증되지 않은 요청의 본문을 읽고 PII 검사할 이유가 없다.

    PII 가 든 본문을 토큰 없이 보내면 **401** 이어야 한다 — 422(PII 거부)면 미들웨어 순서가
    뒤집힌 것이고, 그건 인증 없는 호출자에게 "이 본문에 PII 가 있다"를 알려주는 것이다.
    """
    r = authed.post("/internal/misconception",
                    json={"text": "제 주민번호는 900101-1234567 입니다", "product_type": "ELS"})
    assert r.status_code == 401, "PII 검사가 인증보다 먼저 돌았다"


# ── 헤더명이 server 와 같은가 (#200 리뷰) ────────────────────────────────────
#: 헤더명이 **두 벌**이고 문자열로만 묶여 있다. `AiServiceClient` 주석이 *"#198 과 문자열이
#: 같아야 한다"* 로 그 사실을 적어 뒀는데, **적어둔 것과 대조하는 것은 다르다**
#: (`ErrorCodeContractTest` 가 세 벌을 대조하는 이유가 그것이다).
#:
#: 어긋났을 때가 문제다.
#:
#:     토큰 있음(배포)   틀린 헤더명 → 401 → 모든 판정이 502 → 데모가 죽는다.  시끄럽다
#:     토큰 없음(로컬)   인증을 아예 안 본다 → 헤더명이 뭐든 통과.  **아무 일도 안 난다**
#:
#: 팀 전체가 토큰 없이 개발하므로 **로컬·CI 에서는 절대 안 보이고 배포에서 처음 드러난다.**
#: 대칭 설계(토큰 비면 양쪽 다 끔)가 옳은 판단인데, 그 대가로 불일치를 가려주는 구간이 생겼다.
#:
#: `server/` 는 강희진 파일이라 **읽기만** 한다 — `test_survey_contract.py` 가
#: `web/src/lib/survey.ts` 를 읽어 축 매핑과 대조하는 방식 그대로다(`#113`).
_JAVA_CLIENT = "server/src/main/java/com/sphinxfin/sphinx/core/aiservice/AiServiceClient.java"


def _java_client_source() -> str:
    from pathlib import Path

    path = Path(__file__).resolve().parents[2] / _JAVA_CLIENT
    if not path.is_file():
        pytest.fail(
            f"Java 클라이언트를 못 찾았다: {path}. 옮겨졌으면 이 경로를 갱신한다 — "
            "조용히 skip 하면 대조가 없어지는데 초록으로 보인다"
        )
    return path.read_text(encoding="utf-8")


def _java_header_constant() -> str | None:
    """Java 쪽 헤더명 상수. **보내는 쪽(`#200`)이 아직 머지 전이면 없다.**

    파일이 없으면 `fail` 이지만 상수가 없으면 `None` 이다 — 둘은 다르다. 앞은 경로가 낡은
    것이고, 뒤는 **아직 안 온 것**이다. 상대 PR 이 머지되는 순간 자동으로 대조가 켜진다.
    """
    import re

    found = re.findall(r'INTERNAL_TOKEN_HEADER\s*=\s*"([^"]+)"', _java_client_source())
    return found[0] if found else None


def test_header_name_matches_the_java_client():
    """★ 양쪽 상수가 문자 그대로 같아야 배포에서 인증이 성립한다.

    보내는 쪽(`#200`)이 머지되기 전에는 대조할 상대가 없다. **`#200` 이 머지되는 순간
    자동으로 켜진다** — 그때까지 skip 이지만 사유가 명시되므로 조용하지 않다.
    """
    header = _java_header_constant()
    if header is None:
        pytest.skip("보내는 쪽(#200)이 아직 머지 전 — 머지되면 이 대조가 자동으로 켜진다")
    assert header == main.INTERNAL_TOKEN_HEADER, (
        f"헤더명이 갈렸다 — server={header!r} · ai-service={main.INTERNAL_TOKEN_HEADER!r}. "
        "토큰이 없는 로컬에서는 이 불일치가 안 드러나고 배포에서 401 로 처음 보인다"
    )


def test_env_var_name_matches_the_java_config():
    """토큰이 같은 환경변수에서 와야 배포에서 **한 값**을 주입할 수 있다."""
    if _java_header_constant() is None:
        pytest.skip("보내는 쪽(#200)이 아직 머지 전")
    assert config.INTERNAL_TOKEN_ENV in _java_client_source(), (
        f"{config.INTERNAL_TOKEN_ENV} 가 Java 쪽에 없다 — 양쪽이 다른 변수를 읽으면 "
        "배포에서 한쪽만 채워지고 그 상태가 401 로만 드러난다"
    )
