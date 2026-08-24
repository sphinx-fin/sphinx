"""ELS 손익구조 계산 — 참조 구현. 소유: 정세현 (F-SIM-001)

**이 파일은 최종 산출물이 아니다.** 시뮬레이터 본체는
`server/src/main/java/com/sphinxfin/sphinx/simulator/SimulatorService.java`(Java)다. 여기는
Java 빌드 환경(gradle 래퍼 부재)과 계산 로직 검증을 분리하기 위한 참조 구현이고, 발행사가
문서에 실어둔 모의실험 수치와 대조해 로직이 맞는지 먼저 확인하는 용도다. Java 포팅 시
같은 결과가 나와야 한다.

## 원칙

- **순수 함수.** LLM 미개입(P2). 같은 입력이면 항상 같은 출력.
- **종가만 쓴다.** 상품 문서가 조기상환·낙인 판정을 전부 종가로 정의한다
  (p7 "각 기초자산 종가", p10 "종가가 어느 하나라도 낙인구간 미만으로 하락한 적이 있고").
  시가·고가·저가는 판정에 들어가지 않는다.
- **없는 종가를 만들지 않는다.** 지수마다 휴장일이 다르므로 평가일에 종가가 없으면 그 이전
  최근 영업일 종가를 쓴다(직전 영업일 관행). 보간하거나 앞값으로 채워 새 숫자를 만들지 않는다.
"""
from __future__ import annotations

import bisect
import csv
import pathlib
from dataclasses import dataclass, field
from datetime import date

TIMESERIES_DIR = pathlib.Path(__file__).resolve().parent.parent / "data" / "timeseries"


# --- 시계열 -----------------------------------------------------------------------

class Series:
    """한 지수의 일별 종가. 날짜 오름차순."""

    def __init__(self, name: str, rows: list[tuple[date, float]]):
        self.name = name
        self.dates = [d for d, _ in rows]
        self.closes = [c for _, c in rows]

    def __len__(self):
        return len(self.dates)

    @property
    def first(self) -> date:
        return self.dates[0]

    @property
    def last(self) -> date:
        return self.dates[-1]

    def close_on_or_before(self, target: date) -> tuple[date, float] | None:
        """평가일에 휴장이면 직전 영업일 종가를 쓴다. 그 이전 데이터가 없으면 None."""
        i = bisect.bisect_right(self.dates, target) - 1
        if i < 0:
            return None
        return self.dates[i], self.closes[i]

    def min_close_between(self, start: date, end: date) -> float | None:
        """(start, end] 구간의 최저 종가. 낙인 관찰용 — 매 영업일 종가를 본다."""
        lo = bisect.bisect_right(self.dates, start)
        hi = bisect.bisect_right(self.dates, end)
        if lo >= hi:
            return None
        return min(self.closes[lo:hi])


def load_series(key: str) -> Series:
    path = TIMESERIES_DIR / f"{key}.csv"
    if not path.exists():
        raise FileNotFoundError(
            f"{path.relative_to(TIMESERIES_DIR.parent.parent)} 가 없다. "
            f"python3 scripts/fetch_timeseries.py"
        )
    rows = []
    with path.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            rows.append((date.fromisoformat(row["date"]), float(row["close"])))
    rows.sort()
    return Series(key, rows)


# --- 상품 조건 ---------------------------------------------------------------------

@dataclass(frozen=True)
class Product:
    """스텝다운 ELS 조건. 데모 대상 상품의 실제 값으로 채운다."""

    name: str
    underlyings: tuple[str, ...]
    #: 조기상환·만기 평가 시점 (계약일로부터 개월). 마지막 항목이 만기평가일이다.
    observation_months: tuple[int, ...]
    #: 각 평가일의 배리어 (최초기준가격 대비 비율). observation_months와 길이가 같다.
    barriers: tuple[float, ...]
    #: 연 쿠폰율. i번째 평가일 지급률 = coupon_annual * months/12 (스텝다운 관행)
    coupon_annual: float
    #: 낙인 배리어. None이면 노낙인형.
    knock_in: float | None
    note: str = ""

    def __post_init__(self):
        if len(self.observation_months) != len(self.barriers):
            raise ValueError("observation_months와 barriers의 길이가 다르다")

    @property
    def maturity_months(self) -> int:
        return self.observation_months[-1]

    def payout_rate(self, obs_index: int) -> float:
        return self.coupon_annual * self.observation_months[obs_index] / 12


