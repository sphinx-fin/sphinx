#!/usr/bin/env python3
"""`eval/labeling/worksheet.html` 의 표본 블록을 다시 굽는다. 소유: 정세현 (F-CMN-003)

    python eval/tools/build_worksheet.py

## 왜 굽는가 — 그리고 왜 그게 위험한가

`worksheet.html` 은 **레포를 안 여는 라벨러에게 파일 하나로** 보내는 작업지라 표본이
파일 안에 박혀 있어야 한다. 그 대가가 낡음이다 — `eval/corpus/*.jsonl` 이나 루브릭이
바뀌어도 파일은 **열리기는 열리고 옛 발화를 보여준다.** 그래서 두 가지를 같이 둔다.

1. 이 스크립트 — 표본·루브릭에서 다시 뽑아 마커 사이를 갈아 끼운다
2. `eval/tests/test_worksheet.py::test_the_baked_sheet_matches_the_corpus` —
   구운 것과 코퍼스가 갈리면 **테스트가 빨개진다.** 낡음이 조용하지 않게 하는 쪽이 본체다

## ❗순서가 정답을 흘리지 않게 한다

예전 표본은 항목마다 발화 넷이 `U1 → U2 → U3 → U4` **순서로** 서 있었다. 루브릭을 한 줄도
안 읽고 1-2-3-4 를 반복하면 모델과 36/40 이 맞는다 — 라벨이 사람의 판단이 아니라 **자리의
함수**가 되고, 그렇게 만든 평가자 간 일치도는 상한 구실을 못 한다.

그래서 셋을 같이 한다.

    항목 안에서 섞는다      고정 시드. 두 라벨러가 **같은** 순서를 봐야 한다 —
                            순서가 다르면 불일치가 판단 차이인지 문면 차이인지 갈린다
    화면 번호를 새로 준다   `els-0007` 이 그대로 보이면 번호 자체가 원래 자리를 말한다
    항목 순서도 섞는다      항목 배열이 코퍼스 파일 순서를 그대로 비추지 않게

❗**섞는 것은 응급처치다.** 어휘 신호(`…라고 하셨죠` 가 전부 따라읽기, 물음표 오해문이
전부 U4)는 순서를 섞어도 남는다. 그건 표본 구성으로 푼다 — 경계 사례를 실제로 넣는 것.

## ❗닻을 굽지 않는다

`eval/data/model.jsonl` · `eval/data/ai-reference*` · `eval/data/labels/` 를 **열지 않는다.**
`make_worksheet.py` 와 같은 규칙이고, 이 스크립트는 그 디렉토리를 아예 참조하지 않는다.
"""
from __future__ import annotations

import json
import pathlib
import random
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
CORPUS = REPO / "eval" / "corpus"
RUBRICS = REPO / "ai-service" / "app" / "rubrics"
SHEET = REPO / "eval" / "labeling" / "worksheet.html"

#: 고정 시드. **바꾸지 않는다** — 라벨링이 시작된 뒤에 바꾸면 두 라벨러가 다른 순서를 본다.
SEED = 20260903

BEGIN = "/* ── 표본 (build_worksheet.py 가 굽는다 — 손으로 고치지 않는다) ─────────── */"
END = "/* ── 표본 끝 ──────────────────────────────────────────────────────────── */"


def build() -> list[dict]:
    """코퍼스 + 루브릭 → 작업지에 실을 항목 배열."""
    rows = [
        json.loads(line)
        for path in sorted(CORPUS.glob("*.jsonl"))
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    if not rows:
        sys.exit("build_worksheet: 코퍼스가 비어 있다")

    by_item: dict[str, list[dict]] = {}
    for r in rows:
        by_item.setdefault(r["item_id"], []).append(r)

    rng = random.Random(SEED)
    item_ids = sorted(by_item)
    rng.shuffle(item_ids)

    out: list[dict] = []
    for item_id in item_ids:
        path = RUBRICS / f"{item_id}.yaml"
        if not path.exists():
            sys.exit(f"build_worksheet: 루브릭이 없다 — {item_id}")
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        samples = list(by_item[item_id])
        rng.shuffle(samples)
        out.append({
            "id": item_id,
            "name": doc["name"],
            "req": list(doc.get("required_elements") or []),
            "mis": list(doc.get("misconception_conditions") or []),
            "samples": [{"sid": s["sample_id"], "text": s["utterance"]} for s in samples],
        })

    # 화면 번호는 **표시 순서대로** 다시 매긴다. `els-0007` 을 그대로 보여주면 번호가
    # 원래 자리를 말해서 섞은 의미가 없어진다. 제출 JSONL 에는 진짜 sample_id 가 나간다.
    #
    # ❗**`sample_id` 와 헷갈릴 수 없는 서식이어야 한다.** 처음엔 `01`~`70` 으로 매겼는데
    # `els-0001`~`els-0067` 과 자릿수가 같고, 하필 앞 두 줄이 `01→els-0002`·`02→els-0003`
    # 으로 +1 씩 겹쳐서 **"라벨이 하나씩 밀렸다"** 로 읽혔다. 실제로 라벨링을 마친 뒤
    # 그 신고가 들어왔고, 데이터는 멀쩡한데 대조하느라 한참 걸렸다(#324).
    #
    # 항목 글자 + 항목 안 번호로 준다 — `A1`·`C4` 는 어떤 sample_id 와도 안 겹친다.
    # 항목 안에서 다시 시작하므로 라벨러가 운영자에게 자리를 말하기도 쉽다("C4 가 애매했다").
    for idx, item in enumerate(out):
        letter = chr(ord("A") + idx) if idx < 26 else f"Z{idx - 25}"
        for i, s in enumerate(item["samples"], 1):
            s["no"] = f"{letter}{i}"
    return out


def main() -> int:
    data = build()
    total = sum(len(i["samples"]) for i in data)
    html = SHEET.read_text(encoding="utf-8")
    if BEGIN not in html or END not in html:
        sys.exit(f"build_worksheet: 마커를 못 찾았다 — {SHEET.name} 에 BEGIN/END 주석이 있어야 한다")

    head, rest = html.split(BEGIN, 1)
    _, tail = rest.split(END, 1)
    payload = json.dumps(data, ensure_ascii=False, separators=(", ", ": "))
    body = f"{BEGIN}\nconst DATA = {payload};\nconst TOTAL = {total};\n{END}"
    SHEET.write_text(head + body + tail, encoding="utf-8")

    print(f"{SHEET.relative_to(REPO)} — 항목 {len(data)} · 행 {total} (시드 {SEED})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
