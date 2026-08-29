"""ai-service 엔트리포인트. 내부 전용 (Spring server만 호출). 소유: 윤지석"""
from __future__ import annotations

import hmac
import json
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from . import misconception, routes, rubrics
from .config import (
    DATA_DIR_ENV,
    INTERNAL_TOKEN_ENV,
    REQUIRE_INTERNAL_AUTH_ENV,
    configure_logging,
    effective_log_level,
    settings,
)

from .pii import PiiDetected, assert_payload_clean

log = logging.getLogger(__name__)

# 엔트리포인트에서 한 번 켠다. lifespan 이 아니라 모듈 수준인 이유: `TestClient(app)` 을
# context manager 로 쓰지 않으면 lifespan 이 돌지 않고, 그러면 테스트에서 관측이 꺼진다.
configure_logging()

# starlette 버전에 따라 상수명이 UNPROCESSABLE_ENTITY/CONTENT로 갈린다 — 리터럴로 고정
HTTP_422 = 422


#: 공유 시크릿을 싣는 헤더. Spring `AiServiceClient` 가 `RestClient.Builder` 의
#: `defaultHeader` 로 붙인다(강희진 영역 — 이 PR 은 받는 쪽만 만든다).
INTERNAL_TOKEN_HEADER = "x-sphinx-internal-token"

#: 인증을 요구하는 경로 접두어. `/healthz` 는 뺀다 — 헬스체크가 토큰을 알아야 하면
#: 컨테이너 오케스트레이터가 시크릿을 들고 있어야 하고, 그건 시크릿이 사는 곳을 늘린다.
#: `/openapi.json`·`/docs` 도 뺀다(스키마는 비밀이 아니고, 계약은 이미 레포에 있다).
GUARDED_PREFIX = "/internal/"


class InternalAuthMiddleware:
    """`/internal/*` 공유 시크릿 검증. **네트워크 격리가 1차, 이건 2차다**(결정 10.4).

    `ai-service` 는 브라우저가 직접 부르지 않고 Spring 만 부른다는 전제 위에 P3 가 서 있다.
    :8100 이 퍼블릭 서브넷에 뜨면 그 전제가 깨지고, **PII 마스킹을 건너뛴 경로가 생긴다**
    (이슈 #41 ③). 입구 PII 재검사는 마스킹 누락을 막는 방어선이지 접근 통제가 아니다.

    `PiiGuardMiddleware` 보다 **바깥**에 둔다 — 인증되지 않은 요청의 본문을 읽고 검사할
    이유가 없다. `add_middleware` 는 나중에 추가한 것이 바깥이므로 등록 순서가 그렇게 된다.

    토큰이 설정되지 않으면 **통과시키되 조용히 통과시키지 않는다** — 기동 시 경고를 남기고
    `/healthz` 가 상태를 낸다. 배포에서는 `SPHINX_REQUIRE_INTERNAL_AUTH` 로 기동을 막는다.
    """

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http" or not scope.get("path", "").startswith(GUARDED_PREFIX):
            await self.app(scope, receive, send)
            return

        cfg = settings()
        if not cfg.internal_auth_enabled:
            await self.app(scope, receive, send)
            return

        headers = {k.decode("latin-1").lower(): v.decode("latin-1")
                   for k, v in scope.get("headers", [])}
        supplied = headers.get(INTERNAL_TOKEN_HEADER, "")
        # 상수 시간 비교 — 길이·내용이 응답 시간으로 새지 않게 한다.
        # **바이트로 비교한다**: `compare_digest` 는 비ASCII 문자열에 `TypeError` 를 낸다.
        # 토큰은 `config` 가 ASCII 로 강제하지만, 헤더 값은 통제 밖이라 여기서도 안전해야 한다
        # — 아니면 이상한 헤더 하나가 401 이 아니라 500 을 만든다(실측으로 재현했다).
        if not hmac.compare_digest(supplied.encode("utf-8"), cfg.internal_token.encode("utf-8")):
            # 토큰 값을 로그에 남기지 않는다. 있었는지 없었는지만 남긴다.
            log.warning(
                "내부 토큰 불일치로 거부: path=%s 헤더=%s",
                scope.get("path"), "있음" if supplied else "없음",
            )
            await _json_response(send, 401, {
                "detail": f"{INTERNAL_TOKEN_HEADER} 헤더가 없거나 일치하지 않는다. "
                          "ai-service 는 내부망 전용이다 (결정 10.4)",
            })
            return

        await self.app(scope, receive, send)


