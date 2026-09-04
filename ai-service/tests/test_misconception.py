"""F-DET-001 오해 탐지 — 결정론 단계 검증. 소유: 윤지석

기획서 5절 통제: "라이브러리 기반이라 재현성이 확보되고, LLM은 변형된 표현을 커버하는
역할만 맡는다." 여기서 검증하는 것은 그 재현성 부분이다.
"""
from __future__ import annotations

import pytest
import yaml

from app import misconception
from app.schemas import PRODUCT_TYPES
from tests.helpers import FIXTURES


def _cases():
    return yaml.safe_load((FIXTURES / "utterances" / "els.yaml").read_text(encoding="utf-8"))


def _case(case_id: str) -> dict:
    """dev set 케이스 하나. 없으면 조용히 통과하지 않고 여기서 터진다."""
    for case in _cases()["cases"]:
        if case["id"] == case_id:
            return case
    raise AssertionError(f"dev set 에 {case_id} 가 없다")


def test_library_type_count():
    """현재 10종이다. 기획서·역할분담표는 "오해 라이브러리 8종"이라고 쓰는데 **양쪽으로**
    어긋나 있다 — M07-YIELD-OVERCONFIDENCE 가 인용 가능한 근거를 찾지 못해 빠졌고
    (근거 없는 유형을 남기면 apply_misconception_floor 가 그것으로 U4 를 확정한다),
    M09-NO-LISTING·M10-MIDWAY-REDEMPTION-COST 가 상품문서 조항을 근거로 들어왔다
    (이슈 #148 · source.type=product_document). M11-DEPOSIT-INSURANCE-SCOPE 도 같은 근거
    계열로 들어왔다 — 변액 예금자보호 **범위** 오인(이슈 #284 (a) · 정세현).

    제출 문서의 "8종" 문면은 여전히 손봐야 한다 — 이 테스트가 그 사실을 코드에 남겨
    둔다. 유형이 또 바뀌면 여기서 먼저 걸린다(#148 에서 실제로 그랬다)."""
    lib = misconception.library()
    assert len(lib) == 10
    assert {m.type_id for m in lib} >= {"M01-PRINCIPAL-GUARANTEE", "M08-TYING",
                                        "M09-NO-LISTING", "M10-MIDWAY-REDEMPTION-COST"}
    assert "M07-YIELD-OVERCONFIDENCE" not in {m.type_id for m in lib}


def test_demo_utterance_is_caught_deterministically():
    """기획서 7-2 데모 메인 ③. LLM 응답에 의존하면 데모의 임계 경로가 비결정적이 된다."""
    result = misconception.match("은행에서 파는 거니까 원금은 지켜지는 거죠?", "ELS")
    assert [m.type_id for m in result.matches] == ["M01-PRINCIPAL-GUARANTEE"]
    assert result.matches[0].stage == "pattern"
    assert result.matches[0].score == 1.0


def test_tying_escalates_from_library_data_not_code():
    """escalate는 라이브러리 필드에서 읽는다 — 유형ID를 코드에 박지 않았음을 확인."""
    result = misconception.match("대출받으려면 이것도 들어야 한다고 하셔서요", "ELS")
    assert result.escalate is True
    assert any(m.type_id == "M08-TYING" for m in result.matches)


def test_escalate_field_is_data_driven():
    escalating = {m.type_id for m in misconception.library() if m.escalate == "compliance"}
    assert escalating == {"M08-TYING"}, "라이브러리가 바뀌면 이 테스트가 알려준다"


def test_correct_understanding_is_not_flagged():
    """이해→오해 오판(마찰)의 최소 방어선."""
    result = misconception.match("낙인 밑으로 떨어지면 원금이 깎이는 거네요", "ELS")
    assert result.matches == []
    assert result.escalate is False


def test_product_scope_is_respected():
    """변액 전용 오해가 ELS 발화에 붙지 않는다."""
    result = misconception.match("낸 돈은 다 돌려받는 거죠", "ELS")
    assert [m.type_id for m in result.matches] == []
    result = misconception.match("낸 돈은 다 돌려받는 거죠", "VARIABLE_INSURANCE")
    assert [m.type_id for m in result.matches] == ["M06-SURRENDER-VALUE"]


