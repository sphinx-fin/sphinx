"""채점 루브릭 로더. 소유: 윤지석

기획서 5절 통제: **"루브릭을 공개하고, 근거 표시를 의무화한다."**
따라서 루브릭은 코드가 아니라 데이터로 두고, 판정마다 어느 조항을 근거로 썼는지
`Judgment.evidence.rubric_clause`에 남긴다 (P4).

`status: draft`는 핵심설명서 대조가 아직 안 된 것이다. 기획서 5절이 핵심설명서를
정답지로 쓴다고 명시했으므로, 근거 자료(정세현 공급) 도착 후 confirmed로 올린다.
"""
from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import yaml

RUBRIC_DIR = Path(__file__).resolve().parent / "rubrics"


@dataclass(frozen=True)
class Rubric:
    item_id: str
    product_type: str
    name: str
    status: str                          # confirmed | draft
    required_elements: tuple[str, ...]    # 이해로 인정되려면 언급돼야 하는 것
    misconception_conditions: tuple[str, ...]  # 언급되면 오해(U4)로 보는 것
    related_misconceptions: tuple[str, ...]    # 오해 라이브러리 유형ID

    @property
    def is_draft(self) -> bool:
        return self.status != "confirmed"


class RubricNotFound(KeyError):
    pass


def _parse(path: Path) -> Rubric:
    raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    missing = [k for k in ("item_id", "required_elements") if not raw.get(k)]
    if missing:
        raise ValueError(f"{path.name}: 필수 키 누락 {missing}")
    return Rubric(
        item_id=raw["item_id"],
        product_type=raw.get("product_type", "ELS"),
        name=raw.get("name", raw["item_id"]),
        status=raw.get("status", "draft"),
        required_elements=tuple(raw["required_elements"]),
        misconception_conditions=tuple(raw.get("misconception_conditions") or ()),
        related_misconceptions=tuple(raw.get("related_misconceptions") or ()),
    )


@lru_cache(maxsize=1)
def _all() -> dict[str, Rubric]:
    out: dict[str, Rubric] = {}
    for path in sorted(RUBRIC_DIR.glob("*.yaml")):
        rubric = _parse(path)
        out[rubric.item_id] = rubric
    return out


def get(item_id: str) -> Rubric:
    try:
        return _all()[item_id]
    except KeyError as exc:
        raise RubricNotFound(f"루브릭 없음: {item_id}") from exc


def all_rubrics() -> dict[str, Rubric]:
    return dict(_all())
