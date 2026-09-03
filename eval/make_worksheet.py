#!/usr/bin/env python3
"""라벨링 블라인드 작업지를 만든다. 소유: 정세현 (F-CMN-003 · 이슈 #324)

## 왜 필요한가

`guideline.md` 0절이 **❗모델 등급을 보지 않는다** 로 시작한다 — 보면 그 값이 닻이 되고,
두 사람이 각자 모델을 따라가면 **일치도만 높아지고 평가는 무의미해진다.**

그런데 지금 라벨을 붙이려면 `eval/data/` 를 열어야 하고, 거기 `model.jsonl` 이 같이 있다.
**닻이 손닿는 데 있다.** 이 스크립트는 라벨러가 그 디렉토리를 아예 안 열어도 되게,
**발화와 루브릭만** 담은 파일을 따로 낸다.

## 무엇을 안 담는가

    모델 등급          eval/data/model.jsonl        ← 이 스크립트는 열지 않는다
    AI 참조 등급       eval/data/ai-reference.jsonl ← 열지 않는다
    다른 사람의 라벨   eval/data/labels/*.jsonl     ← 열지 않는다

❗**안 여는 것으로는 부족하다.** 루브릭 문면이나 발화에 등급 토큰이 섞여 들어올 수 있고,
그러면 작업지가 조용히 닻을 나른다. 그래서 **만든 뒤 자기 출력을 되읽어 검사하고**,
걸리면 파일을 안 남기고 죽는다(`_assert_blind`).

## 왜 생성기가 검사하나

`eval/tests` 는 **CI 에서 안 돈다**(이슈 #344 — 워크플로 셋 중 어디도 `eval` 을 모른다).
테스트에만 두면 그 가드가 도는 자리가 없다. 잡이 생기면 테스트로 옮긴다.

## 쓰기

    python3 eval/make_worksheet.py --name 강희진
      → eval/data/worksheet/강희진.md      사람이 읽고 판단하는 것
      → eval/data/worksheet/강희진.jsonl   grade 만 채우면 되는 골격

`.jsonl` 은 **제출물이 아니다.** 다 채운 뒤 `eval/data/labels/<이름>.jsonl` 로 옮긴다 —
그 자리에 두면 `run_eval.py` 가 빈 등급을 라벨로 세려다 죽는다.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent
REPO = ROOT.parent
CORPUS = ROOT / "corpus" / "els.jsonl"
RUBRICS = REPO / "ai-service" / "app" / "rubrics"
OUT_DIR = ROOT / "data" / "worksheet"

#: 작업지에 있으면 안 되는 것. 등급 토큰이 하나라도 새면 그게 닻이다.
FORBIDDEN = re.compile(r"\bU[1-4]\b")

#: 열면 안 되는 파일. 이름을 적어 두는 이유는 "안 열었다" 를 사람이 확인할 수 있게 하려는 것.
NEVER_READ = ("model.jsonl", "ai-reference.jsonl", "labels/")


def load_corpus() -> list[dict]:
    rows = [json.loads(line) for line in CORPUS.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.startswith("#")]
    if not rows:
        sys.exit(f"표본이 비었다: {CORPUS}")
    return rows


def load_rubric(item_id: str) -> dict[str, list[str]]:
    """루브릭 두 목록. 판단 근거는 이 두 줄뿐이다(guideline.md §1)."""
    path = RUBRICS / f"{item_id}.yaml"
    if not path.exists():
        sys.exit(f"루브릭이 없다: {path} — 표본이 가리키는 항목이 실재해야 한다")
    out: dict[str, list[str]] = {"required_elements": [], "misconception_conditions": []}
    key = None
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(("required_elements:", "misconception_conditions:")):
            key = line.split(":", 1)[0]
        elif key and line.startswith("  - "):
            out[key].append(line[4:].strip())
        elif line[:1] and not line[:1].isspace() and not line.startswith("#"):
            key = None
    return out


def render(rows: list[dict], name: str) -> str:
    lines = [
        f"# 라벨링 작업지 — {name}",
        "",
        f"표본 {len(rows)}건. **발화와 루브릭만 있다** — 모델 등급도 AI 참조도 다른 사람 라벨도",
        "여기 없다(`eval/make_worksheet.py` 가 그 파일들을 열지 않는다).",
        "",
        "판단 순서는 `eval/labeling/guideline.md` §2 를 그대로 따른다 —",
        "**위에서부터 걸리는 첫 번째가 답이다.** 순서를 여기 옮겨 적지 않는다:",
        "등급 이름이 작업지에 있으면 그 자체가 약한 닻이고, 가드가 그걸 잡는다.",
        "",
        "❗**애매해도 비워 두지 않는다.** 빈 항목은 두 사람의 교집합에서 빠져 표본만 줄인다.",
        "규칙대로 붙이고 `note` 에 이유를 적는다.",
        "",
        "---",
        "",
    ]
    for i, row in enumerate(rows, 1):
        rubric = load_rubric(row["item_id"])
        lines += [
            f"## {i}. `{row['sample_id']}` · {row['item_id']}",
            "",
            "### 발화",
            "",
            f"> {row['utterance']}",
            "",
            "### 루브릭 — 이걸 말했으면 이해한 것이다",
            "",
        ]
        lines += [f"- {e}" for e in rubric["required_elements"]] or ["- (없음)"]
        lines += ["", "### 루브릭 — 이걸 말했으면 오해다", ""]
        lines += [f"- {c}" for c in rubric["misconception_conditions"]] or ["- (없음)"]
        lines += ["", "**등급:** ______", "", "---", ""]
    return "\n".join(lines) + "\n"


def skeleton(rows: list[dict]) -> str:
    """`grade` 만 채우면 되는 골격. 제출 전에 labels/ 로 옮긴다."""
    return "".join(
        json.dumps({"sample_id": r["sample_id"], "item_id": r["item_id"], "grade": ""},
                   ensure_ascii=False) + "\n"
        for r in rows)


def _assert_blind(text: str, what: str) -> None:
    """❗등급 토큰이 새면 파일을 안 남기고 죽는다.

    안 여는 것만으로는 부족하다 — 루브릭 문면이나 발화에 섞여 들어올 수 있고, 그러면
    작업지가 조용히 닻을 나른다. 여기서 죽는 편이 낫다: 라벨러가 그걸 보고 나면
    되돌릴 방법이 없다.
    """
    hits = sorted(set(FORBIDDEN.findall(text)))
    if hits:
        sys.exit(f"❗{what} 에 등급 토큰이 있다: {hits}\n"
                 f"  작업지는 발화와 루브릭만 담는다 — 등급이 보이면 그게 닻이다"
                 f"(guideline.md 0절). 표본·루브릭 쪽을 고친다.")


def main() -> None:
    ap = argparse.ArgumentParser(description="라벨링 블라인드 작업지")
    ap.add_argument("--name", required=True, help="라벨러 이름 — 파일명이 된다")
    ap.add_argument("--out", type=pathlib.Path, default=OUT_DIR)
    args = ap.parse_args()

    rows = load_corpus()
    md, sk = render(rows, args.name), skeleton(rows)
    _assert_blind(md, "작업지")
    _assert_blind(sk, "골격")

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / f"{args.name}.md").write_text(md, encoding="utf-8")
    (args.out / f"{args.name}.jsonl").write_text(sk, encoding="utf-8")

    print(f"작업지 {len(rows)}건 — {args.out / (args.name + '.md')}")
    print(f"골격       — {args.out / (args.name + '.jsonl')}")
    print(f"안 연 파일 — {' · '.join(NEVER_READ)}")
    print("다 채우면 eval/data/labels/<이름>.jsonl 로 옮긴다.")


if __name__ == "__main__":
    main()
