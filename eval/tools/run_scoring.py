#!/usr/bin/env python3
"""표본을 실제로 채점해 `eval/data/model.jsonl` 을 만든다. 소유: 정세현 (F-CMN-003)

    python eval/tools/run_scoring.py

`eval/data/context/*.json`(고정 문맥)과 `eval/corpus/*.jsonl`(표본)을 읽어 항목마다
`scoring.score()` 를 부른다. **프로덕션과 같은 함수**다 — 평가용 경로를 따로 만들면
평가한 것과 서비스하는 것이 달라진다.

❗**모델 등급은 라벨이 아니다.** 이 파일은 라벨러에게 주지 않는다
(`eval/labeling/guideline.md` 0절 — 보면 그 값이 닻이 된다).
"""

from __future__ import annotations

import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "ai-service"))

from app import scoring  # noqa: E402
from app.config import MODEL_POLICY_SUBSTRING, settings  # noqa: E402
from app.schemas import RiskItem  # noqa: E402

CORPUS = REPO / "eval" / "corpus"
CONTEXT = REPO / "eval" / "data" / "context"
OUT = REPO / "eval" / "data" / "model.jsonl"
SKIPPED = REPO / "eval" / "data" / "model.skipped.json"

#: 무료 티어 한도가 **분당 15회**다(gemini-3.5-flash-lite, 실측: 429 응답이 한도를 알려준다).
#: 4초면 딱 맞으므로 여유를 둔다. 넘으면 429 가 나고, 그때 **일부만 채점된 model.jsonl** 이
#: 남는 것이 제일 나쁘다 — 표본이 조용히 줄어든다.
#:
#: ❗처음에 0.5 로 뒀다가 실제로 그 상태를 만들었다(28/40). 그래서 간격만이 아니라 아래
#: **이어받기와 백오프**를 같이 넣었다 — 간격만으로는 한도가 바뀌는 날 같은 일이 또 난다.
PACE_SEC = 4.5

#: 429 는 재시도로 풀린다. 응답이 "retry in Ns" 를 주므로 그 값을 쓴다.
MAX_RETRY = 3


def _retry_after(message: str) -> float | None:
    """429 응답이 알려주는 대기 시간. 429 가 아니면 ``None`` (재시도하지 않는다).

    ❗**아무 실패나 재시도하지 않는다.** P4 위반(루브릭 밖 조항 인용) 같은 것은 다시 불러도
    같은 이유로 실패할 수 있고, 그건 **기록해야 하는 결과**지 넘길 일이 아니다.
    """
    if "429" not in message:
        return None
    m = re.search(r"retry in ([0-9.]+)s", message)
    return min(float(m.group(1)) + 2, 90.0) if m else 60.0


def main() -> int:
    cfg = settings()
    if not cfg.llm_api_key:
        print("eval: LLM_API_KEY 가 없다 — ai-service/.env 를 본다", file=sys.stderr)
        return 1
    if MODEL_POLICY_SUBSTRING not in cfg.llm_model:
        print(f"eval: 모델 정책 위반 — {cfg.llm_model}. 이 실행의 결과는 성능 수치로 못 쓴다", file=sys.stderr)
        return 1

    contexts = {p.stem.upper(): json.loads(p.read_text(encoding="utf-8")) for p in CONTEXT.glob("*.json")}
    if not contexts:
        print(f"eval: 고정 문맥이 없다: {CONTEXT.relative_to(REPO)} — build_context.py 를 먼저 돌린다", file=sys.stderr)
        return 1

    # ── 이어받기 ──────────────────────────────────────────────────────────────
    # 이미 채점된 표본은 다시 부르지 않는다. 429 로 끊긴 회차를 이어서 채우는 것이
    # 목적이고, 덤으로 같은 표본을 두 번 채점해 값이 갈리는 일도 막는다.
    #
    # ❗키는 **(표본, 항목)** 이다. `sample_id` 만으로 잡으면 한 발화가 두 항목에 걸릴 때
    # (guideline §1 — *"고객이 한 마디로 두 항목을 건드릴 수 있다"*) 두 번째 항목이
    # **이미 채점된 것으로 보여 조용히 건너뛴다.** 이 블록이 막으려던 바로 그 상태
    # ("일부만 채점된 model.jsonl 이 남는 것이 제일 나쁘다")가 다른 경로로 재현된다.
    done: dict[tuple[str, str], dict] = {}
    if OUT.exists():
        for line in OUT.read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.startswith("#"):
                r = json.loads(line)
                done[(r["sample_id"], r["item_id"])] = r
    if done:
        print(f"이어받기: 이미 {len(done)}건이 채점돼 있다")

    rows, skipped = list(done.values()), []
    started = datetime.now(timezone.utc)
    samples = [json.loads(l) for p in sorted(CORPUS.glob("*.jsonl"))
               for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]

    for i, s in enumerate(samples, 1):
        if (s["sample_id"], s["item_id"]) in done:
            continue
        ctx = contexts.get(s["product_type"])
        item = (ctx or {}).get("risk_items", {}).get(s["item_id"])
        question = (ctx or {}).get("questions", {}).get(s["item_id"])
        if not item or not question:
            # ❗건너뛴 것을 조용히 버리지 않는다. 표본이 왜 줄었는지가 리포트에 남아야 한다.
            skipped.append({"sample_id": s["sample_id"], "item_id": s["item_id"],
                            "reason": (ctx or {}).get("unusable", {}).get(s["item_id"])
                                      or "고정 문맥에 이 항목이 없다"})
            continue
        j = None
        for attempt in range(MAX_RETRY):
            try:
                j = scoring.score(item_id=s["item_id"], question=question, answer_text=s["utterance"],
                                  risk_item=RiskItem(**item), product_type=s["product_type"])
                break
            except Exception as e:                   # noqa: BLE001 — 무엇이든 기록하고 계속한다
                wait = _retry_after(str(e))
                if wait is None or attempt == MAX_RETRY - 1:
                    skipped.append({"sample_id": s["sample_id"], "item_id": s["item_id"],
                                    "reason": f"{type(e).__name__}: {e}"})
                    print(f"  [{i}/{len(samples)}] {s['sample_id']} 실패 — {type(e).__name__}", file=sys.stderr)
                    break
                print(f"  [{i}/{len(samples)}] 429 — {wait:.0f}초 대기 후 재시도", file=sys.stderr)
                time.sleep(wait)
        if j is None:
            continue
        rows.append({"sample_id": s["sample_id"], "item_id": s["item_id"], "grade": j.grade.value,
                     "confidence": float(j.confidence), "prompt_version": j.prompt_version,
                     "model": cfg.llm_model})
        print(f"  [{i}/{len(samples)}] {s['sample_id']} {s['item_id']:32} → {j.grade.value}")
        time.sleep(PACE_SEC)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8") as f:
        f.write(f"# 자동 생성 — eval/tools/run_scoring.py · {started.isoformat(timespec='seconds')} · {cfg.llm_model}\n")
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    SKIPPED.write_text(json.dumps({"run_at": started.isoformat(timespec="seconds"),
                                   "skipped": skipped}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"\n채점 {len(rows)}건 · 건너뜀 {len(skipped)}건 → {OUT.relative_to(REPO)}")
    for s in skipped:
        print(f"  ❗{s['sample_id']} {s['item_id']}: {s['reason'][:80]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
