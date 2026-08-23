"""F-SCR-001 채점. 소유: 윤지석
루브릭(rubrics/) 대입 → U1~U4 + 근거 + 사유. confidence<0.7 황색 강등(U4 제외 — P5 미탐 방지).
출력은 contracts/judgment.schema.json.
"""
from __future__ import annotations

from .schemas import Grade, Judgment, RiskItem

CONFIDENCE_FLOOR = 0.7


def score(item_id: str, question: str, answer_text: str, risk_item: RiskItem,
          product_type: str) -> Judgment:
    """고객 발화 → Judgment(측정값). 게이트 판정이 아니다 (P1).

    TODO(윤지석):
      1. rubrics/{item_id}.yaml 로드 (required_elements / misconception_conditions)
      2. llm_client.complete_json(model_cls=Judgment) — 근거 스팬 의무화
      3. misconception.match() 결과를 misconception_type에 병합 (호출 구조는 강희진과 확정 대기)
      4. downgrade_low_confidence() 적용
    """
    raise NotImplementedError("TODO(윤지석): F-SCR-001")


def downgrade_low_confidence(judgment: Judgment) -> Judgment:
    """신뢰도 미달 시 황색(U2)으로 강등. **U4는 예외** — P5 미탐 방지.

    강등이 여기서 일어나는 이유: gate_rules.yaml에 confidence를 보는 룰이 없어
    GateEngine은 신뢰도를 모른다. 따라서 우리가 내보내는 grade를 바꾸는 것으로만
    황색 처리가 실현된다(R-04가 U2를 YELLOW로 받는다).
    P1 경계에 걸치는 지점이므로 **강등 사실을 reason에 반드시 남긴다**(감사 추적).

    TODO(윤지석): 강희진과 이 위치를 확정한 뒤 구현 확정.
    """
    if judgment.confidence >= CONFIDENCE_FLOOR or judgment.grade is Grade.U4:
        return judgment
    if judgment.grade is Grade.U1:
        return judgment.model_copy(
            update={
                "grade": Grade.U2,
                "reason": f"{judgment.reason} (신뢰도 {judgment.confidence:.2f} < "
                          f"{CONFIDENCE_FLOOR} → 황색 강등)",
            }
        )
    return judgment
