"""표본이 **종결어미로 라벨을 흘리는지** 잰다. 소유: 정세현 (이슈 #377)

`#354` 가 잡은 것은 **위치** 누설이었다 — 항목마다 `U1→U2→U3→U4` 로 서 있어서 자리가
곧 정답표였다. 이건 다른 축이다: 발화의 **끝 몇 자**가 등급을 말해 버리는지.

    표본이 답을 형태로 흘리면, 모델은 내용을 안 보고 형태로 지름길을 낸다.
    그렇게 나온 QWK 는 「이해도 채점」이 아니라 「종결어미 분류」의 성적이다.

## ❗왜 스크립트가 필요한가 — 이 숫자가 세 번 갈렸다

`#385` 리뷰에서 같은 값을 세 사람이 세 번 다르게 냈다.

    95% (기준선 63%)   #377 최초 — leave-one-out 이 없고 규칙을 라벨 보고 골랐다
    51% / 57%          #385 반영 — LOO 를 넣었다
    53% / 54%          내 재현 — 같은 프로토콜인데 동률 처리가 달랐다

**문면으로만 두면 계속 갈린다.** `measure_repetition.py` 가 `AnswerRepetition.THRESHOLD`
에 대해 하는 일을 이 숫자에도 한다 — 인용하는 자리는 이 출력을 옮겨 적는다.

## 프로토콜

    끝 k자가 같은 **다른** 행의 최빈 등급으로 예측한다 (leave-one-out)
    그 k자를 가진 다른 행이 없으면 k를 하나 줄여 다시 본다 (백오프)
    끝까지 없으면 전체 최빈 등급
    동률이면 **등급 이름 순으로 작은 것** — 임의 순서에 의존하지 않는다

마지막 줄이 위 `53% ↔ 51/57%` 의 차이였다. `collections.Counter.most_common` 은 동률에서
삽입 순서를 따르므로 **행 순서가 바뀌면 값이 바뀐다.** 재현되지 않는 숫자는 근거가 아니다.

## 같이 찍는 두 값 — 이게 이 스크립트의 본체다

    LOO 없이        자기 자신을 포함해 최빈을 고른다. 「이 표본으로 만든 표가 이 표본을
                    재현하는가」이지 「형태가 라벨 정보를 갖는가」가 아니다
    만장일치 규칙    그룹이 한 등급뿐일 때만 예측한다. **구조적으로 100%** 다

둘을 안 찍으면 다음 사람이 같은 값을 다시 낸다. **틀린 숫자가 어떻게 나오는지를 같이
보여주는 것**이 이 도구가 하는 일이다.

## 쓰기

    python3 eval/tools/measure_suffix_leak.py                 # eval 코퍼스 (라벨러별)
    python3 eval/tools/measure_suffix_leak.py --devset        # ai-service dev set
"""
from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "eval" / "corpus" / "els.jsonl"
LABELS = ROOT / "eval" / "data" / "labels"
DEVSET = ROOT / "ai-service" / "tests" / "fixtures" / "utterances"

#: 몇 자까지 보는가. 6 이 정본인 이유는 `#377` 이 그 값으로 처음 쟀기 때문이다 —
#: 다른 값도 같이 찍어서 **k 에 얼마나 민감한지**가 보이게 한다.
LENGTHS = (4, 6, 8)


def _rows_from_corpus() -> dict[str, list[tuple[str, str]]]:
    """`{라벨러: [(발화, 등급)]}`. 라벨러를 **갈라서** 낸다.

    ❗한 값만 내면 *"어느 라벨로 잰 건가"* 가 빠진다. 그리고 두 값의 차이 자체가
    *"이 숫자는 라벨에 따라 움직인다"* 를 말해 준다 — 사람 불일치가 27.1% 인 표본이다.
    """
    utterance: dict[tuple[str, str], str] = {}
    for line in CORPUS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        row = json.loads(line)
        utterance[(row["sample_id"], row["item_id"])] = row["utterance"]

    out: dict[str, list[tuple[str, str]]] = {}
    for path in sorted(LABELS.glob("*.jsonl")):
        rows = []
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            row = json.loads(line)
            key = (row["sample_id"], row["item_id"])
            if key in utterance:
                rows.append((utterance[key], row["grade"]))
        if rows:
            out[path.stem] = rows
    return out


