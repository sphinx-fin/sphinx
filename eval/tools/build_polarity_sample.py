"""F-DET-001 3단계(극성 게이트) keep 방향 측정용 표본을 뽑는다 — `#397` ③.

**합의 정의를 여기서 다시 쓰지 않는다.** `run_eval` 의 `load_labelers`·`aligned` 를
그대로 부른다 — 리포트 3절이 말하는 「합의」와 이 표본의 「합의」가 갈리면, 게이트가
만든 미탐을 재고도 그 수치를 리포트 옆에 놓을 수 없다.

    $ python eval/tools/build_polarity_sample.py > /tmp/u4_consensus.jsonl

출력 한 줄(`#397` 에서 윤지석이 요청한 형식):

    {"sample_id": "els-0012", "item_id": "ELS-…", "answer": "…", "consensus": "U4"}
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from run_eval import InputError, aligned, load_labelers  # noqa: E402

#: 표본 원문. `measure_repetition.py`·`measure_suffix_leak.py` 와 같은 파일을 본다.
CORPUS = Path(__file__).resolve().parents[2] / "eval" / "corpus" / "els.jsonl"


def main() -> int:
    labelers = load_labelers()
    names = list(labelers)
    a, b = labelers[names[0]], labelers[names[1]]

    # ❗리포트 3절과 **같은 합의**다 — 두 사람이 라벨했고 등급이 같은 것.
    # 3절은 여기에 model 정렬까지 걸어 51건을 만드는데, 이 표본은 모델 출력과 무관하므로
    # (게이트는 1·2단계 매칭 위에서 도는 층이다) 사람 둘만으로 잡는다. 그래서 3절보다
    # 클 수 있고, 그 차이는 **모델이 건너뛴 항목**이다(model.skipped.json).
    keys = [k for k in aligned(a, b) if a[k] == b[k] and a[k] == "U4"]

    utterances = {}
    for raw in CORPUS.read_text(encoding="utf-8").splitlines():
        raw = raw.strip()
        if raw:
            row = json.loads(raw)
            utterances[(row["sample_id"], row["item_id"])] = row["utterance"]

    missing = [k for k in keys if k not in utterances]
    if missing:
        # 조용히 빼지 않는다 — 표본이 작아진 것을 모르면 keep 률의 분모가 틀린다.
        raise InputError(f"코퍼스에 없는 합의 항목 {len(missing)}건: {missing[:3]}")

    for sample_id, item_id in keys:
        print(json.dumps({
            "sample_id": sample_id,
            "item_id": item_id,
            "answer": utterances[(sample_id, item_id)],
            "consensus": "U4",
        }, ensure_ascii=False))
    print(f"# 합의 U4 {len(keys)}건 (라벨러 {names[0]}·{names[1]})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except InputError as e:
        print(f"오류: {e}", file=sys.stderr)
        sys.exit(2)
