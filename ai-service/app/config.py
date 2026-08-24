"""ai-service 설정. 소유: 윤지석

프로바이더 중립: `.env`의 LLM_API_BASE/LLM_API_KEY/LLM_MODEL 3개만 읽는다.
Gemini는 OpenAI 호환 엔드포인트로 붙는다 (온프레미스 교체 가능성 확보 — llm_client 참고).
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache

# Gemini OpenAI 호환 엔드포인트 (끝의 슬래시 필수)
DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
DEFAULT_MODEL = "gemini-3.7-flash"


@dataclass(frozen=True)
class Settings:
    llm_base_url: str
    llm_api_key: str
    llm_model: str
    llm_timeout_sec: float

    @property
    def llm_configured(self) -> bool:
        """키가 없으면 LLM 호출 경로만 막고 서비스는 기동한다(목 개발용)."""
        return bool(self.llm_api_key)


@lru_cache(maxsize=1)
def settings() -> Settings:
    return Settings(
        llm_base_url=os.getenv("LLM_API_BASE") or DEFAULT_BASE_URL,
        llm_api_key=os.getenv("LLM_API_KEY", ""),
        llm_model=os.getenv("LLM_MODEL") or DEFAULT_MODEL,
        llm_timeout_sec=float(os.getenv("LLM_TIMEOUT_SEC", "60")),
    )
