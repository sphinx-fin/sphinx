"""기초자산 지수 시계열 수집 (F-SIM-001 입력). 소유: 정세현

    python3 scripts/fetch_timeseries.py            # 전부
    python3 scripts/fetch_timeseries.py sp500      # 하나만

저장: `data/timeseries/<이름>.csv` + `VERSION`. **CSV는 git에 커밋한다** — 시뮬레이터의
재현성(P2)이 이 파일에 걸려 있어서, 각자 받으면 각자 다른 숫자가 나온다.

## 왜 2000년부터인가

ELS 사태의 결정적 장면이 분석기간에서 2008년을 뺀 것이었다(기획서). 그래서 2008년과 2020년이
구간 안에 반드시 들어와야 하고, 3년 만기 상품을 2008년 시작 시점부터 굴려보려면 그 앞으로도
여유가 필요하다. 2000년부터 받으면 닷컴 붕괴까지 들어온다.

## 종목 선정 근거

키움증권 제4181회(데모 대상 ELS)의 기초자산 3종이다. 기존 VERSION에는 HSCEI가 적혀 있었는데
실제 확보한 문서의 기초자산이 아니다 — 문서 기준으로 정정했다.

## 스냅샷 고정

`SNAPSHOT_DATE`를 코드에 박고 그 날짜까지만 받는다. "오늘까지"로 두면 돌릴 때마다 파일이
달라져 재현성이 깨진다. VERSION에 각 CSV의 sha256을 남기므로, 야후가 과거 데이터를 정정하면
그 사실이 드러난다.
"""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone

OUT_DIR = pathlib.Path(__file__).resolve().parent.parent / "data" / "timeseries"

#: 이 날짜까지의 종가만 받는다. 갱신하려면 이 값을 올리고 VERSION을 다시 쓴다.
SNAPSHOT_DATE = "2026-08-24"
START_DATE = "2000-01-01"

#: 짧게 유지해야 한다. 긴 Chrome UA를 보내면 야후가 429로 막는다 — 실제 Chrome이라면
#: Sec-CH-UA 클라이언트 힌트가 함께 오는데 curl은 안 보내므로 위장으로 판정되는 것으로 보인다.
#: 재현: 같은 URL에 UA만 바꿔 4회 호출 → 짧게 200 / 길게 429 / 없이 429 / 짧게 200.
_UA = "Mozilla/5.0"

INDICES = {
    "sp500": {"symbol": "^GSPC", "label": "S&P500 지수"},
    "nikkei225": {"symbol": "^N225", "label": "NIKKEI225 지수"},
    "eurostoxx50": {"symbol": "^STOXX50E", "label": "EuroStoxx50 지수"},
}


def _epoch(date_str):
    return int(datetime.strptime(date_str, "%Y-%m-%d")
               .replace(tzinfo=timezone.utc).timestamp())


#: 429가 잘 온다. 짧은 간격으로 여러 번 때리면 막히므로 물러서면서 다시 시도한다.
_RETRY_WAITS = (5, 15, 40, 90)


def _curl(url, cookie_jar, *, save=None, retry=True):
    """curl로 받는다 — 시스템 python3에 CA 번들이 없는 환경이 있다(fetch_documents.py 참고)."""
    if not shutil.which("curl"):
        raise RuntimeError("curl이 필요하다")
    cmd = ["curl", "-sS", "--fail", "--location", "--max-time", "60", "-A", _UA,
           "-c", cookie_jar, "-b", cookie_jar]
    if save is not None:
        cmd += ["-o", str(save)]

    last = ""
    for attempt, wait in enumerate((0,) + (_RETRY_WAITS if retry else ())):
        if wait:
            print(f"       429/실패 — {wait}초 후 재시도 ({attempt}/{len(_RETRY_WAITS)})",
                  file=sys.stderr)
            time.sleep(wait)
        proc = subprocess.run(cmd + [url], capture_output=True)
        if proc.returncode == 0:
            return proc.stdout
        last = proc.stderr.decode(errors="replace").strip()
    raise RuntimeError(f"curl 실패: {last}")


