"""상품유형 템플릿 로더. 소유: 윤지석

기획서 5절 [생성형 AI의 역할] 통제: **"상품유형 템플릿으로 추출 범위를 고정한다."**

이 모듈이 F-EXT-002 의 추출 범위를 정하고, 그것이 곧 **F-EXT-003 재현율의 분모**다
(이슈 #26). 템플릿에 없는 항목은 추출되지 않으므로 재현율 상한이 여기서 고정된다 —
루브릭 유무는 재현율에 영향을 주지 않는다(그쪽은 F-SCR-001 채점 커버리지다).

`item_id` 정본은 ADR-006 에 따라 `contracts/samples/*.json` 의 `_expected_risk_items` 다.
템플릿이 그 목록과 어긋나면 추출이 계약에 없는 항목을 내거나 있는 항목을 놓치는데,
**둘 다 예외 없이 조용히 지나간다** — 그래서 로딩 시점에 대조한다.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import yaml

TEMPLATE_DIR = Path(__file__).resolve().parent / "templates"
CONTRACT_SAMPLES = Path(__file__).resolve().parents[2] / "contracts" / "samples"

IMPORTANCE_VALUES = ("required", "recommended")

#: 상품유형 → **재현율 분모가 되는** 계약 샘플. 상품유형마다 정확히 하나다.
#:
#: 샘플 파일이 여럿 있을 수 있다 — 변액은 `parsed_variable_ops_sample.json`(운용설명서)가
#: 교차 검증용으로 함께 있다(PR #104). **그건 분모가 아니다.** 여기 적힌 것만 분모다.
#:
#: 이 표에 없는 상품유형이나 없어진 파일을 만나면 **터진다.** 이전에는 None 을 돌려주고
#: assert_matches_contract() 가 조용히 통과했다 — 새 상품유형을 추가하면 계약 대조가
#: 검사 없이 지나가고, 그건 이 함수가 막으려는 실패 그 자체다.
CONTRACT_SAMPLE_BY_PRODUCT = {
    "ELS": "parsed_els_sample.json",
    "VARIABLE_INSURANCE": "parsed_variable_sample.json",
}


@dataclass(frozen=True)
class TemplateItem:
    """추출 대상 이해항목 한 건.

    `cue` 는 **상품에 무관해야 한다.** 특정 회차의 수치를 넣으면 다른 발행사 문서에
    붙지 않는다 — 값은 추출 시점에 원문에서 가져온다(P6).
    """

    item_id: str
    name: str
    cue: str
    importance: str | None = None
    section_hint: str | None = None
    found_in: tuple[str, ...] = ()
    conflict: str | None = None
    #: F-INT-002 — 생성 질문이 정답 노출 검사를 통과하지 못할 때 쓰는 기본 질문.
    #: 인터뷰가 멈추면 세션이 진행되지 않으므로 폴백이 없으면 안 된다.
    fallback_question: str | None = None
    #: 이 항목의 값이 쓰는 단위. **표 셀 항목에만 있다** (이슈 #175).
    #:
    #: 표에서는 단위가 **표 상단 선언**에 있고 값이 든 행에는 없다. `value_text` 는 행
    #: 하나뿐이라 `source_values()` 가 `('526240', None)` 을 낸다 — 그 상태에서 재설명이
    #: `"526,240원"` 이라고 쓰면 **값은 원문에 그대로 있는데 환각으로 걸린다.** 자연스러운
    #: 문장이 3회 재시도 끝에 버려지고 최소 문면으로 내려간다.
    #:
    #: **손으로 지어낸 값이 아니다 — 원문의 선언을 옮긴 것이다.**
    #:
    #:     원문 p12  "(단위 : 원, %)"   →   units: [원, pct]
    #:
    #: `test_declared_units_come_from_the_document` 가 계약 샘플에서 그 선언을 찾아 대조한다.
    #: 회차·발행사가 바뀌어도 표의 단위는 안 바뀐다(환급률은 언제나 %). `cue` 에 숫자를
    #: 금지한 것과 성격이 다르다 — 그건 회차 값이고 이건 표의 성질이다.
    units: tuple[str, ...] = ()

    @property
    def importance_assigned(self) -> bool:
        return self.importance in IMPORTANCE_VALUES


@dataclass(frozen=True)
class ProductTemplate:
    product_type: str
    version: int
    answer_set_documents: tuple[str, ...]
    items: tuple[TemplateItem, ...]

    @property
    def item_ids(self) -> tuple[str, ...]:
        return tuple(i.item_id for i in self.items)

    def required_items(self) -> tuple[TemplateItem, ...]:
        return tuple(i for i in self.items if i.importance == "required")

    def items_without_importance(self) -> tuple[str, ...]:
        return tuple(i.item_id for i in self.items if not i.importance_assigned)

    def conflicts(self) -> tuple[TemplateItem, ...]:
        """정답지 문서들이 서로 다른 말을 하는 항목 (ADR-007 합집합의 부작용)."""
        return tuple(i for i in self.items if i.conflict)


class TemplateNotFound(KeyError):
    pass


def _parse(path: Path) -> ProductTemplate:
    raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    for key in ("product_type", "items"):
        if not raw.get(key):
            raise ValueError(f"{path.name}: 필수 키 누락 {key!r}")

    items = []
    seen: set[str] = set()
    for entry in raw["items"]:
        for key in ("item_id", "name", "cue", "fallback_question"):
            if not entry.get(key):
                raise ValueError(f"{path.name}: {entry.get('item_id')} 에 {key!r} 없음")
        importance = entry.get("importance")
        if importance is not None and importance not in IMPORTANCE_VALUES:
            raise ValueError(
                f"{path.name}: {entry['item_id']} importance={importance!r} — "
                f"허용: {list(IMPORTANCE_VALUES)} 또는 null(미정)"
            )
        if entry["item_id"] in seen:
            raise ValueError(f"{path.name}: item_id 중복 {entry['item_id']}")
        seen.add(entry["item_id"])
        items.append(
            TemplateItem(
                item_id=entry["item_id"], name=entry["name"], cue=entry["cue"],
                importance=importance, section_hint=entry.get("section_hint"),
                found_in=tuple(entry.get("found_in") or ()),
                conflict=entry.get("conflict"),
                fallback_question=entry.get("fallback_question"),
                units=tuple(entry.get("units") or ()),
            )
        )
    return ProductTemplate(
        product_type=raw["product_type"],
        version=int(raw.get("version", 1)),
        answer_set_documents=tuple(raw.get("answer_set_documents") or ()),
        items=tuple(items),
    )


def _parse_entry_for_test(entry: dict) -> TemplateItem:
    """단일 항목 검증 경로를 테스트에서 쓰기 위한 얇은 진입점."""
    importance = entry.get("importance")
    if importance is not None and importance not in IMPORTANCE_VALUES:
        raise ValueError(
            f"{entry.get('item_id')} importance={importance!r} — "
            f"허용: {list(IMPORTANCE_VALUES)} 또는 null(미정)"
        )
    return TemplateItem(item_id=entry["item_id"], name=entry["name"], cue=entry["cue"],
                        importance=importance)


@lru_cache(maxsize=1)
def _all() -> dict[str, ProductTemplate]:
    out: dict[str, ProductTemplate] = {}
    for path in sorted(TEMPLATE_DIR.glob("*.yaml")):
        tpl = _parse(path)
        out[tpl.product_type] = tpl
    return out


def get(product_type: str) -> ProductTemplate:
    try:
        return _all()[product_type]
    except KeyError as exc:
        raise TemplateNotFound(f"상품유형 템플릿 없음: {product_type}") from exc


def all_templates() -> dict[str, ProductTemplate]:
    return dict(_all())


# ── 계약 대조 ─────────────────────────────────────────────────────────────────
class ContractSampleMissing(FileNotFoundError):
    """분모가 되는 계약 샘플을 찾을 수 없다. 조용히 넘기지 않는다."""


def contract_item_ids(product_type: str) -> tuple[str, ...]:
    """계약 샘플의 `_expected_risk_items` item_id 목록 — **재현율 분모**.

    매핑에 없는 상품유형이거나 파일이 없으면 예외다. None 을 돌려주면 호출자가 "검사할 것이
    없다"로 읽고 대조를 건너뛴다.
    """
    name = CONTRACT_SAMPLE_BY_PRODUCT.get(product_type)
    if name is None:
        raise ContractSampleMissing(
            f"{product_type} 의 계약 샘플 매핑이 없다 — CONTRACT_SAMPLE_BY_PRODUCT 에 추가해야 "
            "재현율 분모가 정해진다"
        )
    path = CONTRACT_SAMPLES / name
    if not path.is_file():
        raise ContractSampleMissing(f"계약 샘플 파일이 없다: {path}")
    raw = json.loads(path.read_text(encoding="utf-8"))
    return tuple(i["item_id"] for i in raw.get("_expected_risk_items", []))


def assert_matches_contract(product_type: str) -> None:
    """템플릿 항목 집합이 계약 샘플과 정확히 같은지 확인한다.

    어긋나면 두 방향으로 조용히 틀어진다 — 템플릿에만 있는 항목은 추출돼도 정답 세트에
    없으니 오탐이 되고, 계약에만 있는 항목은 추출 자체가 안 되니 재현율이 구조적으로 깎인다.
    어느 쪽도 예외를 던지지 않으므로 여기서 대조한다.
    """
    contract = contract_item_ids(product_type)
    template = set(get(product_type).item_ids)
    expected = set(contract)
    only_template = sorted(template - expected)
    only_contract = sorted(expected - template)
    if only_template or only_contract:
        raise ValueError(
            f"{product_type} 템플릿이 계약 샘플과 다르다 — "
            f"템플릿에만: {only_template}, 계약에만: {only_contract}"
        )


def coverage_report() -> dict[str, dict[str, object]]:
    """분모 보고용. F-EXT-003 리포트에 그대로 인용할 수 있는 형태."""
    from . import rubrics

    have_rubric = set(rubrics.all_rubrics())
    out: dict[str, dict[str, object]] = {}
    for product_type, tpl in _all().items():
        out[product_type] = {
            "template_items": len(tpl.items),
            "contract_items": len(contract_item_ids(product_type)),
            "importance_assigned": len(tpl.items) - len(tpl.items_without_importance()),
            "required": len(tpl.required_items()),
            "rubric_covered": sum(1 for i in tpl.item_ids if i in have_rubric),
            "conflicts": [i.item_id for i in tpl.conflicts()],
        }
    return out
