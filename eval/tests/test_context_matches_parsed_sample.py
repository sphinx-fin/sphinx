"""고정 문맥이 **지금의 파싱 산출물과 같은 판인지** 잰다 (이슈 #409 · #455/#458). 소유: 정세현

## 왜 이 그물이 필요한가 — 조용히 어긋난다

`eval/data/context/els.json` 은 `build_context.py` 가 `contracts/samples/parsed_els_sample.json`
을 읽어 **한 번 만들어 커밋하는** 파일이다. 회차마다 다시 만들지 않는 것이 이 파일의 목적이다
(`build_context.py` 머리말 — *"돌리면 문맥이 바뀌고 이전 회차와 비교할 수 없게 된다"*).

그래서 **파서가 바뀌면 둘이 갈린다.** 실제로 갈렸다.

    #458 머지        2026-09-05T14:51:22Z   parser_version 0.2.0 → 0.3.0 · 여섯 쪽이 바뀌었다
    els.json         built_at 2026-09-05T10:01:48Z   ← 3시간 앞. 옛 판으로 굳어 있다

`#458` 은 **코드와 샘플만** 건드렸고 문맥은 다른 디렉토리에 있어 **텍스트 충돌이 안 났다.**
git 이 아무 말도 안 했고 CI 도 초록이었다 — `eval/tests` 에 이 대조가 없었기 때문이다.

## 무엇이 그때 깨져 있었나

`#455` 가 고친 인용(`ELS-EARLY-REDEMPTION-CONDITION` — 같은 y 칸조각 `5.50%` 가 줄 끝에
끼어 「경우」가 갈렸다)이 **문맥에는 갈린 채 그대로** 남았고, 오프셋이 밀리면서 P6 항등식이
13개 중 3개에서 깨졌다(`ELS-KNOCKIN-BARRIER` · `ELS-MATURITY-LOSS-CONDITION` ·
`ELS-COOLING-PERIOD` — 스팬이 7자 앞을 가리켰다).

`run_scoring.py` 는 `risk_items[item_id]` 를 문맥에서 꺼내 `scoring.score()` 에 그대로
넣는다. 즉 **채점이 옛 판 인용에 대고 돈다.** 증상은 등급이 낮게 나오는 것뿐이라, 그것이
고객 발화 때문인지 잘린 문맥 때문인지 회차가 끝난 뒤에도 못 가른다.

## 재는 것 — P6 항등식 하나 (1절 F-EXT-002)

    pages[page].text[start:end] == condition.value_text

이것이 *"문서에 이렇게 적혀 있다"* 의 증명이고, 교부 문서·리포트가 인용의 근거로 드는 값이다.
두 파일이 같은 판이면 반드시 성립하고, 갈리면 거의 반드시 깨진다 — **판 대조를 위해 따로
버전 필드를 만들 필요가 없다.** `built_at` 시각을 비교하지 않는 이유도 같다. 시각은 순서만
말하고 내용이 실제로 어긋났는지는 말하지 못한다.

## 고치는 법

    python eval/tools/build_context.py     # ❗실호출이다 (추출 13항목 + 질문 생성)

문맥을 다시 굳히는 것은 회차 비교를 끊는 일이라, `build_context.py` 머리말대로 **그 사실을
리포트에 적는다.** 이 테스트가 빨간 채로 성능 수치를 내면 안 된다 — 그 수치는 옛 판 인용으로
잰 것이다.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

#: `build_context.py` 의 `PARSED_DOC` 과 같은 파일이어야 한다. **임포트하지 않고 적는다** —
#: `build_context` 는 `app.extraction` 을 끌어오고, CI 의 eval 잡은 `pytest`·`pyyaml` 만
#: 깔기 때문이다(ci.yml 머리말). 대신 아래 `test_this_test_watches_the_file_the_builder_reads`
#: 가 두 곳이 갈리면 문다.
PARSED_SAMPLE = ROOT / "contracts" / "samples" / "parsed_els_sample.json"
CONTEXT = ROOT / "eval" / "data" / "context" / "els.json"

BUILDER = ROOT / "eval" / "tools" / "build_context.py"


def _pages() -> dict[int, str]:
    doc = json.loads(PARSED_SAMPLE.read_text(encoding="utf-8"))
    return {p["page"]: p["text"] for p in doc["pages"]}


def _context() -> dict:
    return json.loads(CONTEXT.read_text(encoding="utf-8"))


def _risk_items() -> dict[str, dict]:
    return _context()["risk_items"]


def test_the_context_names_the_parser_it_was_built_with() -> None:
    """★ 문맥이 **어느 파서 판으로 뽑은 것인지** 적고, 그것이 지금 판과 같은가.

    `build_context.py` 는 이미 `parser_version` 을 찍는다 — `#446` 리뷰가 *"낡음 탐지
    불가"* 로 지적해서 들어간 필드다. 그런데 **커밋된 문맥에는 그 필드가 없다.** 생성기만
    고치고 산출물은 다시 안 만든 것이고, 그 사실 자체가 이 그물이 잡으려는 상태다.

    아래 P6 대조가 더 강하다(내용이 실제로 어긋났는지 본다). 이 단정은 **왜 어긋났는지**를
    한 줄로 말해 준다 — 두 판 번호가 보이면 `build_context.py` 를 다시 돌릴 일이라는 것이
    바로 읽힌다.
    """
    doc_version = json.loads(PARSED_SAMPLE.read_text(encoding="utf-8"))["parser_version"]
    ctx_version = _context().get("parser_version")

    assert ctx_version is not None, (
        "문맥에 parser_version 이 없다 — `#446` 이 그 필드를 넣기 **전에** 만들어진 판이다. "
        f"지금 문서는 {doc_version} 다. build_context.py 를 다시 돌린다"
    )
    assert ctx_version == doc_version, (
        f"문맥은 파서 {ctx_version} 로 뽑았는데 지금 문서는 {doc_version} 다 — "
        "`source_span` 오프셋이 통째로 낡았다. build_context.py 를 다시 돌린다"
    )


def test_every_frozen_quote_still_resolves_in_the_parsed_document() -> None:
    """★ 문맥의 인용이 **지금의 문서 원문에 그대로 있다** (P6).

    깨지면 문맥이 옛 파서 판으로 굳어 있다는 뜻이다 — `build_context.py` 를 다시 돌린다.
    """
    pages = _pages()
    items = _risk_items()

    broken = []
    checked = 0
    for item_id, item in sorted(items.items()):
        condition = item.get("condition")
        if condition is None:
            # 추출 실패 항목은 조건이 없다(E-EXT-03 — 실패를 감추지 않는다). 잴 것이 없다.
            continue
        checked += 1
        span = condition["source_span"]
        actual = pages.get(span["page"], "")[span["start"]:span["end"]]
        if actual != condition["value_text"]:
            broken.append(
                f"  {item_id}  p{span['page']} [{span['start']}:{span['end']}]\n"
                f"    문맥에 저장된 인용: {condition['value_text'][:70]!r}\n"
                f"    지금 문서의 그 구간: {actual[:70]!r}"
            )

    # ❗**0회 돌고 조용히 통과하는 것**을 막는다. 문맥 형식이 바뀌어 `condition` 이 다른
    #   이름이 되면 위 루프가 전부 건너뛰고 이 그물이 사라진다.
    assert checked >= 10, (
        f"조건을 든 항목이 {checked}건뿐이다 — 문맥 형식이 바뀌었는지 본다. "
        "이 단정이 없으면 루프가 0회 돌고 초록이 된다"
    )

    assert not broken, (
        "고정 문맥이 지금의 파싱 산출물과 다른 판이다 — 인용이 원문에 안 닿는다.\n"
        + "\n".join(broken)
        + "\n\n  파서가 바뀌면 문맥은 자동으로 안 따라온다(디렉토리가 달라 텍스트 충돌이 없다).\n"
          "  고침: python eval/tools/build_context.py  — ❗실호출이고, 회차 비교가 끊기므로\n"
          "        다시 굳혔다는 사실을 리포트에 적는다."
    )


def test_this_test_watches_the_file_the_builder_reads() -> None:
    """생성기가 읽는 문서와 여기서 대조하는 문서가 **같은 파일**인가.

    위 테스트는 `PARSED_SAMPLE` 을 손으로 적는다(임포트하면 `app.extraction` 이 딸려 와
    eval 잡의 설치 목록을 넘는다). 그래서 생성기가 다른 문서로 갈아타면 이 그물은 **엉뚱한
    파일과 대조하며 초록**이 된다 — 그 자리를 여기서 문다.
    """
    src = BUILDER.read_text(encoding="utf-8")
    assert PARSED_SAMPLE.name in src, (
        f"build_context.py 가 {PARSED_SAMPLE.name} 을 안 읽는다 — 문서가 갈렸다면 "
        "이 테스트의 PARSED_SAMPLE 도 같이 옮긴다"
    )
    assert CONTEXT.name in src, (
        f"build_context.py 가 {CONTEXT.name} 을 안 만든다 — 산출 경로가 갈렸다면 "
        "이 테스트의 CONTEXT 도 같이 옮긴다"
    )
