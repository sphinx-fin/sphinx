"""구조화 출력의 `strict` — **보내는 스키마가 지켜지는가.** 소유: 윤지석

## 왜 이 파일이 생겼나 — 「보냈다」와 「지켜졌다」가 달랐다

`complete_json` 은 `additionalProperties: false` 를 담은 JSON 스키마를 보낸다. 그런데
`strict: true` 없이 보내면 프로바이더가 **최선노력**으로만 맞춘다 — 실측에서
`QuestionDraft` 가 **5/5** 로 스키마의 `description`(모델 docstring)을 **출력 필드로
되돌려 보냈고**, `Strict`(=`extra="forbid"`)인 우리 모델이 그것을 거부해 `LlmError` 가 났다.

F-INT-002 는 그 예외에서 템플릿 폴백으로 내려가므로 **alpha 질문 폴백률이 93/100** 이
되었다(`#487`). 화면은 멀쩡하고 어긋나는 것은 *"AI 가 질문을 만든다"* 라는 주장이다.

    strict 없음   5/5 실패 (description 을 되돌려 보낸다)
    strict 있음   5/5 성공
    required 10항목 실측   폴백 3/3 → **1/10** (남은 하나는 `leaked` — 설계대로다)

## ❗전부 켤 수는 없다 — 실측으로 다섯이 400 이다

프로바이더의 strict 부분집합이 요구하는 둘을 `Judgment`(계약)가 못 채운다.

    ① 모든 object 에 `additionalProperties: false`
    ② `required` 가 그 object 의 **모든** property 를 포함

그래서 **스키마 모양을 보고 켤 수 있을 때만 켠다.** 못 켜는 스키마는 오늘과 완전히 같은
요청을 보낸다 — 채점·`#409` 수치가 안 흔들린다.
"""
from __future__ import annotations

import json

from app import extraction, mismatch, misconception, retrieval, rubricgen, schemas
from app.llm_client import LlmClient, supports_strict


# ── 판정 자체 ────────────────────────────────────────────────────────────────
def _obj(props: dict, required: list[str] | None = None, extra: object = False) -> dict:
    node = {"type": "object", "properties": props, "additionalProperties": extra}
    if required is not None:
        node["required"] = required
    return node


def test_a_qualifying_schema_is_recognised() -> None:
    assert supports_strict(_obj({"a": {"type": "string"}}, ["a"]))


def test_additional_properties_must_be_false() -> None:
    """이것이 빠지면 **모델이 아무 키나 더 붙일 수 있다** — 이 결함의 직접 원인이다."""
    assert not supports_strict(_obj({"a": {"type": "string"}}, ["a"], extra=True))
    loose = {"type": "object", "properties": {"a": {"type": "string"}}, "required": ["a"]}
    assert not supports_strict(loose), "additionalProperties 가 아예 없는 것도 못 켠다"


def test_every_property_must_be_required() -> None:
    """선택 필드가 하나라도 있으면 프로바이더가 400 을 낸다 — `Judgment` 가 그 경우다."""
    assert not supports_strict(_obj({"a": {"type": "string"}, "b": {"type": "string"}}, ["a"]))


def test_a_nested_object_is_checked_too() -> None:
    """❗바깥만 보면 안 된다 — `$defs` 의 중첩 object 가 400 을 낸다."""
    nested_bad = _obj({"inner": {"$ref": "#/$defs/Inner"}}, ["inner"])
    nested_bad["$defs"] = {"Inner": {"type": "object", "properties": {"x": {"type": "string"}},
                                    "required": ["x"]}}   # additionalProperties 없음
    assert not supports_strict(nested_bad)

    nested_ok = json.loads(json.dumps(nested_bad))
    nested_ok["$defs"]["Inner"]["additionalProperties"] = False
    assert supports_strict(nested_ok)


def test_a_free_form_object_cannot_be_strict() -> None:
    """`properties` 가 없는 object 는 무엇이든 담을 수 있다."""
    assert not supports_strict({"type": "object", "additionalProperties": False})


