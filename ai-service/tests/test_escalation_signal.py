"""F-GTE-003 컴플라이언스 상신 신호 — 값을 만드는 쪽 (이슈 #160). 소유: 윤지석

기획 [기능2]: 꺾기(구속성 판매)가 의심되면 **컴플라이언스로 즉시 알린다.**
기획 7-4(역이용 방지): 그 사실을 **판매자 화면에는 내지 않는다.**

## 이 파일이 무엇을 지키나

`#160` 에서 이 기능이 **한 번도 발행되지 않았다.** 탐지는 만점이었다 —

    M08-TYING  score 1.0 · escalate true

그런데 어느 루브릭도 M08 을 `related_misconceptions` 에 안 걸어서
`apply_misconception_floor` 가 첫 줄에서 반환하고, 유형이 안 실리니
서버의 `publishIfUnfairSales` 도 첫 줄에서 반환했다. 세 모듈이 각각 정상인데
합쳐 놓으면 기능이 없었다.

**등급 필터와 신호 필터를 갈라 놓는 것이 해법이다.** 이 파일은 그 분리가
나중에 "일관성" 이라는 이름으로 다시 붙는 것을 막는다.

## 계약이 아직 안 열렸다

`contracts/judgment.schema.json` 의 `escalate` 는 강희진 승인 대기다(`#160`).
그래서 지금은 **값을 만드는 데까지만** 하고 `schemas.py` 미러는 안 고친다
(CLAUDE.md: 계약 변경은 오너 승인 + 수요자 전원 멘션).
`test_contract_field_is_still_pending` 이 그 순간을 알려 준다.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app import misconception, rubrics, scoring
from app.schemas import Evidence, Grade, Judgment

CONTRACT = Path(__file__).resolve().parents[2] / "contracts" / "judgment.schema.json"

#: 라이브러리 M08-TYING 의 근거 인용문(`source.quote`) 그대로다.
TYING_UTTERANCE = "대출받으려면 이것도 들어야 한다길래 그냥 가입했어요"

#: 꺾기와 무관한 평범한 오해. M01 이 잡히고 상신은 안 된다.
ORDINARY_UTTERANCE = "은행에서 파는 거니까 원금은 지켜지는 거죠"

#: 채점 중이던 항목. **꺾기와 아무 상관 없는 항목이라는 점이 핵심이다.**
ITEM_ID = "ELS-PRINCIPAL-LOSS-WARNING"


def _matched(utterance: str):
    return misconception.match(utterance, "ELS")


# ── #160 회귀 ────────────────────────────────────────────────────────────────
def test_tying_escalates_though_no_rubric_relates_it():
    """★ `#160` 그 자체. 루브릭이 M08 을 안 걸어도 신호는 선다."""
    rubric = rubrics.get(ITEM_ID)
    assert "M08-TYING" not in rubric.related_misconceptions, (
        "이 항목이 M08 을 관련유형으로 걸게 됐다면 이 테스트의 전제가 사라진 것이다 — "
        "M08 을 안 거는 다른 루브릭으로 바꿔라"
    )

    matched = _matched(TYING_UTTERANCE)
    assert matched.escalate is True, "탐지 자체는 원래 되고 있었다"
    assert scoring.escalation_signal(matched, rubric) is True


def test_no_rubric_relates_tying_at_all():
    """전제를 못 박는다 — 17종 어디에도 M08 이 없다.

    이게 깨지면 `#160` 의 다른 해법(루브릭에 M08 을 거는 쪽)이 채택된 것이므로
    이 모듈의 존재 이유를 다시 봐야 한다. 조용히 지나가면 안 된다.
    """
    relating = [
        item_id for item_id in rubrics.all_rubrics()
        if "M08-TYING" in rubrics.get(item_id).related_misconceptions
    ]
    assert not relating, (
        f"{relating} 이 M08 을 관련유형으로 걸었다. 그러면 그 항목의 *등급* 이 꺾기로 "
        "내려간다 — 등급은 '고객이 이해했는가' 를 재는 것이라 판매자 행위로 깎으면 안 된다"
    )


# ── 분리가 다시 붙는 것을 막는다 ──────────────────────────────────────────────
def test_escalation_ignores_the_rubric_filter():
    """루브릭을 무엇으로 주든 답이 같다.

    `apply_misconception_floor` 와 나란히 두고 **한쪽만 필터를 쓴다**는 것을 고정한다.
    누가 일관성을 이유로 `related_misconceptions` 를 붙이면 여기서 깨진다.
    """
    matched = _matched(TYING_UTTERANCE)
    answers = {
        scoring.escalation_signal(matched, rubrics.get(item_id))
        for item_id in rubrics.all_rubrics()
    }
    assert answers == {True}, "루브릭에 따라 답이 갈렸다 — 필터가 붙었다"


def test_grade_floor_still_respects_the_rubric_filter():
    """반대쪽은 그대로다 — 상신 신호를 열었다고 등급 필터까지 열린 것이 아니다.

    꺾기 발화로 원금손실 항목의 등급이 U4 로 내려가면 안 된다. 판매자가 무엇을 했는지와
    고객이 이 항목을 이해했는지는 다른 질문이다.
    """
    from app.schemas import Evidence, Grade, Judgment

    judgment = Judgment(
        item_id=ITEM_ID, grade=Grade.U1, confidence=0.9,
        evidence=Evidence(utterance_quote=TYING_UTTERANCE, rubric_clause="(테스트)"),
        reason="(테스트)",
    )
    out = scoring.apply_misconception_floor(
        judgment, _matched(TYING_UTTERANCE), rubrics.get(ITEM_ID)
    )
    assert out.grade is Grade.U1
    assert out.misconception_type is None


# ── 데이터 주도 ───────────────────────────────────────────────────────────────
def test_ordinary_misconception_does_not_escalate():
    matched = _matched(ORDINARY_UTTERANCE)
    assert matched.matches, "M01 이 잡혀야 대조가 성립한다"
    assert scoring.escalation_signal(matched, rubrics.get(ITEM_ID)) is False


def test_escalation_is_data_driven_not_hardcoded(monkeypatch):
    """★ 라이브러리에서 `escalate` 를 떼면 신호도 꺼진다 — 문자열 스캔보다 이게 증명이다.

    유형ID 가 코드 어딘가에 박혀 있으면 데이터를 바꿔도 신호가 남는다. 패턴은 그대로 두고
    `escalate` 만 떼어서, **탐지는 되는데 상신은 안 되는** 상태를 만들어 대조한다.
    """
    stripped = tuple(
        m.__class__(**{**vars(m), "escalate": None}) if m.escalate else m
        for m in misconception.library()
    )
    assert any(m.escalate for m in misconception.library()), "원래 값이 없으면 대조가 성립 안 한다"
    monkeypatch.setattr(misconception, "library", lambda: stripped)

    matched = _matched(TYING_UTTERANCE)
    assert matched.matches, "패턴은 그대로 두었으므로 탐지는 되어야 한다"
    assert [m.type_id for m in matched.matches] == ["M08-TYING"]
    assert scoring.escalation_signal(matched, rubrics.get(ITEM_ID)) is False, (
        "라이브러리에서 escalate 를 뗐는데도 신호가 섰다 — 유형ID 가 코드에 박혀 있다"
    )


# ── 계약 ↔ 미러 대조 (트립와이어를 전환했다) ──────────────────────────────────
def test_contract_and_mirror_agree_on_escalate():
    """★ 계약에 있는 `escalate` 가 미러에도 있다 — 트립와이어가 할 일을 하고 전환됐다.

    `#242` 로 계약이 열렸고, 그때 이 자리에 있던 트립와이어가 실패하며 배선 4단계를
    출력했다. **의도대로 작동했다** — 그게 없었으면 계약만 들어가고 아무도 모른 채
    지나갔다가, 나중에 값을 싣는 날 원인이 두 PR 전으로 거슬러 올라가는 502 를 만났을 것이다.

    이제는 반대 방향을 지킨다: 계약과 미러가 **같이** 있거나 **같이** 없어야 한다.
    한쪽만 바뀌면 조용히 갈린다 — 계약에만 있으면 값이 영영 안 실리고(원래 결함),
    미러에만 있으면 서버가 모르는 필드를 보내 `/internal/score` 가 전부 502 다.
    """
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    in_contract = "escalate" in contract.get("properties", {})
    in_mirror = "escalate" in Judgment.model_fields
    assert in_contract and in_mirror, (
        f"계약={in_contract} 미러={in_mirror} — 한쪽만 있으면 조용히 갈린다"
    )
    assert contract["properties"]["escalate"]["type"] == "boolean"
    assert Judgment.model_fields["escalate"].default is False, (
        "기본값이 False 가 아니면 신호가 없는 판정이 신호로 읽힌다"
    )


def test_mirror_emits_false_by_default_so_the_server_must_accept_it():
    """❗미러를 만드는 것이 **곧 싣는 것**이다 (`#242` 리뷰, 강희진).

    `routes.py` 의 `@router.post("/score", response_model=Judgment)` 때문에 FastAPI 가
    `response_model` 로 직렬화한다 — 값을 안 채워도 **기본값 `false` 가 응답에 나간다.**
    *"필드만 만들고 아직 안 싣는다"* 는 상태가 존재하지 않는다.

    그래서 서버가 먼저 받을 수 있어야 했고(`#246`), 그게 이 PR 보다 앞선 이유다.
    `AiServiceClient` 의 매퍼는 `FAIL_ON_UNKNOWN_PROPERTIES` 가 켜져 있어서, 순서가
    뒤바뀌면 첫 채점에서 인터뷰가 죽는다 — `#165` 와 방향만 반대인 같은 결함이다.
    """
    j = Judgment(item_id="X", grade=Grade.U3, confidence=0.5,
                 evidence=Evidence(utterance_quote="모르겠어요", rubric_clause="투자원금의 손실이 발생할 수 있음"),
                 reason="사유")
    payload = json.loads(j.model_dump_json())
    assert "escalate" in payload, "직렬화에서 빠지면 서버 계약과 갈린다"
    assert payload["escalate"] is False
