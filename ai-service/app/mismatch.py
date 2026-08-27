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

import logging
import re
import unicodedata
from pathlib import Path
from typing import Any, get_args

from .llm_client import LlmClient, LlmError, client as default_client
from .schemas import Contradiction, SuitabilityMismatch

PROMPT_PATH = Path(__file__).resolve().parent / "prompts" / "F-DET-002_v1.md"
PROMPT_VERSION = "F-DET-002_v1"

log = logging.getLogger(__name__)

#: 이 값 미만의 모순은 `mismatch=true` 로 올리지 않는다. 다만 **버리지 않고 남긴다** —
#: 코칭 스코어·리포트가 근접 사례를 볼 수 있어야 하고(direction 이 코칭 문구를 좌우한다),
#: 조용히 지우면 왜 통과했는지 추적할 수 없다.
MISMATCH_CONFIDENCE_FLOOR = 0.7

#: 발화가 이보다 짧으면 모순을 판정하지 않는다. 한두 마디로 성향을 단정하면 오판이 된다.
MIN_UTTERANCE_CHARS = 10

#: 설문 문항 키 접두어. `surveyResult` 맵에는 문항 말고 메타데이터도 실린다
#: (`_surveySchemaVersion` — `CreateSessionRequest` 에 typed 필드가 없어 우회 중, 이슈 #98).
#: **규약은 이슈 #44 에서 확정됐다: `SUIT-` 로 시작하는 키만 문항이다.**
QUESTION_KEY_PREFIX = "SUIT-"

#: 문항 → 축. `Contradiction.axis` enum 과 **1:1** 이다(import 시점에 대조한다).
#:
#: 설문이 `axis` 를 주지 않기로 확정됐고(강희진 결정 ⓑ), 그래서 v1 은 모델이 문항 문면을
#: 읽어 축을 판단했다 — `build_prompt` docstring 이 그것을 *"이 기능의 약한 고리"* 라고
#: 적어 뒀다. 문항 문면이 바뀌면 판정이 흔들린다는 뜻이었다.
#:
#: 뒤집을 필요가 없었다. 오준서가 *"키가 축을 말하게 한다"* 로 문항 키를 설계했고(#44),
#: 그 결과 키 6개와 축 6개가 정확히 맞물린다. **축은 추론할 값이 아니라 `question_id` 에서
#: 나오는 값이다.** 스팬을 원문에서 계산하고 유형 ID 를 라이브러리에서만 받는 것과 같은 층.
AXIS_BY_QUESTION: dict[str, str] = {
    "SUIT-RISK-PROFILE": "risk_tolerance",
    "SUIT-PRINCIPAL-LOSS": "principal_preservation",
    "SUIT-LOSS-TOLERANCE": "loss_capacity",
    "SUIT-PURPOSE": "purpose",
    "SUIT-HORIZON": "investment_horizon",
    "SUIT-PRODUCT-EXPERIENCE": "product_understanding",
}


class UnknownSurveyQuestion(Exception):
    """축을 모르는 `SUIT-` 문항이 들어왔다 — 설문 세트가 바뀐 것이다.

    판정을 계속하지 않는 이유: `axis` 는 계약 enum 값이라 **모르는 문항으로는 유효한
    `Contradiction` 을 만들 수 없다.** 그 문항을 빼고 판정하면 "그 축에는 모순이 없다"는
    말을 한 셈이 되는데(미탐, P5), 실제로는 보지 않은 것이다.

    고려한 대안: 모르는 문항만 프롬프트에서 빼고 `reason` 에 적는다. 판정이 이어지는 대신
    빠진 축이 출력 문면에만 남는다. 3주차 데모에서 조용히 열화하는 쪽이 하드 실패보다
    나쁘다고 봤다 — 세트 버전이 오르면 축 매핑도 같이 올려야 한다는 게 이 예외의 요지다.
    """

_WS = re.compile(r"\s+")


def assert_axis_map_matches_contract() -> None:
    """`AXIS_BY_QUESTION` 의 값이 계약의 `axis` enum 과 정확히 같은지 — import 시점에 본다.

    한쪽만 늘면 조용히 어긋난다. 축을 추가하고 매핑을 안 하면 그 축은 영원히 안 나오고,
    매핑에만 있으면 pydantic 이 판정 시점에야 죽는다. 둘 다 로딩 때 드러나야 한다.
    """
    contract = set(get_args(Contradiction.model_fields["axis"].annotation))
    mapped = set(AXIS_BY_QUESTION.values())
    if contract != mapped:
        raise AssertionError(
            f"axis 매핑이 계약과 다르다 — 계약만: {sorted(contract - mapped)} · "
            f"매핑만: {sorted(mapped - contract)}"
        )


