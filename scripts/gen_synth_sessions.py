#!/usr/bin/env python3
"""F-DSH-003 합성 세션 생성기. 소유: 정세현

`data/synth_sessions/distribution.yaml` 을 읽어 `sessions.json` 을 만든다.
`scripts/fetch_documents.py` → `data/documents/` 와 같은 모양이다 — **도구는 scripts,
산출물은 data, 서버는 산출물만 읽는다.**

## 왜 서버(Java)가 직접 생성하지 않나

항목별 오해율의 근거가 **루브릭의 `related_misconceptions`** 인데 그 파일은
`ai-service/app/rubrics/` 에 있다. 서버가 거기를 읽으면 모듈 경계를 넘고, 매핑을
`distribution.yaml` 에 복제하면 **두 벌이 되어 갈린다** — 이 레포가 `CanonicalJson` 을
한 벌만 두는 이유와 같다.

생성기를 여기 두면 루브릭을 직접 읽으므로 매핑이 한 벌로 남고, 서버는 커밋된 산출물만
읽는 얇은 로더가 된다.

## 재현성

`seed` 와 파라미터가 같으면 같은 산출물이 나온다. **산출물은 커밋하지 않는다**
(`.gitignore`) — 커밋해 두고 테스트가 그걸 읽으면 로컬에 남은 생성물이 러너에서도 있는
것처럼 보여 **초록이 근거 없이 초록**이 된다. 대신 파라미터를 커밋하고 `--check` 로 현재
파일과 대조한다. 대시보드 수치가 달라지면 `distribution.yaml` 의 diff 가 그 이유를 말한다.

    python3 scripts/gen_synth_sessions.py            # 생성 후 data/ 에 쓴다
    python3 scripts/gen_synth_sessions.py --check    # 쓰지 않고 현재 파일과 대조만
"""
from __future__ import annotations

import argparse
import json
import pathlib
import random
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[1]
PARAMS = REPO / "data" / "synth_sessions" / "distribution.yaml"
OUT = REPO / "data" / "synth_sessions" / "sessions.json"
RUBRICS = REPO / "ai-service" / "app" / "rubrics"
TEMPLATES = REPO / "ai-service" / "app" / "templates"

#: 등급 넷. 오해율은 U4 비율이고(`AggregateService.Tally`), 나머지는 U1~U3 로 흩는다.
GRADES = ["U1", "U2", "U3", "U4"]


def _load_items() -> dict[str, list[str]]:
    """상품유형 → required 항목 목록. 집계 셀이 이 조합이다."""
    out: dict[str, list[str]] = {}
    for path in sorted(TEMPLATES.glob("*.yaml")):
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        out[path.stem] = [i["item_id"] for i in doc["items"]
                          if i.get("importance") == "required"]
    return out


def _load_related() -> dict[str, list[str]]:
    """항목 → 그 루브릭이 선언한 오해 유형. **오해율의 유일한 근거다.**"""
    out: dict[str, list[str]] = {}
    for path in sorted(RUBRICS.glob("*.yaml")):
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        out[path.stem] = list(doc.get("related_misconceptions") or [])
    return out


def _assert_weights_cover(related: dict[str, list[str]], weight: dict) -> None:
    """루브릭이 선언한 오해 유형이 가중치 표에 다 있는지. 없으면 **터뜨린다**.

    `weight.get(m, 0)` 은 모르는 유형을 조용히 0 으로 본다 — 생성은 성공하고 값만 틀린다.
    유형을 새로 넣은 효과가 히트맵에 안 나타나는데 **예외도 경고도 없다**(#194 리뷰, 윤지석).

    그 일이 예정돼 있다. `#148` 이 들어오면 `ELS-MIDWAY-REDEMPTION-COST` 에 `M09`,
    `ELS-NO-LISTING` 에 `M10` 이 붙는데 `distribution.yaml` 이 안 따라오면 두 항목이
    가중치 0 인 채로 지나간다. 그리고 `misrate_of` 가 `lo/hi` 로 **선형 사상**하므로
    최댓값이 바뀌면 **모든 항목의 오해율이 재계산된다** — 틀리는 것이 두 항목에 그치지 않는다.

    이 PR 의 근거가 *"손으로 적으면 라이브러리가 바뀌어도 안 따라온다"* 인데 유도의 입력
    절반(가중치 표)이 손으로 적혀 있다. 그 절반이 낡는 순간을 **실패로** 알린다 —
    `assert_related_misconceptions_exist()` 가 로딩 시점에 죽는 것과 같은 결이다(결정 3.18).

    반대 방향(표에는 있는데 루브릭이 안 쓰는 유형)은 막지 않는다. 라이브러리에 유형이 먼저
    들어오고 루브릭이 나중에 붙는 순서가 정상이고, 그건 값을 틀리게 만들지 않는다.
    """
    unknown = {m for types in related.values() for m in types} - set(weight)
    if unknown:
        raise ValueError(
            f"오해 유형이 가중치 표에 없다: {sorted(unknown)} — 루브릭이 늘었으면 "
            f"{PARAMS.name} 의 misconception_weight 도 같이 올린다. 조용히 0 으로 두면 "
            "새 유형을 넣은 효과가 히트맵에 안 나타나고 생성은 그대로 성공한다"
        )


