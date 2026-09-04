#!/usr/bin/env python3
"""「유사도 매칭을 켰으면 등급이 몇 건 달라졌을까」를 잰다. 소유: 윤지석 (이슈 #284 (b))

## 왜 이 숫자가 필요한가

`#284` 가 *"선언한 오해 46개 중 강제되는 건 라이브러리 9종뿐"* 을 열었고, 답은 `(b)`
유사도 매칭이다. 그런데 **그건 채점 동작을 바꾸는 일**이라 3주차 정량평가 전에 켜면
등급 분포가 흔들리고 그 회차 수치를 못 쓴다.

지금은 **켤지를 근거 없이** 정해야 한다. 이 도구가 그 근거를 만든다 —
판정을 안 건드리고 *"켰으면 바뀌었을 건수"* 만 센다.

## LLM 을 안 부른다

평가 표본(`eval/corpus/els.jsonl`)의 발화와 루브릭만 쓴다. **모델 등급이 필요한 자리는
하나뿐**이고(*"이미 U4 면 켜도 안 바뀐다"*) 그건 `eval/data/model.jsonl` 에 있다 —
없으면 그 칸만 비우고 나머지를 낸다.

    python3 ai-service/tools/measure_shadow.py
    python3 ai-service/tools/measure_shadow.py --model ../eval/data/model.jsonl
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from app import rubrics, shadow          # noqa: E402

REPO = pathlib.Path(__file__).resolve().parents[2]
CORPUS = REPO / "eval" / "corpus" / "els.jsonl"
MODEL = REPO / "eval" / "data" / "model.jsonl"


def _read(path: pathlib.Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.startswith("#")]


def main() -> None:
    ap = argparse.ArgumentParser(description="그림자 매칭 측정")
    ap.add_argument("--corpus", type=pathlib.Path, default=CORPUS)
    ap.add_argument("--model", type=pathlib.Path, default=MODEL)
    ap.add_argument("--sweep", action="store_true",
                    help="임계값 곡선 — 낮출수록 몇 건이 더 걸리는지")
    args = ap.parse_args()

    samples = _read(args.corpus)
    if not samples:
        sys.exit(f"표본이 비었다: {args.corpus}")
    grades = {(r["sample_id"], r["item_id"]): r["grade"] for r in _read(args.model)}

    # ── 1. 정적 — 강제 통로가 없는 조건이 몇 개인가 ──────────────────────────
    declared = unenforced = 0
    per_item: dict[str, tuple[int, int]] = {}
    for item_id, rubric in sorted(rubrics.all_rubrics().items()):
        n_declared = len(rubric.misconception_conditions)
        n_unenforced = len(shadow.unenforced_conditions(rubric))
        declared += n_declared
        unenforced += n_unenforced
        if n_unenforced:
            per_item[item_id] = (n_unenforced, n_declared)

    print(f"\n## 선언한 조건 중 강제 통로가 없는 것\n")
    print(f"  전체        {unenforced}/{declared}")
    print(f"  루브릭      {len(per_item)}/{len(rubrics.all_rubrics())} 종이 하나 이상")
    for item_id, (u, d) in sorted(per_item.items(), key=lambda kv: -kv[1][0])[:6]:
        print(f"    {item_id:34} {u}/{d}")

    # ── 2. 동적 — 실제 발화에 몇 건이 걸리나 ─────────────────────────────────
    hit_rows: list[tuple[str, str, str, float, bool]] = []
    unknown_grade = 0
    for row in samples:
        try:
            rubric = rubrics.get(row["item_id"])
        except rubrics.RubricNotFound:
            continue
        grade = grades.get((row["sample_id"], row["item_id"]))
        if grade is None:
            unknown_grade += 1
        for hit in shadow.probe(row["utterance"], rubric, grade or "U1"):
            hit_rows.append((row["sample_id"], row["item_id"], hit.condition,
                             hit.score, grade is not None and grade != "U4"))

    changed = [r for r in hit_rows if r[4]]
    print(f"\n## 표본 {len(samples)}건에 실제로 걸린 것\n")
    print(f"  그림자 매칭  {len(hit_rows)}건")
    print(f"  등급이 달라졌을 것  {len(changed)}건"
          + (f"   (등급 미상 {unknown_grade}건은 제외)" if unknown_grade else ""))
    for sample_id, item_id, condition, score, would in sorted(hit_rows, key=lambda r: -r[3])[:10]:
        mark = "→U4" if would else "   "
        print(f"    {sample_id:10} {item_id:34} {score:.2f} {mark}  {condition}")

    if args.sweep:
        _sweep(samples, grades)

    print(f"\n❗판정은 하나도 안 바꿨다. 켤지는 #284 (b) 에서 정한다.")
    if not hit_rows:
        print("  걸린 것이 0 이면 (b) 의 값이 낮다는 뜻이다 — 그것도 결론이다.")


def _sweep(samples: list[dict], grades: dict) -> None:
    """임계값을 낮추면 몇 건이 더 걸리나.

    ❗**한 숫자로는 못 정한다.** 매처 임계값(0.62)에서 거의 안 걸린다는 것은 두 가지 중
    하나다 — *"조건이 실제로 안 나온다"* 이거나 *"임계값이 조건-발화 대조에는 너무 빡빡하다"*.
    조건은 짧은 서술문이고 발화는 대화체라 바이그램 포함도가 구조적으로 낮게 나온다.

    곡선을 보면 그 둘이 갈린다. 낮춰도 안 늘면 앞엣것이고, 급히 늘면 뒤엣것인데 **그때
    같이 늘어나는 오탐이 (b) 의 진짜 비용**이다.
    """
    import importlib
    from app import textsim

    print(f"\n## 임계값 곡선 — 낮출수록 얼마나 걸리나\n")
    print(f"  {'임계값':>6}  {'걸린 건수':>9}  {'항목 수':>7}")
    for threshold in (0.62, 0.55, 0.50, 0.45, 0.40, 0.35, 0.30):
        hits = 0
        items: set[str] = set()
        for row in samples:
            try:
                rubric = rubrics.get(row["item_id"])
            except rubrics.RubricNotFound:
                continue
            norm = textsim.normalize(row["utterance"])
            for condition in shadow.unenforced_conditions(rubric):
                if textsim.containment(textsim.normalize(condition), norm) >= threshold:
                    hits += 1
                    items.add(row["item_id"])
        mark = "  ← 매처 임계값" if threshold == shadow.THRESHOLD else ""
        print(f"  {threshold:>6.2f}  {hits:>9}  {len(items):>7}{mark}")

    print(f"\n  ❗낮출수록 오탐이 같이 는다. 이 표는 '얼마나 잡히나' 만 말하고")
    print(f"     '그게 맞나' 는 안 말한다 — 사람이 걸린 건을 읽어야 한다.")


if __name__ == "__main__":
    main()