def detect(
    session_id: str,
    survey_result: dict[str, Any],
    utterances: list[dict[str, Any]],
    survey_schema_version: str | None = None,
    llm: LlmClient | None = None,
) -> SuitabilityMismatch:
    """설문 + 세션 내 발화 → 모순 판정.

    `survey_result` 는 맵 전체다. **입구에서 한 번 문항만 걸러내고 그 뒤로는 걸러진 맵만
    쓴다** — 프롬프트와 P4 대조가 서로 다른 맵을 보면 대조가 대조가 아니게 된다(아래 참고).
    """
    texts = _utterance_texts(utterances)
    survey = survey_questions(survey_result)

    reason = _insufficient_reason(survey, texts)
    if reason:
        return SuitabilityMismatch(
            session_id=session_id, status="insufficient_input", mismatch=False,
            confidence=0.0, contradictions=[], reason=reason,
            survey_schema_version=survey_schema_version,
        )

    judged = (llm or default_client()).complete_json(
        prompt=build_prompt(survey, utterances),
        model_cls=SuitabilityMismatch,
        schema_name="SuitabilityMismatch",
        system=load_system_prompt(),
    )

    kept = [
        _pin_axis(c) for c in judged.contradictions
        if _is_traceable(c, survey, texts)
    ]
    return _finalize(session_id, kept, survey_schema_version)


# ── 설문 맵: 문항과 메타데이터를 가른다 ───────────────────────────────────────
def survey_questions(survey_result: dict[str, Any]) -> dict[str, Any]:
    """맵에서 문항만 남긴다 (규약: 이슈 #44).

    걸러내지 않으면 두 곳이 동시에 틀린다.

    1. 프롬프트에 `- _surveySchemaVersion: s02-survey-v1` 이 설문 문항으로 들어간다.
    2. **`_is_traceable` 이 그걸 통과시킨다** — 대조가 `question_id in survey_result` 뿐이고
       메타키도 맵에 있기 때문이다. 문항이 아닌 것을 근거로 든 모순이 "설문 근거 있음"으로
       살아남는다. P4 의 뜻에 어긋나는데 코드는 초록이다.

    그래서 필터가 `build_prompt` 안에 있으면 안 된다. 2번은 안 막힌다.
    """
    questions = {
        k: v for k, v in survey_result.items() if k.startswith(QUESTION_KEY_PREFIX)
    }
    unknown = sorted(set(questions) - set(AXIS_BY_QUESTION))
    if unknown:
        raise UnknownSurveyQuestion(
            f"축을 모르는 설문 문항: {', '.join(unknown)}. "
            f"설문 세트가 바뀌었으면 AXIS_BY_QUESTION 과 Contradiction.axis 를 같이 올린다"
        )
    return questions


def _pin_axis(contradiction: Contradiction) -> Contradiction:
    """축을 `question_id` 에서 고정한다 — 모델이 낸 값을 쓰지 않는다.

    `_is_traceable` 이 먼저 `question_id` 가 걸러진 맵에 있는지 확인하므로 조회는 안전하다.

    **불일치를 로그로 남긴다.** 판정에는 영향이 없다(계산값이 이긴다) — 이건 프롬프트 품질
    신호다. PR #113 리뷰(정세현)에서 *"덮어쓰기만 하고 비교하지 않는다"* 로 지적됐다.

      · 불일치가 잦으면 프롬프트 규칙 5의 매핑 표가 읽히지 않고 있다는 뜻이다
      · 0 이면 모델이 키에서 축을 정확히 읽는다는 뜻이고, 계산은 안전망으로만 남는다

    `Contradiction` 이나 `SuitabilityMismatch` 에 필드를 늘리지 않는다 — 판정 근거가 아니라
    관측이고, 계약 면적을 늘릴 값이 아니다.
    """
    pinned = AXIS_BY_QUESTION[contradiction.survey_ref.question_id]
    if contradiction.axis != pinned:
        log.info(
            "축 불일치: 모델=%s 계산=%s (question_id=%s) — 계산값을 쓴다",
            contradiction.axis, pinned, contradiction.survey_ref.question_id,
        )
    return contradiction.model_copy(update={"axis": pinned})


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
        return "설문 문항이 없어 판정 불가"
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
    """걸러진 설문 문항을 펼친다. `survey_questions()` 를 통과한 맵만 넣는다.

    v1 docstring 은 여기를 *"이 기능의 약한 고리"* 라고 적었다 — 설문이 `axis` 를 주지 않아
    (결정 ⓑ) 모델이 문항 문면을 읽어 축을 판단했고, 문면이 바뀌면 판정이 흔들렸다.
    **축을 `question_id` 에서 고정해서(`_pin_axis`) 그 고리를 끊었다.** 결정 ⓑ 는 그대로다 —
    설문에 필드를 추가한 게 아니라 키가 이미 축을 말하고 있었다(#44).

    남은 문면 의존은 **어느 문항이 발화와 어긋나는가**의 판단뿐이다. 그건 모델이 할 일이다.

    문항 문면(질문 텍스트)은 일부러 넣지 않는다. `SurveyRef.recorded_answer` 는 고객이 기재한
    값이어야 하는데, 프롬프트에 질문이 있으면 모델이 값 대신 질문을 인용하는 경로가 열린다.
    그렇게 온 모순은 `_is_traceable` 이 값 대조에서 떨어뜨리므로 **실제 모순이 사라진다**
    (오해→이해 방향 오판). `question_text` 는 리포트 층에서 세트 버전으로 복원한다(#44 회신).
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


# 순수 코드 대조라 파일 접근이 없다 — import 시점에 돌려도 환경에 따라 달라지지 않는다.
# (`misconception.assert_products_are_canonical` 은 데이터 파일을 읽어서 같이 하지 못한다.
#  그쪽은 `LIBRARY_PATH` 경로 문제와 한 묶음이다 — 결정로그 10.7.)
assert_axis_map_matches_contract()
