"""F-DET-002 적합성 모순 탐지. 소유: 윤지석

설문 기재와 실제 발화의 모순을 세션 단위로 판정한다. 기획서 4절:
"설문에는 '원금 손실 감수 가능'이라고 체크해놓고 대화에서는 '원금은 절대 손해 보면
안 된다'고 말하는 경우를 잡는다."

**취약 요인 가중(연령·가입비중·투자경험·채널)은 여기서 하지 않는다** — 강희진 소유다
(ADR-005, 역할분담표 v1.2 §38). `resources/vulnerability_weights.yaml` → 코칭 스코어 →
세션 메타 경로로 서버가 처리한다. 게이트 R-02 는 `mismatch=true` 만 본다.

출력은 `contracts/suitability_mismatch.schema.json`.

`MISMATCH_CONFIDENCE_FLOOR` 가 여기 남는 이유(ADR-005): 이건 **탐지 자신감**의 하한이지
게이트 정책이 아니다. F-SCR-001 의 황색 강등 임계값은 grade 를 바꿔 게이트 입력을 흔들었으므로
게이트로 넘겼지만, 이쪽은 "우리가 모순을 봤다고 말할 만한가"의 문제다.

**모순은 양쪽이 다 추적 가능해야 한다** (P4). 발화 인용은 실제 발화에서, 설문 참조는 실제
설문에서 와야 한다. 한쪽이라도 지어낸 것이면 판정이 무효다 — 근거 없는 판정은 감사 시점에
검증할 수 없다.
"""
from __future__ import annotations

import re
import unicodedata
from pathlib import Path
from typing import Any

from .llm_client import LlmClient, LlmError, client as default_client
from .schemas import Contradiction, SuitabilityMismatch

PROMPT_PATH = Path(__file__).resolve().parent / "prompts" / "F-DET-002_v1.md"
PROMPT_VERSION = "F-DET-002_v1"

#: 이 값 미만의 모순은 `mismatch=true` 로 올리지 않는다. 다만 **버리지 않고 남긴다** —
#: 코칭 스코어·리포트가 근접 사례를 볼 수 있어야 하고(direction 이 코칭 문구를 좌우한다),
#: 조용히 지우면 왜 통과했는지 추적할 수 없다.
MISMATCH_CONFIDENCE_FLOOR = 0.7

#: 발화가 이보다 짧으면 모순을 판정하지 않는다. 한두 마디로 성향을 단정하면 오판이 된다.
MIN_UTTERANCE_CHARS = 10

_WS = re.compile(r"\s+")


def detect(
    session_id: str,
    survey_result: dict[str, Any],
    utterances: list[dict[str, Any]],
    survey_schema_version: str | None = None,
    llm: LlmClient | None = None,
) -> SuitabilityMismatch:
    """설문 + 세션 내 발화 → 모순 판정."""
    texts = _utterance_texts(utterances)

    reason = _insufficient_reason(survey_result, texts)
    if reason:
        return SuitabilityMismatch(
            session_id=session_id, status="insufficient_input", mismatch=False,
            confidence=0.0, contradictions=[], reason=reason,
            survey_schema_version=survey_schema_version,
        )

    judged = (llm or default_client()).complete_json(
        prompt=build_prompt(survey_result, utterances),
        model_cls=SuitabilityMismatch,
        schema_name="SuitabilityMismatch",
        system=load_system_prompt(),
    )

    kept = [
        c for c in judged.contradictions
        if _is_traceable(c, survey_result, texts)
    ]
    return _finalize(session_id, kept, survey_schema_version)


# ── 입력 판단 ─────────────────────────────────────────────────────────────────
def _utterance_texts(utterances: list[dict[str, Any]]) -> list[str]:
    return [u["text"] for u in utterances if isinstance(u.get("text"), str) and u["text"].strip()]


def _insufficient_reason(survey_result: dict[str, Any], texts: list[str]) -> str | None:
    """판정할 수 없는 경우의 사유. None 이면 판정 가능하다.

    `mismatch=false` 만 돌려주면 호출자가 '적합'으로 읽는다. 판정하지 못한 것과 모순이 없는
    것은 다르므로 `status` 로 구분한다(F-EXT-002 가 실패를 extraction_failed 로 노출하는 것과
    같은 원칙).
    """
    if not survey_result:
        return "설문 결과가 비어 판정 불가"
    if not texts:
        return "세션 내 발화가 없어 판정 불가"
    if sum(len(t) for t in texts) < MIN_UTTERANCE_CHARS:
        return f"발화가 {MIN_UTTERANCE_CHARS}자 미만이라 성향 판정 불가"
    return None


