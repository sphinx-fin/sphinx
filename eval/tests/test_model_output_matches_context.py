"""모델 출력이 **지금의 고정 문맥으로 잰 것인지** 잰다 (이슈 #409 · #480 리뷰). 소유: 정세현

## 왜 이 그물이 필요한가 — 찍는 것만으로는 안 문다

`#477` 이 문맥과 파싱 산출물의 대조를 넣었고, `#480` 이 `model.jsonl` 헤더에 문맥 판을
찍게 했다. 그런데 **두 짝의 성격이 다르다** — 앞쪽은 물고, 뒤쪽은 **찍기만 한다**.

    파서가 오르고 → 샘플·문맥 재생성 → ❗model.jsonl 만 안 돌린다
      pytest eval/tests   통과       ← 아무도 안 문다
      리포트              parser 0.3.0 을 «정직하게» 찍는다   ← 문맥은 이미 0.4.0 인데

`#409` 자신이 정확히 이 모양이었다. 리포트가 정직하게 `F-SCR-001_v2` 를 찍고 있었고
**아무도 안 봤다.** 판을 적는 것만으로는 그때와 같다.

## ❗방아쇠가 이미 잡혀 있다

`#456`(추출이 조건절·결론 중 한쪽만 집는다)을 고치면 **문맥이 재생성되고**, 그 순간
`model.jsonl` 이 낡는다. 그 회차는 91회 실호출이라 «그때 가서 다시 돌리면 된다» 가 싸지
않다 — 낡은 채로 리포트가 나가는 쪽이 훨씬 싸게 일어난다.

## 재는 것 — 두 파일에 이미 있는 값을 비교한다

    model.jsonl 헤더        # 문맥 ELS — parser 0.3.0 · built 2026-09-06T05:18:31+00:00
    context/els.json        parser_version · built_at

같아야 한다. 새 필드도 새 계약도 필요 없고 **이미 적혀 있는 것을 대조**할 뿐이다.
실호출도 없다.

## 고치는 법

    rm eval/data/model.jsonl && python eval/tools/run_scoring.py    # ❗실호출 91회

문맥을 다시 굳혔으면 채점도 다시 돌아야 한다. 그 회차가 무엇으로 잰 것인지는
`#480` 이 헤더로 남기므로, **돌리고 나면 이 테스트가 저절로 초록**이 된다.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

MODEL = ROOT / "eval" / "data" / "model.jsonl"
CONTEXT_DIR = ROOT / "eval" / "data" / "context"

#: `run_scoring.py` 가 쓰는 줄. 형식이 바뀌면 아래 테스트가 「미기록」으로 떨어져 잡는다.
_MARK = re.compile(r"^#\s*문맥\s+(?P<product>\S+)\s+—\s*parser\s+(?P<parser>\S+)"
                   r"\s*·\s*built\s+(?P<built>\S+)\s*$")


def _stamped() -> dict[str, tuple[str, str]]:
    out: dict[str, tuple[str, str]] = {}
    for raw in MODEL.read_text(encoding="utf-8").splitlines():
        m = _MARK.match(raw.strip())
        if m:
            out[m.group("product")] = (m.group("parser"), m.group("built"))
    return out


def test_the_model_output_was_scored_with_the_context_that_is_committed_now() -> None:
    """★ `model.jsonl` 이 **지금 레포에 있는 문맥**으로 채점된 것인가.

    깨지면 문맥을 다시 굳히고 채점을 안 돌린 것이다 — 리포트가 낡은 회차의 수치를
    지금 문맥의 것처럼 인용하게 된다.
    """
    stamped = _stamped()
    contexts = {p.stem.upper(): json.loads(p.read_text(encoding="utf-8"))
                for p in sorted(CONTEXT_DIR.glob("*.json"))}

    assert contexts, "고정 문맥이 없다 — build_context.py 를 먼저 돌린다"

    # ❗**0회 돌고 조용히 통과하는 것**을 막는다. 헤더 형식이 바뀌면 stamped 가 비고,
    #   그러면 아래 루프가 한 번도 안 돌아 이 그물이 사라진다.
    assert stamped, (
        "model.jsonl 에 문맥 판이 안 찍혀 있다 — run_scoring.py 를 다시 돌리면 찍힌다"
        f" (#409 · #480). 지금 문맥: {sorted(contexts)}"
    )

    mismatched = []
    for product_type, ctx in contexts.items():
        if product_type not in stamped:
            mismatched.append(
                f"  {product_type}: 문맥은 있는데 model.jsonl 이 그 판을 안 찍었다")
            continue
        parser, built = stamped[product_type]
        if parser != str(ctx.get("parser_version")) or built != str(ctx.get("built_at")):
            mismatched.append(
                f"  {product_type}\n"
                f"    model.jsonl 이 잰 문맥: parser {parser} · built {built}\n"
                f"    지금 커밋된 문맥:      parser {ctx.get('parser_version')}"
                f" · built {ctx.get('built_at')}")

    assert not mismatched, (
        "model.jsonl 이 지금 문맥으로 잰 것이 아니다 — 문맥을 다시 굳히고 채점을 안 돌렸다.\n"
        + "\n".join(mismatched)
        + "\n\n  리포트가 낡은 회차의 수치를 지금 문맥의 것처럼 인용하게 된다(#409 가 그 모양이었다).\n"
          "  고침: rm eval/data/model.jsonl && python eval/tools/run_scoring.py"
          "  — ❗실호출 91회다."
    )
