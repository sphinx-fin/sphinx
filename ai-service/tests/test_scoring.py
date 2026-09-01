"""F-SCR-001 채점 후처리 검증. 소유: 윤지석

기획서 5절 [채점 성능 목표와 오판 처리]의 비대칭을 코드로 고정한 부분을 검증한다.
후처리는 전부 한 방향(안전한 쪽)으로만 움직여야 한다.
"""
from __future__ import annotations

import pytest

from app import rubrics, scoring
from app.llm_client import LlmError
from app.schemas import Condition, Grade, RiskItem, SourceSpan
from tests.helpers import FakeLlm, make_judgment

RISK_ITEM = RiskItem(
    item_id="ELS-PRINCIPAL-LOSS-WARNING",
    product_id="mock-els-001",
    name="원금손실 조건",
    importance="required",
    condition=Condition(
        value_text="만기평가일에 기초자산 중 하나라도 최초기준가격의 50% 미만인 경우 …(원문 인용)",
        source_span=SourceSpan(page=3, start=120, end=210),
    ),
    status="extracted",
)
QUESTION = "이 상품에서 원금 손실이 나는 상황을 본인 말씀으로 설명해 주시겠어요?"
DEMO_ANSWER = "은행에서 파는 거니까 원금은 지켜지는 거죠?"


def _score(judgment, answer=DEMO_ANSWER, item_id="ELS-PRINCIPAL-LOSS-WARNING"):
    llm = FakeLlm(judgment)
    return scoring.score(item_id, QUESTION, answer, RISK_ITEM, "ELS", llm=llm), llm


# ── 루브릭 ────────────────────────────────────────────────────────────────────
def test_no_rubric_references_a_missing_misconception_type():
    """없는 유형을 참조하면 apply_misconception_floor 가 조용히 발동하지 않는다.

    M07-YIELD-OVERCONFIDENCE 가 근거 미확보로 라이브러리에서 빠졌을 때 두 루브릭이
    그것을 계속 참조했고, 채점은 성공한 채로 결정론 상향만 사라졌다. 개수 단정문이
    뒤늦게 잡았을 뿐이다 — 이 검사가 그 실패 양식을 로딩 시점으로 끌어올린다."""
    rubrics.assert_related_misconceptions_exist()
def test_rubric_clauses_reach_the_prompt():
    """루브릭 공개 의무(기획서 5절)는 프롬프트에 실제로 들어가야 의미가 있다."""
    rubric = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING")
    prompt = scoring.build_prompt(rubric, RISK_ITEM, QUESTION, DEMO_ANSWER)
    for clause in rubric.required_elements + rubric.misconception_conditions:
        assert clause in prompt
    assert RISK_ITEM.condition.value_text in prompt
    assert DEMO_ANSWER in prompt


def test_system_prompt_states_the_conservative_rule():
    system = scoring.load_system_prompt()
    assert "U2" in system and "U1" in system


def test_unknown_item_id_raises():
    with pytest.raises(rubrics.RubricNotFound):
        scoring.score("NO-SUCH-ITEM", QUESTION, DEMO_ANSWER, RISK_ITEM,
                      llm=FakeLlm(make_judgment()))


# ── P4: 근거 인용 대조 ────────────────────────────────────────────────────────
def test_fabricated_quote_is_rejected():
    """지어낸 인용은 근거 없는 것보다 나쁘다 — 감사 시점에 검증 불가한 기록이 남는다."""
    bogus = make_judgment(quote="고객이 원금 손실을 이해한다고 답변함")
    with pytest.raises(LlmError, match="P4"):
        _score(bogus)


def test_verbatim_quote_with_different_spacing_is_accepted():
    judgment, _ = _score(make_judgment(quote="원금은  지켜지는  거죠"))
    assert judgment.evidence.utterance_quote


# ── 오해 라이브러리 상향 (오해→이해 오판 상한 1%) ──────────────────────────────
def test_library_match_raises_grade_to_u4():
    """LLM이 이해로 봤어도 분쟁조정례 오해 문장이면 U4다."""
    judgment, _ = _score(make_judgment(grade=Grade.U1, confidence=0.95))
    assert judgment.grade is Grade.U4
    assert judgment.misconception_type == "M01-PRINCIPAL-GUARANTEE"
    assert "U4 상향" in judgment.reason  # 감사 추적