def test_library_products_stay_within_contract():
    """라이브러리 products 값이 계약(contracts/parsed_document.schema.json)을 벗어나면
    match()가 예외도 로그도 없이 빗나가고 해당 상품 오해가 하나도 안 잡힌다.
    그 조용한 실패를 막는 지점이므로 여기서 고정한다."""
    misconception.assert_products_are_canonical()
    allowed = set(PRODUCT_TYPES) | {"ALL"}
    for mtype in misconception.library():
        assert set(mtype.products) <= allowed, f"{mtype.type_id}: {mtype.products}"


def test_legacy_short_value_no_longer_matches():
    """VARIABLE_INS 는 폐기된 표기다. 조용히 받아주지 않는다 —
    상류가 옛 값을 보내면 스키마(ProductType)가 422로 거부해야 한다."""
    result = misconception.match("낸 돈은 다 돌려받는 거죠", "VARIABLE_INS")
    assert result.matches == []


def _synthetic_type(pattern: str) -> misconception.MisconceptionType:
    return misconception.MisconceptionType(
        type_id="MZZ-SYNTHETIC", label="합성 유형", patterns=(pattern,),
        products=("ALL",), escalate=None,
        source=misconception.SourceRef(type="proposal_example", ref="tests/test_misconception.py"),
    )


def test_near_miss_goes_to_review_queue_instead_of_silent_drop(monkeypatch):
    """임계값에 못 미치지만 무시하기엔 가까운 발화는 조용히 버리지 않고 후보 큐로 올린다.

    라이브러리의 실제 발화를 픽스처로 박으면 두 가지 이유로 깨진다. 후보 큐 구간이
    [0.45, 0.62) = 0.17 폭뿐이라 임계값을 조금만 조정해도 뒤집히고, 라이브러리 데이터
    소유가 정세현이라 패턴 한 줄이 추가되면(PR #21) 내 테스트가 남의 PR을 막는다.
    여기서 고정할 것은 라이브러리 내용이 아니라 **엔진의 near-miss 처리**이므로,
    발화를 합성하고 구간을 임계값에서 계산한다.
    """
    chars = "".join(chr(0xAC00 + i) for i in range(101))   # 서로 다른 바이그램 100개
    monkeypatch.setattr(misconception, "library", lambda: (_synthetic_type(chars),))

    mid = (misconception.REVIEW_THRESHOLD + misconception.NGRAM_THRESHOLD) / 2
    near = chars[: int(mid * 100) + 1]                     # 포함도 ≈ mid — 구간 안
    assert (misconception.REVIEW_THRESHOLD
            <= misconception._containment(chars, near) < misconception.NGRAM_THRESHOLD)

    result = misconception.match(near, "ELS")
    assert result.matches == []
    assert result.unclassified_candidate is True


def test_far_miss_is_dropped_without_polluting_the_queue(monkeypatch):
    """REVIEW_THRESHOLD 아래는 후보 큐에 올리지 않는다. 하한이 없으면 큐가 무의미해진다."""
    chars = "".join(chr(0xAC00 + i) for i in range(101))
    monkeypatch.setattr(misconception, "library", lambda: (_synthetic_type(chars),))

    far = chars[: int(misconception.REVIEW_THRESHOLD * 100) - 4]
    assert misconception._containment(chars, far) < misconception.REVIEW_THRESHOLD

    result = misconception.match(far, "ELS")
    assert result.matches == []
    assert result.unclassified_candidate is False


#: 기획서 4절 154행 대화 예시. 데모 임계 경로다.
SPEC_DIALOGUE_UTTERANCE = "은행에서 파는 거니까 3년 뒤에 이자 붙어서 나오는 거죠."


def _library_supports(utterance: str, type_id: str) -> bool:
    """라이브러리가 이 발화를 해당 유형으로 결정론적으로 잡는가.

    라이브러리는 정세현 소유다. 패턴이 아직 없는 상태를 실패로 두면 내 테스트가 남의
    PR 을 기다리는 동안 이 브랜치가 빨갛게 남는다 — 파서 테스트가 원본 문서 부재를
    skip 으로 처리하는 것과 같은 성격이므로 같은 방식으로 다룬다.
    """
    return type_id in {m.type_id for m in misconception.match(utterance, "ELS").matches}


_M04_PENDING = "M04 조각 패턴 미머지 (PR #21). 머지되면 이 테스트가 자동으로 활성화된다."


