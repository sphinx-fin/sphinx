"""평가 표본이 시험 자격을 갖췄는지. 소유: 정세현 (F-CMN-003)

여기서 지키는 것 둘이다.

1. **표본이 라벨을 들고 다니지 않는다.** 표본을 만든 사람(운영자)은 라벨링에서 빠진다
   (eval/README.md). 표본 파일에 등급이 실려 있으면 그 분리가 이름만 남는다.
2. **오해 라이브러리 `patterns` 를 베끼지 않는다.** 매칭 엔진이 그 문자열로 U4 를 확정
   하므로(`apply_misconception_floor`), 같은 문자열을 표본에 넣으면 **학습 데이터로
   시험하는 것**이 된다. 점수는 올라가고 일반화는 하나도 안 재게 된다.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "eval" / "corpus"
RUBRICS = ROOT / "ai-service" / "app" / "rubrics"
LIBRARY = ROOT / "data" / "misconception_library" / "misconceptions.yaml"


def samples() -> list[dict]:
    rows = []
    for path in sorted(CORPUS.glob("*.jsonl")):
        for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if raw.strip():
                rows.append({"_file": path.name, "_line": lineno, **json.loads(raw)})
    return rows


def library_patterns() -> list[str]:
    """오해 라이브러리의 `patterns` 값만.

    ❗**정규식으로 긁지 않는다.** 처음엔 그렇게 짰다가 `source.quote`·`id:` 줄·**주석 본문**
    까지 38개를 긁어서 오탐이 났다 — 하필 *"조각을 패턴으로 두면 정답 발화도 잡는다"* 고
    경고하는 주석에서 `중간에 빼면` 을 뽑아, 그 조각을 담은 **정답 발화**를 표본 위반으로
    신고했다. 이 파일이 막으려는 것과 정확히 같은 종류의 결함이라 파서를 쓴다.

    ❗뽑은 개수를 아래에서 단정한다. 0개가 되면 대조가 **아무것도 안 재면서 초록**이 된다.
    """
    doc = yaml.safe_load(LIBRARY.read_text(encoding="utf-8"))
    out: list[str] = []
    for entry in doc.get("types", []):
        out += [p.strip() for p in entry.get("patterns", []) if p and p.strip()]
    return out


class TestCorpusCarriesNoLabels:
    def test_no_grade_field_anywhere(self):
        """❗등급이 실리면 라벨링에서 빠진 사람이 정답을 흘리는 것이 된다."""
        offenders = [
            f"{s['_file']}:{s['_line']}"
            for s in samples()
            if any(k in s for k in ("grade", "gold", "label", "expected"))
        ]
        assert offenders == [], f"표본에 등급이 실려 있다: {offenders}"

    def test_fields_are_exactly_what_a_labeler_needs(self):
        allowed = {"_file", "_line", "sample_id", "product_type", "item_id", "utterance", "note"}
        for s in samples():
            assert set(s) <= allowed, f"{s['_file']}:{s['_line']} 예상 밖 키: {set(s) - allowed}"


class TestCorpusIsNotTrainingData:
    def test_the_pattern_list_is_not_empty(self):
        """★ 이 단정이 먼저다 — 0개면 아래 대조가 공회전한다."""
        patterns = library_patterns()
        assert len(patterns) >= 8, f"라이브러리에서 patterns 를 {len(patterns)}개밖에 못 뽑았다 — 형식이 바뀌었는지 본다"

    def test_no_utterance_copies_a_library_pattern(self):
        """❗베끼면 `apply_misconception_floor` 가 문자열 일치로 U4 를 확정한다 — 일반화를 하나도 안 재게 된다."""
        patterns = library_patterns()
        hits = [
            (s["sample_id"], p)
            for s in samples()
            for p in patterns
            if p in s["utterance"]
        ]
        assert hits == [], f"표본이 라이브러리 patterns 를 그대로 담고 있다: {hits}"


class TestCoverage:
    def test_every_els_rubric_item_has_samples(self):
        rubric_items = {p.stem for p in RUBRICS.glob("ELS-*.yaml")}
        assert rubric_items, "ELS 루브릭을 못 찾았다 — 경로가 바뀌었는지 본다"
        covered = {s["item_id"] for s in samples() if s.get("product_type") == "ELS"}
        assert rubric_items - covered == set(), f"표본이 없는 항목: {sorted(rubric_items - covered)}"

    def test_item_ids_exist_as_rubrics(self):
        """오타 난 item_id 는 라벨러가 볼 루브릭이 없다는 뜻이다."""
        known = {p.stem for p in RUBRICS.glob("*.yaml")}
        unknown = sorted({s["item_id"] for s in samples()} - known)
        assert unknown == [], f"루브릭이 없는 item_id: {unknown}"

    def test_sample_ids_are_unique(self):
        ids = [s["sample_id"] for s in samples()]
        dupes = sorted({i for i in ids if ids.count(i) > 1})
        assert dupes == [], f"sample_id 중복: {dupes}"

    def test_utterances_are_distinct(self):
        """같은 문장이 두 번 들어가면 라벨러가 두 번 판단하고 표본만 부풀린다."""
        utts = [s["utterance"] for s in samples()]
        dupes = sorted({u for u in utts if utts.count(u) > 1})
        assert dupes == [], f"발화 중복: {dupes}"