async def _json_response(send, status: int, body: dict) -> None:
    payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    await send({"type": "http.response.start", "status": status,
                "headers": [(b"content-type", b"application/json; charset=utf-8"),
                            (b"content-length", str(len(payload)).encode())]})
    await send({"type": "http.response.body", "body": payload})


class PiiGuardMiddleware:
    """P3 입구 방어선. 요청 본문의 모든 문자열을 검사하고 걸리면 **거부**한다.

    순수 ASGI 미들웨어로 구현한다 — 본문을 읽고 다시 흘려보내는 제어가 명시적이어야
    하고, BaseHTTPMiddleware의 스트림 재주입에 의존하지 않기 위해서다.

    이건 두 겹 중 바깥쪽이다. 안쪽(최종 방어선)은 `llm_client.send()`로,
    외부로 나가는 모든 프롬프트가 거기서 한 번 더 검사된다.
    """

    GUARDED_METHODS = frozenset({"POST", "PUT", "PATCH"})

    #: 본문이 **공시 상품문서**인 경로. 기획서 7-3: "상품설명서(공시 자료이므로 개인정보가
    #: 아니다)". 발행사 민원부서 번호 같은 법인 연락처가 인쇄돼 있어 넓은 휴리스틱이 정상
    #: 문서를 막는다 — 실측으로 `02-785-7424` 가 ACCOUNT 에 걸려 추출이 422 로 거부됐다.
    #:
    #: 이 경로에서도 좁은 패턴(RRN·PHONE)은 그대로 검사한다. 공시 문서에 주민번호나 개인
    #: 휴대번호가 있으면 그건 문서 쪽 사고이므로 막아야 한다.
    #:
    #: **고객 텍스트가 오는 경로는 여기 넣지 않는다.** 나머지 전부가 strict 범위임을
    #: 테스트로 고정했다.
    PUBLIC_DOCUMENT_PATHS = frozenset({"/internal/parse", "/internal/extract"})

    def _scope(self, path: str) -> str:
        return "public_document" if path in self.PUBLIC_DOCUMENT_PATHS else "customer"

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http" or scope.get("method") not in self.GUARDED_METHODS:
            await self.app(scope, receive, send)
            return

        chunks: list[bytes] = []
        more_body = True
        while more_body:
            message = await receive()
            if message["type"] != "http.request":
                break
            chunks.append(message.get("body", b""))
            more_body = message.get("more_body", False)
        body = b"".join(chunks)

        if body:
            try:
                payload = json.loads(body)
            except ValueError:
                payload = None  # JSON이 아니면 FastAPI 검증이 처리한다
            if payload is not None:
                try:
                    assert_payload_clean(payload, scope=self._scope(scope.get("path", "")))
                except PiiDetected as exc:
                    log.warning("PII 차단: kinds=%s where=%s path=%s",
                                exc.kinds, exc.where, scope.get("path"))
                    response = JSONResponse(
                        status_code=HTTP_422,
                        content={
                            "error": "pii_detected",
                            "kinds": exc.kinds,
                            "where": exc.where,
                            "detail": "P3 위반 — 상류 PiiGateway를 거치지 않은 텍스트입니다.",
                        },
                    )
                    await response(scope, receive, send)
                    return

        async def replay():
            return {"type": "http.request", "body": body, "more_body": False}

        await self.app(scope, replay, send)


def _check_internal_auth() -> None:
    """내부 인증 상태를 기동 시점에 드러낸다 (결정 10.4).

    **꺼진 것이 조용하면 안 된다.** 목 개발 중이라 기본은 꺼짐이고 그건 의도지만, 배포에서
    꺼진 채로 뜨면 `:8100` 이 무인증으로 열린다 — 그 상태가 로그 어디에도 안 보이면 데모 전
    점검에서 못 찾는다. `SPHINX_REQUIRE_INTERNAL_AUTH` 를 켜면 기동 자체를 막는다.
    """
    cfg = settings()
    if cfg.internal_auth_enabled:
        log.info("내부 인증 켜짐 — /internal/* 는 %s 헤더를 요구한다", INTERNAL_TOKEN_HEADER)
        return
    if cfg.require_internal_auth:
        raise RuntimeError(
            f"{REQUIRE_INTERNAL_AUTH_ENV} 가 켜져 있는데 {INTERNAL_TOKEN_ENV} 가 비어 있다. "
            "배포에서 무인증으로 뜨는 것을 막기 위해 기동을 중단한다 (결정 10.4)"
        )
    log.warning(
        "⚠ 내부 인증 꺼짐 — /internal/* 가 무인증이다. %s 를 설정하면 켜진다. "
        "배포에서는 %s=1 로 기동 자체를 막을 것 (결정 10.4 · 이슈 #41)",
        INTERNAL_TOKEN_ENV, REQUIRE_INTERNAL_AUTH_ENV,
    )


