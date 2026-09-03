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

    def test_three_labelers_is_refused_not_silently_truncated(self, tmp_path):
        """❗셋이면 한 명이 상한에서도 합의에서도 빠지는데 리포트는 2인 회차와 똑같다.

        실제 방아쇠는 `오준서.v2.jsonl` 같은 잔여 파일 하나다 (PR #225 리뷰, 오준서).
        """
        gold, model = corpus(40)
        write_jsonl(tmp_path / "data" / "model.jsonl", model)
        for name in ("강희진", "오준서", "오준서.v2"):
            write_jsonl(tmp_path / "data" / "labels" / f"{name}.jsonl", gold)
        r = run(tmp_path)
        assert r.returncode != 0
        assert "2인 독립이 전제다" in r.stderr
        assert "오준서.v2.jsonl" in r.stderr, "무엇을 찾았는지 이름으로 보여 줘야 한다"

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

    def test_zero_consensus_says_so_instead_of_printing_zeros(self, tmp_path):
        """❗합의 0이면 분포가 전부 0 으로 찍힌다 — "안 쟀다" 와 "재서 0" 이 구별돼야 한다."""
        gold, model = corpus(40)
        # 두 라벨러가 전 항목에서 어긋나게 만든다(한 칸씩 민다).
        shifted = [{**g, "grade": {"U1": "U2", "U2": "U3", "U3": "U4", "U4": "U1"}[g["grade"]]} for g in gold]
        write_jsonl(tmp_path / "data" / "model.jsonl", model)
        write_jsonl(tmp_path / "data" / "labels" / "a.jsonl", gold)
        write_jsonl(tmp_path / "data" / "labels" / "b.jsonl", shifted)
        r = run(tmp_path)
        assert r.returncode == 0, r.stderr
        assert "합의 0건" in r.stdout or "합의한 항목이 하나도 없다" in r.stdout

    def test_ceiling_is_reported_next_to_the_model_score(self, tmp_path):
        gold, model = corpus(40)
        disagreeing = [{**g, "grade": "U3" if g["grade"] == "U4" else g["grade"]} for g in gold]
        write_jsonl(tmp_path / "data" / "model.jsonl", model)
        write_jsonl(tmp_path / "data" / "labels" / "강희진.jsonl", gold)
        write_jsonl(tmp_path / "data" / "labels" / "오준서.jsonl", disagreeing)
        r = run(tmp_path)
        assert r.returncode == 0, r.stderr
        assert "상한과의 거리" in r.stdout
        assert "불일치 제외 10건" in r.stdout

    def test_the_ceiling_is_compared_on_the_same_sample(self, tmp_path):
        """❗상한과 나란히 놓는 값은 **2절**(전체 표본)이지 3절(합의 부분집합)이 아니다.

        합의 집합은 두 사람이 갈린 항목이 빠진 쪽이라 모델에게 더 쉽다. 그래서 라벨이
        멀쩡해도 3절 값이 상한을 넘을 수 있고, 예전 문면은 그때 *"표본·라벨을 먼저
        의심한다"* 를 찍었다 — 읽는 사람이 있지도 않은 누출을 찾으러 간다.

        실측으로 그렇게 났다(70건 회차): 3절 0.795 > 상한 0.769 인데 같은 표본에서는
        0.706·0.682 로 상한 아래였다.
        """
        gold, model = corpus(40)
        disagreeing = [{**g, "grade": "U3" if g["grade"] == "U4" else g["grade"]} for g in gold]
        write_jsonl(tmp_path / "data" / "model.jsonl", model)
        write_jsonl(tmp_path / "data" / "labels" / "강희진.jsonl", gold)
        write_jsonl(tmp_path / "data" / "labels" / "오준서.jsonl", disagreeing)
        r = run(tmp_path)
        assert r.returncode == 0, r.stderr

        block = r.stdout.split("상한과의 거리", 1)[1]
        assert "모델 ↔ 강희진" in block and "모델 ↔ 오준서" in block, \
            "상한 비교 블록이 2절의 라벨러별 값을 싣지 않는다"
        # ❗3절(합의 부분집합) 값이 이 블록에 섞이면 안 된다 — 표본이 다르다.
        assert "합의" not in block, "상한 비교에 합의 부분집합 값이 섞였다"

    def test_section_three_warns_it_is_a_subset(self, tmp_path):
        """3절 값을 상한과 직접 비교하지 말라는 경고가 문면에 있어야 한다."""
        gold, model = corpus(40)
        disagreeing = [{**g, "grade": "U3" if g["grade"] == "U4" else g["grade"]} for g in gold]
        write_jsonl(tmp_path / "data" / "model.jsonl", model)
        write_jsonl(tmp_path / "data" / "labels" / "a.jsonl", gold)
        write_jsonl(tmp_path / "data" / "labels" / "b.jsonl", disagreeing)
        r = run(tmp_path)
        assert "1절 상한과 직접 비교하지 않는다" in r.stdout
