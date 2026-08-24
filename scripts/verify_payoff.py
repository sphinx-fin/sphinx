"""손익 계산 로직 검산 (F-SIM-001). 소유: 정세현

    python3 scripts/verify_payoff.py

발행사가 간이투자설명서에 실어둔 모의실험 결과와 우리 계산을 대조한다. 우리끼리 계산해서
우리끼리 맞다고 하는 것보다, 발행사가 같은 상품에 대해 공시한 숫자와 맞춰보는 것이 강하다.

## 정확히 일치할 수 없는 이유

문서에 모의실험의 **관찰기간과 휴장일 처리 방식이 안 적혀 있다.** 발행사는 4,688개 계약일을
썼는데 우리가 굴릴 수 있는 건 4,110개다(야후의 EuroStoxx50 데이터가 2007-03-30부터라
그 앞을 못 본다). 구성 종목이 같아도 기간이 다르면 분포가 달라진다.

따라서 판정 기준은 **건수 일치가 아니라 분포 형태**다. 1차 조기상환이 압도적이고(≈88%),
손실이 2%대이고, 손실이 나면 -30%대~-50%대에 몰리는 형태가 재현되면 로직이 맞다고 본다.
손실률이 20%나 0.1%로 나오면 어딘가 틀린 것이다.
"""
import sys
from collections import Counter

sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parent))

import payoff_reference as pr  # noqa: E402

#: 간이투자설명서 p11~p12 "4. 기초자산의 과거 데이터를 이용한 수익률 모의실험" 표 전문.
#: (모든 기초자산의 기준가격을 100이라 가정)
ISSUER = {
    "total": 4688,
    "buckets": [
        ("early_1", "1차 조기상환", 0.0550, 4141, 88.332),
        ("early_2", "2차 조기상환", 0.1100, 166, 3.541),
        ("early_3", "3차 조기상환", 0.1650, 120, 2.560),
        ("early_4", "4차 조기상환", 0.2200, 49, 1.045),
        ("early_5", "5차 조기상환", 0.2750, 47, 1.003),
        ("maturity", "만기상환", 0.3300, 60, 1.280),
        ("loss", "손실", None, 105, 2.240),
    ],
    #: 손실 구간별 분포. 합계 105로 표의 손실 건수와 일치한다.
    "loss_bands": [
        ("-56% ~ -52%", -0.56, -0.52, 0, 0.000),
        ("-52% ~ -48%", -0.52, -0.48, 8, 0.171),
        ("-48% ~ -44%", -0.48, -0.44, 39, 0.832),
        ("-44% ~ -40%", -0.44, -0.40, 36, 0.768),
        ("-40% ~ -36%", -0.40, -0.36, 6, 0.128),
        ("-36% ~ -32%", -0.36, -0.32, 16, 0.341),
        ("-32% ~ -28%", -0.32, -0.28, 0, 0.000),
    ],
}


def main():
    product = pr.KIWOOM_4181
    series = {k: pr.load_series(k) for k in product.underlyings}
    dates = pr.start_dates(product, series)

    outcomes = []
    for d in dates:
        out = pr.simulate(product, series, d)
        if out is not None:
            outcomes.append(out)

    counts = Counter(o.result for o in outcomes)
    total = len(outcomes)

    print(f"상품: {product.name}")
    print(f"기초자산: {', '.join(product.underlyings)}")
    print(f"조건: 3년/6개월 {'-'.join(str(int(b*100)) for b in product.barriers)}"
          f" / KI{int(product.knock_in*100)} / 연 {product.coupon_annual*100:.2f}%")
    print(f"관찰기간: {dates[0]} ~ {dates[-1]}  계약일 {total:,}개"
          f"  (발행사 {ISSUER['total']:,}개)")
    print()
    print(f"{'구분':<14}{'수익률':>8}{'우리 건수':>10}{'우리 비중':>10}"
          f"{'발행사 건수':>12}{'발행사 비중':>12}{'차이(%p)':>10}")
    print("-" * 78)

    for key, label, rate, i_count, i_pct in ISSUER["buckets"]:
        n = counts.get(key, 0)
        pct = n / total * 100 if total else 0.0
        rate_s = f"{rate*100:.2f}%" if rate is not None else "-"
        print(f"{label:<14}{rate_s:>8}{n:>10,}{pct:>9.3f}%"
              f"{i_count:>12,}{i_pct:>11.3f}%{pct - i_pct:>+10.2f}")

    print("-" * 78)
    print(f"{'합계':<14}{'':>8}{total:>10,}{100.0:>9.3f}%"
          f"{ISSUER['total']:>12,}{100.0:>11.3f}%")

    losses = [o for o in outcomes if o.is_loss]
    print()
    print("손실 구간 분포")
    print(f"{'구간':<16}{'우리 건수':>10}{'우리 비중':>10}{'발행사 건수':>12}{'발행사 비중':>12}")
    print("-" * 60)
    banded = 0
    for label, lo, hi, i_count, i_pct in ISSUER["loss_bands"]:
        n = sum(1 for o in losses if lo <= o.pnl_rate < hi)
        banded += n
        pct = n / total * 100 if total else 0.0
        print(f"{label:<16}{n:>10,}{pct:>9.3f}%{i_count:>12,}{i_pct:>11.3f}%")
    outside = len(losses) - banded
    if outside:
        worst = min(o.pnl_rate for o in losses)
        print(f"{'구간 밖':<16}{outside:>10,}{outside/total*100:>9.3f}%"
              f"{'-':>12}{'-':>12}   최악 {worst*100:.1f}%")
    print("-" * 60)
    print(f"{'손실 합계':<16}{len(losses):>10,}{len(losses)/total*100:>9.3f}%"
          f"{105:>12,}{2.240:>11.3f}%")

    window_sensitivity(product, series, outcomes)
    demo_table(product, outcomes)