@pytest.mark.skipif(
    not _library_supports(SPEC_DIALOGUE_UTTERANCE, "M04-EARLY-REDEMPTION"),
    reason=_M04_PENDING,
)
def test_spec_dialogue_example_is_caught_deterministically():
    """기획서 4절 154행 대화 예시. M04 에 조각 패턴이 들어가면 후보 큐가 아니라 pattern
    단계에서 잡힌다 — 데모 임계 경로가 LLM 응답에서 떨어진다."""
    result = misconception.match(SPEC_DIALOGUE_UTTERANCE, "ELS")
    assert [m.type_id for m in result.matches] == ["M04-EARLY-REDEMPTION"]
    assert result.matches[0].stage == "pattern"
    assert result.unclassified_candidate is False


def test_deterministic_fixture_cases_match_expected_type():
    """dev set 의 `deterministic: true` 케이스가 라이브러리에서 실제로 잡히는지.

    라이브러리에 아직 패턴이 없는 케이스는 skip 하고 무엇이 빠졌는지 남긴다. dev set 의
    플래그는 *목표 상태*를 적은 것이고 라이브러리 데이터 소유는 정세현이므로, 패턴 도착
    전에 실패로 두면 이 브랜치가 남의 PR 을 기다리며 빨갛게 남는다.
    """
    product_type = _cases()["product_type"]
    pending = []
    checked = 0
    for case in _cases()["cases"]:
        if not case.get("deterministic"):
            continue
        result = misconception.match(case["answer"], product_type)
        found = {m.type_id for m in result.matches}
        expected = case.get("expected_misconception")
        if expected and expected not in found:
            pending.append(f"{case['id']}({expected})")
            continue
        if expected:
            assert expected in found, case["id"]
        if case.get("expected_escalate"):
            assert result.escalate is True, case["id"]
        checked += 1

    assert checked, "결정론 케이스가 하나도 검증되지 않았다"
    if pending:
        pytest.skip(f"라이브러리 패턴 대기: {', '.join(pending)} — {_M04_PENDING}")


# ── 경로 주입 (결정로그 10.7) ─────────────────────────────────────────────────
def _reset_caches():
    from app import config

    config.settings.cache_clear()
    misconception.library.cache_clear()


@pytest.fixture
def clean_caches():
    _reset_caches()
    yield
    _reset_caches()


def test_data_dir_is_injectable(monkeypatch, tmp_path, clean_caches):
    """상대경로 하드코딩이면 컨테이너 안에서 파일을 못 찾는다."""
    from app import config

    monkeypatch.setenv(config.DATA_DIR_ENV, str(tmp_path))
    assert misconception.library_path() == tmp_path / misconception.LIBRARY_RELPATH


def test_missing_library_says_which_env_var_to_set(monkeypatch, tmp_path, clean_caches):
    """`FileNotFoundError` 만 나면 배포자가 무엇을 고쳐야 하는지 모른다."""
    from app import config

    monkeypatch.setenv(config.DATA_DIR_ENV, str(tmp_path / "없는곳"))
    with pytest.raises(misconception.MisconceptionLibraryMissing) as exc:
        misconception.library()
    assert config.DATA_DIR_ENV in str(exc.value)


def test_library_validates_products_at_load_time(monkeypatch, tmp_path, clean_caches):
    """★ docstring 이 "로딩 시점에 터뜨린다"고 적었는데 테스트에서만 불렸다.

    검증을 로더 안으로 옮겼으므로, 테스트를 안 돌린 환경에서도 이 파일은 로드되지 않는다.
    """
    from app import config

    lib = tmp_path / misconception.LIBRARY_RELPATH
    lib.parent.mkdir(parents=True)
    lib.write_text(
        "version: 1\ntypes:\n  - id: M99-BOGUS\n    products: [CRYPTO]\n    patterns: [x]\n",
        encoding="utf-8",
    )
    monkeypatch.setenv(config.DATA_DIR_ENV, str(tmp_path))
    with pytest.raises(ValueError) as exc:
        misconception.library()          # assert_products_are_canonical 를 따로 부르지 않는다
    assert "CRYPTO" in str(exc.value)


