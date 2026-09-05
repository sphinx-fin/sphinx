#!/usr/bin/env python3
"""평가 문맥을 **한 번 만들어 고정한다**. 소유: 정세현 (F-CMN-003)

    python eval/tools/build_context.py            # LLM 을 실제로 부른다

── 왜 고정하나 ───────────────────────────────────────────────────────────────

채점(`scoring.score`)의 입력은 넷이다.

    (루브릭, 이해항목 RiskItem, 질문, 고객 발화)

이 중 **발화만 표본이 바꾼다.** 나머지 셋이 회차마다 달라지면 점수가 왜 움직였는지
말할 수 없다 — 채점기가 나빠진 것인지 추출이 다르게 나온 것인지 구별이 안 된다.
그래서 RiskItem 과 질문을 한 번 만들어 `eval/data/context/` 에 **커밋하고**, 이후
채점은 그 파일을 읽는다.

루브릭은 이미 레포에 고정돼 있다(`ai-service/app/rubrics/`).

❗**이 스크립트를 매번 돌리지 않는다.** 돌리면 문맥이 바뀌고 이전 회차와 비교할 수 없게
된다. 다시 만들 이유가 생기면(문서 교체·템플릿 변경) 그 사실을 리포트에 적는다.
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "ai-service"))

from app import extraction, question_gen  # noqa: E402
from app.config import MODEL_POLICY_SUBSTRING, settings  # noqa: E402

CORPUS = REPO / "eval" / "corpus"
PARSED_DOC = REPO / "contracts" / "samples" / "parsed_els_sample.json"
OUT = REPO / "eval" / "data" / "context" / "els.json"
PRODUCT_TYPE = "ELS"
PRODUCT_ID = "eval-els-001"


def main() -> int:
    cfg = settings()
    if not cfg.llm_api_key:
        print("eval: LLM_API_KEY 가 없다 — ai-service/.env 를 본다", file=sys.stderr)
        return 1
    if MODEL_POLICY_SUBSTRING not in cfg.llm_model:
        # config.py 도 경고하지만 여기서 멈춘다. 문맥은 한 번 만들어 오래 쓰는 파일이라,
        # 정책 밖 모델로 만든 것이 섞이면 나중에 출처를 못 가린다.
        print(f"eval: 모델 정책 위반 — {cfg.llm_model}. 팀 결정은 {MODEL_POLICY_SUBSTRING} 다", file=sys.stderr)
        return 1

    doc = json.loads(PARSED_DOC.read_text(encoding="utf-8"))
    result = extraction.extract(product_id=PRODUCT_ID, product_type=PRODUCT_TYPE, parsed_document=doc)

    items, questions, unusable = {}, {}, {}
    for item in result.items:
        if item.status != "extracted":
            # ❗실패를 감추지 않는다. 조건이 없으면 질문도 채점도 만들 수 없고(E-EXT-03),
            #    그 사실이 리포트에 **표본이 줄어든 이유**로 남아야 한다.
            unusable[item.item_id] = item.failure_reason
            continue
        items[item.item_id] = item.model_dump()
        questions[item.item_id] = question_gen.generate(item, product_type=PRODUCT_TYPE).question

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({
        "_생성": "eval/tools/build_context.py — 손으로 고치지 않는다",
        "built_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "model": cfg.llm_model,
        "product_type": PRODUCT_TYPE,
        "product_id": PRODUCT_ID,
        "parsed_document": str(PARSED_DOC.relative_to(REPO)),
        "risk_items": items,
        "questions": questions,
        "unusable": unusable,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"문맥 저장: {OUT.relative_to(REPO)}")
    print(f"  사용 가능 {len(items)}항목 · 추출 실패 {len(unusable)}항목")
    for k, v in unusable.items():
        print(f"  ❗{k}: {v}")
    for w in result.warnings:
        print(f"  경고 {w.code} {w.item_id}")

    missing = _items_the_corpus_needs() - set(items)
    if missing:
        # ❗**여기서 멈추지 않으면 91회를 쓴 뒤에야 드러난다.** run_scoring 은 문맥에 없는
        #   항목의 표본을 건너뛰고 model.skipped.json 에 적는데, 그때는 이미 호출을 다 쓴
        #   뒤다. 그리고 줄어든 표본으로 낸 수치가 이전 회차와 나란히 놓인다.
        #
        #   추출은 회차마다 갈린다(#436 — "13/13 은 합격 기준이 아니다"). 그래서 이건
        #   **고칠 결함이 아니라 다시 돌릴 사건**이다. 다만 다시 돌린 것 중에서 결과가
        #   좋은 회차를 고르면 안 된다 — 조건은 "표본이 쓰는 항목을 다 덮는가" 하나이고,
        #   그걸 통과한 첫 회차를 쓴다.
        print(f"\n❗표본이 쓰는 항목 {len(missing)}개가 문맥에 없다: {sorted(missing)}",
              file=sys.stderr)
        print("   이대로 run_scoring 을 돌리면 해당 표본이 통째로 건너뛰어진다. 다시 돌린다.",
              file=sys.stderr)
        return 2
    return 0


def _items_the_corpus_needs() -> set[str]:
    """`eval/corpus/*.jsonl` 이 실제로 채점하는 item_id 전부."""
    needed: set[str] = set()
    for path in sorted(CORPUS.glob("*.jsonl")):
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            item_id = json.loads(line).get("item_id")
            if item_id:
                needed.add(item_id)
    return needed


if __name__ == "__main__":
    raise SystemExit(main())
