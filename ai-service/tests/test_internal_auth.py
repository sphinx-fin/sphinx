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
import re
from pathlib import Path

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


# ── 서버와의 문자열 계약 (#198 에서 뺐다가 #200 머지 후 되살림) ────────────────
#
# 공유 시크릿은 **양쪽이 같은 문자열을 알아야만** 동작한다. 헤더 이름이나 환경변수 이름이
# 한쪽에서 바뀌면 컴파일도 되고 테스트도 통과하는데 **런타임에 401 만 난다** — 그리고
# 그건 두 모듈을 같이 띄우기 전까지 안 드러난다. `#165`(`failure_reason` 422)와 같은 모양이다.
#
# 이 대조를 `#198` 에 넣었다가 뺐었다. 그때는 서버 절반(`#200`)이 아직 안 들어와서 파일이
# 없었고, skip 으로 처리하면 `ci.yml` 이 그것을 실패로 친다(*"러너에서 skip 은 검산이 안
# 돌았다는 뜻"*). `#200` 이 머지됐으므로 되살린다.
#
# ❗**없으면 skip 하지 않고 실패한다.** 상대 파일이 사라진 것 자체가 알아야 할 사건이다.
SERVER_ROOT = Path(__file__).resolve().parents[2] / "server" / "src" / "main"
AI_SERVICE_CLIENT = (
    SERVER_ROOT / "java" / "com" / "sphinxfin" / "sphinx" / "core" / "aiservice" / "AiServiceClient.java"
)
APPLICATION_YML = SERVER_ROOT / "resources" / "application.yml"


def _server_source(path: Path) -> str:
    assert path.exists(), (
        f"서버 파일이 없다: {path}\n"
        "옮겨졌으면 이 경로를 고친다. skip 하지 않는 이유는 이 대조가 안 도는 것을 "
        "초록으로 덮으면 안 되기 때문이다(#198 에서 실제로 그랬다)."
    )
    return path.read_text(encoding="utf-8")


def test_header_name_matches_the_server():
    """Java `INTERNAL_TOKEN_HEADER` 와 이쪽 상수가 **글자 그대로** 같아야 한다.

    한쪽만 고치면 `AiServiceClient` 가 붙인 헤더를 미들웨어가 못 찾아 `/internal/*` 이
    전부 401 이 된다. 인터뷰가 첫 요청에서 죽는다.
    """
    source = _server_source(AI_SERVICE_CLIENT)
    found = re.search(r'INTERNAL_TOKEN_HEADER\s*=\s*"([^"]+)"', source)
    assert found, "서버에서 INTERNAL_TOKEN_HEADER 선언을 못 찾았다 — 이름이 바뀌었나"
    assert found.group(1) == main.INTERNAL_TOKEN_HEADER, (
        f"헤더 이름이 갈렸다: server={found.group(1)!r} ai-service={main.INTERNAL_TOKEN_HEADER!r}"
    )


def test_env_var_name_matches_the_server():
    """`application.yml` 이 읽는 환경변수와 이쪽이 읽는 것이 같아야 한다.

    배포는 **양쪽에 같은 값을 주입**하는 것으로 성립한다(`application.yml` 주석). 이름이
    갈리면 한쪽만 토큰을 받고, 받은 쪽은 헤더를 붙이는데 못 받은 쪽은 인증을 꺼서
    **조용히 무인증으로 돈다** — 401 보다 나쁘다. 그 상태가 정상처럼 보인다.
    """
    source = _server_source(APPLICATION_YML)
    found = re.search(r"internal-token:\s*\$\{([A-Z_][A-Z0-9_]*)", source)
    assert found, "application.yml 에서 internal-token 주입을 못 찾았다"
    assert found.group(1) == config.INTERNAL_TOKEN_ENV, (
        f"환경변수 이름이 갈렸다: server={found.group(1)} ai-service={config.INTERNAL_TOKEN_ENV}"
    )


def test_both_sides_disable_auth_on_an_empty_token():
    """빈 값이면 양쪽 다 끈다 — 대칭이라는 것이 로컬 목 개발의 전제다.

    서버가 *"비어 있으면 헤더를 안 붙인다"* 인데 이쪽이 켜져 있으면 로컬에서 401 이 나고,
    반대면 배포에서 조용히 무인증이 된다. 서버 쪽 조건을 문면으로 확인한다.
    """
    source = _server_source(AI_SERVICE_CLIENT)
    assert re.search(r"internalToken\s*!=\s*null\s*&&\s*!\s*internalToken\.isBlank\(\)", source), (
        "서버가 빈 토큰을 걸러내는 조건을 못 찾았다 — 대칭이 깨졌을 수 있다"
    )


def test_empty_token_disables_auth_on_this_side_too(monkeypatch):
    """이쪽 절반. 위 테스트가 서버 조건을 문면으로 봤으니 여기는 실물로 잰다."""
    monkeypatch.setenv(config.INTERNAL_TOKEN_ENV, "")
    config.settings.cache_clear()
    assert TestClient(main.app).get("/healthz").json()["internal_auth"] == "disabled"
