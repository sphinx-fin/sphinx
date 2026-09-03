#!/usr/bin/env python3
"""F-CMN 지표 수집 — 실세션을 몰고 가며 값을 딴다. 소유: 정세현 (이슈 #327)

## 왜 스크립트가 세션을 만드는가

`evidence/` 는 `jdbc:h2:mem:` 이라 **프로세스와 함께 사라진다**(dev·prod 둘 다). 그래서
"이미 쌓인 것을 나중에 읽는" 방식이 성립하지 않는다. 세션을 **만든 쪽이 응답을 손에 든
동안** 값을 따는 것이 유일한 경로다(#327 코멘트).

파일 DB(`jdbc:h2:file:`) 전환으로 푸는 길은 지금 닫혀 있다 — 결정 10.2.1(NFC 정본 경계)이
미구현이라 전환하면 정규화 안 된 문면으로 해시가 굳는다. append-only 라 되돌릴 수 없다.

## 무엇을 재는가 — 계약을 안 늘린다

    confidence 분포        POST /answers 응답의 confidence
    채점 소요시간          이 스크립트가 자기 호출 시각을 잰다
    재설명 전후 등급 전환   재설명 전후로 같은 항목의 grade 를 두 번 받는다
    게이트 신호            POST /judge 응답의 signal

## ❗못 재는 것 — 폴백률

`questionSource`(LLM 인가 템플릿 폴백인가)는 `evidence/` 와 리포트 **본문**에만 있고
서버 밖으로 나가는 통로가 없다. `GET /report` 는 메타만 내고 `ReportPdf` 는 안 찍는다.
`#327` 이 통로를 열지 말지를 정한 뒤에 여기 더한다. **그때까지 이 스크립트는 폴백률을
"모른다"고 말한다 — 0 으로 적지 않는다.**

## 실제 LLM 호출이 붙는다

세션 1건이 대략 `항목수 × (질문 1 + 채점 1)` 회다. 기본값을 2건으로 둔 이유가 그것이다.
"많이 돌리면 정확해지는" 종류의 측정이 아니다 — confidence 쏠림(ADR-005)처럼 **몇 건에서도
보이는 것**을 보려는 것이다.

    python3 scripts/evidence_report.py                    # 2건
    python3 scripts/evidence_report.py --sessions 5 --out docs/evidence-report.md
"""
from __future__ import annotations

import argparse
import collections
import json
import statistics
import sys
import time
import urllib.error
import urllib.request

# 취약/비취약이 갈리도록 섞는다 — vulnerability_weights.yaml 이 네 요인의 합으로 정한다.
PROFILES = [
    {"ageBand": "70대", "experienceLevel": "없음", "amountBand": "5천만원대"},   # 취약
    {"ageBand": "30대", "experienceLevel": "3년이상", "amountBand": "1천만원대"},  # 비취약
]

GOOD = ("원금이 손실될 수 있다고 알고 있습니다. 기초자산이 많이 떨어지면 "
        "원금을 다 못 받을 수도 있다고 이해했습니다.")
VAGUE = "잘 모르겠어요."


