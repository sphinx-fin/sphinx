"""ai-service 엔트리포인트. 내부 전용 (Spring server만 호출). 소유: 윤지석"""
from __future__ import annotations

import json
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from . import misconception, routes, rubrics
from .config import DATA_DIR_ENV, configure_logging, effective_log_level, settings

from .pii import PiiDetected, assert_payload_clean

log = logging.getLogger(__name__)

# 엔트리포인트에서 한 번 켠다. lifespan 이 아니라 모듈 수준인 이유: `TestClient(app)` 을
# context manager 로 쓰지 않으면 lifespan 이 돌지 않고, 그러면 테스트에서 관측이 꺼진다.
configure_logging()

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


@asynccontextmanager
async def lifespan(_: FastAPI):
    """기동 시점에 데이터 파일을 실제로 읽는다 — 첫 요청 때 죽지 않게.

    컨테이너에서 `data/` 를 안 마운트하면 여기서 `MisconceptionLibraryMissing` 이 나고
    메시지가 `SPHINX_DATA_DIR` 을 알려준다(결정로그 10.7). 이걸 지연 로딩으로 두면
    **기동은 성공하고 첫 고객 요청에서 500** 이 된다 — 배포 실수를 데모 중에 발견하는 경로다.

    루브릭↔오해 라이브러리 교차 참조도 여기서 본다. `assert_related_misconceptions_exist`
    는 로더 안에 넣을 수 없다 — 두 로더가 서로를 부르면 순환이 된다.
    """
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
        "log_level_requested": cfg.log_level,   # 환경변수 원본. 둘이 다르면 오타가 있었다
        "data_dir": str(cfg.data_dir),      # 어디서 오해 라이브러리를 읽는지 (10.7)
        "data_dir_env": DATA_DIR_ENV,
        "misconception_library_version": misconception.library_version(),
        "prompt_versions": {
            "F-SCR-001": scoring.PROMPT_VERSION,
            "F-DET-002": mismatch.PROMPT_VERSION,
        },
    }
