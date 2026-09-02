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


#: 링크가 비어 있는 것이 **누락이 아니라 의도**인 루브릭 → 그 판단이 적힌 PR (이슈 #298 리뷰).
#:
#: 처음에는 이 딕셔너리 없이 냈고, `VAR-PARTIAL-DEPOSIT-INSURANCE` 를 *"누가 봐도 M02 자리인데
#: 링크가 비어 있다 — 단순 누락으로 보인다"* 고 적었다. **틀렸다.** 그 파일 머리말이 이미 이유를
#: 적어 두고 있었다 — `#57` 로 M02 가 ELS 전용이 됐고, 변액에서 *"예금자보호 되는 줄"* 은
#: **부분적으로 참**이라 결정론 상향이 **오판**이었다. 빈 목록이 그 정정의 결과다.
#:
#: 그 항목은 대신 `required_elements` 가 *"어디까지 보호되는가"* 를 요구하는 쪽으로 설계됐다
#: (`ADR-007` 부수 발견 · `PR #53`) — 결정론 보조가 없는 것을 전제로 조항이 촘촘하다.
#:
#: ❗**그래서 경고를 내면 안 된다.** 이 기능이 내는 경고는 지금 이것뿐이라, 오탐 하나가 기동
#: 로그에 상시로 서면 **다음에 진짜 사각이 생겨도 같은 줄로 보인다.** 그리고 확인받는 쪽이
#: M02 를 도로 링크할 수 있다 — `#57` 이 오판이라고 판정한 상향이 돌아온다.
#:
#: `#228` 의 `_CUE_UNREACHABLE` 과 성격이 다르다. 그건 *닿지 못하는 것*을 못박은 것이고
#: 이건 *닿지 않게 한 것*이다. 그래서 이름도 `unlinked` 가 아니라 `intentionally` 다.
#:
#: **손으로 채울 수 없다.** `test_intentional_exceptions_cite_their_reason` 이 각 항목의
#: 루브릭 파일에 그 PR 번호가 실제로 적혀 있는지 대조한다 — 없으면 실패한다.
_INTENTIONALLY_UNLINKED: dict[str, str] = {
    "VAR-PARTIAL-DEPOSIT-INSURANCE": "PR #57",
}


def enforcement_gaps() -> dict[str, tuple[str, ...]]:
    """오해 조건을 선언했는데 **강제할 통로가 없는** 루브릭 → 그 조건들 (이슈 #284).

    ## 무엇이 문제인가

    루브릭에 목록이 둘 있고 **성격이 다르다.**

        misconception_conditions   프롬프트에 실린다. 모델이 읽고 판단한다    → 강제력 없음
        related_misconceptions     apply_misconception_floor 가 U4 로 올린다  → 강제력 있음

    두 목록이 **어디서도 대조되지 않는다.** 그래서 루브릭이 *"이건 오해다"* 를 선언해도
    모델이 놓치면 아무 일도 안 일어난다 — `els-0028`(발행사 신용위험)이 실물이다.
    `gpt-5-mini` 가 `U2` 로 부르고 통과했는데, 그 항목 루브릭은
    *"기초자산만 오르면 안전하다"* 를 이미 오해 조건으로 적어 뒀다.

    ## 왜 여기서 다 못 잡나

    조건 문면 → 라이브러리 유형ID 의 대응은 **정적으로 계산할 수 없다.**
    `"증권사가 망할 리 없다"` 가 어느 유형인지 코드가 알 방법이 없고, 그걸 알아내려는 것이
    `(b)`(유사도 매칭)인데 그건 채점 동작을 바꾸는 일이라 별건이다.

    그래서 **확실한 것만** 잡는다: `related_misconceptions` 가 **비어 있으면** 그 루브릭의
    조건은 하나도 강제될 수 없다. 이건 계산이 아니라 사실이다.

    나머지(링크는 있는데 그 조건에 안 맞는 경우)는 이 함수로 안 보인다 —
    `tests/test_enforcement_gap.py` 가 **전체 비율을 못박아** 조용히 늘지 않게 한다.

    ## 예외를 던지지 않는 이유

    `assert_related_misconceptions_exist` 는 던진다 — 그건 **참조가 깨진 것**이라 데이터
    오류다. 이쪽은 *"아직 라이브러리에 그 유형이 없다"* 이고 유형 추가는 근거 자료가
    필요하다(`misconceptions.yaml` 의 `source` 를 채워야 한다). 기동을 막으면 그 사이
    서비스가 안 뜬다 — `#228` 이 cue 사각을 예외가 아니라 **목록으로 못박은** 것과 같은
    판단이다.
    """
    gaps: dict[str, tuple[str, ...]] = {}
    for item_id, rubric in _all().items():
        if item_id in _INTENTIONALLY_UNLINKED:
            continue                    # 의도된 정정이다 — 위 주석 참고
        if rubric.misconception_conditions and not rubric.related_misconceptions:
            gaps[item_id] = rubric.misconception_conditions
    return gaps


def intentionally_unlinked() -> dict[str, str]:
    """링크가 비어 있는 것이 의도인 루브릭 → 근거 PR. 공개 진입점."""
    return dict(_INTENTIONALLY_UNLINKED)


def enforcement_coverage() -> tuple[int, int, int]:
    """(선언된 조건 수, 링크를 가진 루브릭 수, 전체 루브릭 수).

    비율 자체가 결함은 아니다 — 조건 하나에 유형 하나가 대응할 이유가 없다. 다만 **그
    비율이 어디쯤인지 아무도 모르는 것**이 `#284` 가 드러낸 상태였다. 기동 로그에 남긴다.
    """
    rs = _all().values()
    declared = sum(len(r.misconception_conditions) for r in rs)
    linked = sum(1 for r in rs if r.related_misconceptions)
    return declared, linked, len(rs)


def assert_related_misconceptions_exist() -> None:
    """루브릭의 `related_misconceptions` 가 오해 라이브러리에 실제로 있는지 확인한다.

    없는 유형을 참조하면 `apply_misconception_floor` 가 **예외도 로그도 없이 발동하지
    않는다** — 해당 항목의 결정론적 U4 상향이 사라지고, 채점은 계속 성공한다.
    라이브러리 데이터 소유는 정세현이므로 유형이 빠지는 일이 실제로 있었다
    (M07-YIELD-OVERCONFIDENCE, 근거 미확보로 삭제). 그때 이 검사가 없어서 테스트의
    개수 단정문이 뒤늦게 잡았다.

    misconception 을 지연 임포트한다 — 그쪽이 이 모듈을 쓰지는 않지만 순환 위험을 남기지 않는다.
    """
    from .misconception import library

    known = {m.type_id for m in library()}
    dangling: dict[str, list[str]] = {}
    for item_id, rubric in _all().items():
        missing = [t for t in rubric.related_misconceptions if t not in known]
        if missing:
            dangling[item_id] = missing
    if dangling:
        raise ValueError(
            "라이브러리에 없는 오해 유형을 참조하는 루브릭이 있다 "
            f"(결정론 상향이 조용히 사라진다): {dangling}"
        )