def window_sensitivity(product, series, outcomes):
    """우리 비중이 발행사와 어긋나는 이유가 로직인지 관찰기간인지 가른다.

    우리 창은 2007-03-30에 시작한다 — 금융위기 직전이다. 발행사는 578개 계약일을 더 썼고
    그 앞은 2005~2006년의 평온한 구간일 것이다. 그렇다면 우리 창이 위기에 과대 노출된 것이고,
    위기 진입 구간을 빼면 발행사 쪽으로 붙어야 한다. 그러면 차이의 원인은 로직이 아니다.
    """
    from datetime import date as _date

    print()
    print("관찰기간 민감도 — 계약일 시작점을 옮기면")
    print(f"{'계약일 시작':<14}{'건수':>8}{'1차 조기상환':>14}{'손실':>10}")
    print("-" * 46)
    for cutoff, label in ((None, "전체 (2007-03)"), (_date(2009, 1, 1), "2009-01 이후"),
                          (_date(2011, 1, 1), "2011-01 이후")):
        subset = [o for o in outcomes if cutoff is None or o.start_date >= cutoff]
        if not subset:
            continue
        n = len(subset)
        e1 = sum(1 for o in subset if o.result == "early_1") / n * 100
        ls = sum(1 for o in subset if o.is_loss) / n * 100
        print(f"{label:<14}{n:>8,}{e1:>13.3f}%{ls:>9.3f}%")
    print(f"{'발행사':<14}{ISSUER['total']:>8,}{88.332:>13.3f}%{2.240:>9.3f}%")


def demo_table(product, outcomes):
    """기획서 7-2 데모 표 재산출.

    기존 표는 가정치(낙인 50%, 쿠폰 연 6%)로 만들어졌다. 실제 확보한 상품은 낙인 45%,
    쿠폰 연 11.00%이므로 금액이 달라진다.
    """
    AMOUNT = 50_000_000
    losses = [o for o in outcomes if o.is_loss]
    worst = min(losses, key=lambda o: o.pnl_rate)
    early1 = next(o for o in outcomes if o.result == "early_1")
    at_maturity = next((o for o in outcomes if o.result == "maturity"), None)

    print()
    print(f"기획서 7-2 데모 표 재산출 (가입금액 {AMOUNT:,}원)")
    print(f"{'시나리오':<10}{'전개':<34}{'상환금액':>14}{'손익':>14}{'비중':>9}")
    print("-" * 82)

    total = len(outcomes)
    rows = [
        ("최선", f"6개월 뒤 1차 조기상환 (배리어 85)", early1,
         sum(1 for o in outcomes if o.result == "early_1") / total * 100),
        ("중간", f"조기상환 없이 만기 상환 조건 충족(3년)", at_maturity,
         sum(1 for o in outcomes if o.result == "maturity") / total * 100),
        ("최악", f"낙인 45 하회 + 만기 최저종목 {worst.worst_final*100:.0f}", worst,
         len(losses) / total * 100),
    ]
    for label, desc, o, pct in rows:
        if o is None:
            continue
        payout = o.payout(AMOUNT)
        print(f"{label:<10}{desc:<34}{payout:>13,}원{payout - AMOUNT:>+13,}원{pct:>8.2f}%")

    print()
    print(f"최악 계약일: {worst.start_date} (만기 {pr.add_months(worst.start_date, 36)})"
          f"  손익률 {worst.pnl_rate*100:.2f}%")
    print(f"기존 기획서 표(가정치 낙인 50·쿠폰 6%)의 최악은 3,200만원(-1,800만원)이었다.")
    print(f"실제 상품 조건으로는 {worst.payout(AMOUNT):,}원({worst.payout(AMOUNT)-AMOUNT:+,}원) — 더 나쁘다.")


if __name__ == "__main__":
    main()
