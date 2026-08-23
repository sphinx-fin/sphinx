"""F-DET-002 적합성 모순 탐지. 소유: 윤지석

설문 기재와 실제 발화의 모순을 세션 단위로 판정한다. 기획서 4절:
"설문에는 '원금 손실 감수 가능'이라고 체크해놓고 대화에서는 '원금은 절대 손해 보면
안 된다'고 말하는 경우를 잡는다."

**취약 요인 가중(연령·총자산 대비 비중·투자경험·손실경험)은 여기서 하지 않는다** —
강희진 소유다. 이 모듈은 가중 전의 모순 사실만 낸다.

출력 스키마는 초안 상태다: `proposals/suitability_mismatch.schema.json`.
`contracts/`에 아직 없고, 엔드포인트도 `AiServiceClient`의 6개 목록에 없다.

**착수 전제:** `CreateSession.surveyResult`가 `Map<String, Object>`로 열려 있어
설문 문항의 최소 형태가 정해지지 않았다. 그것 없이는 프롬프트를 고정할 수 없다.
→ `proposals/F-DET-002-mismatch.md` 확인 사항 2번.
"""
from __future__ import annotations

from typing import Any

from .schemas import SuitabilityMismatch, SurveyRef

# 이 임계값 아래의 모순은 mismatch=true로 올리지 않는다.
# 채점(F-SCR-001)은 애매하면 황색으로 내리는 것이 기획서 근거가 있지만, 모순은 R-02로
# 곧장 적색이라 마찰 비용이 다르다. 이 숫자는 강희진 확인 대상이다.
MISMATCH_CONFIDENCE_FLOOR = 0.7


def detect(
    session_id: str,
    survey_result: list[SurveyRef],
    utterances: list[dict[str, Any]],
    survey_schema_version: str | None = None,
) -> SuitabilityMismatch:
    """설문 + 세션 내 발화 → 모순 판정.

    TODO(윤지석): 설문 스키마 확정 후 착수.
      1. 설문 문항을 axis로 정규화 (설문 쪽에서 axis를 주면 이 단계가 사라진다)
      2. axis별로 발화를 모아 llm_client.complete_json(model_cls=SuitabilityMismatch)
      3. 각 contradiction의 utterance_quote를 원문 대조
         (scoring.verify_quote_is_verbatim과 같은 규칙 — 지어낸 인용은 근거가 아니다)
      4. MISMATCH_CONFIDENCE_FLOOR 미달이면 contradictions에는 남기되 mismatch=false
      5. 입력이 부족하면 status="insufficient_input" — false를 '적합'으로 읽히게 두지 않는다
    """
    raise NotImplementedError("TODO(윤지석): F-DET-002 — 설문 스키마 확정 대기")
