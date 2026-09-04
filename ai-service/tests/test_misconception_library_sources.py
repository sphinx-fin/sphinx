"""오해 라이브러리의 **근거와 판별자**를 잠근다. 소유: 정세현 (`data/misconception_library/`)

매칭 엔진 자체는 `test_misconception.py`(윤지석)가 본다. 여기서 보는 것은 그 엔진에
실리는 **데이터가 성립하는가**다 — 근거가 실재하는가, 그리고 잡으면 안 되는 것을 안 잡는가.
둘 다 데이터를 고칠 때 깨져야 하는 성질이라 파일을 갈라 둔다.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import pytest
import yaml

from app import misconception

ROOT = Path(__file__).resolve().parents[2]
SAMPLES = ROOT / "contracts" / "samples"
LIBRARY = ROOT / "data" / "misconception_library" / "misconceptions.yaml"


def _flat(text: str) -> str:
    """공백을 지운다. 파서가 원문을 줄 중간에서 끊으므로 그대로는 못 찾는다."""
    return re.sub(r"\s+", "", text)


def _corpus() -> dict[str, str]:
    """`{파일명: 공백 지운 전문}`. 근거자료가 곧 정본이다."""
    out = {}
    for path in sorted(SAMPLES.glob("parsed_*.json")):
        parsed = json.loads(path.read_text(encoding="utf-8"))
        out[path.name] = _flat(" ".join(p.get("text", "") for p in parsed.get("pages", [])))
    return out


def _product_document_entries() -> list[dict]:
    """❗**로더가 아니라 파일에서 읽는다.**

    `SourceRef` 는 `type`·`ref` 만 받고 `quote` 를 버린다(라이브러리 머리말이 그렇게
    적어 뒀다). 그래서 `misconception.library()` 로는 이 검사를 아예 쓸 수가 없고,
    동시에 그것이 **이 검사가 필요한 이유**다 — 인용 문면은 지금 어느 코드도 안 읽으므로
    원문과 갈려도 런타임에서는 영원히 안 걸린다.
    """
    raw = yaml.safe_load(LIBRARY.read_text(encoding="utf-8"))
    return [
        entry
        for entry in raw["types"]
        if (entry.get("source") or {}).get("type") == "product_document"
    ]


def test_there_is_at_least_one_product_document_type() -> None:
    """아래 검사가 **빈 목록 위에서 조용히 통과**하는 것을 막는다."""
    assert _product_document_entries(), "product_document 근거 유형이 하나도 없다"


def test_the_loader_still_drops_quote() -> None:
    """위 함수가 파일을 읽는 **이유**를 잠근다.

    로더가 언젠가 `quote` 를 받게 되면 이 검사가 먼저 빨개진다 — 그때는 파일 파싱을
    걷어내고 `misconception.library()` 로 옮기는 게 맞다. 근거가 사라졌는데 우회가
    남아 있는 상태를 안 만든다.
    """
    sample = _product_document_entries()[0]
    loaded = next(t for t in misconception.library() if t.type_id == sample["id"])
    assert not hasattr(loaded.source, "quote"), (
        "SourceRef 가 quote 를 갖게 됐다 — 이 파일의 YAML 직접 파싱을 걷어내라"
    )


@pytest.mark.parametrize(
    "entry", _product_document_entries(), ids=lambda e: e["id"]
)
def test_product_document_quotes_exist_in_the_source(entry: dict) -> None:
    """★ `product_document` 근거의 `quote` 는 근거자료 원문에 **실재해야** 한다.

    이 계열이 근거로 서는 이유는 *"원문이 정면으로 반대로 적고 있다"* 하나다
    (`misconception.SOURCE_TYPES` 주석 · 이슈 #148). 그 원문이 실제로 그렇게 적혀
    있지 않으면 근거가 아니라 **주장**이고, 결정 3.17 이 막은 자리로 되돌아간다.

    ❗로더는 `quote` 를 읽지 않는다(`SourceRef` 는 type·ref 만 받는다). 그래서 여기가
    아니면 이 문장이 원문과 갈려도 **아무 데서도 안 걸린다** — 감사 시점에야 드러난다.

    공백을 지우고 비교하는 이유는 파서가 원문을 줄 중간에서 끊기 때문이다
    (`"최저사\\n망지급금"`). 글자는 원문 그대로여야 한다.
    """
    source = entry["source"]
    needle = _flat(source.get("quote") or "")
    assert needle, f"{entry['id']} 의 product_document 근거에 quote 가 없다"
    found = [name for name, text in _corpus().items() if needle in text]
    assert found, (
        f"{entry['id']} 의 quote 가 어느 근거자료에도 없다 — ref: {source.get('ref')}"
    )


# ── M11 판별자 ────────────────────────────────────────────────────────────────
#
# ❗`apply_misconception_floor` 는 **stage 를 안 본다.** `related_misconceptions` 에 있고
# score >= ngram_match(0.62) 이면 `ngram` 단계도 그대로 U4 로 확정한다. 그래서 M11 의
# 안전성은 "패턴 단계로만 잡는다"가 아니라 **부분적으로 참인 발화가 0.62 에 못 미친다**는
# 데 있고, 그것을 만드는 것은 패턴 **길이**다(`textsim.containment` 의 분모가 패턴
# 바이그램 수다). 짧은 조각으로 쪼개면 아래 셋이 0.6667 로 걸린다 — 실측했다.
#
# PR #57 이 M02 에서 변액을 뺀 이유가 정확히 이 오판이므로, 같은 실패를 M11 이 다른
# 이름으로 반복하지 않게 여기서 잠근다.

#: 원문상 **맞거나 부분적으로 참**인 변액 예금자보호 발화. 하나도 걸리면 안 된다.
_TRUE_ENOUGH = (
    "이거 예금자보호 되는 줄 알았는데요",
    "최저사망지급금은 예금자보호가 된다는 거죠",
    "1억원까지는 예금자보호가 된다고 하셨죠",
    "계약 전체가 아니라 최저보증하고 특약만 한도 안에서 보호된다는 거네요",
)

#: 원문이 정면으로 반박하는 발화. 걸려야 한다.
_FALSE = (
    "이것도 예금처럼 전액 보호되는 거 아닌가요",
    "낸 보험료 전액이 보호 대상이라고 들었어요",
)

_SCOPE = "M11-DEPOSIT-INSURANCE-SCOPE"


def _scope_match(utterance: str):
    matched = misconception.match(utterance, "VARIABLE_INSURANCE")
    return next((m for m in matched.matches if m.type_id == _SCOPE), None)


@pytest.mark.parametrize("utterance", _TRUE_ENOUGH)
def test_the_scope_type_does_not_catch_true_enough_speech(utterance: str) -> None:
    """★ 부분적으로 참인 발화를 M11 이 물면 안 된다 (PR #57 의 오판 재발 방지)."""
    hit = _scope_match(utterance)
    assert hit is None, (
        f"부분적으로 참인 발화를 {_SCOPE} 가 물었다 ({hit.stage} {hit.score}) — "
        "패턴을 짧게 쪼개면 이렇게 된다. floor 는 stage 를 안 보므로 ngram 도 U4 확정이다"
    )


@pytest.mark.parametrize("utterance", _FALSE)
def test_the_scope_type_catches_totality_claims(utterance: str) -> None:
    """전체성("전액")을 주장하는 발화는 잡는다 — 원문이 「한하여」로 정면 반박한다."""
    assert _scope_match(utterance) is not None, f"정면 거짓을 {_SCOPE} 가 놓쳤다"