def test_floor_only_applies_to_rubric_related_types():
    """다른 항목의 오해가 이 항목 등급을 끌어내리면 안 된다.
    ELS-EARLY-REDEMPTION-CONDITION 루브릭은 M01을 관련 유형으로 선언하지 않았다."""
    judgment, _ = _score(
        make_judgment(grade=Grade.U1, confidence=0.95, item_id="ELS-EARLY-REDEMPTION-CONDITION"),
        item_id="ELS-EARLY-REDEMPTION-CONDITION",
    )
    assert judgment.grade is Grade.U1
    assert judgment.misconception_type is None


def test_already_u4_keeps_its_reason_but_gains_type():
    judgment, _ = _score(make_judgment(grade=Grade.U4, confidence=0.9, reason="원문 사유"))
    assert judgment.grade is Grade.U4
    assert judgment.reason == "원문 사유"          # 상향할 게 없으면 사유를 건드리지 않는다
    assert judgment.misconception_type == "M01-PRINCIPAL-GUARANTEE"


def test_llm_supplied_misconception_type_is_discarded():
    """실측에서 모델이 존재하지 않는 유형ID(M-PRINCIPAL-GUARANTEE)를 지어냈다.
    유형ID는 오해 지도 집계 키이므로 환각이 하나 섞이면 집계가 조용히 오염된다.
    루브릭이 관련 유형을 선언하지 않은 항목에서는 반드시 None이어야 한다."""
    judgment, _ = _score(
        make_judgment(grade=Grade.U4, item_id="ELS-EARLY-REDEMPTION-CONDITION",
                      misconception_type="M-존재하지-않는-유형"),
        item_id="ELS-EARLY-REDEMPTION-CONDITION",
    )
    assert judgment.misconception_type is None


def test_library_type_still_wins_over_discarded_llm_value():
    judgment, _ = _score(make_judgment(grade=Grade.U4, misconception_type="M-엉터리"))
    assert judgment.misconception_type == "M01-PRINCIPAL-GUARANTEE"


# ── P4: 루브릭 조항 대조 (강희진 리뷰 반영) ───────────────────────────────────
def test_clause_outside_the_rubric_is_rejected():
    """근거로 적힌 조항이 공개 루브릭에 없으면 근거 표시 의무가 형식만 남는다."""
    bogus = make_judgment()
    bogus = bogus.model_copy(update={
        "evidence": bogus.evidence.model_copy(
            update={"rubric_clause": "고객이 충분히 이해한 것으로 보임"})})
    with pytest.raises(LlmError, match="루브릭 밖"):
        _score(bogus)


def test_published_clause_passes():
    rubric = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING")
    for clause in rubric.required_elements + rubric.misconception_conditions:
        j = make_judgment()
        j = j.model_copy(update={
            "evidence": j.evidence.model_copy(update={"rubric_clause": clause})})
        assert _score(j)[0].evidence.rubric_clause == clause


def test_two_clauses_joined_are_accepted():
    """실측에서 모델이 "A 및 B"로 합쳐 인용했다. 공개 조항으로 환원되므로 추적 가능하다."""
    rubric = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING")
    joined = f"{rubric.required_elements[0]} 및 {rubric.misconception_conditions[0]}"
    j = make_judgment()
    j = j.model_copy(update={
        "evidence": j.evidence.model_copy(update={"rubric_clause": joined})})
    assert _score(j)[0].grade is Grade.U4   # 오해 상향은 그대로 동작


def test_clause_with_extra_content_is_rejected():
    """조항에 없는 내용이 붙으면 거부한다 — 합성 허용이 구멍이 되지 않게."""
    rubric = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING")
    j = make_judgment()
    j = j.model_copy(update={"evidence": j.evidence.model_copy(
        update={"rubric_clause": rubric.required_elements[0] + " 이므로 판매 가능하다"})})
    with pytest.raises(LlmError, match="루브릭 밖"):
        _score(j)


# ── 신뢰도는 그대로 통과시킨다 (게이트가 판단) ────────────────────────────────
def test_low_confidence_grade_is_not_altered():
    """황색 강등은 게이트 정책이다(강희진 결정). ai-service는 측정값만 낸다 —
    양쪽에서 하면 이중계산이 된다."""
    judgment, _ = _score(make_judgment(grade=Grade.U1, confidence=0.3,
                                       item_id="ELS-EARLY-REDEMPTION-CONDITION"),
                         item_id="ELS-EARLY-REDEMPTION-CONDITION")
    assert judgment.grade is Grade.U1
    assert judgment.confidence == 0.3
    assert "강등" not in judgment.reason


