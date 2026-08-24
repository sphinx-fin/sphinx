"""손익 계산 참조 구현 단위 테스트 (F-SIM-001 P2). 소유: 정세현

    .venv/bin/python -m pytest scripts/ -q

시계열을 합성해서 판정 규칙을 하나씩 고정한다. 실제 시장 데이터로는 "낙인은 터졌는데 만기는
배리어 이상" 같은 경계 조합을 골라 넣을 수 없다.

Java(SimulatorService) 포팅 시 같은 케이스가 같은 결과를 내야 한다.
"""
import sys
from datetime import date
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import payoff_reference as pr  # noqa: E402

#: 테스트용 상품. 기초자산 2종, 1년/6개월, 배리어 90-80, 낙인 50, 연 10%.
#: 실제 상품보다 짧게 잡아 시계열을 손으로 만들 수 있게 한다.
TOY = pr.Product(
    name="toy",
    underlyings=("a", "b"),
    observation_months=(6, 12),
    barriers=(0.90, 0.80),
    coupon_annual=0.10,
    knock_in=0.50,
)

START = date(2020, 1, 1)


def series_from(name, points):
    """points: {날짜: 종가}. 빠진 날은 종가가 없는 것으로 둔다(휴장일)."""
    return pr.Series(name, sorted(points.items()))


def flat(name, value, extra=None):
    """START부터 만기까지 매달 1일 같은 값. extra로 특정 날짜만 덮어쓴다."""
    points = {}
    for m in range(0, 13):
        points[pr.add_months(START, m)] = value
    points.update(extra or {})
    return series_from(name, points)


# --- add_months -------------------------------------------------------------------

def test_add_months_basic():
    assert pr.add_months(date(2020, 1, 15), 6) == date(2020, 7, 15)


def test_add_months_clamps_to_last_day_of_shorter_month():
    """1월 31일 + 1개월은 2월 31일이 없으므로 말일로 내린다."""
    assert pr.add_months(date(2020, 1, 31), 1) == date(2020, 2, 29)   # 윤년
    assert pr.add_months(date(2021, 1, 31), 1) == date(2021, 2, 28)
    assert pr.add_months(date(2020, 8, 31), 6) == date(2021, 2, 28)


def test_add_months_crosses_year():
    assert pr.add_months(date(2020, 8, 24), 36) == date(2023, 8, 24)


# --- Series -----------------------------------------------------------------------

def test_close_on_or_before_uses_previous_business_day():
    s = series_from("a", {date(2020, 1, 3): 100.0, date(2020, 1, 6): 110.0})
    assert s.close_on_or_before(date(2020, 1, 5)) == (date(2020, 1, 3), 100.0)
    assert s.close_on_or_before(date(2020, 1, 6)) == (date(2020, 1, 6), 110.0)


def test_close_on_or_before_returns_none_before_history():
    s = series_from("a", {date(2020, 1, 3): 100.0})
    assert s.close_on_or_before(date(2019, 12, 31)) is None


def test_min_close_between_excludes_start_includes_end():
    s = series_from("a", {date(2020, 1, 1): 10.0, date(2020, 1, 2): 20.0,
                          date(2020, 1, 3): 30.0})
    # 계약일 종가(10)는 낙인 관찰 대상이 아니다 — 그날이 기준가격이므로.
    assert s.min_close_between(date(2020, 1, 1), date(2020, 1, 3)) == 20.0
    assert s.min_close_between(date(2020, 1, 3), date(2020, 1, 3)) is None


# --- 조기상환 ---------------------------------------------------------------------

def test_early_redemption_when_all_underlyings_clear_barrier():
    series = {"a": flat("a", 100.0), "b": flat("b", 100.0)}
    out = pr.simulate(TOY, series, START)
    assert out.result == "early_1"
    assert out.pnl_rate == pytest.approx(0.05)   # 연 10% × 6/12
    assert out.payout(50_000_000) == 52_500_000


def test_no_early_redemption_when_one_underlying_misses():
    """하나만 미달해도 조기상환이 안 된다 — worst-of 구조."""
    series = {
        "a": flat("a", 100.0),
        "b": flat("b", 100.0, {pr.add_months(START, 6): 89.0}),   # 배리어 90 미달
    }
    out = pr.simulate(TOY, series, START)
    assert out.result != "early_1"


def test_second_observation_can_redeem_after_first_misses():
    series = {
        "a": flat("a", 100.0, {pr.add_months(START, 6): 80.0}),
        "b": flat("b", 100.0, {pr.add_months(START, 6): 80.0}),
    }
    out = pr.simulate(TOY, series, START)
    assert out.result == "maturity"      # 12개월은 배리어 80, 종가 100 → 충족
    assert out.pnl_rate == pytest.approx(0.10)


# --- 만기·낙인 (문서 p10의 and 조건) -----------------------------------------------

