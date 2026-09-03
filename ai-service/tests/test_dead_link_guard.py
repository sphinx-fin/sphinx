"""루브릭이 **그 상품에서 도달할 수 없는** 오해 유형을 참조하면 로딩 시점에 터진다.

## 발단 (이슈 #284, 정세현 지적)

`#284` 가 *"`VAR-PARTIAL-DEPOSIT-INSURANCE` 에 `M02` 링크가 비어 있다 — 누락으로 보인다"*
로 시작했는데 두 가지가 틀렸다. 하나는 **의도된 정정**이라는 것(`#57`)이고, 다른 하나가
이 파일이 막는 것이다.

    붙여도 아무 일도 안 일어난다 — 죽은 링크가 된다.

매처가 `products` 로 **먼저 거르므로**, 루브릭의 `product_type` 이 그 유형의 `products`
에 없으면 매칭이 아예 안 만들어진다. 그런데 `assert_related_misconceptions_exist()` 는
**ID 가 실재하는지만** 봤다.

    [ELS               ] "예금자보호 되는 상품 아닌가요" → ['M02-DEPOSIT-INSURANCE']
    [VARIABLE_INSURANCE] 같은 발화                      → []      ← M02 는 products: [ELS]

## 왜 조용한 실패인가 — 두 겹이다

    ① floor 가 안 걸린다        등급은 정상으로 나온다. 아무도 모른다
    ② 공백이 공백으로 안 보인다  enforcement_gaps() 가 "링크가 있으니 강제된다" 로 센다

②가 더 나쁘다. `#298` 이 만든 관측 자체를 **거짓으로 만든다** — 죽은 링크 하나면 그 항목이
경고 목록에서 사라진다.

## 오늘은 0건이다

실측으로 도달 불가 링크가 없다. 이 파일은 **생기는 순간 잡으려고** 있다.
"""
from __future__ import annotations

import dataclasses
import textwrap

import pytest

from app import misconception, rubrics


#: 복제 원본. **모듈 로드 시점에 한 번 잡는다** — 함수 안에서 `library()` 를 부르면
#: 그 함수를 monkeypatch 로 덮은 테스트가 자기 자신을 불러 `RecursionError` 가 난다
#: (실제로 그랬다). 픽스처가 대체 대상을 런타임에 읽으면 이 자리가 계속 생긴다.
_BASE = next(m for m in misconception.library() if m.type_id == "M01-PRINCIPAL-GUARANTEE")


def _type(type_id: str, products: tuple[str, ...]):
    """실제 유형 하나를 복제해 `products` 만 바꾼다 — 필드가 늘어도 안 낡는다."""
    return dataclasses.replace(_BASE, type_id=type_id, products=products)


def _rubric(item_id: str, product_type: str, related: tuple[str, ...]):
    return rubrics.Rubric(
        item_id=item_id, product_type=product_type, name="시험용", status="draft",
        required_elements=("무언가",), misconception_conditions=("조건",),
        related_misconceptions=related,
    )


def test_today_there_are_no_dead_links() -> None:
    """★ 실물 루브릭 전건이 통과한다 — 이 가드가 지금 상태를 막지 않는다.

    새 가드를 넣을 때 제일 먼저 확인할 것이다. 여기가 빨가면 가드가 아니라 데이터를
    먼저 봐야 한다.
    """
    rubrics.assert_related_misconceptions_exist()


def test_a_link_the_matcher_can_never_produce_is_rejected(monkeypatch) -> None:
    """★ 유형은 실재하는데 그 상품에서 매처가 안 만드는 링크 → 터진다.

    `#284` 가 *"M02 를 붙이면 되지 않나"* 로 시작했던 그 자리다. 붙이면 초록인데
    floor 는 안 걸린다.
    """
    els_only = _type("M02-DEPOSIT-INSURANCE", ("ELS",))
    monkeypatch.setattr(misconception, "library", lambda: (els_only,))
    monkeypatch.setattr(rubrics, "_all", lambda: {
        "VAR-X": _rubric("VAR-X", "VARIABLE_INSURANCE", ("M02-DEPOSIT-INSURANCE",)),
    })

    with pytest.raises(ValueError) as exc:
        rubrics.assert_related_misconceptions_exist()

    assert "도달할 수 없는" in str(exc.value)
    assert "products=('ELS',)" in str(exc.value), (
        "왜 도달 불가인지가 메시지에 없으면 사람이 라이브러리를 다시 열어야 한다"
    )


def test_the_same_link_is_fine_for_the_right_product(monkeypatch) -> None:
    """★ 양성 대조 — 상품이 맞으면 통과한다.

    없으면 *"항상 터진다"* 로 고쳐도 위 테스트가 초록이다.
    """
    els_only = _type("M02-DEPOSIT-INSURANCE", ("ELS",))
    monkeypatch.setattr(misconception, "library", lambda: (els_only,))
    monkeypatch.setattr(rubrics, "_all", lambda: {
        "ELS-X": _rubric("ELS-X", "ELS", ("M02-DEPOSIT-INSURANCE",)),
    })

    rubrics.assert_related_misconceptions_exist()


