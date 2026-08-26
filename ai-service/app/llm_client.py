"""LLM 호출 어댑터. 소유: 윤지석

**설계 의도: 온프레미스 교체 가능.** 그래서 Gemini SDK를 직접 쓰지 않고
OpenAI 호환 엔드포인트에 붙는다 — base_url/model/key 3개만 갈아끼우면 다른 모델로 간다.
호출부(extraction/question_gen/scoring/...)는 이 파일 밖의 프로바이더를 몰라야 한다.

**P3 최종 방어선이 여기다.** 외부로 나가는 모든 프롬프트는 send() 안에서
pii.assert_clean()을 통과한다. 우회 경로를 만들지 않는다 — ai-service에서 망 밖으로
텍스트가 나가는 지점은 이 함수 하나뿐이다.

**P4 강제:** complete_json()은 pydantic 모델로 검증된 객체만 돌려준다. 스키마를 못 맞추면
LlmError를 던진다 — 근거 필드가 빈 판정이 흘러나가지 못한다.
"""
from __future__ import annotations

import json
import logging
from typing import Any, TypeVar

from pydantic import BaseModel, ValidationError

from . import pii
from .config import Settings, settings

log = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)


class LlmError(RuntimeError):
    """호출 실패 또는 스키마 불일치. 상위에서 fallback/재시도를 결정한다."""


class LlmNotConfigured(LlmError):
    """LLM_API_KEY 미설정 — 목 개발 중에는 정상 상태다."""


class LlmClient:
    def __init__(self, cfg: Settings | None = None) -> None:
        self._cfg = cfg or settings()
        self._client: Any = None

    # ── 내부 ──────────────────────────────────────────────────────────────────
    def _openai(self) -> Any:
        """openai SDK는 지연 임포트 — 키 없이도 서비스가 기동해야 한다."""
        if self._client is None:
            if not self._cfg.llm_configured:
                raise LlmNotConfigured("LLM_API_KEY 미설정")
            from openai import OpenAI

            self._client = OpenAI(
                api_key=self._cfg.llm_api_key,
                base_url=self._cfg.llm_base_url,
                timeout=self._cfg.llm_timeout_sec,
            )
        return self._client

    # ── 공개 API ──────────────────────────────────────────────────────────────
    def send(
        self,
        *,
        prompt: str,
        system: str | None = None,
        response_format: dict[str, Any] | None = None,
        extra_body: dict[str, Any] | None = None,
        model: str | None = None,
        pii_scope: str = "customer",
    ) -> str:
        """원문 응답 문자열을 돌려준다. 외부로 나가는 유일한 경로 (P3).

        `pii_scope` 는 **무엇을 보내는지** 선언한다. 기본값 `customer` 는 고객 텍스트를
        전제하고 넓은 휴리스틱까지 적용한다. 공시 상품문서를 보낼 때만 `public_document`
        를 쓴다 — 기획서 7-3 이 공시 자료를 개인정보가 아니라고 명시하고, 법인 연락처가
        인쇄돼 있어 넓은 휴리스틱이 정상 문서를 막는다. 좁은 패턴(RRN·PHONE)은 어느
        범위에서도 검사한다.

        **고객 텍스트를 public_document 로 보내면 안 된다.** 그 경로를 쓰는 곳은
        F-EXT-002 추출 하나뿐이고, 고객 발화를 다루는 라우트가 그것을 쓰지 않는 것을
        테스트로 고정했다.
        """
        pii.assert_clean(prompt, "llm.prompt", scope=pii_scope)
        if system:
            pii.assert_clean(system, "llm.system", scope=pii_scope)

        messages: list[dict[str, str]] = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})

        kwargs: dict[str, Any] = {
            "model": model or self._cfg.llm_model,
            "messages": messages,
        }
        if response_format:
            kwargs["response_format"] = response_format
        if extra_body:
            # Gemini 고유 옵션(thinking_level, safety settings)은 호환 레이어에서
            # extra_body로만 전달된다. 정확한 중첩 형태는 키 확보 후 실측 확인 필요.
            kwargs["extra_body"] = extra_body

        try:
            resp = self._openai().chat.completions.create(**kwargs)
        except LlmNotConfigured:
            raise
        except Exception as exc:  # SDK 예외 계층에 의존하지 않는다
            raise LlmError(f"LLM 호출 실패: {exc}") from exc

        content = (resp.choices[0].message.content or "").strip()
        if not content:
            raise LlmError("LLM 빈 응답")
        return content

    def complete_json(
        self,
        *,
        prompt: str,
        model_cls: type[T],
        schema_name: str,
        system: str | None = None,
        extra_body: dict[str, Any] | None = None,
        model: str | None = None,
        pii_scope: str = "customer",
    ) -> T:
        """JSON 스키마를 강제하고, 받은 결과를 pydantic으로 재검증해서 반환한다.

        모델이 스키마를 지켜도 우리가 한 번 더 검증한다 — P4(근거 필수)는 프로바이더
        약속이 아니라 우리 코드가 보장해야 한다.
        """
        response_format = {
            "type": "json_schema",
            "json_schema": {
                "name": schema_name,
                "schema": model_cls.model_json_schema(),
            },
        }
        raw = self.send(
            prompt=prompt,
            system=system,
            response_format=response_format,
            extra_body=extra_body,
            model=model,
            pii_scope=pii_scope,
        )
        try:
            return model_cls.model_validate(json.loads(raw))
        except (json.JSONDecodeError, ValidationError) as exc:
            raise LlmError(f"{schema_name} 스키마 불일치: {exc}") from exc


_default: LlmClient | None = None


def client() -> LlmClient:
    global _default
    if _default is None:
        _default = LlmClient()
    return _default
