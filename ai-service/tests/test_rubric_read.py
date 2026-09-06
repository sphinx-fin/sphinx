"""루브릭 «열람» — 공개 의무의 실물 경로. 소유: 윤지석 (이슈 #474 ③)

## 왜 이 경로가 필요한가

루브릭은 *"고객이 무엇을 말해야 이해로 보는가"* 라는 판정 기준이고 **공개 의무 대상**인데,
그것을 읽는 경로가 레포 어디에도 없었다(ai-service 라우트 0 · server 0 · web 0).
화면이 볼 수 있던 것은 채점 결과에 인용된 **조항 한 줄**(`evidence.rubric_clause`)뿐이라
*"그 조항이 어느 기준에서 왔나"* 를 심사자가 대조할 방법이 없었다.

## ❗읽기 전용이다

승인 산출물은 `app/rubrics/*.yaml` **파일**이고 승인은 git 커밋이다(`#475` 에서 강희진
확정 — 서버가 승인 상태를 갖지 않는다). 이 경로로 **쓰지 않는다** — 쓰면 정본이 둘이 되고,
채점이 파일만 읽는 규약이 깨지면 `verify_rubric_clause_is_published` 가 순환한다(P4).
"""
from __future__ import annotations

from dataclasses import replace
from pathlib import Path

from fastapi.testclient import TestClient

from app import rubrics
from app.main import app

RUBRIC_DIR = Path(__file__).resolve().parents[1] / "app" / "rubrics"


def _client() -> TestClient:
    return TestClient(app)


def test_every_rubric_file_is_reachable() -> None:
    """★ 파일에 있는 것이 전부 나온다 — 골라 내지 않는다(공개 의무)."""
    with _client() as c:
        body = c.get("/internal/rubrics").json()
    on_disk = {p.stem for p in RUBRIC_DIR.glob("*.yaml")}
    served = {r["item_id"] for r in body["rubrics"]}
    assert served == on_disk, f"파일에만 있는 것 {on_disk - served} · 응답에만 {served - on_disk}"
    assert body["total"] == len(on_disk)
    assert on_disk, "루브릭 디렉토리가 비면 위 단정이 둘 다 참이 된다(빈 모집단)"


def test_the_threshold_is_served_not_just_the_elements() -> None:
    """★ `u1_requires` 를 «반드시» 낸다 — 이것이 `#450` 의 결함이었다.

    화면이 요소 목록만 보이고 문턱을 안 보이면 *"이 전부를 말해야 한다"* 로 읽힌다.
    `VAR-PARTIAL-DEPOSIT-INSURANCE` 는 요소 2 · 문턱 **1** 이고, 그 부분성이 설계다
    (`ADR-007` · `#53` · `#57` — 변액 예금자보호는 부분적으로 참이다).
    """
    with _client() as c:
        r = c.get("/internal/rubrics/VAR-PARTIAL-DEPOSIT-INSURANCE").json()
    assert r["u1_requires"] == 1
    assert len(r["required_elements"]) == 2
    assert r["u1_requires"] != len(r["required_elements"]), (
        "요소 수와 문턱이 같은 항목으로 이걸 재면 «다를 수 있다» 를 못 잠근다"
    )


def test_the_threshold_matches_the_file_for_every_rubric() -> None:
    """응답이 파일과 «같은가». 한 항목만 보면 다른 열일곱이 갈려도 안 보인다."""
    with _client() as c:
        served = {r["item_id"]: r for r in c.get("/internal/rubrics").json()["rubrics"]}
    for item_id, r in rubrics.all_rubrics().items():
        got = served[item_id]
        assert got["u1_requires"] == r.u1_requires, item_id
        assert got["required_elements"] == list(r.required_elements), item_id
        assert got["status"] == r.status, item_id