# ── item_id 고정 ──────────────────────────────────────────────────────────────
def test_item_id_is_pinned_to_caller_value():
    """LLM이 엉뚱한 item_id를 써 보내도 호출자가 지정한 항목이 진실이다."""
    bogus = make_judgment(item_id="ELS-PRINCIPAL-LOSS-WARNING").model_copy(
        update={"item_id": "WRONG-ID"})
    judgment, _ = _score(bogus)
    assert judgment.item_id == "ELS-PRINCIPAL-LOSS-WARNING"


# ── 데모 임계 경로 ────────────────────────────────────────────────────────────
def test_demo_main_scenario_is_red_without_relying_on_the_llm():
    """기획서 7-2 ④ 적색 3건 중 원금손실 오해.
    LLM이 최악으로 틀려도(U1, 확신 0.99) 결과는 U4여야 한다."""
    judgment, _ = _score(make_judgment(grade=Grade.U1, confidence=0.99))
    assert judgment.grade is Grade.U4, "데모의 임계 경로가 LLM 응답에 의존하고 있다"


# ── 유니코드 정규화 (ADR-008 검토에서 발견) ────────────────────────────────────
def test_decomposed_hangul_quote_is_accepted():
    """한글 조합형(NFD) 인용이 완성형(NFC) 발화와 대조돼야 한다.

    눈으로 같고 바이트가 다르다. 모델이 조합형으로 돌려주면 글자가 같은데도 P4 위반으로
    거부됐다 — 실측으로 재현한 뒤 고쳤다. ADR-008 이 짚은 그 지점이다.
    """
    import unicodedata

    nfd_quote = unicodedata.normalize("NFD", "원금은 지켜지는")
    assert not unicodedata.is_normalized("NFC", nfd_quote)
    judgment, _ = _score(make_judgment(quote=nfd_quote))
    assert judgment.grade is Grade.U4          # 정상 경로로 끝까지 진행


def test_decomposed_rubric_clause_is_accepted():
    """조항 인용도 같다 — 대조 함수를 공유한다."""
    import unicodedata

    rubric = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING")
    nfd_clause = unicodedata.normalize("NFD", rubric.required_elements[0])
    judgment, _ = _score(make_judgment(rubric_clause=nfd_clause))
    assert judgment.evidence.rubric_clause == nfd_clause   # 저장값은 손대지 않는다


def test_fabricated_quote_still_rejected_after_normalization():
    """정규화가 검증을 무르게 하지 않았음을 고정한다."""
    with pytest.raises(LlmError, match="P4"):
        _score(make_judgment(quote="고객이 원금 손실을 이해한다고 답변함"))


# ── 문면 복창 (프롬프트 v2 / ADR-005 confidence 재정의) ───────────────────────
def _rubric_and_item():
    from app import rubrics as r
    return r.get("ELS-PRINCIPAL-LOSS-WARNING"), RISK_ITEM


def test_echo_score_separates_parroting_from_paraphrase():
    """같은 내용을 자기 순서로 말한 발화와 문면을 옮긴 발화가 갈려야 한다.

    모델은 이걸 못 했다 — 종결어미만 바꿔도 confidence 가 0.90 ↔ 0.30 으로 흔들렸다.
    """
    rubric, item = _rubric_and_item()
    paraphrase = "기초자산이 낙인 밑으로 떨어지면 떨어진 만큼 원금이 깎여서 나온다고 들었어요."
    verbatim = rubric.required_elements[0] + "."
    assert scoring.echo_score(paraphrase, rubric, item) < scoring.ECHO_THRESHOLD
    assert scoring.echo_score(verbatim, rubric, item) >= scoring.ECHO_THRESHOLD


def test_sentence_ending_does_not_change_echo_score():
    """`~라고 들었어요` 는 공손 표현이다. 모델이 여기서 틀렸으므로 계산으로 고정한다."""
    rubric, item = _rubric_and_item()
    hearsay = "기초자산이 낙인 밑으로 떨어지면 떨어진 만큼 원금이 깎여서 나온다고 들었어요."
    plain = "기초자산이 낙인 밑으로 떨어지면 떨어진 만큼 원금이 깎여서 나옵니다."
    a, b = scoring.echo_score(hearsay, rubric, item), scoring.echo_score(plain, rubric, item)
    assert abs(a - b) < 0.05, f"어미만 다른데 {a:.3f} vs {b:.3f}"


