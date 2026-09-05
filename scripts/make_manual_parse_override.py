#!/usr/bin/env python3
"""ELS 간이투자설명서의 수동 파스 출력을 만든다 (이슈 #436 · 기구는 #441). 소유: 정세현

❗**손으로 쓴 JSON 이 아니다.** 파서 출력을 그대로 받아 **페이지 8 한 곳만** 고친다. 그래야
무엇이 사람 손을 탔는지가 이 스크립트 하나로 설명되고, 문서가 바뀌면 다시 돌릴 수 있다.

## 무엇을 고치나 — 표의 열이 문장 안으로 끼어든 것

`pdfplumber` 가 페이지 8 의 상환조건 표를 읽을 때, **좌측 라벨 열(만기/상환)과 우측 요율
열(33.00% · (연 11.00%) · -100% ~-30% …)이 가운데 열 문장 사이로 끼어든다.**

    도 각각의 최초기준가격의 45%인 45/ 45/ 45 미만으로 33.00%      ← 우측 열
    만기 하락한 적이 없는 경우(어느 한 기초자산의 …) (연 11.00%)   ← 좌·우 열
    상환 는 날도 포함) 만기상환금액은 다음과 같습니다.              ← 좌측 열

그래서 `ELS-MATURITY-LOSS-CONDITION` 의 인용이 원문에서 연속으로 안 잡히고, 추출이
`NARROWING_REFUSED` 로 **거부한다.** 거부 자체는 옳다 — P6 항등식
(`text[start:end] == value_text`)을 못 지키느니 실패로 내는 쪽이 맞다.

## ❗글자를 더하거나 빼지 않는다

조각을 **지우지 않고 표 끝으로 옮긴다.** 검증으로 글자 다중집합이 보존되는 것을 확인한다 —
내용을 고치는 것이 아니라 **열 순서를 되돌리는 것**이다.

근본 수정은 파서가 표를 열 단위로 읽는 것이고(`#436` 의 (나)), 그건 데모 뒤다. 이 파일을
지우면 즉시 원래 경로(PDF 파싱)로 돌아간다.

    python scripts/make_manual_parse_override.py
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "ai-service"))

from app import parsing  # noqa: E402

SOURCE = "documents/els_kiwoom_4181_simple_prospectus.pdf"
TARGET = REPO / "data" / "documents" / "els_kiwoom_4181_simple_prospectus.json"

#: 표의 좌·우 열 조각. 문장이 아니라 **셀 값**이다.
CELL = re.compile(r"^(33\.00%|\(연 11\.00%\)|-100% ~-30%|\(기초자산 중 하|락폭이 큰 종목의|수익률\)|만기|상환)$")
TAIL = [" 33.00%", " (연 11.00%)"]
HEAD = ["만기 ", "상환 "]

#: 고치는 구간. ⑥ 만기상환 조건에서 ⑧ 손실 산식까지가 한 표다.
BLOCK_START = "만기평가일에 기초자산인"
BLOCK_END = "원금 × (만기평가가격/최초기준가격)"


def main() -> int:
    doc = parsing.parse_upload(SOURCE, product_type="ELS")
    page = next(p for p in doc["pages"] if p["page"] == 8)
    lines = page["text"].split("\n")

    start = next(i for i, l in enumerate(lines) if BLOCK_START in l)
    end = next(i for i, l in enumerate(lines) if l.startswith(BLOCK_END))

    moved: list[str] = []
    out: list[str] = []
    for i, line in enumerate(lines):
        if not (start <= i <= end):
            out.append(line)
            continue
        if CELL.match(line):
            moved.append(line)
            continue
        for t in TAIL:
            if line.endswith(t):
                line = line[: -len(t)]
                moved.append(t.strip())
        for h in HEAD:
            if line.startswith(h):
                line = line[len(h) :]
                moved.append(h.strip())
        out.append(line)

    at = out.index(BLOCK_END) + 1
    fixed = "\n".join(out[:at] + moved + out[at:])

    # ❗내용 보존 검산. 글자 다중집합이 같아야 "열 순서를 되돌렸다" 가 참이다.
    flat = lambda s: sorted(s.replace("\n", "").replace(" ", ""))
    if flat(page["text"]) != flat(fixed):
        print("오류: 글자가 보존되지 않았다 — 표 구조가 바뀌었을 수 있다", file=sys.stderr)
        return 2

    page["text"] = fixed
    page["char_count"] = len(fixed)
    doc["parse_warnings"] = [{
        "page": 8,
        "code": parsing.MANUAL_OVERRIDE_CODE,
        "message": "페이지 8 상환조건 표에서 좌측 라벨(만기/상환)과 우측 요율 열이 가운데 열 "
                   "문장 사이로 끼어들어 만기 손실조건의 스팬이 해소되지 않았다. 조각을 지우지 "
                   "않고 표 끝으로 옮겼다(글자 보존 검산). 생성: "
                   "scripts/make_manual_parse_override.py · 근본 수정은 이슈 #436 (나).",
    }]

    TARGET.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{TARGET.relative_to(REPO)} · 페이지 {len(doc['pages'])} · 옮긴 조각 {len(moved)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
