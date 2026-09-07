"""NGRAM_THRESHOLD(0.62) 를 사람 라벨 코퍼스로 재측정한다 — F-DET-001 2단계.

`0.62` 는 첫 커밋(`96ab471`)에 손으로 들어간 값이고, 그때 붙인 주석이 스스로
*"튜닝 시 dev set으로 재측정한다"* 고 적었다. **그 재측정을 한 번도 하지 않았다.**
`#368` 이 값을 `app/scoring_thresholds.yaml` 로 옮겼지만 *"왜 0.62 인가"* 는 여전히
빈 칸이었다. 이 도구가 그 칸을 채운다.

## 무엇을 재는가 — 모집단을 문면에 적는다

    발화    eval/corpus/els.jsonl
            ❗키는 (sample_id, item_id) 짝이다. sample_id 로만 잡으면 3건을 조용히
            잃는다 — 한 발화가 두 항목에 걸린 건이 있다(els-0048·0060·0062).
    라벨    eval/data/labels/{정세현,강희진}.jsonl 의 **합의분만**
            (두 라벨러 등급이 같은 것. 불일치 건은 정답이 없으므로 분모에서 뺀다)
    양성    합의 U4 = "틀리게 안다"
            ❗라벨에 오해 **유형**은 없다. 그래서 재는 것은 유형 정확도가 아니라
            `scoring.apply_misconception_floor` 가 실제로 소비하는 이진 —
            「루브릭 `related_misconceptions` 유형의 패턴 중 하나라도 문턱을 넘나」.
            floor 가 그 필터를 걸고, 걸리면 등급을 U4 로 올린다.

## 이 측정이 대답하는 질문

문턱을 바꾸면 **판정이 바뀌는가.** 바뀌지 않으면 값은 임의여도 무해하고, 바뀌면
어느 방향으로 바뀌는지가 P5(미탐 최소화)와 오탐 비용의 저울에 올라간다.

오탐 하나는 floor → `U4` → 게이트 `R-01` → **RED(판매 차단)** 로 직결된다.
그래서 문턱을 **낮추는** 변경은 미탐 감소분보다 오탐 증가분이 작아야만 정당하다.
"""

from __future__ import annotations

import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from app import rubrics  # noqa: E402
from app.misconception import NGRAM_THRESHOLD, _containment, _normalize, library  # noqa: E402

EVAL = pathlib.Path(__file__).resolve().parents[2] / "eval"


def _jsonl(path: pathlib.Path) -> list[dict]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def _key(row: dict) -> tuple[str, str]:
    return (row["sample_id"], row["item_id"])


def load() -> tuple[dict, dict, dict, list]:
    corpus = {_key(r): r for r in _jsonl(EVAL / "corpus/els.jsonl")}
    a = {_key(r): r["grade"] for r in _jsonl(EVAL / "data/labels/정세현.jsonl")}
    b = {_key(r): r["grade"] for r in _jsonl(EVAL / "data/labels/강희진.jsonl")}
    consensus = {k: g for k, g in a.items() if b.get(k) == g}
    # 모델 판정 — floor 는 **모델이 U4 를 안 냈을 때만** 등급을 움직인다. 사람 라벨만
    # 보면 "미탐 구제" 를 과대 계상한다(모델이 이미 U4 인 건까지 센다).
    # ❗eval/data/model.jsonl 은 정세현 소유다. 읽기만 한다 — 절대 덮어쓰지 않는다.
    rows = _jsonl(EVAL / "data/model.jsonl")
    model = {_key(r): r["grade"] for r in rows}
    versions = [r.get("prompt_version") for r in rows]
    return corpus, consensus, model, versions


def best_related(corpus: dict, key: tuple[str, str]) -> tuple[float, str | None, str | None]:
    """루브릭이 related 로 선언한 유형의 패턴 중 최고점. floor 가 보는 것과 같은 범위."""
    record = corpus[key]
    norm = _normalize(record["utterance"])
    related = set(rubrics.get(record["item_id"]).related_misconceptions)
    top: tuple[float, str | None, str | None] = (0.0, None, None)
    for mtype in library():
        if mtype.type_id not in related or not mtype.applies_to(record["product_type"]):
            continue
        for pattern in mtype.patterns:
            npat = _normalize(pattern)
            if not npat:
                continue
            # 1단계가 걸리면 점수는 계산이 아니라 상수 1.0 이다 — 문턱과 무관하게 통과한다.
            score = 1.0 if npat in norm else _containment(npat, norm)
            if score > top[0]:
                top = (score, mtype.type_id, pattern)
    return top