def test_echo_threshold_has_margin_over_real_utterances():
    """임계값이 실제 발화 쪽으로 내려오면 정상 이해가 황색이 된다(관리지표 10%).

    dev set 전체를 대조한다 — 발화를 추가할 때 여유가 줄면 여기서 걸린다. 복창으로 라벨한
    케이스(`expected_confidence_max`)는 제외한다. 그건 걸려야 하는 쪽이다.
    """
    import yaml

    from app import rubrics as r

    worst = 0.0
    import sys
    from pathlib import Path

    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
    from run_devset import load_risk_items

    fixtures = Path(__file__).resolve().parent / "fixtures" / "utterances"
    for path in sorted(fixtures.glob("*.yaml")):
        spec = yaml.safe_load(path.read_text(encoding="utf-8"))
        items = load_risk_items(spec["product_type"])
        for case in spec["cases"]:
            item = items.get(case["item_id"])
            if item is None or case.get("expected_confidence_max") is not None:
                continue
            try:
                rubric = r.get(case["item_id"])
            except r.RubricNotFound:
                continue
            worst = max(worst, scoring.echo_score(case["answer"], rubric, item))
    margin = scoring.ECHO_THRESHOLD - worst
    assert margin >= scoring.ECHO_MARGIN_MIN, (
        f"여유 부족: 실제 발화 최대 {worst:.3f}, 임계 {scoring.ECHO_THRESHOLD} "
        f"→ 간격 {margin:.3f} < {scoring.ECHO_MARGIN_MIN}"
    )


def test_prompt_version_matches_the_file_in_use():
    """상수와 파일이 어긋나면 `/healthz` 가 거짓말을 한다 — 그리고 아무도 모른다."""
    assert scoring.PROMPT_PATH.name == f"{scoring.PROMPT_VERSION}.md"
    assert scoring.PROMPT_PATH.exists()


def test_v1_prompt_is_kept_for_audit():
    """루브릭·프롬프트는 공개 의무 대상이다. 정의가 바뀐 이전 버전을 지우면 v1 로 측정된
    판정을 나중에 설명할 수 없다."""
    assert (scoring.PROMPT_PATH.parent / "F-SCR-001_v1.md").exists()


def test_short_answers_are_not_treated_as_parroting():
    """짧은 발화는 복창 판정을 아예 건너뛴다 — **이유는 우연 일치가 아니다.**

    처음 근거는 *"분모가 작으면 우연 일치가 점수를 지배한다"* 였는데 **실측이 그것을
    부정했다**(`scoring.min_echo_bigrams` 주석). `"손실"` 의 containment 1.000 은 우연이
    아니라 **조항 문면 그대로**이기 때문이다.

        '손실'      1bg  containment=1.000   조항 부분열 — 우연이 아니다
        '원금 손실'  3bg  containment=0.667   어휘만 겹친다(순서 다름)

    실제 이유는 **그 길이에서는 상한을 씌워도 판정이 안 바뀐다**는 것이다. 복창 판정이
    잡으려는 것은 *"요소는 다 말했는데 자기 말인지 모르겠다"* 이고 그건 U1 일 때만 생기는데,
    조항 하나를 담을 길이보다 짧으면 요소 미충족이라 U2 이하가 되어 게이트 R-04 가 이미
    YELLOW 로 잡는다. 그래서 계산을 건너뛰는 것이 맞다.

    이 docstring 이 철회된 근거를 들고 있으면 다음 사람이 그걸 읽고 판단한다 (이슈 #128 ②).
    """
    rubric, item = _rubric_and_item()
    for short in ("손실", "원금", "원금 손실"):
        assert scoring.echo_score(short, rubric, item) == 0.0, short


def test_short_answers_would_have_scored_high_without_the_floor():
    """하한이 하는 일이 실제로 있는지 — 없으면 이 발화들이 상한을 받는다.

    `"손실"` 은 조항 문면 그대로라 containment 가 1.000 이다. **우연 일치가 아니다**
    (원래 주석이 그렇게 적었고 실측이 부정했다). 하한이 막는 것은 우연이 아니라
    **U1 이 나올 수 없는 길이의 발화** 다.
    """
    from app import textsim

    rubric, _ = _rubric_and_item()
    clause = rubric.required_elements[0]
    assert textsim.containment("손실", clause) == 1.0
    assert textsim.containment("원금 손실", clause) >= scoring.ECHO_THRESHOLD