@asynccontextmanager
async def lifespan(_: FastAPI):
    """기동 시점에 데이터 파일을 실제로 읽는다 — 첫 요청 때 죽지 않게.

    컨테이너에서 `data/` 를 안 마운트하면 여기서 `MisconceptionLibraryMissing` 이 나고
    메시지가 `SPHINX_DATA_DIR` 을 알려준다(결정로그 10.7). 이걸 지연 로딩으로 두면
    **기동은 성공하고 첫 고객 요청에서 500** 이 된다 — 배포 실수를 데모 중에 발견하는 경로다.

    루브릭↔오해 라이브러리 교차 참조도 여기서 본다. `assert_related_misconceptions_exist`
    는 로더 안에 넣을 수 없다 — 두 로더가 서로를 부르면 순환이 된다.
    """
    _check_internal_auth()           # 인증이 꺼져 있으면 기동 로그에서 보여야 한다
    misconception.library()          # 파일 읽기 + products 계약 검증
    rubrics.all_rubrics()            # 루브릭 파싱
    rubrics.assert_related_misconceptions_exist()
    log.info(
        "데이터 로드 완료: data_dir=%s 오해유형=%d 루브릭=%d",
        settings().data_dir, len(misconception.library()), len(rubrics.all_rubrics()),
    )
    yield


app = FastAPI(
    title="SphinX AI Service",
    version="0.1.0",
    description="LLM 파이프라인 (내부망 전용 — 브라우저에 노출 금지)",
    lifespan=lifespan,
)
app.add_middleware(PiiGuardMiddleware)
# **나중에 추가한 것이 바깥**이다 — 인증이 PII 검사보다 먼저 돈다.
app.add_middleware(InternalAuthMiddleware)
app.include_router(routes.router)


@app.exception_handler(PiiDetected)
async def _pii_handler(request: Request, exc: PiiDetected) -> JSONResponse:
    """라우트 안에서 직접 assert_clean()을 부른 경우."""
    log.warning("PII 차단(route): kinds=%s where=%s", exc.kinds, exc.where)
    return JSONResponse(
        status_code=HTTP_422,
        content={"error": "pii_detected", "kinds": exc.kinds, "where": exc.where},
    )


@app.get("/healthz")
def healthz() -> dict:
    """키 유무를 노출하지만 값은 절대 노출하지 않는다.

    `prompt_versions` 는 **어느 프롬프트로 측정 중인지**다. 프롬프트 v2 에서 `confidence` 의
    정의가 바뀌어(등급 확신도 → 채점 재현 가능성) v1 과 v2 의 숫자를 섞어 비교할 수 없게
    됐는데, `Judgment` 에 프롬프트 버전 필드가 없어 판정 기록만 봐서는 어느 정의인지 모른다.
    계약 변경(강희진 승인)이 필요한 사안이라 우선 실행 중인 값을 여기서 볼 수 있게 한다.
    """
    from . import mismatch, scoring

    cfg = settings()
    return {
        "status": "ok",
        "llm_model": cfg.llm_model,
        "llm_base_url": cfg.llm_base_url,
        "llm_configured": cfg.llm_configured,
        "env_files": list(cfg.env_files),   # 어느 .env를 읽었는지. 값은 노출하지 않는다
        "log_level": effective_log_level(),     # **적용된** 레벨. 요청값이 아니다 (#121 리뷰)
        # 토큰 값은 절대 내지 않는다 — 켜졌는지만. LLM 키를 안 내는 것과 같은 규칙이다.
        "internal_auth": "enabled" if cfg.internal_auth_enabled else "disabled",
        "internal_auth_required": cfg.require_internal_auth,
        "log_level_requested": cfg.log_level,   # 환경변수 원본. 둘이 다르면 오타가 있었다
        "data_dir": str(cfg.data_dir),      # 어디서 오해 라이브러리를 읽는지 (10.7)
        "data_dir_env": DATA_DIR_ENV,
        "misconception_library_version": misconception.library_version(),
        "prompt_versions": {
            "F-SCR-001": scoring.PROMPT_VERSION,
            "F-DET-002": mismatch.PROMPT_VERSION,
        },
    }
