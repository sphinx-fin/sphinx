"""수동 파스에서 나온 항목은 **추출 출력에도 그 사실을 싣는다** (#436·#441 후속).

## ❗표식이 층을 못 건너고 있었다

`#441` 이 `data/documents/x.json` 으로 파스 출력을 사람이 만든 것으로 대체할 수 있게 했고,
그 사실이 `ParsedDocument.parse_warnings` 의 `MANUAL_OVERRIDE` 로 남는다. **그런데 추출은
`parse_warnings` 를 안 본다.**

    parse       MANUAL_OVERRIDE 있음     ParsedDocument.is_manual → True
    extract     ❗아무것도 안 실렸다      RiskItem 만 나간다
    서버·게이트  구분할 방법이 없다

`is_manual` 의 docstring 이 *"성능 수치를 인용할 때 구분해야 한다"* 고 적어 뒀는데
**읽는 쪽이 `app/` 에 하나도 없었다**(전수 grep — 테스트 한 곳뿐이었다).

## 왜 이게 문제인가

`#436` 이 `ELS-MATURITY-LOSS-CONDITION` 하나를 살리려고 수동 JSON 을 넣는다. 그러면 그
문서의 **13개 항목 전부**가 수동 파스에서 나오는데, 표식이 없으면

    F-EXT-003(추출 재현율)   사람이 만든 문서를 파서 성능의 분모에 넣는다 → 수치가 부푼다
    #409(model.jsonl)        같은 자리
    감사                     "이 조건 문면은 파서가 뽑았나 사람이 넣었나" 에 답이 없다

**표식을 만들어 두고 아무도 안 읽으면 다음 사람이 그 표식을 안 믿는다.**

## 문서 단위라 `item_id` 를 비운다

항목별 사실이 아니라 **그 파스 출력 전체**의 성질이다. 계약이 `item_id` 를 nullable 로
허용하는 그 경우이고, `#401` ④ 에서 내가 *"문서 전체에 대한 경고"* 로 적어 둔 자리다.
"""
from __future__ import annotations

import pytest

from app import extraction
from app.schemas import ExtractionDraft


class _NoCandidates:
    """LLM 을 안 부른다 — 이 파일이 재는 것은 경고 전파이지 추출 품질이 아니다."""

    def complete_json(self, **_kwargs) -> ExtractionDraft:
        return ExtractionDraft(candidates=[])


def _doc(*, manual: bool) -> dict:
    warnings = [{"code": "MANUAL_OVERRIDE", "message": "(테스트) 사람이 만든 파스 출력"}]
    return {
        "document_id": "doc-test", "product_type": "ELS", "parser_version": "1",
        "pages": [{"page": 1, "text": "투자원금의 손실이 발생할 수 있습니다"}],
        "parse_warnings": warnings if manual else [],
    }


def _codes(doc: dict) -> list[str]:
    return [w.code for w in extraction.extract("p", "ELS", doc, llm=_NoCandidates()).warnings]


def test_a_manual_parse_is_carried_into_the_extraction_output() -> None:
    """★ `MANUAL_OVERRIDE` 가 있으면 추출 경고에 `MANUAL_SOURCE` 가 실린다."""
    assert "MANUAL_SOURCE" in _codes(_doc(manual=True)), (
        "수동 파스에서 나온 항목인데 추출 출력이 그 사실을 안 싣는다 — "
        "F-EXT-003·#409 가 파서 성능의 분모에서 걸러야 할 자리다")


def test_a_normal_parse_carries_nothing() -> None:
    """★ 양성 대조의 반대쪽 — 정상 파스에 붙으면 오탐이다.

    이게 없으면 `MANUAL_SOURCE` 를 **무조건 싣는** 구현도 위 테스트를 통과한다.
    """
    assert "MANUAL_SOURCE" not in _codes(_doc(manual=False))


def test_the_warning_is_document_scoped() -> None:
    """★ `item_id` 를 비운다 — 항목별 사실이 아니라 그 파스 출력 전체의 성질이다."""
    got = [w for w in extraction.extract("p", "ELS", _doc(manual=True), llm=_NoCandidates()).warnings
           if w.code == "MANUAL_SOURCE"]
    assert got and got[0].item_id is None, (
        f"문서 단위 경고인데 item_id 가 붙었다: {got[0].item_id if got else '(없음)'}")


def test_the_marker_matches_what_parsing_writes() -> None:
    """★ **두 벌이 되지 않게** — 파싱이 쓰는 코드와 추출이 읽는 코드가 같은 문자열이다.

    `parse_warnings` 의 코드가 바뀌면 여기서 걸린다. 안 걸리면 추출이 조용히 아무것도 안
    싣고, 그 침묵이 *"수동이 아니다"* 로 읽힌다 — `#441` 이 `.json` 폴백을 막은 것과 같은
    이유다(**조용한 실패가 제일 나쁘다**).
    """
    from app.schemas import ParsedDocument

    fields = ParsedDocument.model_fields
    assert "parse_warnings" in fields, "파싱 출력의 필드명이 바뀌었다 — 이 전파가 끊긴다"

    manual = ParsedDocument.model_validate(_doc(manual=True))
    assert manual.is_manual, "ParsedDocument.is_manual 이 MANUAL_OVERRIDE 를 못 읽는다"
    assert not ParsedDocument.model_validate(_doc(manual=False)).is_manual