def test_the_total_is_the_undivided_denominator() -> None:
    """❗필터를 걸어도 `total` 은 안 걸린다 — 걸러진 목록만 보이면 «이게 전부» 로 읽힌다."""
    with _client() as c:
        every = c.get("/internal/rubrics").json()
        els = c.get("/internal/rubrics?product_type=ELS").json()
        var = c.get("/internal/rubrics?product_type=VARIABLE_INSURANCE").json()
    assert els["total"] == var["total"] == every["total"]
    assert len(els["rubrics"]) + len(var["rubrics"]) == every["total"]
    assert len(els["rubrics"]) and len(var["rubrics"]), "한쪽이 비면 위 합이 우연히 맞는다"


def test_an_unknown_item_is_404_not_an_empty_rubric() -> None:
    """❗빈 루브릭을 지어내지 않는다.

    채점은 루브릭이 없는 항목을 아예 못 매긴다(`recommended` 가 그렇다 — `#435`).
    빈 것을 내주면 화면이 *"기준이 없다"* 와 *"기준이 비어 있다"* 를 못 가른다.
    """
    with _client() as c:
        assert c.get("/internal/rubrics/NO-SUCH-ITEM").status_code == 404


def test_the_reason_a_link_is_empty_is_served() -> None:
    """`unlinked_until` 을 낸다 — 빈 목록이 「해당 없음」인지 「아직 못 걸었다」인지 가른다.

    `#284`·`#396` 에서 그 구별을 파일에 만들었다. 화면이 그것을 못 받으면
    **침묵이 「해당 없음」으로 읽힌다.**

    ❗**합성으로 잰다.** 지금 그 필드를 가진 루브릭이 **0건**이다 — `#425` 가 M11 을
    링크하며 `#284` 를 닫아서 비었다. 실물로만 재면 «빈 모집단이 단정을 참으로 만든다»
    (`#396` 에서 밟은 자리) — 필드를 아예 안 내도 초록이다. 실제로 변조로 확인했다.
    """
    from app.routes import _view

    filled = rubrics.Rubric(
        item_id="SYNTH-ITEM", product_type="ELS", name="합성", status="draft",
        required_elements=("가",), u1_requires=1,
        misconception_conditions=(), related_misconceptions=(),
        unlinked_until=("근거 문서가 아직 없다", "라이브러리에 그 유형이 생기면"),
    )
    assert _view(filled).unlinked_until == [
        "근거 문서가 아직 없다", "라이브러리에 그 유형이 생기면",
    ], "링크가 빈 «이유» 가 응답에서 사라진다 — 화면이 「해당 없음」으로 읽는다"

    empty = replace(filled, unlinked_until=None)
    assert _view(empty).unlinked_until is None, "없는 것을 있는 것처럼 만들지 않는다"


def test_reading_does_not_write() -> None:
    """★ 이 경로는 파일을 안 건드린다 — 승인은 git 커밋이다(`#475` ⓐ)."""
    before = {p: p.read_bytes() for p in RUBRIC_DIR.glob("*.yaml")}
    with _client() as c:
        c.get("/internal/rubrics")
        c.get("/internal/rubrics/ELS-PRINCIPAL-LOSS-WARNING")
    assert {p: p.read_bytes() for p in RUBRIC_DIR.glob("*.yaml")} == before
    src = (Path(__file__).resolve().parents[1] / "app" / "routes.py").read_text(encoding="utf-8")
    tail = src[src.index("# ── 루브릭 열람"):]
    # ❗**부분열로 찾지 않는다.** 첫 판이 `"unlink"` 를 찾다가 `unlinked_until` 에 걸렸다 —
    #   그물이 자기 파일의 정상 문면을 물었다. 호출 형태(`(`)까지 요구해서 가른다.
    for forbidden in ("write_text(", ".open(", ".unlink(", "yaml.dump(", "safe_dump("):
        assert forbidden not in tail, f"열람 경로에 쓰기가 있다: {forbidden}"
    assert "unlinked_until" in tail, "양성 대조 — 위 검사가 이 절을 실제로 읽고 있다"