def devset_firing_rate() -> tuple[int, int]:
    """내 dev set 에서 1·2단계가 몇 번 발동하나 — 씨앗 오염의 크기를 재는 대조군."""
    import yaml

    from app.misconception import match

    fixtures = pathlib.Path(__file__).resolve().parents[1] / "tests/fixtures/utterances"
    hits = total = 0
    for path in sorted(fixtures.glob("*.yaml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        for case in data.get("cases") or data.get("utterances") or []:
            item_id, answer = case.get("item_id"), case.get("answer") or case.get("utterance")
            if not (item_id and answer):
                continue
            total += 1
            rubric = rubrics.get(item_id)
            related = set(rubric.related_misconceptions)
            if any(m.type_id in related for m in match(answer, rubric.product_type).matches):
                hits += 1
    return hits, total


def main() -> None:
    corpus, consensus, model, prompt_versions = load()
    positives = sum(1 for g in consensus.values() if g == "U4")
    print(
        f"코퍼스 {len(corpus)}쌍 · 합의 {len(consensus)}건 "
        f"(U4 {positives} / 비U4 {len(consensus) - positives})"
    )

    rows = [(k, g, *best_related(corpus, k)) for k, g in sorted(consensus.items())]
    stage1 = sum(1 for r in rows if r[2] >= 1.0)
    print(f"1단계(pattern)로 확정 {stage1}건 — 점수 1.0 이라 어떤 문턱에서도 걸린다")

    print("\n── 최고 포함도 분포 (문턱을 어디에 놓을 수 있는지가 여기서 정해진다)")
    for name, want in (("U4  ", True), ("비U4", False)):
        xs = sorted(r[2] for r in rows if (r[1] == "U4") is want)
        print(f"  {name} n={len(xs):2}  최고 {max(xs):.3f}  상위5 {[f'{x:.3f}' for x in xs[-5:]]}")

    print(f"\n── 문턱 스윕 (현행 {NGRAM_THRESHOLD})")
    print(f"{'문턱':>6} {'미탐':>6} {'오탐':>6}")
    for step in range(30, 102, 2):
        t = step / 100
        fn = sum(1 for r in rows if r[1] == "U4" and r[2] < t)
        fp = sum(1 for r in rows if r[1] != "U4" and r[2] >= t)
        mark = "   ← 현행" if abs(t - NGRAM_THRESHOLD) < 1e-9 else ""
        print(f"{t:6.2f} {fn:6} {fp:6}{mark}")

    print("\n── 문턱을 낮추면 뒤집히는 건 (0.30 ≤ 점수 < 현행)")
    print("   floor 는 모델이 U4 를 안 냈을 때만 등급을 움직인다 — 그래서 모델 판정을 겹쳐 센다")
    tally = {"✅ 미탐 구제": 0, "❌ 오탐 신설 — floor → U4 → RED": 0, "무해 — 모델이 이미 U4": 0}
    for key, grade, score, type_id, pattern in rows:
        if not 0.30 <= score < NGRAM_THRESHOLD:
            continue
        seen = model.get(key, "—")
        if seen == "U4":
            effect = "무해 — 모델이 이미 U4"
        elif grade == "U4":
            effect = "✅ 미탐 구제"
        else:
            effect = "❌ 오탐 신설 — floor → U4 → RED"
        tally[effect] += 1
        print(f"  {key[0]} 사람 {grade} · 모델 {seen} · {score:.3f} · {type_id} {pattern!r}")
        print(f"      {effect}")
    print("  합계 " + " · ".join(f"{k} {v}" for k, v in tally.items()))
    # ❗**판을 파일에서 읽는다. 문자열로 박지 않는다.** 예전에는 여기 `F-SCR-001_v2` 가
    # 박혀 있었는데 `#409`(9/6)가 v3 로 재채점하면서 **도구가 틀린 조건을 찍게 됐다** —
    # 조건을 적으라고 만든 도구가 조건을 틀리는 것이 제일 나쁘다.
    versions = sorted({r for r in prompt_versions if r})
    print(f"  ❗모델 판정은 model.jsonl 기준이고 그 파일의 prompt_version 은 "
          f"{', '.join(versions) or '(기록 없음)'} 이다.")
    print("     다시 채점하면 이 셈이 바뀐다 — 인용할 때 이 줄을 같이 옮긴다.")

    hits, total = devset_firing_rate()
    print("\n── 씨앗 오염 대비 — 같은 매처를 내 dev set 에 돌린다")
    print(f"  eval 코퍼스 (독립 작성)     {len(rows)}건 중 발동 "
          f"{sum(1 for r in rows if r[2] >= NGRAM_THRESHOLD)}건")
    print(f"  내 dev set (패턴에서 씨앗)  {total}건 중 발동 {hits}건 ({hits / total:.0%})")
    print("  ❗이 차이가 이 단계의 성격이다 — 라이브러리 문면을 되쓴 발화에만 걸린다.")
    print("     dev set 발동률을 성능 근거로 쓰지 않는다(씨앗이 패턴에서 왔다).")


if __name__ == "__main__":
    main()
