"""수치 추출·대조. 소유: 윤지석

`numerics` 는 세 기능이 공유하는 **단 하나의 추출기**다 — F-INT-002(질문에서 수치 금지) ·
F-INT-004(원문 수치만 허용) · F-EXT-002 구제(표 셀 선택 키). 모듈 docstring 이
*"두 벌로 두면 한쪽만 고쳐지고 다른 쪽이 뚫린 채 남는다"* 로 그 이유를 적어 뒀다.

여기서 잠그는 것은 **정규화가 두 벌이라는 사실**이다(이슈 #175). 추출기는 하나로 두되
대조용(`canonical`)과 추출용(`_for_numbers`)의 공백 처리가 반대여야 한다.
"""
from __future__ import annotations

from app import numerics


#: 계약 샘플 `VAR-EARLY-SURRENDER-RATIO` 의 조건 원문. **표 한 행**이고 셀 구분이 공백뿐이다.
TABLE_ROW = "3개월 900,000 526,240 58.4"


# ── 이슈 #175 — 공백이 셀 경계다 ──────────────────────────────────────────────
def test_table_row_splits_into_cells():
    """★ 표 한 행이 셀마다 갈려야 한다.

    `canonical()` 로 공백을 지우고 추출하면 `\\d+(?:[.,]\\d+)*` 가 행을 통째로 먹어
    `['3', '90000052624058.4']` 가 나왔다. 셀 경계가 추출 전에 사라지기 때문이다.
    """
    assert numerics.numbers(TABLE_ROW) == ["3", "900000", "526240", "58.4"]
    assert numerics.extract(TABLE_ROW) == [
        ("3", "개월"), ("900000", None), ("526240", None), ("58.4", None),
    ]


def test_mashed_value_never_appears():
    """뭉친 덩어리가 하나라도 남으면 이 수정이 부분적으로만 된 것이다.

    자릿수로 잡는다 — 표의 어느 셀도 이만큼 길지 않으므로, 긴 값이 나오면 두 셀 이상이
    이어붙은 것이다.
    """
    for number in numerics.numbers(TABLE_ROW + " 766,350 85.1 6개월 1,800,000"):
        assert len(number.replace(".", "")) <= 8, f"셀이 뭉쳤다: {number}"


def test_the_two_normalizations_disagree_on_purpose():
    """★ 대조용과 추출용이 공백을 반대로 다룬다 — 그 사실 자체를 잠근다.

    한쪽으로 통일하려는 변경이 오면 여기서 먼저 걸린다. 통일하면 둘 중 하나가 깨진다.

      `canonical` 이 공백을 남기면 — 부분열 대조가 띄어쓰기에 민감해진다. 한국어는 같은 말이
        띄어쓰기만 다른 경우가 흔하고(`"원금 손실"`/`"원금손실"`), 어긋나는 방향이
        **정답 노출을 놓치는 쪽**이다(F-INT-002).
      `_for_numbers` 가 공백을 지우면 — 표의 셀 경계가 사라진다(위 테스트).
    """
    assert numerics.canonical("원금 손실") == "원금손실"
    assert numerics._for_numbers("원금 손실") == "원금 손실"
    assert numerics.canonical("원금손실") in numerics.canonical("그래서 원금 손실이 난다")


# ── 자연어 회귀 — 원래 용도가 안 흔들려야 한다 ────────────────────────────────
def test_natural_language_extraction_is_unchanged():
    """조건 문면·법령 상수·수식이 그대로 나와야 한다."""
    assert numerics.extract("최초기준가격의 85%인") == [("85", "pct")]
    assert numerics.extract("원금의 100분의 20") == [("100", None), ("20", None)]
    assert numerics.extract("연 11.00%") == [("11.00", "pct")]
    assert numerics.extract("1억 + [1억 × (-100%)]= 0 원") == [
        ("1", "억원"), ("1", "억원"), ("100", "pct"), ("0", "원"),
    ]


def test_unit_separated_by_space_still_attaches():
    """`_NUMERIC` 이 숫자와 단위 사이를 `\\s*` 로 흡수하므로 한 칸이 남아도 붙는다.

    공백을 지우던 시절에는 자동으로 붙었다 — 접는 방식으로 바꿨을 때 이게 깨지면
    `45 %` 가 맨숫자 `45` 로 읽혀 단위 대조(P6)가 헐거워진다.
    """
    assert numerics.extract("45 %") == [("45", "pct")]
    assert numerics.extract("3 개월") == [("3", "개월")]


