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


class LlmTruncated(LlmError):
    """응답이 토큰 상한에서 잘렸다 (`finish_reason == "length"`). 이슈 #280.

    **진짜 실패와 갈라야 하는 이유가 있다.** 잘림은 입력이 컸다는 뜻이라 같은 입력이면
    또 잘린다 — 재시도가 의미 없고, 프롬프트나 상한을 고쳐야 한다. 반면 스키마 불일치나
    호출 실패는 재시도로 넘어갈 수 있다.

    그리고 예전에는 이것이 `"LLM 빈 응답"` 으로 나왔다. 추론 모델은 상한을 **추론 토큰으로
    먼저 소진**하므로 `content` 가 빈 문자열이 되고, 그러면 *"모델이 이상한 답을 했다"* 로
    읽힌다. 실물은 *"우리가 상한을 너무 낮게 줬다"* 다 — 고칠 곳이 반대편이다.
    """


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

    def _extra_body(self, caller: dict[str, Any] | None) -> dict[str, Any]:
        """설정의 사고 예산을 얹는다. **호출자가 준 값이 이긴다.**

        `reasoning_effort` 를 안 박으면 gpt-5-mini 가 호출당 사고토큰 1,024개를 태운다
        (실측 20.3초). `minimal` 이면 0개 · 2.4초이고 등급은 같았다. 사고토큰은 출력
        요금이라 이 한 줄이 요금을 두 배 가른다.

        여기서 얹는 이유는 **한 곳이기 때문**이다. 라우트마다 넣으면 새 라우트가 생길 때
        조용히 빠지고, 그 누락은 요금과 지연으로만 드러난다 — 테스트로는 안 보인다.

        빈 문자열이면 아무것도 안 보낸다. 이 파라미터를 모르는 프로바이더로 옮길 때
        **코드가 아니라 설정으로** 끄기 위한 문이다(`LLM_REASONING_EFFORT=`).
        """
        merged: dict[str, Any] = dict(caller or {})
        effort = self._cfg.llm_reasoning_effort
        if effort and "reasoning_effort" not in merged:
            merged["reasoning_effort"] = effort
        return merged

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
        # 편차 축소 (이슈 #280). `temperature` 는 정책 모델이 거부하므로 seed 만 보낸다.
        # ❗**이것으로 재현성이 확보되지 않는다** — 같은 seed 로 complete_json 3회가
        # 3가지였다(실측). 실행 간 재현성은 결정 10.10 의 재판정 경로 몫이다.
        # None 이면 안 보낸다 — 튜닝에서 표본을 여러 개 보려고 끄는 경우가 있다.
        if self._cfg.llm_seed is not None:
            kwargs["seed"] = self._cfg.llm_seed
        if response_format:
            kwargs["response_format"] = response_format
        body = self._extra_body(extra_body)
        if body:
            # 프로바이더 고유 옵션은 호환 레이어에서 extra_body 로만 전달된다.
            kwargs["extra_body"] = body

        try:
            resp = self._openai().chat.completions.create(**kwargs)
        except LlmNotConfigured:
            raise
        except Exception as exc:  # SDK 예외 계층에 의존하지 않는다
            raise LlmError(f"LLM 호출 실패: {exc}") from exc

        choice = resp.choices[0]
        content = (choice.message.content or "").strip()

        # ❗잘림을 먼저 본다 — 내용이 비어 있는 이유가 여기일 수 있다 (이슈 #280).
        # 추론 모델은 상한을 추론 토큰으로 먼저 쓰므로 content 가 빈 채로 length 가 온다.
        if choice.finish_reason == "length":
            raise LlmTruncated(
                f"응답이 토큰 상한에서 잘렸다: model={kwargs['model']} "
                f"{_usage_note(resp)} — 프롬프트를 줄이거나 상한을 올려야 한다"
            )
        if not content:
            raise LlmError(
                f"LLM 빈 응답: model={kwargs['model']} "
                f"finish_reason={choice.finish_reason!r} {_usage_note(resp)}"
            )
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


def _usage_note(resp: Any) -> str:
    """토큰 사용량을 진단 문면에 남긴다 (이슈 #280).

    잘림을 만났을 때 **무엇이 상한을 먹었는지** 알아야 고칠 곳이 정해진다. 추론 토큰이
    대부분이면 프롬프트가 아니라 `reasoning_effort` 나 상한을 봐야 한다. 없으면 조용히
    비운다 — 프로바이더마다 usage 모양이 달라서 여기서 죽으면 진단이 진단을 막는다.
    """
    usage = getattr(resp, "usage", None)
    if usage is None:
        return "(usage 없음)"
    parts = [f"완성={getattr(usage, 'completion_tokens', '?')}"]
    detail = getattr(usage, "completion_tokens_details", None)
    reasoning = getattr(detail, "reasoning_tokens", None) if detail else None
    if reasoning is not None:
        parts.append(f"추론={reasoning}")
    return "(" + " ".join(parts) + ")"