def test_the_floor_tracks_the_rubric_not_a_constant(monkeypatch):
    """★ **하한이 실제로 루브릭을 따라간다** — 상수로 되돌리는 회귀를 잡는다 (이슈 #128 ①).

    아래 `..._derived_from_the_shortest_clause` 가 `★` 를 달고 이 성질을 지킨다고 적었는데,
    실측하니 **상수로 되돌려도 안 잡혔다.**

        유도식 -1 → +1        2 failed   (잡힌다)
        유도를 `return 5` 로   35 passed  ❗아무것도 안 잡는다

    부등식(`하한 < 조항최단`)만 보므로, 우연히 모든 최단값보다 작은 상수면 통과한다.
    **지키겠다고 적은 것을 안 지키고 있었다.**

    그래서 루브릭을 갈아끼워 하한이 **따라 움직이는지**를 본다. 상수면 안 움직인다.
    """
    from dataclasses import replace

    from app import rubrics as r
    from app import textsim

    baseline = scoring.min_echo_bigrams()

    # 조항이 더 긴 루브릭만 남긴다 — 최단이 올라가면 하한도 올라가야 한다
    longest = max(
        (clause for rubric in r.all_rubrics().values() for clause in rubric.required_elements),
        key=lambda c: len(textsim.bigrams(textsim.normalize(c))),
    )
    only_long = {"X": replace(next(iter(r.all_rubrics().values())),
                              item_id="X", required_elements=(longest,))}
    monkeypatch.setattr(r, "all_rubrics", lambda: only_long)
    scoring.min_echo_bigrams.cache_clear()
    try:
        moved = scoring.min_echo_bigrams()
    finally:
        monkeypatch.undo()
        scoring.min_echo_bigrams.cache_clear()

    expected = len(textsim.bigrams(textsim.normalize(longest))) - 1
    assert moved == expected, f"루브릭을 갈았는데 하한이 {moved} 다 (기대 {expected})"
    assert moved != baseline, (
        "루브릭이 바뀌었는데 하한이 그대로다 — 유도가 아니라 상수로 되돌아갔다"
    )


def test_the_floor_is_derived_from_the_shortest_clause():
    """하한이 조항 최단보다 작다 — 유도식의 **정의**를 본다.

    이 단정만으로는 상수 회귀를 못 잡는다(위 `..._tracks_the_rubric_not_a_constant` 참고).
    그래도 남기는 이유는 유도식이 잘못된 방향으로 바뀌는 것(`-1` → `+1`)을 잡기 때문이다.

    처음 상수 8 로 박았다가 `#112`(required 루브릭 10종)가 조항 최단을 13bg → 6bg 로 바꾸면서
    깨졌다. 하한의 유일한 실제 제약은 **가장 짧은 조항을 그대로 옮긴 복창을 놓치지 않는 것**
    이므로(PR #114 리뷰), 그 값에서 유도하는 것이 맞다.

    이전에는 이 성질을 두 테스트로 나눠 뒀는데 **하나가 다른 하나에 포섭돼 단독으로 깨질 수
    없었다**(정세현 지적). 유도로 바꾸면서 상방은 이 한 건으로 모은다.
    """
    from app import rubrics as r
    from app import textsim

    shortest = min(
        len(textsim.bigrams(textsim.normalize(clause)))
        for rubric in r.all_rubrics().values()
        for clause in rubric.required_elements
    )
    assert scoring.min_echo_bigrams() < shortest, (
        f"하한 {scoring.min_echo_bigrams()} ≥ 조항 최단 {shortest} — 그 조항 복창을 놓친다.\n"
        "★ 하한이 4 이하로 내려가야 하면 **개수 기준을 버리고 조항 길이에 상대적인 임계로 "
        "옮겨야 한다** — 오발동 상한(3bg)과 1 차이가 되면 개수로는 두 군을 못 가른다. "
        "하한만 한 칸 내리고 지나가지 말 것 (PR #114 리뷰)."
    )


def test_the_floor_sits_below_real_utterances():
    """하한이 실제 발화 쪽으로 올라오면 복창 판정이 아무 발화에도 도달하지 않는다.

    **`..._derived_from_the_shortest_clause` 와 겹치지 않는다.** 하한을 조항에서 유도하도록
    바꾼 뒤로 이 둘은 서로 다른 것을 본다 — 그쪽은 유도의 정의(조항 최단보다 작다), 이쪽은
    유도 결과가 **실제 데이터에 대해** 쓸 만한지다. 조항과 발화 중 어느 쪽이 더 타이트한지는
    데이터에 따라 뒤집힌다(`#112` 가 조항 최단을 13bg → 6bg 로 바꿨을 때 실제로 뒤집혔다).
    그래서 둘을 다 둔다 — PR #114 리뷰에서 정세현이 포섭 지적을 접은 근거가 그 사건이다.
    """
    import sys
    from pathlib import Path

    import yaml

    from app import textsim

    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))

    fixtures = Path(__file__).resolve().parent / "fixtures" / "utterances"
    shortest = min(
        len(textsim.bigrams(textsim.normalize(case["answer"])))
        for path in sorted(fixtures.glob("*.yaml"))
        for case in yaml.safe_load(path.read_text(encoding="utf-8"))["cases"]
    )
    assert scoring.min_echo_bigrams() < shortest, (
        f"하한 {scoring.min_echo_bigrams()} ≥ 실제 발화 최단 {shortest}bg — "
        "어떤 발화도 복창 판정을 받지 못한다"
    )