#: 키움증권 제4181회 파생결합증권(주가연계증권) — 데모 대상 ELS.
#: 조건 근거: 간이투자설명서 p7(평가일·손익구조), p10(낙인 45%·만기 손실조건 70%),
#: 금투협 청약정보 요약("[스텝다운] 3년/6개월/85-85-85-80-75-70/KI45", 연 11.00%).
KIWOOM_4181 = Product(
    name="키움증권 제4181회 ELS",
    underlyings=("sp500", "nikkei225", "eurostoxx50"),
    observation_months=(6, 12, 18, 24, 30, 36),
    barriers=(0.85, 0.85, 0.85, 0.80, 0.75, 0.70),
    coupon_annual=0.11,
    knock_in=0.45,
    note="3년/6개월 스텝다운, 원화, 최대손실률 -100%",
)


# --- 손익 계산 ---------------------------------------------------------------------

@dataclass
class Outcome:
    """한 번의 시뮬레이션 결과."""

    start_date: date
    #: "early_1".."early_5" | "maturity" | "loss"
    result: str
    #: 원금 대비 손익률. 손실이면 음수.
    pnl_rate: float
    #: 만기까지 간 경우 만기평가일의 최저 종목 비율. 조기상환이면 None.
    worst_final: float | None
    knocked_in: bool
    initial: dict[str, float] = field(default_factory=dict)

    @property
    def is_loss(self) -> bool:
        return self.result == "loss"

    def payout(self, amount: int) -> int:
        """가입금액을 넣으면 상환금액(원). 반올림은 원 단위 절사 — 표시용이므로 보수적으로."""
        return int(amount * (1 + self.pnl_rate))


def add_months(d: date, months: int) -> date:
    """계약일 + n개월. 말일 처리는 '해당 월에 그 날짜가 없으면 말일'."""
    year = d.year + (d.month - 1 + months) // 12
    month = (d.month - 1 + months) % 12 + 1
    day = d.day
    while True:
        try:
            return date(year, month, day)
        except ValueError:
            day -= 1


def simulate(product: Product, series: dict[str, Series], start: date) -> Outcome | None:
    """start를 최초기준가격평가일로 보고 상품을 굴린다. 데이터가 부족하면 None."""
    initial = {}
    for key in product.underlyings:
        got = series[key].close_on_or_before(start)
        if got is None:
            return None
        initial[key] = got[1]

    maturity = add_months(start, product.maturity_months)
    # 만기 평가일이 보유 데이터 끝을 넘으면 아직 결과를 모른다 — 굴리지 않는다.
    for key in product.underlyings:
        if maturity > series[key].last:
            return None

    # 조기상환 판정: 모든 기초자산이 배리어 이상이어야 한다.
    for i, months in enumerate(product.observation_months[:-1]):
        eval_date = add_months(start, months)
        ratios = []
        for key in product.underlyings:
            got = series[key].close_on_or_before(eval_date)
            if got is None:
                return None
            ratios.append(got[1] / initial[key])
        if min(ratios) >= product.barriers[i]:
            return Outcome(
                start_date=start,
                result=f"early_{i + 1}",
                pnl_rate=product.payout_rate(i),
                worst_final=None,
                knocked_in=False,
                initial=initial,
            )

    # 만기 판정
    final_ratios = {}
    for key in product.underlyings:
        got = series[key].close_on_or_before(maturity)
        if got is None:
            return None
        final_ratios[key] = got[1] / initial[key]
    worst_final = min(final_ratios.values())

    knocked_in = False
    if product.knock_in is not None:
        for key in product.underlyings:
            low = series[key].min_close_between(start, maturity)
            if low is not None and low / initial[key] < product.knock_in:
                knocked_in = True
                break

    maturity_barrier = product.barriers[-1]
    full_coupon = product.payout_rate(len(product.observation_months) - 1)

    # 문서 p10: 낙인이 발생했고(and) 만기평가일 최저 종목이 만기배리어 미만인 경우에만 손실.
    # 둘 중 하나만 해당하면 쿠폰을 받는다 — 노낙인 보호가 여기서 작동한다.
    if knocked_in and worst_final < maturity_barrier:
        return Outcome(start, "loss", worst_final - 1.0, worst_final, True, initial)

    return Outcome(start, "maturity", full_coupon, worst_final, knocked_in, initial)


def start_dates(product: Product, series: dict[str, Series]) -> list[date]:
    """굴릴 수 있는 모든 계약일. 기준이 되는 지수는 데이터가 가장 늦게 시작하는 것."""
    latest_start = max(series[k].first for k in product.underlyings)
    earliest_last = min(series[k].last for k in product.underlyings)
    # 기준 지수의 영업일을 계약일 후보로 쓴다.
    pivot = max((series[k] for k in product.underlyings), key=lambda s: s.first)
    out = []
    for d in pivot.dates:
        if d < latest_start:
            continue
        if add_months(d, product.maturity_months) > earliest_last:
            break
        out.append(d)
    return out