# ── 실제로 쓰는 스키마의 표 ───────────────────────────────────────────────────
#
# ❗**이 표는 실측이다.** 각 스키마를 `strict: true` 로 실제 API 에 보내 본 결과이고
# (2026-09-06 · gpt-5-mini), 아래 판정이 그것과 **8/8 일치**하는 것을 확인했다.
# 재현: 각 `model_json_schema()` 를 `response_format.json_schema.strict=true` 로 보낸다.
#
# 값이 바뀌면 **판정이 틀린 것이 아니라 스키마가 바뀐 것**일 수 있다 — 그때는 그 스키마를
# 실제로 한 번 보내 보고 이 표를 고친다. 여기 값을 그냥 뒤집으면 400 이 운영에서 난다.
EXPECTED = {
    "QuestionDraft": True,        # ❗#487 이 걸린 자리
    "ReexplainResponse": True,
    "ExtractionDraft": True,
    "Judgment": False,            # 계약(judgment.schema.json) — 선택 필드가 있다
    "MismatchDraft": False,
    "PolarityVerdict": False,
    "Reranked": False,
    "_Draft": False,              # rubricgen
}


def _in_use() -> dict[str, type]:
    return {
        "QuestionDraft": schemas.QuestionDraft,
        "ReexplainResponse": schemas.ReexplainResponse,
        "ExtractionDraft": extraction.ExtractionDraft,
        "Judgment": schemas.Judgment,
        "MismatchDraft": mismatch.MismatchDraft,
        "PolarityVerdict": misconception.PolarityVerdict,
        "Reranked": retrieval.Reranked,
        "_Draft": rubricgen._Draft,
    }


def test_the_table_matches_the_schemas_in_use() -> None:
    got = {name: supports_strict(cls.model_json_schema()) for name, cls in _in_use().items()}
    assert got == EXPECTED, (
        "strict 자격이 바뀌었다. 표를 고치기 전에 **그 스키마를 실제로 한 번 보내 본다** — "
        "여기 값만 뒤집으면 400 이 운영에서 난다"
    )
    # ❗양성 대조 — 전부 False 여도 위 단정은 통과할 수 있다(표까지 같이 틀리면).
    assert any(got.values()), "하나도 못 켜면 이 기능이 아무것도 안 한다"
    assert not all(got.values()), "전부 켜지면 400 이 나는 스키마가 있다는 실측과 어긋난다"


def test_question_draft_is_the_one_that_had_to_be_fixed() -> None:
    """★ `#487` 의 실질. 이것이 False 로 돌아가면 폴백률이 93% 로 되돌아간다."""
    assert supports_strict(schemas.QuestionDraft.model_json_schema())


def test_the_contract_schema_is_untouched() -> None:
    """★ `Judgment` 는 **오늘과 같은 요청**으로 나간다 — 채점과 `#409` 수치가 안 흔들린다."""
    assert not supports_strict(schemas.Judgment.model_json_schema())


# ── 요청에 실제로 실리는가 ────────────────────────────────────────────────────
class _Capture(LlmClient):
    """`send` 만 가로채 `response_format` 을 잡는다 — 실제 호출은 없다."""

    def __init__(self) -> None:  # noqa: D107 — 설정을 안 읽는다
        self.seen: dict = {}

    def send(self, *, prompt, system=None, response_format=None, **kw):  # type: ignore[override]
        self.seen = response_format or {}
        return json.dumps({"question": "어떤 상황인지 말씀해 주시겠어요?",
                           "question_type": "situation"})


def test_the_flag_actually_reaches_the_request() -> None:
    """❗판정이 맞아도 **요청에 안 실리면** 아무 일도 안 일어난다."""
    c = _Capture()
    c.complete_json(prompt="p", model_cls=schemas.QuestionDraft, schema_name="QuestionDraft")
    assert c.seen["json_schema"]["strict"] is True


def test_a_non_qualifying_schema_sends_no_flag() -> None:
    """켤 수 없을 때 `strict: false` 를 «보내지 않는다** — 오늘과 바이트가 같은 요청이다."""
    c = _Capture()
    try:
        c.complete_json(prompt="p", model_cls=schemas.Judgment, schema_name="Judgment")
    except Exception:  # noqa: BLE001 — 스텁 응답이 Judgment 가 아니다. 여기선 요청만 본다
        pass
    assert "strict" not in c.seen["json_schema"]
