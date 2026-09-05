"""극성 게이트 표본 도구가 리포트와 **같은 합의**를 쓰는지. 소유: 정세현 (F-CMN-003)

## 왜 이 파일이 있나 — 같은 수치가 두 번 갈렸다

`#397` 에서 게이트 keep 방향을 재려고 「합의 U4」 표본을 넘겼는데, 받는 쪽이 코퍼스를
손으로 읽어 **67건 · 합의 U4 18건** 을 얻었다. 실제는 **70건 · 합의 51건 · U4 19건** 이고,
리포트 4절의 `미탐 4/19` 가 그 19를 분모로 쓴다. 원인은 코퍼스가 아니라 읽는 방식이었다.

`#388` 이 같은 계열이었다(*"누설 수치를 한 출처로 모은다 — 내 57% 와 26% 도 틀렸다"*).
그때 세운 규칙이 **재현 스크립트의 출력을 옮겨 적는다** 이고, 이 파일은 그 스크립트가
리포트와 갈리지 않는 것을 잠근다.

❗**숫자를 박지 않는다.** `19` 를 기대값으로 적으면 라벨이 바뀌는 날 이 테스트가 먼저
깨지는데, 그건 결함이 아니라 정상이다. 잠글 것은 **두 계산이 같은 집합을 본다**는 성질이다.

돌리기: ``python -m pytest eval/tests -q``
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "eval"))

from run_eval import aligned, load_labelers  # noqa: E402

TOOL = ROOT / "eval" / "tools" / "build_polarity_sample.py"


def _rows() -> list[dict]:
    out = subprocess.run([sys.executable, str(TOOL)], capture_output=True, text=True,
                         cwd=ROOT, check=True).stdout
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def _consensus_u4() -> set[tuple[str, str]]:
    """리포트 3절이 쓰는 그 정의 — `run_eval` 에서 그대로 가져온다."""
    labelers = load_labelers()
    names = list(labelers)
    a, b = labelers[names[0]], labelers[names[1]]
    return {k for k in aligned(a, b) if a[k] == b[k] and a[k] == "U4"}


def test_the_sample_is_exactly_the_report_consensus() -> None:
    """★ 도구가 뽑는 집합이 리포트의 「합의 U4」와 **같다**.

    다르면 게이트 측정을 리포트 옆에 놓을 수 없다 — 두 표가 서로 다른 표본을 말하게 된다.
    """
    got = {(r["sample_id"], r["item_id"]) for r in _rows()}
    assert got == _consensus_u4(), (
        "표본과 리포트가 다른 집합을 본다 — 합의 정의가 두 벌이 됐다는 뜻이다. "
        f"도구에만 {sorted(got - _consensus_u4())[:3]} · 리포트에만 "
        f"{sorted(_consensus_u4() - got)[:3]}")


def test_every_row_carries_the_utterance_and_the_grade() -> None:
    """받는 쪽이 그대로 1·2단계에 태울 수 있어야 한다 — 발화가 없으면 표본이 아니다."""
    rows = _rows()
    assert rows, "표본이 비었다 — 합의 U4 가 0건이면 그 자체가 보고할 사실이다"
    for r in rows:
        assert r["consensus"] == "U4"
        assert r["answer"].strip(), f"{r['sample_id']} 에 발화가 없다"


def test_the_key_is_the_pair_not_the_sample_id() -> None:
    """❗키는 `(sample_id, item_id)` 쌍이다 — `sample_id` 하나로 접으면 행이 사라진다.

    `#397` 에서 받는 쪽이 3줄을 잃은 원인이 정확히 이것이다. 같은 발화가 여러 항목으로
    라벨될 수 있으므로 `sample_id` 는 키가 아니다.
    """
    rows = _rows()
    ids = [r["sample_id"] for r in rows]
    pairs = {(r["sample_id"], r["item_id"]) for r in rows}
    assert len(pairs) == len(rows), "쌍이 중복이다 — 도구가 같은 행을 두 번 냈다"
    if len(set(ids)) != len(ids):
        # 실제로 겹치면 그것이 이 테스트가 지키는 사실의 실물 근거다.
        assert len(pairs) > len(set(ids))
