"""`AnswerRepetition.THRESHOLD` 를 실발화로 잰다. 소유: 강희진 (이슈 #268 (d))

서버의 되풀이 판정(`core/session/AnswerRepetition.java`)이 쓰는 임계값의 근거다.
**손으로 지어낸 문장쌍이 아니라 라벨된 코퍼스로 잰다** — 처음에 지어낸 다섯 쌍으로
0.9 를 골랐다가 어미 변경(0.737)을 못 잡는 것이 드러났고, 그 뒤로도 표본이 내 문장이라
"이 값이 실제 발화에서 어떻게 도나" 에 답하지 못했다.

두 방향을 잰다.

    오탐   같은 항목에 대한 **서로 다른** 발화 쌍 — 여기서 임계값을 넘으면 오탐이다
    미탐   실발화에 되풀이 변형을 가한 쌍 — 여기서 못 넘으면 미탐이다

❗**오탐 쪽 숫자는 하한이다.** 진짜 오탐 모집단은 *"같은 사람이 재설명을 듣고 다시 설명한
답"* 인데 코퍼스에 재검증 쌍이 없다. 같은 사람은 자기 어휘를 다시 쓰므로 겹침이 남보다
높다 — 그래서 측정된 최대값에 **여유를 두고** 임계값을 잡는다.

    python3 eval/tools/measure_repetition.py
"""
from __future__ import annotations

import itertools
import json
import re
import statistics
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "eval" / "corpus" / "els.jsonl"
LABELS = ROOT / "eval" / "data" / "labels"

#: 서버 상수와 **같은 값**이어야 한다. 다른 숫자로 재면 이 표가 그 임계값을 안 말한다.
THRESHOLD = 0.6


def normalize(text: str) -> str:
    return re.sub(r"[\s\W_·…]+", "", unicodedata.normalize("NFKC", text))


def bigrams(text: str) -> set[str]:
    return {text[i:i + 2] for i in range(len(text) - 1)}


def similarity(a: str, b: str) -> float:
    """`AnswerRepetition.essentiallySame` 과 같은 계산."""
    na, nb = normalize(a), normalize(b)
    ba, bb = bigrams(na), bigrams(nb)
    if not ba or not bb:
        return 1.0 if na == nb else 0.0
    return len(ba & bb) / len(ba | bb)


# ── 되풀이 변형 ────────────────────────────────────────────────────────────────
ENDINGS = (("어요", "습니다"), ("아요", "습니다"), ("네요", "습니다"), ("거죠", "건가요"),
           ("죠?", "지요?"), ("잖아요", "지 않습니까"), ("는데요", "는데 말입니다"))


def changed_ending(text: str) -> str:
    for old, new in ENDINGS:
        if text.rstrip(".").endswith(old) or old in text[-6:]:
            return text.replace(old, new)
    return text.rstrip(".") + "라는 말씀이시죠"


VARIANTS = {
    "마침표만": lambda t: t.rstrip(".") + ".",
    "띄어쓰기만": lambda t: t.replace(" ", ""),
    "맞장구 추가": lambda t: "네, " + t,
    "어미 변경": changed_ending,
}


def load_corpus() -> list[dict]:
    return [json.loads(line) for line in CORPUS.read_text(encoding="utf-8").splitlines() if line.strip()]


def agreed_grades() -> dict[tuple[str, str], str]:
    """두 라벨러가 **합의한** 것만. 한쪽만 있는 판단은 기준으로 못 쓴다."""
    files = sorted(LABELS.glob("*.jsonl"))
    tables = []
    for path in files:
        table = {}
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                table[(row["sample_id"], row["item_id"])] = row["grade"]
        tables.append(table)
    if len(tables) < 2:
        return {}
    first, *rest = tables
    return {k: g for k, g in first.items() if all(t.get(k) == g for t in rest)}


def main() -> None:
    rows = load_corpus()
    print(f"코퍼스 {len(rows)}건 · 임계값 {THRESHOLD}\n")

    # ── 오탐 방향 ─────────────────────────────────────────────────────────────
    by_item: dict[str, list[str]] = {}
    for row in rows:
        by_item.setdefault(row["item_id"], []).append(row["utterance"])
    pairs = [(similarity(a, b), a, b)
             for utterances in by_item.values()
             for a, b in itertools.combinations(utterances, 2)]
    values = sorted((p[0] for p in pairs), reverse=True)
    over = sum(1 for v in values if v >= THRESHOLD)
    print(f"── 오탐 방향 — 같은 항목의 서로 다른 발화 {len(pairs)}쌍")
    print(f"   최대 {values[0]:.3f} · 중앙 {statistics.median(values):.3f}")
    print(f"   임계값 이상 {over}쌍  ← 0 이어야 한다")
    print(f"   여유 {THRESHOLD - values[0]:.3f}  (측정 최대와의 거리)\n")

    # ── 미탐 방향 ─────────────────────────────────────────────────────────────
    grades = agreed_grades()
    print(f"── 미탐 방향 — 되풀이 변형 (합의 라벨 {len(grades)}건 기준)")
    for name, transform in VARIANTS.items():
        missed = [(similarity(r["utterance"], transform(r["utterance"])), r) for r in rows]
        caught = [m for m in missed if m[0] >= THRESHOLD]
        leaks = [m for m in missed if m[0] < THRESHOLD]
        # ❗놓친 것 중 **통과 판정(U1)** 이 있으면 그건 게이트가 새는 것이다.
        passing = [m for m in leaks
                   if grades.get((m[1]["sample_id"], m[1]["item_id"])) == "U1"]
        print(f"   {name:8s} 잡음 {len(caught):2d}/{len(missed)}"
              f"   놓침 중 U1 {len(passing)}건  ← 0 이어야 한다")
        for score, row in sorted(leaks, key=lambda m: m[0])[:3]:
            grade = grades.get((row["sample_id"], row["item_id"]), "??")
            print(f"      {score:.3f} [{grade}] {row['utterance'][:30]}")

    # ── U1 은 짧을 수 없다 ────────────────────────────────────────────────────
    lengths = sorted(len(u) for (sid, iid), g in grades.items()
                     for r in rows if (r["sample_id"], r["item_id"]) == (sid, iid)
                     and g == "U1" for u in [r["utterance"]])
    if lengths:
        print(f"\n── U1 발화 길이 {len(lengths)}건 · 최소 {lengths[0]}자 · "
              f"중앙 {statistics.median(lengths):.0f}자")
        print("   놓치는 구간(짧은 답)에 통과 판정이 없다는 근거다.")


if __name__ == "__main__":
    main()
