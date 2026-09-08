#!/usr/bin/env python3
"""데모 진행용 답지를 라벨 코퍼스에서 뽑는다 (이슈 #539 ② · PR #541 이 소비자).

S-03 질문 아래 캡션에 「예시 정답(U1) · 예시 오답(U4)」을 띄우려면 **항목ID 를 키로 하는
데이터**가 필요하다. 사람이 읽는 표를 손으로 옮기면 라벨이 바뀔 때 조용히 낡는다 —
`#525` 가 정확히 그 결함이었다(설문 선택지가 바뀌었는데 픽스처가 열흘 동안 죽은 문면을
들고 있었고, 문항 ID 도 텍스트도 안 바뀌어서 대조 셋이 전부 초록이었다).

## ❗키는 `(sample_id, item_id)` 짝이다

**한 발화가 여러 항목에 라벨될 수 있다.** ELS 코퍼스에 실제로 3건 있다.

    els-0048   KNOCKIN-BARRIER U4 합의  ·  MATURITY-LOSS-CONDITION 불일치
    els-0062   NO-LISTING U1 합의       ·  MIDWAY-REDEMPTION-COST 불일치
    els-0060   ISSUER-CREDIT-RISK U1 합의 ·  NO-DEPOSIT-INSURANCE U1 합의

`sample_id` 만 키로 잡으면 뒤에 오는 항목이 앞을 덮어써서 **합의가 51 이 아니라 49 로
세어지고, 덮인 쪽 답지가 사라진다.** `#541` 이 그 방식으로 세다가
`ELS-ISSUER-CREDIT-RISK` 의 U1 합의(`els-0060`)를 «합의 없음» 으로 읽고 작성본으로
대체했다. 튜닝 도구(`tune_ngram_threshold.py`)가 같은 키를 쓰는 이유도 이것이다.

## 무엇을 담고 무엇을 안 담나

**두 라벨러가 합의한 것만** 담는다. 불일치는 「사람도 갈린 발화」라 답지로 못 쓴다.
U1·U4 만 담는다 — 화면이 쓰는 둘이고, 등급 넷을 다 주면 캡션에 안 들어간다
(`#541` 형태 결정).

**U1 합의가 없는 항목은 키를 만들지 않는다.** 빈 값을 담으면 소비자가 「없다」와
「비었다」를 못 가른다. 그 자리는 루브릭에서 작성한 예시가 채우고, 그건 web 쪽 몫이다 —
`source` 로 갈린다.

## 고르는 규칙 (결정론)

한 항목에 후보가 여럿일 때 **규칙으로 고른다.** 사람이 그때그때 고르면 다시 돌릴 때
값이 달라지고, 그러면 이 파일이 재현 가능한 산출물이 아니게 된다.

    U1  가장 긴 발화     필수요소를 여러 개 짚을 가능성이 높다
    U4  가장 짧은 발화   시연에서 입력이 빠르다
    동률 sample_id 순

사용법:

    python3 eval/tools/build_demo_answers.py            # 요약만 찍는다
    python3 eval/tools/build_demo_answers.py --write    # data/demo_answers/els.json 을 쓴다
"""
from __future__ import annotations

import argparse
import collections
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
CORPUS = ROOT / "eval" / "corpus" / "els.jsonl"
LABELS = ROOT / "eval" / "data" / "labels"
OUT = ROOT / "data" / "demo_answers" / "els.json"

#: 답지로 쓰는 등급. U2(부분)·U3(불명)은 담지 않는다 — 화면이 안 쓴다.
GRADES = ("U1", "U4")


def _read_jsonl(path: pathlib.Path) -> list[dict]:
    """주석(`#`)과 빈 줄을 건너뛴다 — 라벨 파일 머리말이 그 형식이다."""
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            out.append(json.loads(line))
    return out


def labelers() -> list[str]:
    """라벨러 이름을 파일명에서 얻는다. 목록을 코드에 박지 않는다 — 사람이 늘면 파일이 는다."""
    return sorted(p.stem for p in LABELS.glob("*.jsonl"))


