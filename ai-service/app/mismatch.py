"""F-DET-002 적합성 모순 탐지. 소유: 윤지석

설문 기재와 실제 발화의 모순을 세션 단위로 판정한다. 기획서 4절:
"설문에는 '원금 손실 감수 가능'이라고 체크해놓고 대화에서는 '원금은 절대 손해 보면
안 된다'고 말하는 경우를 잡는다."

**취약 요인 가중(연령·총자산 대비 비중·투자경험·손실경험)은 여기서 하지 않는다** —
강희진 소유다. 이 모듈은 가중 전의 모순 사실만 낸다.

출력 스키마는 `proposals/suitability_mismatch.schema.json`. 강희진이 형태를 승인했고
(`mismatch` + `confidence` + `direction` + `evidence`) `contracts/` 승격은 그쪽 몫이다.

엔드포인트는 `POST /internal/mismatch`로 확정됐다(7번째). 게이트 판정 직전에 호출된다.

분담(역할분담표 v1.2 §38, 강희진 결정 ⓓ):
  - **여기**: 모순 원시 신호 + `confidence`. 그것이 '탐지 자신감'이다.
  - **강희진**: `resources/vulnerability_weights.yaml`로 연령·가입비중·경험·채널 가중 →
    코칭 스코어 → 세션 메타 저장. 게이트 R-02는 `mismatch=true`만 본다.

`MISMATCH_CONFIDENCE_FLOOR`가 여기 남는 이유: 이건 탐지 자신감의 하한이지 게이트 정책이
아니다. F-SCR-001의 황색 강등 임계값은 grade를 바꿔 게이트 입력을 흔들었으므로 게이트로
넘겼지만(proposals/F-SCR-001-yellow-downgrade.md), 이쪽은 "우리가 모순을 봤다고 말할
만한가"의 문제다.
"""
from __future__ import annotations

from typing import Any

from .schemas import SuitabilityMismatch

# 이 임계값 아래의 모순은 mismatch=true로 올리지 않는다.
# 채점(F-SCR-001)은 애매하면 황색으로 내리는 것이 기획서 근거가 있지만, 모순은 R-02로
# 곧장 적색이라 마찰 비용이 다르다. 이 숫자는 강희진 확인 대상이다.
MISMATCH_CONFIDENCE_FLOOR = 0.7


def detect(
    session_id: str,
    survey_result: dict[str, Any],
    utterances: list[dict[str, Any]],
    survey_schema_version: str | None = None,
) -> SuitabilityMismatch:
    """설문 + 세션 내 발화 → 모순 판정.

    TODO(윤지석):
      1. freeform 설문 매핑을 axis로 정규화. 설문 쪽에서 axis를 주지 않기로 확정됐으므로
         (결정 ⓑ) 문항 키·값 문면을 해석하는 단계가 필요하다 — 여기가 정확도의 약한 고리다
      2. axis별로 발화를 모아 llm_client.complete_json(model_cls=SuitabilityMismatch)
      3. 각 contradiction의 utterance_quote를 원문 대조
         (scoring.verify_quote_is_verbatim과 같은 규칙 — 지어낸 인용은 근거가 아니다)
      4. MISMATCH_CONFIDENCE_FLOOR 미달이면 contradictions에는 남기되 mismatch=false
      5. 입력이 부족하면 status="insufficient_input" — false를 '적합'으로 읽히게 두지 않는다
    """
    raise NotImplementedError("TODO(윤지석): F-DET-002 — 설문 스키마 확정 대기")
