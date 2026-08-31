"""러너 종단 검증. 소유: 정세현 (F-CMN-003)

❗**픽스처는 여기 있고 ``eval/data/`` 에는 없다.** `eval/data/` 에 예시 라벨을 두면 다음
사람이 그걸 실제 라벨로 착각하고, 파이프라인이 **사람이 안 붙인 숫자로 리포트를 낸다.**
그 상태는 리포트 문면만 봐서는 진짜와 구별되지 않는다.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

EVAL = Path(__file__).resolve().parents[1]
RUNNER = EVAL / "run_eval.py"


def write_jsonl(path: Path, rows) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8")


def run(cwd: Path):
    """러너를 그 디렉토리를 eval 루트로 삼아 돌린다 (data/ 를 격리하려고 복사해 쓴다)."""
    for name in ("run_eval.py", "metrics.py"):
        (cwd / name).write_bytes((EVAL / name).read_bytes())
    return subprocess.run(
        [sys.executable, str(cwd / "run_eval.py")], capture_output=True, text=True, cwd=cwd
    )


def corpus(n: int, *, model_misses: int = 0):
    """n 항목. 두 라벨러는 완전 합의, 모델은 U4 중 `model_misses` 건을 U1 으로 읽는다."""
    grades = [["U1", "U2", "U3", "U4"][i % 4] for i in range(n)]
    rows_gold = [{"sample_id": f"s{i:03d}", "item_id": "ITEM", "grade": g} for i, g in enumerate(grades)]
    left = model_misses
    rows_model = []
    for r in rows_gold:
        g = r["grade"]
        if g == "U4" and left > 0:
            g, left = "U1", left - 1
        rows_model.append({**r, "grade": g})
    return rows_gold, rows_model


class TestRefusesRatherThanFaking:
    def test_no_model_file_exits_nonzero(self, tmp_path):
        r = run(tmp_path)
        assert r.returncode != 0
        assert "model.jsonl" in r.stderr

    def test_no_labels_dir_exits_nonzero(self, tmp_path):
        write_jsonl(tmp_path / "data" / "model.jsonl", corpus(40)[1])
        r = run(tmp_path)
        assert r.returncode != 0
        assert "라벨 디렉토리가 없다" in r.stderr

    def test_single_labeler_is_refused(self, tmp_path):
        """❗2인 독립이 전제다 — 한 사람이면 상한을 모르고, 상한 없이는 점수가 무의미하다."""
        gold, model = corpus(40)
        write_jsonl(tmp_path / "data" / "model.jsonl", model)
        write_jsonl(tmp_path / "data" / "labels" / "a.jsonl", gold)
        r = run(tmp_path)
        assert r.returncode != 0
        assert "2인 독립" in r.stderr

    def test_duplicate_key_is_refused_not_overwritten(self, tmp_path):
        gold, model = corpus(40)
        write_jsonl(tmp_path / "data" / "model.jsonl", model + [model[0]])
        write_jsonl(tmp_path / "data" / "labels" / "a.jsonl", gold)
        write_jsonl(tmp_path / "data" / "labels" / "b.jsonl", gold)
        r = run(tmp_path)
        assert r.returncode != 0 and "두 번 나온다" in r.stderr

    def test_bad_grade_is_refused(self, tmp_path):
        gold, model = corpus(40)
        write_jsonl(tmp_path / "data" / "model.jsonl", model)
        write_jsonl(tmp_path / "data" / "labels" / "a.jsonl", gold)
        write_jsonl(tmp_path / "data" / "labels" / "b.jsonl", gold[:-1] + [{**gold[-1], "grade": "U9"}])
        r = run(tmp_path)
        assert r.returncode != 0 and "enum" in r.stderr


class TestReport:
    def test_perfect_model_reports_target_met(self, tmp_path):
        gold, model = corpus(40)
        write_jsonl(tmp_path / "data" / "model.jsonl", model)
        write_jsonl(tmp_path / "data" / "labels" / "강희진.jsonl", gold)
        write_jsonl(tmp_path / "data" / "labels" / "오준서.jsonl", gold)
        r = run(tmp_path)
        assert r.returncode == 0, r.stderr
        assert "QWK +1.000" in r.stdout
        assert "**달성**" in r.stdout

    def test_misses_are_reported_separately_from_kappa(self, tmp_path):
        """정답 U4 10건 중 3건을 U1 으로 → 미탐 30%."""
        gold, model = corpus(40, model_misses=3)
        write_jsonl(tmp_path / "data" / "model.jsonl", model)
        write_jsonl(tmp_path / "data" / "labels" / "강희진.jsonl", gold)
        write_jsonl(tmp_path / "data" / "labels" / "오준서.jsonl", gold)
        r = run(tmp_path)
        assert r.returncode == 0, r.stderr
        assert "미탐 3/10 = 30.0%" in r.stdout

    def test_small_sample_is_not_scored(self, tmp_path):
        """❗30건 미만이면 카파를 찍지 않는다 — 그럴듯한 숫자가 회차 비교를 망친다."""
        gold, model = corpus(12)
        write_jsonl(tmp_path / "data" / "model.jsonl", model)
        write_jsonl(tmp_path / "data" / "labels" / "강희진.jsonl", gold)
        write_jsonl(tmp_path / "data" / "labels" / "오준서.jsonl", gold)
        r = run(tmp_path)
        assert r.returncode == 0, r.stderr
        assert "찍지 않는다" in r.stdout
        assert "판정 보류" in r.stdout

    def test_corpus_without_u4_says_so_loudly(self, tmp_path):
        """❗오해 케이스가 없는 표본은 게이트의 핵심 실패 모드를 아예 못 잰다."""
        rows = [{"sample_id": f"s{i:03d}", "item_id": "I", "grade": ["U1", "U2", "U3"][i % 3]} for i in range(36)]
        write_jsonl(tmp_path / "data" / "model.jsonl", rows)
        write_jsonl(tmp_path / "data" / "labels" / "강희진.jsonl", rows)
        write_jsonl(tmp_path / "data" / "labels" / "오준서.jsonl", rows)
        r = run(tmp_path)
        assert r.returncode == 0, r.stderr
        assert "U4 가 **0건**" in r.stdout

    def test_ceiling_is_reported_next_to_the_model_score(self, tmp_path):
        gold, model = corpus(40)
        disagreeing = [{**g, "grade": "U3" if g["grade"] == "U4" else g["grade"]} for g in gold]
        write_jsonl(tmp_path / "data" / "model.jsonl", model)
        write_jsonl(tmp_path / "data" / "labels" / "강희진.jsonl", gold)
        write_jsonl(tmp_path / "data" / "labels" / "오준서.jsonl", disagreeing)
        r = run(tmp_path)
        assert r.returncode == 0, r.stderr
        assert "상한(평가자 간)" in r.stdout
        assert "불일치 제외 10건" in r.stdout
