"""ai-service 설정. 소유: 윤지석

프로바이더 중립: `.env`의 LLM_API_BASE/LLM_API_KEY/LLM_MODEL 3개만 읽는다.
Gemini는 OpenAI 호환 엔드포인트로 붙는다 (온프레미스 교체 가능성 확보 — llm_client 참고).
기획서 7-3: "외부 LLM API를 그대로 호출하는 구조는 재위탁 문제를 일으킨다."

## .env 위치

`ai-service/.env`를 먼저 읽고, 없는 값은 레포 루트 `.env`에서 채운다.
이미 프로세스 환경에 있는 값은 **덮어쓰지 않는다**(CI·컨테이너가 우선).

    프로세스 환경 > ai-service/.env > 레포 루트 .env > 아래 기본값

LLM 키는 ai-service만 쓰므로 서비스 디렉토리에 두는 것을 권한다. 루트에 둬도 동작한다
(루트 `.env.example`이 그 위치를 가정하고 있다). 두 위치 모두 `.gitignore`의 `.env`
규칙에 걸린다 — 슬래시가 없는 패턴이라 모든 깊이에 적용된다.

우리가 직접 로드하는 이유: `uvicorn --env-file`은 플래그를 빼먹으면 조용히 무설정으로
기동하고, pytest에는 아예 적용되지 않는다.
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

# Gemini OpenAI 호환 엔드포인트 (끝의 슬래시 필수)
DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
# ── 모델 정책 ────────────────────────────────────────────────────────────────
# 팀 결정: **모든 LLM 호출은 flash-lite 계열로 한다.** 기능별로 모델을 나누지 않는다.
# 근거: 무료 티어 한도와 비용. 채점·추출·질문생성·재설명 전부 같은 모델을 쓴다.
#
# gemini-2.5-flash-lite는 신규 키에 제공되지 않는다(404). API가 안내하는 대체가
# gemini-3.5-flash-lite이므로 그것을 기본값으로 둔다.
# gemini-3.1-flash-lite가 약간 더 저렴하고 역시 동작한다.
#
# .env의 LLM_MODEL로 덮어쓸 수 있지만, flash-lite가 아니면 경고를 남긴다 —
# 정책에서 벗어난 실행이 조용히 지나가면 성능 수치의 출처를 알 수 없게 된다.
DEFAULT_MODEL = "gemini-3.5-flash-lite"
MODEL_POLICY_SUBSTRING = "flash-lite"

#: 로그 레벨. **기본이 INFO 다** — 우리 코드의 관측 기록이 전부 `log.info` 이고, 파이썬
#: 기본값(WARNING)이면 그게 하나도 안 찍힌다.
#:
#: PR #113·#114 리뷰(정세현)에서 걸렸다. 두 PR 이 *"빈도를 로그로 본다"* 를 근거로 관측을
#: 약속했는데 `basicConfig`·`dictConfig`·`setLevel` 이 레포에 하나도 없었다 — **약속한
#: 관측 경로가 아예 없었다.** 조용한 실패의 한 형태다: 코드는 남기려 하고 아무도 못 본다.
LOG_LEVEL_ENV = "SPHINX_LOG_LEVEL"
DEFAULT_LOG_LEVEL = "INFO"

#: 우리 로거의 루트. `app.*` 만 설정하고 root 는 건드리지 않는다 — uvicorn 이 자기 핸들러를
#: root 에 붙이므로 `basicConfig(force=True)` 로 덮으면 access 로그 형식까지 바뀐다.
APP_LOGGER = "app"

#: 우리가 붙인 핸들러임을 표시한다. `if not logger.handlers` 로 판단하면 **남이 붙인
#: 핸들러가 하나라도 있을 때 우리 것을 안 붙인다** — pytest 가 `app` 로거에 캡처 핸들러
#: 넷을 붙이는 것으로 실측했다(전체 실행에서 5개). 운영에서는 uvicorn 이 `uvicorn.*` 만
#: 설정하므로 안 겹치지만, "남의 것이 있으면 내 것을 안 붙인다" 는 조용한 실패다.
HANDLER_MARK = "_sphinx_app_handler"

log = logging.getLogger(__name__)

SERVICE_ROOT = Path(__file__).resolve().parents[1]   # ai-service/
REPO_ROOT = SERVICE_ROOT.parent

# 앞쪽이 우선. 이미 설정된 환경변수는 어느 파일도 덮어쓰지 않는다.
ENV_FILES = (SERVICE_ROOT / ".env", REPO_ROOT / ".env")


def _load_env_files() -> list[Path]:
    """찾아서 로드한 파일 목록을 돌려준다. python-dotenv가 없으면 조용히 건너뛴다."""
    try:
        from dotenv import load_dotenv
    except ImportError:  # 의존성 없이 돌리는 경우 — export 방식으로 동작
        return []
    loaded = []
    for path in ENV_FILES:
        if path.is_file():
            load_dotenv(path, override=False)
            loaded.append(path)
    return loaded


@dataclass(frozen=True)
class Settings:
    llm_base_url: str
    llm_api_key: str
    llm_model: str
    llm_timeout_sec: float
    env_files: tuple[str, ...]
    log_level: str

    @property
    def llm_configured(self) -> bool:
        """키가 없으면 LLM 호출 경로만 막고 서비스는 기동한다(목 개발용)."""
        return bool(self.llm_api_key)


@lru_cache(maxsize=1)
def settings() -> Settings:
    loaded = _load_env_files()
    model = os.getenv("LLM_MODEL") or DEFAULT_MODEL
    if MODEL_POLICY_SUBSTRING not in model:
        log.warning(
            "모델 정책 위반: LLM_MODEL=%s — 팀 결정은 flash-lite 계열이다. "
            "이 실행의 결과를 성능 수치로 인용하지 말 것.", model,
        )
    return Settings(
        llm_base_url=os.getenv("LLM_API_BASE") or DEFAULT_BASE_URL,
        llm_api_key=os.getenv("LLM_API_KEY", ""),
        llm_model=model,
        llm_timeout_sec=float(os.getenv("LLM_TIMEOUT_SEC", "60")),
        env_files=tuple(str(p.relative_to(REPO_ROOT)) for p in loaded),
        log_level=(os.getenv(LOG_LEVEL_ENV) or DEFAULT_LOG_LEVEL).upper(),
    )


def configure_logging() -> str:
    """`app.*` 로거에 레벨과 핸들러를 붙인다. 실제로 적용된 레벨 이름을 돌려준다.

    **root 를 건드리지 않는 이유**: uvicorn 이 root 에 자기 핸들러를 붙이므로
    `basicConfig(force=True)` 로 덮으면 access 로그 형식까지 바뀐다. 우리 관측만 켜는 것이
    목적이라 범위를 `app` 으로 좁힌다.

    핸들러를 붙이고 `propagate=False` 로 둔다 — root 에도 핸들러가 있으면 같은 줄이 두 번
    찍힌다. **한 줄이 두 번 나오면 빈도 관측이 정확히 두 배로 틀린다.**

    알 수 없는 레벨 이름은 조용히 무시하지 않고 기본값으로 내려가면서 경고를 남긴다 —
    `SPHINX_LOG_LEVEL=INFOO` 같은 오타가 관측을 끄는데 그게 안 보이면 안 된다.

    여러 번 불려도 핸들러가 쌓이지 않는다(테스트가 반복 호출한다).
    """
    requested = settings().log_level
    level = getattr(logging, requested, None)
    if not isinstance(level, int):
        log.warning(
            "%s 값을 알 수 없어 %s 로 내려간다: %r",
            LOG_LEVEL_ENV, DEFAULT_LOG_LEVEL, requested,
        )
        requested = DEFAULT_LOG_LEVEL
        level = getattr(logging, DEFAULT_LOG_LEVEL)

    logger = logging.getLogger(APP_LOGGER)
    logger.setLevel(level)
    if not any(getattr(h, HANDLER_MARK, False) for h in logger.handlers):
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(levelname)-7s %(name)s  %(message)s"))
        setattr(handler, HANDLER_MARK, True)
        logger.addHandler(handler)
    logger.propagate = False
    return requested