def agreed() -> dict[tuple[str, str], str]:
    """두 라벨러가 **같은 등급**을 준 것만. 키는 (sample_id, item_id) 짝이다."""
    votes: dict[tuple[str, str], dict[str, str]] = collections.defaultdict(dict)
    for who in labelers():
        for row in _read_jsonl(LABELS / f"{who}.jsonl"):
            votes[(row["sample_id"], row["item_id"])][who] = row["grade"]

    everyone = set(labelers())
    out = {}
    for key, v in votes.items():
        if set(v) == everyone and len(set(v.values())) == 1:
            out[key] = next(iter(v.values()))
    return out


def build() -> tuple[dict, dict]:
    """(답지, 진단) — 진단은 사람이 읽고 답지는 화면이 읽는다."""
    utterances = {(r["sample_id"], r["item_id"]): r["utterance"] for r in _read_jsonl(CORPUS)}
    consensus = agreed()

    # 항목 → 등급 → [(sample_id, 발화)]
    pool: dict[str, dict[str, list[tuple[str, str]]]] = collections.defaultdict(
        lambda: collections.defaultdict(list))
    for (sid, item), grade in consensus.items():
        if grade in GRADES and (sid, item) in utterances:
            pool[item][grade].append((sid, utterances[(sid, item)]))

    answers: dict[str, dict] = {}
    for item in sorted(pool):
        entry = {}
        for grade in GRADES:
            rows = pool[item].get(grade)
            if not rows:
                continue
            # ❗결정론 — U1 은 긴 것, U4 는 짧은 것, 동률은 sample_id 순.
            longest = grade == "U1"
            rows = sorted(rows, key=lambda r: (-len(r[1]) if longest else len(r[1]), r[0]))
            sid, text = rows[0]
            entry[grade.lower()] = {"text": text, "source": "labeled", "sampleId": sid}
        if entry:
            answers[item] = entry

    diagnostics = {
        "labelers": labelers(),
        "corpusRows": len(utterances),
        "consensus": len(consensus),
        "consensusByGrade": dict(sorted(collections.Counter(consensus.values()).items())),
        "itemsWithU1": sorted(i for i in answers if "u1" in answers[i]),
        "itemsWithoutU1": sorted(i for i in pool if "u1" not in answers.get(i, {})),
        "multiItemUtterances": _multi_item(utterances),
    }
    return answers, diagnostics


def _multi_item(utterances: dict[tuple[str, str], str]) -> dict[str, list[str]]:
    """한 발화가 여러 항목에 붙은 것. 이 값이 비지 않는 한 sample_id 를 키로 쓰면 안 된다."""
    by_sample: dict[str, list[str]] = collections.defaultdict(list)
    for sid, item in utterances:
        by_sample[sid].append(item)
    return {s: sorted(items) for s, items in sorted(by_sample.items()) if len(items) > 1}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true", help=f"{OUT.relative_to(ROOT)} 를 쓴다")
    args = ap.parse_args()

    answers, diag = build()

    print(f"라벨러 {diag['labelers']} · 코퍼스 {diag['corpusRows']}행")
    print(f"합의 {diag['consensus']} · 등급별 {diag['consensusByGrade']}")
    print()
    print(f"❗한 발화가 여러 항목에 붙은 것 {len(diag['multiItemUtterances'])}건 "
          f"— sample_id 를 키로 쓰면 이만큼 덮인다")
    for sid, items in diag["multiItemUtterances"].items():
        print(f"   {sid} → {' · '.join(i.replace('ELS-', '') for i in items)}")
    print()
    print(f"답지가 선 항목 {len(answers)}종")
    for item in sorted(answers):
        have = " ".join(g.upper() for g in ("u1", "u4") if g in answers[item])
        print(f"   {item:34} {have}")
    if diag["itemsWithoutU1"]:
        print()
        print("U1 합의가 없는 항목 — 루브릭에서 작성한 예시가 필요하다(web 쪽):")
        for item in diag["itemsWithoutU1"]:
            print(f"   {item}")

    if args.write:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "_generated": "eval/tools/build_demo_answers.py — 손으로 고치지 않는다",
            "_source": "eval/corpus/els.jsonl + eval/data/labels/*.jsonl 의 라벨러 합의분",
            "answers": answers,
        }
        OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print()
        print(f"→ {OUT.relative_to(ROOT)} ({len(answers)}종)")
    else:
        print()
        print("(--write 를 주면 파일을 쓴다)", file=sys.stderr)


if __name__ == "__main__":
    main()