def call(base: str, method: str, path: str, body: dict | None = None) -> tuple[dict | None, float, int]:
    """(data, 소요초, HTTP상태). 실패도 값으로 돌려준다 — 한 건 실패로 전체를 죽이지 않는다."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"{base}{path}", data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=120) as res:
            elapsed = time.monotonic() - started
            payload = json.loads(res.read())
            return payload.get("data"), elapsed, res.status
    except urllib.error.HTTPError as e:
        elapsed = time.monotonic() - started
        try:
            payload = json.loads(e.read())
            sys.stderr.write(f"  ! {method} {path} → {e.code} {payload.get('error', {}).get('code')}\n")
        except Exception:
            sys.stderr.write(f"  ! {method} {path} → {e.code}\n")
        return None, elapsed, e.code
    except Exception as exc:
        return None, time.monotonic() - started, 0


def walk(base: str, product: str, profile: dict, obs: dict) -> None:
    """세션 하나를 끝까지 몰고 가며 관측값을 obs 에 쌓는다."""
    created, _, _ = call(base, "POST", "/sessions",
                         {"productId": product, "channel": "FACE_TO_FACE", **profile})
    if not created:
        return
    sid = created["sessionId"]
    print(f"  세션 {sid[:8]}  {profile['ageBand']}/{profile['experienceLevel']}")

    first = True
    while True:
        q, _, _ = call(base, "POST", f"/sessions/{sid}/questions/next")
        if not q or q.get("done"):
            break
        item = q["itemId"]

        # 첫 항목만 일부러 흐리게 답한다 — 재설명 루프를 타야 전후 전환을 잴 수 있다.
        text = VAGUE if first else GOOD
        j, took, _ = call(base, "POST", f"/sessions/{sid}/answers", {"itemId": item, "text": text})
        if not j:
            break
        obs["score_secs"].append(took)
        obs["confidence"][str(j["confidence"])] += 1
        obs["grade"][j["grade"]] += 1
        print(f"    {item:34} {j['grade']} · conf {j['confidence']} · {took:.1f}s")

        if first:
            first = False
            before = j["grade"]
            re, _, status = call(base, "POST", f"/sessions/{sid}/re-explain", {"itemId": item})
            if re:
                # 재설명 뒤 같은 항목을 다시 답하면 그 답이 재검증으로 기록된다.
                j2, took2, _ = call(base, "POST", f"/sessions/{sid}/answers",
                                    {"itemId": item, "text": GOOD})
                if j2:
                    obs["score_secs"].append(took2)
                    obs["confidence"][str(j2["confidence"])] += 1
                    obs["transitions"][f"{before} → {j2['grade']}"] += 1
                    print(f"      재설명 후 {before} → {j2['grade']}")
            elif status == 400:
                obs["reexplain_skipped"] += 1

    gate, _, _ = call(base, "POST", f"/sessions/{sid}/judge")
    if gate:
        obs["signal"][gate["signal"]] += 1
        print(f"    판정 {gate['signal']}  {gate.get('ruleTrace')}")


def render(obs: dict, sessions: int) -> str:
    L = [f"# 실세션 지표 — 세션 {sessions}건", ""]
    L.append("evidence 가 인메모리라 이 값들은 **이 프로세스 생애 안에서 만든 세션**의 것이다.")
    L.append("누적 통계가 아니다 — 재기동하면 0 에서 시작한다(이슈 #327).")
    L.append("")

    L.append("## confidence 분포")
    L.append("")
    if obs["confidence"]:
        total = sum(obs["confidence"].values())
        for v, n in sorted(obs["confidence"].items(), key=lambda kv: -float(kv[0])):
            L.append(f"- `{v}` — {n}건 ({n / total:.0%})")
        L.append("")
        L.append(f"고유값 {len(obs['confidence'])}개. ADR-005 가 dev set 24건에서 "
                 "`[0.7, 0.9, 1.0]` 세 값만 관측했고 `<0.7` 이 0건이었다 — "
                 "**여기서도 같은 모양이면 R-05(anyConfidenceBelow 0.7)는 모델 자기보고로는 "
                 "발동하지 않는다는 뜻이다.**")
    else:
        L.append("- 관측 없음")
    L.append("")

    L.append("## 채점 소요시간 (`POST /answers` 왕복)")
    L.append("")
    s = obs["score_secs"]
    if s:
        L.append(f"- 건수 {len(s)} · 중앙값 {statistics.median(s):.1f}s · "
                 f"최대 {max(s):.1f}s · 합계 {sum(s):.0f}s")
        L.append("")
        L.append("창구 체류시간의 하한이다 — 고객이 답을 적는 시간은 여기 안 들어간다.")
    else:
        L.append("- 관측 없음")
    L.append("")

    L.append("## 재설명 전후 등급 전환")
    L.append("")
    if obs["transitions"]:
        for k, n in obs["transitions"].most_common():
            L.append(f"- `{k}` — {n}건")
        L.append("")
        L.append("**이 제품이 막기만 한 게 아니라 이해시켰는가**의 숫자다.")
    else:
        L.append(f"- 관측 없음 (재설명 대상이 아니었던 항목 {obs['reexplain_skipped']}건)")
    L.append("")

    L.append("## 게이트 신호")
    L.append("")
    if obs["signal"]:
        for k, n in obs["signal"].most_common():
            L.append(f"- `{k}` — {n}건")
        L.append("")
        L.append("합성 세션으로는 이 분포를 만들 수 없다(#321 실측: RED 92.6% / GREEN 0%). "
                 "실세션만 의미가 있다.")
    else:
        L.append("- 관측 없음")
    L.append("")

    L.append("## 못 잰 것 — 폴백률")
    L.append("")
    L.append("`questionSource`(LLM 인가 템플릿 폴백인가)가 서버 밖으로 나가는 통로가 없다. "
             "`GET /report` 는 메타만 내고 `ReportPdf` 는 안 찍는다. **0 이 아니라 모른다.** "
             "통로를 열지 말지는 `#327` 에서 정한다.")
    return "\n".join(L) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8000")
    ap.add_argument("--sessions", type=int, default=2)
    ap.add_argument("--product", default=None)
    ap.add_argument("--out")
    args = ap.parse_args()

    products, _, _ = call(args.base, "GET", "/products")
    if not products:
        sys.stderr.write("상품 목록을 못 받았다 — 서버가 떠 있는지 본다\n")
        return 1
    product = args.product or products[0]["productId"]

    obs = {
        "confidence": collections.Counter(),
        "grade": collections.Counter(),
        "signal": collections.Counter(),
        "transitions": collections.Counter(),
        "score_secs": [],
        "reexplain_skipped": 0,
    }
    for i in range(args.sessions):
        walk(args.base, product, PROFILES[i % len(PROFILES)], obs)

    report = render(obs, args.sessions)
    print()
    print(report)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(report)
        sys.stderr.write(f"저장: {args.out}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