def test_startup_fails_when_data_is_missing(monkeypatch, tmp_path, clean_caches):
    """기동 때 죽어야 한다 — 지연 로딩이면 기동은 성공하고 첫 고객 요청에서 500 이다.

    ## ❗`setenv` 뒤에 캐시를 한 번 더 지운다 — 없으면 **파일 단독 실행에서 실패했다**

    `app.main` 은 모듈 수준에서 `configure_logging()` 을 부르고 그게 `settings()` 를 부른다.
    그래서 **import 하는 행위 자체가 캐시를 채운다.** `clean_caches` 가 setup 에서 지워도
    아래 import 가 다시 채우고, 그 다음 줄의 `setenv` 는 이미 늦다.

        clean_caches      캐시 비움
        import app.main   configure_logging() → settings() → 진짜 data/ 로 캐시 채움
        setenv            ← 무력하다
        lifespan          진짜 data/ 를 읽어서 성공한다 → DID NOT RAISE

    전체 스위트에서는 **다른 파일이 `app.main` 을 먼저 import 해서** 이 import 가 no-op 이
    되고, 그래서 통과하고 있었다. 즉 이 테스트는 **파일 순서 덕분에 통과하던 것**이고
    단독으로는 계속 실패했다(`pytest tests/test_misconception.py` 로 재현된다).

    CI 는 전체 스위트만 돌려서 초록이었다. 이건 `#176`·`#192` 에서 겪은 것과 같은 모양이다 —
    **테스트가 런타임과 다른 상태를 재고 있으면 결함 경로가 안 보인다.**
    """
    from fastapi.testclient import TestClient

    from app import config
    from app.main import app

    monkeypatch.setenv(config.DATA_DIR_ENV, str(tmp_path / "없는곳"))
    config.settings.cache_clear()     # ↑ import 가 채운 것을 지운다. 위 docstring 참고
    with pytest.raises(misconception.MisconceptionLibraryMissing):
        with TestClient(app):
            pass


def test_this_file_alone_is_a_valid_run():
    """★ 위 함정을 일반화한다 — 이 파일이 **혼자 돌아도** 같은 답을 내야 한다.

    `settings()` 를 캐시에 채우는 경로가 import 부작용이라, 다른 테스트가 먼저 돌았는지에
    따라 결과가 갈리는 자리가 또 생길 수 있다. 그런 테스트를 찾아내는 그물은 아니고,
    **캐시를 지우는 헬퍼가 실제로 지우는지**를 잰다.

    `_reset_caches()` 뒤에 `settings()` 가 비어 있어야 한다. 누가 캐시를 하나 더 만들고
    여기에 안 넣으면 이 단정이 아니라 그 테스트가 이상하게 깨진다 — 그러면 여기를 본다.
    """
    from app import config

    _reset_caches()
    assert config.settings.cache_info().currsize == 0
    assert misconception.library.cache_info().currsize == 0


def test_healthz_shows_where_data_comes_from():
    from fastapi.testclient import TestClient

    from app.main import app

    body = TestClient(app).get("/healthz").json()
    assert body["data_dir_env"] == "SPHINX_DATA_DIR"
    assert body["misconception_library_version"] >= 1


# ── product_document 근거 종류 (이슈 #148) ───────────────────────────────────
def test_product_document_is_an_accepted_source_type():
    """상품문서 조항 자신이 근거인 유형을 넣을 칸. 정세현 요청(이슈 #148)."""
    assert "product_document" in misconception.SOURCE_TYPES


def test_product_document_is_citable_but_not_dispute_grounded():
    """인용은 되지만 조정례와 같은 층은 아니다.

    기획서 5절이 말하는 근거는 조정례·검사결과다. 심사에서 *"분쟁까지 간 오해만 들어간다"* 를
    주장할 때 셀 수 있는 것은 `is_dispute_grounded` 가 참인 것뿐이므로, 여기에 새 값을 넣으면
    그 주장이 넓어진다(이슈 #148 — 정세현도 그대로 두는 게 맞다고 했다).
    """
    from app.misconception import SourceRef

    ref = SourceRef(type="product_document", ref="parsed_els_sample.json p14 560~579")
    assert ref.is_citable
    assert not ref.is_dispute_grounded


