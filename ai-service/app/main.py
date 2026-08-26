"""ai-service 엔트리포인트. 내부 전용 (Spring server만 호출). 소유: 윤지석"""
from __future__ import annotations

import json
import logging

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from . import routes
from .config import settings
from .pii import PiiDetected, assert_payload_clean

log = logging.getLogger(__name__)

# starlette 버전에 따라 상수명이 UNPROCESSABLE_ENTITY/CONTENT로 갈린다 — 리터럴로 고정
HTTP_422 = 422


class PiiGuardMiddleware:
    """P3 입구 방어선. 요청 본문의 모든 문자열을 검사하고 걸리면 **거부**한다.

    순수 ASGI 미들웨어로 구현한다 — 본문을 읽고 다시 흘려보내는 제어가 명시적이어야
    하고, BaseHTTPMiddleware의 스트림 재주입에 의존하지 않기 위해서다.

    이건 두 겹 중 바깥쪽이다. 안쪽(최종 방어선)은 `llm_client.send()`로,
    외부로 나가는 모든 프롬프트가 거기서 한 번 더 검사된다.
    """

    GUARDED_METHODS = frozenset({"POST", "PUT", "PATCH"})

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
                    assert_payload_clean(payload)
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


app = FastAPI(
    title="SphinX AI Service",
    version="0.1.0",
    description="LLM 파이프라인 (내부망 전용 — 브라우저에 노출 금지)",
)
app.add_middleware(PiiGuardMiddleware)
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
        "prompt_versions": {
            "F-SCR-001": scoring.PROMPT_VERSION,
            "F-DET-002": mismatch.PROMPT_VERSION,
        },
    }