def test_loss_requires_both_knock_in_and_final_below_barrier():
    """낙인이 터지고 만기 최저종목이 배리어 미만 — 이때만 손실이다."""
    series = {
        "a": flat("a", 100.0, {pr.add_months(START, 6): 40.0,      # 낙인 50 하회
                               pr.add_months(START, 12): 60.0}),   # 만기 배리어 80 미만
        "b": flat("b", 100.0, {pr.add_months(START, 6): 85.0}),
    }
    out = pr.simulate(TOY, series, START)
    assert out.result == "loss"
    assert out.knocked_in is True
    assert out.pnl_rate == pytest.approx(-0.40)     # 최저종목 60% → -40%
    assert out.payout(50_000_000) == 30_000_000


def test_knock_in_but_recovered_above_barrier_pays_coupon():
    """낙인이 터졌어도 만기에 배리어 이상으로 회복하면 쿠폰을 받는다."""
    series = {
        "a": flat("a", 100.0, {pr.add_months(START, 6): 40.0,      # 낙인 하회
                               pr.add_months(START, 12): 95.0}),   # 만기 배리어 80 이상
        "b": flat("b", 100.0, {pr.add_months(START, 6): 85.0}),
    }
    out = pr.simulate(TOY, series, START)
    assert out.result == "maturity"
    assert out.knocked_in is True
    assert out.pnl_rate == pytest.approx(0.10)


def test_no_knock_in_pays_coupon_even_below_final_barrier():
    """낙인을 안 건드렸으면 만기에 배리어 미만이어도 쿠폰이다 — 노낙인 보호."""
    series = {
        "a": flat("a", 100.0, {pr.add_months(START, 6): 85.0,      # 낙인 50은 안 건드림
                               pr.add_months(START, 12): 60.0}),   # 만기 배리어 80 미만
        "b": flat("b", 100.0, {pr.add_months(START, 6): 85.0}),
    }
    out = pr.simulate(TOY, series, START)
    assert out.result == "maturity"
    assert out.knocked_in is False
    assert out.pnl_rate == pytest.approx(0.10)


def test_knock_in_watched_on_every_trading_day_not_just_evaluation_dates():
    """낙인은 평가일이 아니라 매 영업일 종가로 관찰한다 (문서 p10)."""
    mid = date(2020, 3, 17)      # 어떤 평가일도 아닌 날
    series = {
        "a": flat("a", 100.0, {mid: 40.0, pr.add_months(START, 6): 85.0,
                               pr.add_months(START, 12): 60.0}),
        "b": flat("b", 100.0, {pr.add_months(START, 6): 85.0}),
    }
    out = pr.simulate(TOY, series, START)
    assert out.knocked_in is True
    assert out.result == "loss"


# --- P2 재현성 --------------------------------------------------------------------

def test_same_input_gives_same_output_100_times():
    series = {
        "a": flat("a", 100.0, {pr.add_months(START, 6): 40.0,
                               pr.add_months(START, 12): 60.0}),
        "b": flat("b", 100.0, {pr.add_months(START, 6): 85.0}),
    }
    first = pr.simulate(TOY, series, START)
    for _ in range(100):
        again = pr.simulate(TOY, series, START)
        assert (again.result, again.pnl_rate, again.worst_final, again.knocked_in) == \
               (first.result, first.pnl_rate, first.worst_final, first.knocked_in)


# --- 데이터 부족 -------------------------------------------------------------------

def test_returns_none_when_maturity_beyond_data():
    """만기가 보유 데이터 끝을 넘으면 결과를 모른다 — 추정하지 않는다."""
    series = {
        "a": series_from("a", {START: 100.0, date(2020, 6, 1): 100.0}),
        "b": series_from("b", {START: 100.0, date(2020, 6, 1): 100.0}),
    }
    assert pr.simulate(TOY, series, START) is None


# --- 상품 조건 무결성 ---------------------------------------------------------------

def test_product_rejects_mismatched_barriers():
    with pytest.raises(ValueError, match="길이가 다르다"):
        pr.Product(name="bad", underlyings=("a",), observation_months=(6, 12),
                   barriers=(0.9,), coupon_annual=0.1, knock_in=0.5)


def test_kiwoom_4181_terms_match_document():
    """조건이 조용히 바뀌면 데모 표 수치가 근거를 잃는다. 문서 값으로 고정한다."""
    p = pr.KIWOOM_4181
    assert p.underlyings == ("sp500", "nikkei225", "eurostoxx50")
    assert p.observation_months == (6, 12, 18, 24, 30, 36)
    assert p.barriers == (0.85, 0.85, 0.85, 0.80, 0.75, 0.70)
    assert p.coupon_annual == 0.11
    assert p.knock_in == 0.45
    # 문서 p7 손익구조의 지급률과 일치해야 한다
    assert [round(p.payout_rate(i), 4) for i in range(6)] == \
        [0.055, 0.11, 0.165, 0.22, 0.275, 0.33]