# ── 프롬프트 ──────────────────────────────────────────────────────────────────
def _prompt_sections() -> tuple[str, str]:
    text = PROMPT_PATH.read_text(encoding="utf-8")
    system = text.split("## system", 1)[1].split("## user", 1)[0].strip()
    user = text.split("## user", 1)[1].strip()
    return system, user


def load_system_prompt() -> str:
    return _prompt_sections()[0]


def build_prompt(survey_result: dict[str, Any], utterances: list[dict[str, Any]]) -> str:
    """freeform 설문 매핑을 그대로 펼친다.

    설문 쪽에서 `axis` 를 주지 않기로 확정됐으므로(강희진 결정 ⓑ) 문항 키와 값의 문면을
    모델이 해석해야 한다. **여기가 이 기능의 약한 고리다** — 문항 문면이 바뀌면 판정이
    흔들린다. 정확도가 문제되면 설문 스키마에 axis 추가를 다시 제안한다.
    """
    _, template = _prompt_sections()
    survey_lines = "\n".join(f"- {k}: {v}" for k, v in survey_result.items()) or "- (없음)"
    utterance_lines = "\n".join(
        f"- [{u.get('item_id') or '항목미지정'}] {u['text']}"
        for u in utterances
        if isinstance(u.get("text"), str) and u["text"].strip()
    ) or "- (없음)"
    return template.format(survey_lines=survey_lines, utterance_lines=utterance_lines)


# ── 후처리: 양쪽 추적 가능성 확인 ──────────────────────────────────────────────
def _canonical(text: str) -> str:
    """대조용 정규화 — NFC + 공백 제거. scoring._canonical 과 같은 규칙이다.

    한글 완성형/조합형은 눈으로 같고 바이트가 다르다. 모델이 조합형으로 인용을 돌려주면
    글자가 같은데도 거부된다(F-SCR-001 에서 실측으로 재현한 결함).
    """
    return _WS.sub("", unicodedata.normalize("NFC", text))


def _is_traceable(
    contradiction: Contradiction, survey_result: dict[str, Any], texts: list[str]
) -> bool:
    """모순의 양쪽이 실제 입력에서 나온 것인지 확인한다 (P4).

    한쪽이라도 지어낸 것이면 그 모순을 버린다. 예외를 던지지 않는 이유: 모순 하나가
    환각이어도 나머지가 유효할 수 있고, 세션 단위 판정이라 전체를 버리면 실제 모순을
    놓친다 — 오해→이해 방향으로 기울지 않게 개별로 걸러낸다.
    """
    quote = _canonical(contradiction.utterance_quote)
    if not quote or not any(quote in _canonical(t) for t in texts):
        return False

    ref = contradiction.survey_ref
    if ref.question_id not in survey_result:
        return False
    recorded = _canonical(str(survey_result[ref.question_id]))
    return _canonical(ref.recorded_answer) in recorded or recorded in _canonical(
        ref.recorded_answer
    )


def _finalize(
    session_id: str, contradictions: list[Contradiction], survey_schema_version: str | None
) -> SuitabilityMismatch:
    """스키마 불변식을 우리가 계산한다 — LLM 이 낸 mismatch·confidence 를 믿지 않는다."""
    if not contradictions:
        return SuitabilityMismatch(
            session_id=session_id, status="evaluated", mismatch=False, confidence=0.0,
            contradictions=[], reason="설문 기재와 발화 사이에 확인된 모순이 없다",
            survey_schema_version=survey_schema_version,
        )

    top = max(contradictions, key=lambda c: c.confidence)
    confident = [c for c in contradictions if c.confidence >= MISMATCH_CONFIDENCE_FLOOR]
    mismatch = bool(confident)

    if mismatch:
        axes = ", ".join(sorted({c.axis for c in confident}))
        reason = f"설문 기재와 발화가 {axes} 축에서 모순 ({len(confident)}건)"
    else:
        reason = (
            f"모순 후보 {len(contradictions)}건이 모두 탐지 자신감 "
            f"{MISMATCH_CONFIDENCE_FLOOR} 미만이라 모순으로 확정하지 않음"
        )

    return SuitabilityMismatch(
        session_id=session_id, status="evaluated", mismatch=mismatch,
        confidence=top.confidence, contradictions=contradictions, reason=reason,
        survey_schema_version=survey_schema_version,
    )