def _rows_from_devset() -> dict[str, list[tuple[str, str]]]:
    """dev set 은 라벨러가 하나다(프롬프트 당사자가 붙였다 — 평가셋과 무관하다)."""
    import yaml

    rows = []
    for path in sorted(DEVSET.glob("*.yaml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
        for case in data["cases"]:
            grade = case.get("expected_grade")
            if grade:
                rows.append((case["answer"], grade))
    return {"dev set (윤지석)": rows} if rows else {}


def _vote(counts: collections.Counter[str]) -> str:
    """최빈 등급. **동률은 등급 이름 순**으로 깬다 — 행 순서에 의존하지 않는다."""
    top = max(counts.values())
    return min(g for g, n in counts.items() if n == top)


def baseline(rows: list[tuple[str, str]]) -> tuple[str, float]:
    """항상 최빈 등급을 답하는 기준선.

    ❗**지표와 같은 과제여야 한다.** `#377` 이 4단계 예측 정확도를 이진 기준선(항상
    「비U4」 = 63%)과 비교해서 누설을 과대평가했다.
    """
    counts = collections.Counter(g for _, g in rows)
    grade = _vote(counts)
    return grade, counts[grade] / len(rows)


def suffix_accuracy(rows: list[tuple[str, str]], kmax: int, *, loo: bool = True,
                    backoff: bool = True) -> float:
    """끝 k자로 등급을 예측한 정확도.

    `loo=False` 는 **틀린 프로토콜**이다 — 자기 자신을 표에 포함하므로 그 종결이 1건뿐인
    행은 구조적으로 자기를 맞힌다. 비교용으로만 낸다.
    """
    hit = 0
    for i, (text, grade) in enumerate(rows):
        pool = [(t, g) for j, (t, g) in enumerate(rows) if not loo or j != i]
        pred = None
        for k in range(kmax, 0, -1) if backoff else (kmax,):
            key = text.rstrip()[-k:]
            counts = collections.Counter(g for t, g in pool if t.rstrip()[-k:] == key)
            if counts:
                pred = _vote(counts)
                break
        if pred is None and pool:
            pred = _vote(collections.Counter(g for _, g in pool))
        if pred == grade:
            hit += 1
    return hit / len(rows)


def unanimous_only(rows: list[tuple[str, str]], k: int) -> tuple[int, int, int]:
    """그룹이 **만장일치일 때만** 예측한다 → `(판정, 적중, 그중 싱글턴)`.

    ❗**구조적으로 100%** 다. 1건뿐인 그룹은 자기 자신이 그 만장일치이므로 늘 맞는다.
    `#377` 의 `95%` 가 이 계열이고, 그래서 그 숫자는 누설의 크기가 아니다.
    """
    groups: dict[str, list[str]] = collections.defaultdict(list)
    for text, grade in rows:
        groups[text.rstrip()[-k:]].append(grade)
    decided = hit = singleton = 0
    for text, grade in rows:
        members = groups[text.rstrip()[-k:]]
        if len(set(members)) == 1:
            decided += 1
            hit += members[0] == grade
            singleton += len(members) == 1
    return decided, hit, singleton


def report(name: str, rows: list[tuple[str, str]]) -> None:
    grade, base = baseline(rows)
    print(f"\n── {name} — {len(rows)}행")
    print(f"   기준선(최빈 {grade}) {base:.0%}   ← 4단계 예측 지표의 기준선이다")
    for k in LENGTHS:
        acc = suffix_accuracy(rows, k)
        ratio = acc / base if base else float("nan")
        mark = "  ❗기준선 아래" if acc < base else ""
        print(f"   끝 {k}자 · LOO · 백오프   {acc:.0%}  (기준선의 {ratio:.1f}배){mark}")
    naive = suffix_accuracy(rows, 6, loo=False)
    decided, hit, singleton = unanimous_only(rows, 6)
    print(f"   ❗끝 6자 · LOO 없이       {naive:.0%}"
          f"   ← 틀린 프로토콜. 자기 자신을 표에 포함한다")
    print(f"   ❗끝 6자 · 만장일치만     {hit}/{decided} = {hit / decided:.0%}"
          f" (그중 싱글턴 {singleton}건)   ← 구조적으로 100%")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--devset", action="store_true",
                        help="eval 코퍼스 대신 ai-service dev set 을 본다")
    args = parser.parse_args()

    tables = _rows_from_devset() if args.devset else _rows_from_corpus()
    if not tables:
        raise SystemExit("표본을 못 읽었다 — 라벨 파일이나 픽스처가 비어 있다")

    print("종결어미 누설 측정 (이슈 #377)")
    print("프로토콜: 끝 k자가 같은 **다른** 행의 최빈 등급 · leave-one-out · 짧은 쪽 백오프")
    print("          동률은 등급 이름 순으로 깬다 (행 순서에 의존하지 않는다)")
    for name, rows in tables.items():
        report(name, rows)
    print("\n❗아래 두 줄은 **틀린 값**이다. 어떻게 틀리는지를 보이려고 같이 찍는다.")
    print("   LOO 없이 · 만장일치만 — 둘 다 「표가 표본을 재현하는가」이지 누설의 크기가 아니다")


if __name__ == "__main__":
    main()