def test_all_products_reaches_every_rubric(monkeypatch) -> None:
    """`products: ['ALL']` 은 어느 상품에서도 도달한다 — `M08-TYING` 이 그것이다.

    `applies_to()` 를 그대로 쓰므로 이 규칙이 두 벌이 되지 않는다. 여기서 `products` 를
    따로 해석했으면 `ALL` 을 빠뜨렸을 자리다.
    """
    everywhere = _type("M08-TYING", ("ALL",))
    monkeypatch.setattr(misconception, "library", lambda: (everywhere,))
    monkeypatch.setattr(rubrics, "_all", lambda: {
        "VAR-X": _rubric("VAR-X", "VARIABLE_INSURANCE", ("M08-TYING",)),
    })

    rubrics.assert_related_misconceptions_exist()


def test_a_missing_type_still_raises_its_own_message(monkeypatch) -> None:
    """기존 `dangling` 검사가 안 죽었다 — 두 실패는 고칠 곳이 다르다.

        없는 ID       라이브러리에 유형을 만들거나 오타를 고친다
        도달 불가     products 를 넓히거나 그 상품용 유형을 새로 만든다
    """
    monkeypatch.setattr(misconception, "library", lambda: (_type("M01-PRINCIPAL-GUARANTEE", ("ELS",)),))
    monkeypatch.setattr(rubrics, "_all", lambda: {
        "ELS-X": _rubric("ELS-X", "ELS", ("M99-NOPE",)),
    })

    with pytest.raises(ValueError) as exc:
        rubrics.assert_related_misconceptions_exist()

    assert "라이브러리에 없는" in str(exc.value)
    assert "도달할 수 없는" not in str(exc.value), "두 실패가 한 메시지로 뭉치면 고칠 곳을 모른다"


def test_the_message_names_both_sides(monkeypatch) -> None:
    """★ 도달 불가는 **두 변의 관계**다 — 유형 쪽만 보이면 사람을 틀린 쪽으로 보낸다.

    `#310` 리뷰(정세현)가 실측한 경로다. `VAR-PRINCIPAL-LOSS.yaml` 에서 `product_type:`
    한 줄이 빠지면 로더가 조용히 `ELS` 로 떨어뜨리고 이 가드가 잡는데, 그때 메시지가
    유형의 `products` 만 싣고 있으면 이렇게 읽힌다.

        메시지가 제안   M05-SAVINGS 의 products 에 ELS 를 더한다   ← 결정 10.24 가 막는 변경
        실제 고칠 것    루브릭 한 줄                               ← 메시지에 안 나온다

    **시키는 두 가지가 둘 다 틀린** 상태였다.
    """
    var_only = _type("M05-SAVINGS", ("VARIABLE_INSURANCE",))
    monkeypatch.setattr(misconception, "library", lambda: (var_only,))
    monkeypatch.setattr(rubrics, "_all", lambda: {
        "VAR-X": _rubric("VAR-X", "ELS", ("M05-SAVINGS",)),   # product_type 이 틀린 상태
    })

    with pytest.raises(ValueError) as exc:
        rubrics.assert_related_misconceptions_exist()

    msg = str(exc.value)
    assert "product_type=ELS" in msg, "루브릭 쪽 변이 없으면 고칠 자리를 못 찾는다"
    assert "products=('VARIABLE_INSURANCE',)" in msg, "유형 쪽 변도 있어야 관계가 보인다"
    assert "먼저 루브릭의 product_type" in msg, (
        "어느 쪽을 먼저 볼지 안 적으면 사람이 유형을 고치는 쪽으로 간다"
    )


def test_a_rubric_without_product_type_is_rejected(tmp_path, monkeypatch) -> None:
    """★ `product_type:` 이 빠지면 **로딩에서 터진다** — 조용히 ELS 가 되지 않는다.

    같은 레포의 다른 로더 셋은 전부 검증하는데(`misconception`·`templates`·`parsing`)
    여기만 `.get(..., "ELS")` 였다. 그 필드를 읽는 곳이 없어서 무해했는데, 이 PR 이
    처음 소비하면서 **누락이 엉뚱한 자리에서 터지게** 됐다.
    """
    path = tmp_path / "VAR-NO-TYPE.yaml"
    path.write_text(textwrap.dedent("""
        item_id: VAR-NO-TYPE
        name: 상품유형 없는 루브릭
        required_elements:
          - 무언가
    """).strip(), encoding="utf-8")

    with pytest.raises(ValueError) as exc:
        rubrics._parse(path)

    assert "product_type" in str(exc.value)
    assert "ELS" in str(exc.value), "허용값을 안 적으면 무엇을 써야 하는지 모른다"


def test_an_unknown_product_type_is_rejected(tmp_path) -> None:
    """오타도 같은 자리에서 잡는다 — `VARIABLE-INSURANCE`(하이픈)가 실제로 나올 오타다."""
    path = tmp_path / "X.yaml"
    path.write_text(textwrap.dedent("""
        item_id: X
        product_type: VARIABLE-INSURANCE
        required_elements:
          - 무언가
    """).strip(), encoding="utf-8")

    with pytest.raises(ValueError, match="product_type"):
        rubrics._parse(path)