def test_product_document_passes_the_loader_contract():
    """`_parse_source()` 가 알 수 없는 type 을 로딩 시점에 터뜨린다 — 새 값이 통과해야 한다."""
    parsed = misconception._parse_source(
        {"id": "M99", "source": {"type": "product_document", "ref": "원문 p14"}}
    )
    assert parsed.type == "product_document"


# ── #148 배선: 라이브러리 → 루브릭 → dev set 이 셋 다 서야 유형이 실린다 ──────
_M148 = {
    "ELS-NO-LISTING": ("M09-NO-LISTING", "NO-LISTING-SELLABLE"),
    "ELS-MIDWAY-REDEMPTION-COST": ("M10-MIDWAY-REDEMPTION-COST", "MIDWAY-FULL-WITHDRAWAL"),
}


@pytest.mark.parametrize("item_id", sorted(_M148))
def test_m09_m10_are_wired_all_three_places(item_id):
    """라이브러리에만 있으면 **매칭은 되는데 판정에 안 실린다.**

    `apply_misconception_floor` 가 `rubric.related_misconceptions` 로 거르므로,
    라이브러리 등재(#203)만으로는 `misconception_type` 이 Judgment 에 안 붙는다.
    `#160` 이 정확히 그 모양의 결함이었다 — 탐지는 만점인데 실리지 않았다.
    """
    from app import rubrics, scoring
    from app.schemas import Evidence, Grade, Judgment

    type_id, case_id = _M148[item_id]
    utterance = _case(case_id)["answer"]

    matched = misconception.match(utterance, "ELS")
    assert [m.type_id for m in matched.matches] == [type_id]
    assert matched.matches[0].score == 1.0, "결정론 경로가 아니다"
    assert type_id in rubrics.get(item_id).related_misconceptions

    judgment = Judgment(
        item_id=item_id, grade=Grade.U1, confidence=0.9,
        evidence=Evidence(utterance_quote=utterance, rubric_clause="(테스트)"),
        reason="(테스트)",
    )
    out = scoring.apply_misconception_floor(judgment, matched, rubrics.get(item_id))
    assert out.misconception_type == type_id, "라이브러리·루브릭은 섰는데 판정에 안 실린다"
    assert out.grade is Grade.U4, "오해 라이브러리 매칭은 U4 아래로 내려가지 않는다"


@pytest.mark.parametrize("item_id", sorted(_M148))
def test_devset_case_is_marked_deterministic(item_id):
    """dev set 표기가 실물과 같아야 한다.

    `deterministic` 이 `false` 인 채로 남으면 `run_devset.py` 가 이 케이스를 LLM 으로
    돌린다 — 쿼터를 쓰고 재현성이 없어진다. 세 곳 중 여기만 빠뜨리기 쉬워서 박아 둔다.
    """
    type_id, case_id = _M148[item_id]
    case = _case(case_id)
    assert case["deterministic"] is True
    assert case["expected_misconception"] == type_id


def test_devset_patterns_that_came_from_this_file_are_disclosed():
    """★ 패턴이 내 dev set 발화에서 온 2건을 README 가 밝히고 있어야 한다.

    그 2건의 매칭 성공은 구성상 보장된 값이라 **놓침 0 의 근거가 되지 못한다.**
    결정론 10/24 를 성능 수치로 인용할 때 붙어야 하는 사실이고, 나는 F-CMN-003 라벨링에서
    배제된 사람이라 이런 종류의 사실은 내가 먼저 적어 둔다(fixtures/README.md 머리말).

    문서와 실물이 갈리는 것을 막으려고 **패턴 == 발화** 인지를 여기서 직접 잰다.
    """
    import pathlib

    readme = (pathlib.Path(__file__).parent / "fixtures" / "README.md").read_text(encoding="utf-8")
    lib = {m.type_id: m for m in misconception.library()}

    for item_id, (type_id, case_id) in _M148.items():
        answer = _case(case_id)["answer"].rstrip(".?! ")
        assert answer in lib[type_id].patterns, (
            f"{type_id} 패턴이 더는 dev set 발화와 같지 않다 — 좁혀졌다면 README 의 "
            "그 문단도 같이 고쳐야 한다(그때 이 테스트를 지운다)"
        )
        assert type_id in readme, f"README 가 {type_id} 의 출처를 밝히지 않는다"
        assert case_id in readme, f"README 가 {case_id} 를 밝히지 않는다"
