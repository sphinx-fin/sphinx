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


#: 오해 라이브러리·지수 스냅샷이 있는 디렉토리. 레포에서는 `data/` 지만 컨테이너에서는
#: 마운트 지점이 달라진다 — 상대경로 하드코딩이면 이미지 안에서 파일을 못 찾는다
#: (결정로그 10.7 · 이슈 #37). 강희진 몫(`application.yml` 시계열 주입)은 #50 으로 끝났다.
DATA_DIR_ENV = "SPHINX_DATA_DIR"


@dataclass(frozen=True)
class Settings:
    llm_base_url: str
    llm_api_key: str
    llm_model: str
    llm_timeout_sec: float
    env_files: tuple[str, ...]
    data_dir: Path

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
        data_dir=Path(os.getenv(DATA_DIR_ENV) or (REPO_ROOT / "data")).expanduser(),
    )