# ── F-INT-004 — 표 값을 인용한 재설명이 환각으로 걸리지 않는다 ────────────────
def test_quoting_a_table_cell_is_not_fabrication():
    """★ 원문 표의 값을 그대로 인용한 재설명이 통과해야 한다.

    수정 전에는 `source_values(TABLE_ROW)` 가 `{('3','개월'), ('90000052624058.4', None)}`
    이라 **어느 셀 값도 허용 집합에 없었다.** `reexplain` 이 MAX_ATTEMPTS 회 재시도 후
    `_minimal` 로 떨어지고, 그 폴백은 `value_text` 를 그대로 보여준다 — 고령 고객 화면에
    표 원문이 나가는 경로다(기획서 4절).
    """
    allowed = numerics.source_values(TABLE_ROW)
    assert numerics.fabricated("3개월 시점에 526,240 을 돌려받습니다.", allowed) == []
    assert numerics.fabricated("환급률은 58.4 입니다.", allowed) == []


def test_a_number_absent_from_the_table_is_still_fabrication():
    """구멍을 넓힌 것이 아니다 — 원문에 없는 수치는 그대로 걸린다 (P6)."""
    allowed = numerics.source_values(TABLE_ROW)
    assert numerics.fabricated("해약환급금은 700,000 입니다.", allowed) == ["700000"]


def test_unit_attached_to_a_bare_table_cell_is_still_refused():
    """**아직 안 풀린 절반을 명시한다** (이슈 #175 후속).

    표의 단위는 **열 머리글**에 있고 `condition.value_text` 는 행 하나뿐이라 단위가 없다.
    그래서 `526,240원`·`58.4%` 처럼 단위를 붙여 쓴 재설명은 여전히 환각으로 걸린다 —
    자연스러운 문장이 막히는 자리다.

    맨숫자를 허용하듯 단위 부착까지 허용하면 안 된다. 그건 **원문에 없는 단위를 모델이
    주장하는 것**이라 P6 이 막으려는 그 경우다(`45%` 배리어를 `45년` 으로 바꿔 말하기).
    올바른 해법은 열 머리글을 `value_text` 에 넣는 쪽이고 그건 F-EXT-002 몫이다.

    지금 상태를 테스트로 박아 두는 이유: 고쳐지면 여기서 깨지고, 그때 이 주석이 근거가 된다.
    """
    allowed = numerics.source_values(TABLE_ROW)
    assert numerics.fabricated("해약환급금은 526,240원입니다.", allowed) == ["526240원"]
    assert numerics.fabricated("환급률은 58.4%입니다.", allowed) == ["58.4%"]


# ── 이슈 #183 — 누출 대조는 양쪽이 같은 정규화를 지나야 한다 ──────────────────
def test_for_leak_check_drops_digit_grouping_commas():
    """`numbers()` 가 조각에서 콤마를 지우므로 대조 상대도 지워야 한다."""
    assert numerics.for_leak_check("526,240") == "526240"
    assert numerics.for_leak_check("36,000,000 원") == "36000000원"
    assert numerics.numbers("526,240") == ["526240"]


def test_for_leak_check_keeps_prose_commas():
    """산문의 콤마는 남긴다 — 텍스트 조각의 대조 의미를 조용히 바꾸지 않는다.

    `.replace(",", "")` 로 전부 지우면 루브릭 조항(`및, 또는`)까지 형태가 바뀐다.
    자릿수 구분은 숫자 사이에서만 성립하므로 그 자리만 본다.
    """
    assert numerics.for_leak_check("원금, 이자") == "원금,이자"
    assert numerics.for_leak_check("만기, 3년") == "만기,3년"      # 콤마 뒤가 숫자여도 앞이 아니면 남는다


def test_canonical_is_untouched_by_the_leak_normalization():
    """`canonical()` 은 그대로다 — `_cited_spans`(F-INT-004) 가 같이 바뀌면 안 된다."""
    assert numerics.canonical("526,240") == "526,240"