def test_the_floor_sits_below_every_condition_text():
    """`echo_score` 는 조항 **+ 조건 원문**을 대조한다. 하한은 조항에서만 유도된다.

    조건 원문이 조항보다 짧아지면 **그 원문을 그대로 옮긴 복창을 놓친다.** 지금은 조건 원문
    최단이 11bg 로 조항 최단(6bg)보다 크지만 그건 현재 데이터의 사실이고, 유도식에는 그
    보장이 없다 — 계약 샘플이 바뀌면 조용히 깨진다(PR #114 리뷰, 정세현).
    """
    import sys
    from pathlib import Path

    from app import textsim

    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
    from run_devset import load_risk_items

    shortest = min(
        len(textsim.bigrams(textsim.normalize(item.condition.value_text)))
        for product_type in ("ELS", "VARIABLE_INSURANCE")
        for item in load_risk_items(product_type).values()
    )
    assert scoring.min_echo_bigrams() < shortest, (
        f"하한 {scoring.min_echo_bigrams()} ≥ 조건 원문 최단 {shortest}bg — "
        "그 원문을 옮긴 복창을 놓친다. 유도는 조항만 보므로 여기서만 드러난다"
    )


def test_the_shortest_clause_verbatim_is_still_caught():
    """유도가 맞는지 문면으로 확인한다 — 가장 짧은 조항을 그대로 답하면 잡혀야 한다."""
    from app import rubrics as r
    from app import textsim

    shortest = min(
        ((len(textsim.bigrams(textsim.normalize(c))), item_id, c)
         for item_id, rubric in r.all_rubrics().items()
         for c in rubric.required_elements),
    )
    _, item_id, clause = shortest
    rubric = r.get(item_id)
    probe = RISK_ITEM.model_copy(update={"item_id": item_id})
    assert scoring.echo_score(clause, rubric, probe) >= scoring.ECHO_THRESHOLD, (
        f"{item_id} 의 최단 조항 {clause!r} 복창이 안 잡힌다"
    )


def test_capped_confidence_records_the_reason():
    """조용히 숫자만 바뀌면 감사 시점에 왜 황색이었는지 설명할 수 없다."""
    rubric, item = _rubric_and_item()
    verbatim = rubric.required_elements[0] + "."
    judgment = make_judgment(
        grade=Grade.U1, confidence=0.95, quote=verbatim, reason="필수 요소를 언급했다",
    )
    out = scoring.cap_confidence_if_echoed(judgment, verbatim, rubric, item)
    assert out.confidence == scoring.ECHO_CONFIDENCE_CAP
    assert "복창" in out.reason and "포함도" in out.reason
    assert out.grade == Grade.U1, "등급은 건드리지 않는다"


# ── prompt_version (결정 10.46 · 계약 10.38) ─────────────────────────────────
def test_prompt_version_is_pinned_by_us_not_the_model():
    """`confidence` 의 정의가 프롬프트 버전마다 다르다 — v1 등급 확신도 / v2 재현 가능성.

    `evidence/` 가 append-only 라 두 정의가 같은 컬럼에 섞이면 감사 시점에 어느 쪽으로
    해석할지 판단할 근거가 없어진다(PR #114 리뷰 → 결정 10.38 → 10.46).
    """
    bogus = make_judgment().model_copy(update={"prompt_version": "모델이 만든 값"})
    judgment, _ = _score(bogus)
    assert judgment.prompt_version == scoring.PROMPT_VERSION


def test_prompt_version_matches_the_prompt_file_actually_used():
    """상수와 파일이 어긋나면 레코드가 없는 프롬프트를 가리킨다."""
    assert scoring.PROMPT_PATH.name == f"{scoring.PROMPT_VERSION}.md"
    assert scoring.PROMPT_PATH.exists()
