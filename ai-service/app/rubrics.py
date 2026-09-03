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

from .schemas import PRODUCT_TYPES

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

    # ❗`product_type` 을 검증한다 (PR #310 리뷰, 정세현).
    #
    # 전에는 `raw.get("product_type", "ELS")` 였다 — 그 줄이 빠진 변액 루브릭이 **조용히
    # ELS 가 됐다.** 그 필드를 읽는 곳이 없어서 아무 일도 안 일어났는데, 이 PR 이 그것을
    # 처음 소비하면서(도달 불가 대조) **누락이 엉뚱한 자리에서 터진다** — 가드가 유형의
    # `products` 를 탓하고, 그 안내를 따르면 결정 10.24 가 막는 변경을 하게 된다.
    #
    # 같은 레포의 다른 로더 셋은 전부 검증한다 — `misconception` 의
    # `_assert_products_are_canonical`, `templates` 의 필수 키, `parsing` 의 PRODUCT_TYPES
    # 대조. **여기만 없었다.** 기본값을 남겨 두지 않는 이유는 루브릭이 상품별로 갈리는
    # 파일이라 *"안 적으면 ELS"* 가 조용히 참이 되는 자리가 없어야 하기 때문이다.
    product_type = raw.get("product_type")
    if product_type not in PRODUCT_TYPES:
        raise ValueError(
            f"{path.name}: product_type 은 {PRODUCT_TYPES} 중 하나여야 한다 — "
            f"받은 값 {product_type!r}. 안 적으면 기본값으로 떨어지지 않는다(PR #310 리뷰)"
        )

    return Rubric(
        item_id=raw["item_id"],
        product_type=product_type,
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


def assert_related_misconceptions_exist() -> None:
    """루브릭의 `related_misconceptions` 가 오해 라이브러리에 실제로 있는지 확인한다.

    없는 유형을 참조하면 `apply_misconception_floor` 가 **예외도 로그도 없이 발동하지
    않는다** — 해당 항목의 결정론적 U4 상향이 사라지고, 채점은 계속 성공한다.
    라이브러리 데이터 소유는 정세현이므로 유형이 빠지는 일이 실제로 있었다
    (M07-YIELD-OVERCONFIDENCE, 근거 미확보로 삭제). 그때 이 검사가 없어서 테스트의
    개수 단정문이 뒤늦게 잡았다.

    ## ❗ID 실재만으로는 부족하다 — 도달 불가 링크 (이슈 #284, 정세현 지적)

    매처는 `products` 로 **먼저 거른다.** 그래서 루브릭의 `product_type` 이 그 유형의
    `products` 에 없으면 **매칭이 아예 만들어지지 않는다.**

        misconception.match(발화, product_type)
          [ELS               ] "예금자보호 되는 상품 아닌가요" → ['M02-DEPOSIT-INSURANCE']
          [VARIABLE_INSURANCE] 같은 발화                      → []   ← M02 는 products: [ELS]

    ID 만 보면 **통과한다.** 그러면 링크는 초록인데 floor 는 안 걸리고, 더 나쁘게는
    `enforcement_gaps()` 가 *"링크가 있으니 강제된다"* 로 세어서 **공백이 공백으로 안
    보이게 된다.** `#284` 가 드러낸 그 공백 위에 아무것도 안 하는 링크가 얹히는 모양이다.

    `applies_to()` 를 그대로 쓴다 — 매처가 거를 때 쓰는 그 함수다. 여기서 `products` 를
    따로 해석하면 판정 기준이 두 벌이 되고, 언젠가 한쪽만 바뀐다.

    **던지는 이유**: 참조가 실재해도 **도달할 수 없으면 데이터 오류**다. 위 `dangling` 과
    같은 성격이고, `enforcement_gaps()` 가 로그로만 남기는 것(*"유형이 아직 없다"*)과는
    다르다 — 그쪽은 **아직 안 만든 것**이고 이쪽은 **잘못 이은 것**이다.

    misconception 을 지연 임포트한다 — 그쪽이 이 모듈을 쓰지는 않지만 순환 위험을 남기지 않는다.
    """
    from .misconception import library

    types = {m.type_id: m for m in library()}
    dangling: dict[str, list[str]] = {}
    dead: dict[str, list[str]] = {}
    for item_id, rubric in _all().items():
        missing = [t for t in rubric.related_misconceptions if t not in types]
        if missing:
            dangling[item_id] = missing
        # ❗도달 불가 링크 — 유형은 실재하는데 그 상품에서는 **매처가 만들지 않는다**
        unreachable = [
            t for t in rubric.related_misconceptions
            if t in types and not types[t].applies_to(rubric.product_type)
        ]
        if unreachable:
            dead[item_id] = unreachable
    if dangling:
        raise ValueError(
            "라이브러리에 없는 오해 유형을 참조하는 루브릭이 있다 "
            f"(결정론 상향이 조용히 사라진다): {dangling}"
        )
    if dead:
        # ❗**두 변을 다 싣는다** (PR #310 리뷰, 정세현). 도달 불가는 루브릭의
        # `product_type` 과 유형의 `products` 사이의 관계인데, 유형 쪽만 보이면 메시지가
        # 사람을 **틀린 쪽으로 보낸다.**
        #
        # 실측된 경로: `VAR-PRINCIPAL-LOSS.yaml` 에서 `product_type:` 한 줄이 빠지면
        # 로더가 조용히 `ELS` 로 떨어뜨리고(아래 `_parse` 참고) 이 가드가 잡는다. 그때
        # 유형 쪽만 보이면 *"M05-SAVINGS 의 products 에 ELS 를 더해라"* 로 읽히는데
        # **그건 결정 10.24 가 막는 변경**이다. 실제로 고칠 것은 루브릭 한 줄이다.
        detail = {
            f"{k}(product_type={_all()[k].product_type})":
                [f"{t}(products={types[t].products})" for t in v]
            for k, v in dead.items()
        }
        raise ValueError(
            "그 상품에서 도달할 수 없는 오해 유형을 참조하는 루브릭이 있다 — 링크는 "
            "있는데 매처가 그 유형을 만들지 않으므로 결정론 상향이 일어나지 않는다. "
            "**먼저 루브릭의 product_type 이 맞는지 본다** — 그 줄이 빠지면 로더가 조용히 "
            "ELS 로 떨어뜨린다. 그게 맞다면, 유형의 products 를 넓히는 것은 그 유형이 그 "
            "상품에서도 참일 때만 하고 아니면 그 상품용 유형을 새로 만든다(결정 10.24). "
            f"{detail}"
        )
