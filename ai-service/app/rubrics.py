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
    u1_requires: int                      # 그중 **몇 개**를 충족해야 U1 인가 (아래 참조)
    misconception_conditions: tuple[str, ...]  # 언급되면 오해(U4)로 보는 것
    related_misconceptions: tuple[str, ...]    # 오해 라이브러리 유형ID
    unlinked_until: tuple[str, str] | None     # (근거, 빼는 조건) — 링크가 빈 이유

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

    elements = tuple(raw["required_elements"])

    # ❗**U1 의 문턱을 파일이 스스로 말한다.** `product_type` 과 같은 이유로 기본값을 안 둔다.
    #
    # 이 필드가 없던 동안 U1↔U2 는 *"필수 요소를 자기 말로 설명했다"* 라는 **홀리스틱
    # 판단**이었고, 프롬프트의 *"애매하면 U2"* 는 기준이 아니라 동점 처리 규칙이었다.
    # F-CMN-003 실측이 그 대가를 보여 준다 — **사람 둘이 같은 루브릭을 들고 27.1% 에서
    # 갈렸고, 불일치 19건 중 9건(47%)이 U1↔U2** 였다(리포트 1절 혼동행렬).
    #
    # 모델이 그 천장을 못 넘는 것은 모델 탓이 아니다. **정답이 정해져 있지 않았다.**
    #
    #     충족 == u1_requires    → U1
    #     1 ≤ 충족 < u1_requires → U2
    #     충족 == 0              → U3   (오해 조건에 걸리면 U4)
    #
    # 기본값을 두면 *"안 적으면 전부 충족"* 이 조용히 참이 되고, 그러면 이 필드가 생기기
    # 전과 같은 상태가 파일마다 숨는다 — `#310` 이 `product_type` 에서 잡은 그 모양이다.
    bar = raw.get("u1_requires")
    if not isinstance(bar, int) or isinstance(bar, bool) or not 1 <= bar <= len(elements):
        raise ValueError(
            f"{path.name}: u1_requires 는 1 이상 {len(elements)} 이하의 정수여야 한다 — "
            f"받은 값 {bar!r}. 필수 요소 {len(elements)}개 중 **몇 개**를 충족해야 U1 인지 "
            "적는다. 안 적으면 U1↔U2 가 다시 홀리스틱 판단이 된다(F-CMN-003 실측: "
            "사람 불일치 19건 중 9건이 그 경계)"
        )
    related = tuple(raw.get("related_misconceptions") or ())

    # ❗**양방향으로 대조한다.** 한쪽만 보면 둘 중 하나가 조용하다.
    #
    #   링크가 있는데 unlinked_until 도 있다   → 모순. 어느 쪽이 참인지 알 수 없다 → 터진다
    #   링크가 없는데 unlinked_until 이 없다    → enforcement_gaps() 가 기동 경고로 받는다
    #                                            (여기서 안 던진다 — 그 함수의 이유 참고)
    block = raw.get("unlinked_until")
    unlinked: tuple[str, str] | None = None
    if block is not None:
        if related:
            raise ValueError(
                f"{path.name}: related_misconceptions 가 있는데 unlinked_until 도 있다 — "
                "그건 링크가 **빈** 이유를 적는 자리다. 둘 다 두면 어느 쪽이 참인지 모른다"
            )
        if not isinstance(block, dict) or not block.get("reason") or not block.get("until"):
            raise ValueError(
                f"{path.name}: unlinked_until 은 reason·until 두 키가 다 있어야 한다 — "
                f"받은 값 {block!r}. `until` 이 없으면 **지우는 사건이 안 온다**(결정 10.67)"
            )
        unlinked = (str(block["reason"]).strip(), str(block["until"]).strip())

    return Rubric(
        item_id=raw["item_id"],
        product_type=product_type,
        name=raw.get("name", raw["item_id"]),
        status=raw.get("status", "draft"),
        required_elements=elements,
        u1_requires=bar,
        misconception_conditions=tuple(raw.get("misconception_conditions") or ()),
        related_misconceptions=related,
        unlinked_until=unlinked,
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


#: ❗**링크가 빈 이유는 루브릭 파일이 말한다** (이슈 #284 (c)).
#:
#: 전에는 여기 `_UNLINKED_UNTIL` 하드코딩 dict 가 있었고 **같은 내용이 그 루브릭 YAML
#: 주석에도** 있었다 — 두 벌이면 갈린다. 정세현이 `#284` 에서 그 자리를 짚었다:
#: *"루브릭에 명시하고 로딩 시점에 대조하면 빈 목록이 「빠뜨림」이 아니라 「의도」라고
#: 파일이 스스로 말한다."*
#:
#: ## 왜 "의도" 가 아니라 "아직" 인가 (`#298` 리뷰)
#:
#: 「의도적으로 안 걸었다」로 적으면 **영구히 그렇다고 읽히고 지우는 사건이 안 온다**
#: (결정 10.67). 그래서 파일이 `reason`(왜 지금 없는 게 옳은가)과 `until`(무엇이 생기면
#: 빼는가)을 **둘 다** 적게 하고, `_parse` 가 한쪽만 있으면 터진다.
#:
#: `test_unlinked_until_has_not_expired` 가 그 조건이 충족되는 순간 실패한다.


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
        if rubric.unlinked_until is not None:
            continue                    # 파일이 이유·빼는 조건을 적었다 — 위 주석 참고
        if rubric.misconception_conditions and not rubric.related_misconceptions:
            gaps[item_id] = rubric.misconception_conditions
    return gaps


def unlinked_until() -> dict[str, tuple[str, str]]:
    """링크가 아직 없는 루브릭 → (근거, 빼는 조건). **루브릭 파일에서 읽는다.**"""
    return {i: r.unlinked_until for i, r in _all().items() if r.unlinked_until is not None}


def enforcement_coverage() -> tuple[int, int, int]:
    """(선언된 조건 수, 링크를 가진 루브릭 수, 전체 루브릭 수).

    비율 자체가 결함은 아니다 — 조건 하나에 유형 하나가 대응할 이유가 없다. 다만 **그
    비율이 어디쯤인지 아무도 모르는 것**이 `#284` 가 드러낸 상태였다. 기동 로그에 남긴다.
    """
    rs = _all().values()
    declared = sum(len(r.misconception_conditions) for r in rs)
    linked = sum(1 for r in rs if r.related_misconceptions)
    return declared, linked, len(rs)


def condition_enforcement() -> tuple[int, int, int]:
    """**조건 단위**로 (강제 가능, 권고만, 이유가 적힌 것).

    ❗`enforcement_coverage()` 는 *루브릭* 단위라 *"46개 중 무엇이 강제되고 무엇이
    권고인지"* 에 답하지 못한다 — 정세현이 `#284` 에서 물은 것이 그 질문이다.

    ## 조건 하나하나를 유형에 대응시키지는 못한다

        `"증권사가 망할 리 없다"` 가 어느 유형인지 **코드가 알 방법이 없다.**
        그걸 알아내려는 것이 `(b)`(유사도 매칭)이고, 그건 채점 동작을 바꾸는 일이라 별건이다.
        (그리고 `#364`·임베딩 실측이 그 방향의 한계를 이미 보여 줬다 — 닮음으로는 안 된다.)

    ## 그래서 **확실한 쪽만** 조건 단위로 센다

        링크가 있는 루브릭의 조건    → 강제될 **수** 있다 (어느 것이 강제되는지는 못 가른다)
        링크가 없는 루브릭의 조건    → 하나도 강제될 수 없다. **이건 계산이 아니라 사실이다**

    두 번째가 정세현이 원한 답이다 — 그 조건들은 **프롬프트 문면일 뿐**이고, 모델이
    놓치면 아무 일도 안 일어난다(`els-0028` 이 실물).

    세 번째 값(`explained`)은 **그중 이유가 파일에 적힌 것**이다. `advisory` 와 같아야
    한다 — 다르면 어딘가 `unlinked_until` 이 없는 채로 조건만 선언돼 있다.
    """
    enforced = advisory = explained = 0
    for rubric in _all().values():
        n = len(rubric.misconception_conditions)
        if not n:
            continue
        if rubric.related_misconceptions:
            enforced += n
        else:
            advisory += n
            if rubric.unlinked_until is not None:
                explained += n
    return enforced, advisory, explained


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