def misrate_of(item_id: str, related: dict[str, list[str]], params: dict) -> float:
    """항목의 U4 비율. **손으로 적지 않고 라이브러리 가중치에서 유도한다.**

    그 항목이 걸린 오해 유형들의 가중치를 더하고, 전체 항목의 합 범위를
    `misrate_range` 로 선형 사상한다. 절대값은 합성이라 의미가 없고 **편차가 보이는 것**이
    목적이다 — 대시보드가 답해야 하는 것은 *"몇 %인가"* 가 아니라 *"어느 항목이 유독
    높은가"* 다.

    related 가 없는 항목(`VAR-PARTIAL-DEPOSIT-INSURANCE`)은 가중치 0 → 하한이다.
    빠뜨린 것이 아니라 **그 항목에 묶인 오해 유형이 라이브러리에 아직 없다**는 사실이다.
    """
    weight = params["misconception_weight"]
    _assert_weights_cover(related, weight)
    scores = {k: sum(weight.get(m, 0) for m in v) for k, v in related.items()}
    lo, hi = min(scores.values()), max(scores.values())
    rng = params["misrate_range"]
    if hi == lo:
        return rng["min"]
    score = scores.get(item_id, 0)
    return rng["min"] + (rng["max"] - rng["min"]) * (score - lo) / (hi - lo)


def _grade(rnd: random.Random, misrate: float) -> str:
    """U4 를 `misrate` 확률로, 나머지는 U1~U3 으로 흩는다."""
    if rnd.random() < misrate:
        return "U4"
    return rnd.choices(["U1", "U2", "U3"], weights=[5, 3, 2])[0]


def generate(params: dict) -> list[dict]:
    rnd = random.Random(params["seed"])
    items = _load_items()
    related = _load_related()
    branches = params["branches"]
    under = {(u["product"], u["item"]): u["n"] for u in params["under_sampled"]}

    # ❗**절대 시각을 굽지 않는다.** `datetime.now()` 로 `createdAt` 을 박으면 산출물이
    # 생성한 날짜에 묶여서, 다음 날 `--check` 가 다르다고 하고 몇 주 뒤에는 선행지표의
    # 최근 6주 창 밖으로 나가 대시보드가 다시 빈다. 대신 **몇 주 전인가**만 적고 실제
    # 시각은 로더가 적재 시점 기준으로 계산한다 — 언제 넣어도 "최근 6주" 가 된다.

    sessions: list[dict] = []
    produced: dict[tuple[str, str], int] = {}
    n = 0
    for back in range(params["weeks"] - 1, -1, -1):
        for product, item_ids in items.items():
            count = params["sessions_per_week"][product]
            count += rnd.randint(-params["weekly_jitter"], params["weekly_jitter"])
            for _ in range(max(count, 1)):
                n += 1
                branch = branches[n % len(branches)]
                # 판매자는 실제 계정이 아니다 — demo_accounts.yaml 의 id 를 쓰면 그 판매자가
                # own_session 으로 합성 세션을 열 수 있다. 집계에서는 어차피 대체키로 바뀐다.
                seller = f"synth-seller-{(n % 4) + 1:02d}"

                judgments = {}
                for item_id in item_ids:
                    cap = under.get((product, item_id))
                    if cap is not None and produced.get((product, item_id), 0) >= cap:
                        continue        # 일부러 표본을 적게 남긴다 — 마스킹 시연
                    produced[(product, item_id)] = produced.get((product, item_id), 0) + 1
                    judgments[item_id] = _grade(rnd, misrate_of(item_id, related, params))

                sessions.append({
                    "sessionId": f"synth-{n:04d}",
                    "productId": product,
                    "branchId": branch,
                    "sellerId": seller,
                    "ageBand": rnd.choice(params["age_bands"]),
                    "channel": rnd.choice(params["channels"]),
                    # 적재 시점의 그 주 월요일에서 이만큼 떨어진 시각으로 로더가 계산한다.
                    "weeksAgo": back,
                    "dayOfWeek": rnd.randint(0, 6),
                    "hour": rnd.randint(9, 17),
                    "judgments": judgments,
                })
    return sessions


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="쓰지 않고 현재 파일과 대조만")
    args = ap.parse_args()

    params = yaml.safe_load(PARAMS.read_text(encoding="utf-8"))
    sessions = generate(params)
    payload = {
        "generator": "scripts/gen_synth_sessions.py",
        "params": "data/synth_sessions/distribution.yaml",
        "paramsVersion": params["version"],
        "seed": params["seed"],
        "synthetic": True,
        "sessions": sessions,
    }
    text = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"

    if args.check:
        if not OUT.exists():
            print(f"{OUT.relative_to(REPO)} 가 없다. 인자 없이 한 번 돌린다.", file=sys.stderr)
            return 1
        same = OUT.read_text(encoding="utf-8") == text
        print("일치" if same else "다르다 — 파라미터가 바뀌었으면 다시 생성해 커밋한다")
        return 0 if same else 1

    OUT.write_text(text, encoding="utf-8")
    print(f"{OUT.relative_to(REPO)} · 세션 {len(sessions)}건")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
