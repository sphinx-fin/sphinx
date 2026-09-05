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
import os
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "ai-service"))

# ❗**평가는 자기일관성 재질의를 순차로 돌린다** (이슈 #437 · PR #447).
#
# `#447` 이 재질의를 첫 채점과 **동시에** 던져 통과 답변을 4.5초 → 2.1초로 줄였다. 그 대가는
# **투기 호출**이다 — 등급을 보기 전에 던지므로 U1 이 아닌 답변에서는 그 호출이 버려진다.
#
# 두 경로의 셈이 정반대다.
#
#     데모·리허설   대부분 U1 (「이해했다」를 보여주는 시연)  → 거의 다 적중. 아끼는 것은 벽시계
#     평가(여기)    U1 30% (공식 70건 회차 실측)             → ❗70% 가 낭비. 아끼는 것은 쿼터
#
# 70건 회차의 실측 U1 은 21건이다. 순차는 **70 + 21 = 91회**, 병렬은 던지고 보므로
# **70 × 2 = 140회** — 켜 두면 **+49회(1.54배)** 다. 배치라 그 대가로 사는 벽시계는 아무 값이 없다.
#
# ❗**이것이 "평가용 경로를 따로 만들지 않는다" 를 안 깬다.** 병렬은 **호출 타이밍**만 바꾸고
# 판정을 안 바꾼다 — 등급·확신도·캡 규칙이 그대로다(`#447`: 켠 상태는 `#370` 이전이 아니라
# `#437` 이전 동작이다). 만약 판정이 바뀐다면 여기서 끄는 순간 평가한 것과 서비스하는 것이
# 달라져 이 스위치를 못 쓴다.
#
# `setdefault` 라 **명시적으로 준 값이 이긴다** — 병렬 경로를 평가로 재 보고 싶으면
# `SPHINX_PARALLEL_CONSISTENCY=1 python eval/tools/run_scoring.py` 로 켠다.
#
# ❗**`.env` 로는 못 켠다(그리고 못 깬다).** `setdefault` 가 **먼저** 키를 박고,
# `config._load_env_files()` 는 `load_dotenv(path, override=False)` 라 이미 있는 키를 안 덮는다.
# 여는 길도 아래 빈 문자열 함정도 **프로세스 환경변수일 때만** 성립한다(#449 리뷰, 윤지석).
#
# ❗**임포트보다 먼저 둔다 — 오늘의 필요가 아니라 보험이다.**
# 처음에 나는 *"`settings()` 가 `@lru_cache` 라 임포트 때 굳는다"* 고 적었는데 **그건 틀렸다**
# (#449 리뷰, 윤지석). 실측하면 `run_scoring` 임포트 직후 `settings.cache_info()` 는
# `hits=0 misses=0` 이고, `scoring` 은 임계값을 `settings()` 가 아니라
# `thresholds.get(...)`(`scoring_thresholds.yaml`)에서 읽는다 — 그래서 **오늘은** 이 줄이
# 임포트 뒤에 있어도 먹는다.
#
# 그래도 앞에 두는 이유는, `settings()` 가 `@lru_cache(maxsize=1)` 라 임포트 사슬 중 누군가가
# 한 번만 불러도 그 순간 값이 굳기 때문이다. 그날 나는 증상은 *"쿼터가 1.5배로 나갔다"* 뿐이라
# 회차가 끝난 뒤에나 안다. 자리를 코드가 아니라 **줄 번호로** 잠그는 것이 그래서 필요하다 —
# `eval/tests/test_scoring_runner_is_serial.py` 가 그 대조를 한다.
os.environ.setdefault("SPHINX_PARALLEL_CONSISTENCY", "0")

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