def fetch(key, spec, cookie_jar):
    url = (
        f"https://query1.finance.yahoo.com/v8/finance/chart/{spec['symbol']}"
        f"?period1={_epoch(START_DATE)}&period2={_epoch(SNAPSHOT_DATE) + 86400}"
        f"&interval=1d"
    )
    payload = json.loads(_curl(url, cookie_jar))
    result = payload["chart"]["result"][0]
    stamps = result["timestamp"]
    closes = result["indicators"]["quote"][0]["close"]

    rows = []
    for ts, close in zip(stamps, closes):
        if close is None:  # 휴장일. 앞값으로 채우지 않는다 — 있지도 않은 종가를 만들면 안 된다.
            continue
        date = datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%d")
        if date > SNAPSHOT_DATE:
            continue
        rows.append((date, close))

    # 같은 날짜가 두 번 오는 경우(장중 스냅샷 혼입)를 막는다. 마지막 값을 쓴다.
    dedup = dict(rows)
    rows = sorted(dedup.items())
    if not rows:
        raise RuntimeError(f"{key}: 받은 데이터가 없다")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    dest = OUT_DIR / f"{key}.csv"
    with dest.open("w", encoding="utf-8", newline="\n") as f:
        f.write("date,close\n")
        for date, close in rows:
            f.write(f"{date},{close:.4f}\n")

    digest = hashlib.sha256(dest.read_bytes()).hexdigest()
    print(f"[ok] {key}: {len(rows):,}행 {rows[0][0]}~{rows[-1][0]}  sha256 {digest[:16]}…")
    return {
        "file": dest.name,
        "symbol": spec["symbol"],
        "label": spec["label"],
        "rows": len(rows),
        "first_date": rows[0][0],
        "last_date": rows[-1][0],
        "sha256": digest,
    }


def write_version(meta):
    lines = [
        "# 기초자산 지수 시계열 스냅샷 (F-SIM-001). 소유: 정세현",
        "#",
        "# 이 파일이 시뮬레이터 재현성(P2)의 근거다. CSV가 바뀌면 sha256이 어긋나고,",
        "# 그러면 시뮬레이터 출력이 달라진 원인이 코드가 아니라 데이터임을 알 수 있다.",
        "#",
        "# 종목은 키움증권 제4181회(데모 대상 ELS)의 기초자산 3종이다.",
        "# 이전 값(HSCEI)은 실제 확보 문서의 기초자산이 아니어서 정정했다.",
        "",
        f"snapshot: {meta['snapshot_date']}",
        f"source: {meta['source']}",
        f"range: {START_DATE} ~ {SNAPSHOT_DATE}",
        f"fetched_by: scripts/fetch_timeseries.py",
        "",
        "series:",
    ]
    for key, m in meta["series"].items():
        lines += [
            f"  {key}:",
            f"    label: {m['label']}",
            f"    symbol: {m['symbol']}",
            f"    file: {m['file']}",
            f"    rows: {m['rows']}",
            f"    first_date: {m['first_date']}",
            f"    last_date: {m['last_date']}",
            f"    sha256: {m['sha256']}",
        ]
    (OUT_DIR / "VERSION").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"[ok] VERSION 갱신")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("keys", nargs="*", help=f"대상 (기본: 전부). 선택지: {', '.join(INDICES)}")
    args = ap.parse_args()

    keys = args.keys or list(INDICES)
    unknown = [k for k in keys if k not in INDICES]
    if unknown:
        ap.error(f"등록되지 않은 지수: {unknown}. 선택지: {list(INDICES)}")

    series = {}
    with tempfile.TemporaryDirectory() as tmp:
        cookie_jar = str(pathlib.Path(tmp) / "yahoo_cookies.txt")
        for i, key in enumerate(keys):
            if i:
                time.sleep(4)  # 연속 호출은 간격을 둔다
            try:
                series[key] = fetch(key, INDICES[key], cookie_jar)
            except RuntimeError as exc:
                print(f"[FAIL] {key}: {exc}", file=sys.stderr)
                return 1

    if set(keys) == set(INDICES):
        write_version({
            "snapshot_date": SNAPSHOT_DATE,
            "source": "Yahoo Finance chart API (query1.finance.yahoo.com/v8/finance/chart)",
            "series": series,
        })
    else:
        print("일부만 받았으므로 VERSION은 갱신하지 않았다. 전부 받아야 스냅샷이 일관된다.",
              file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
