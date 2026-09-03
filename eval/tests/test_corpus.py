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

    def test_sample_item_pairs_are_unique(self):
        """❗단위는 `sample_id` 가 아니라 **(표본, 항목)** 이다.

        guideline §1 이 *"같은 발화가 여러 항목에 대해 각각 라벨된다 — 고객이 한 마디로
        두 항목을 건드릴 수 있기 때문이다"* 로 설계를 적어 뒀는데, 예전 문면은
        `sample_id` 자체의 유일성을 요구해서 **그 설계를 금지하고 있었다.**
        `run_eval.load_jsonl` 도 `make_worksheet` 도 이미 쌍으로 키를 잡는다.
        """
        pairs = [(s["sample_id"], s["item_id"]) for s in samples()]
        dupes = sorted({p for p in pairs if pairs.count(p) > 1})
        assert dupes == [], f"(sample_id, item_id) 중복: {dupes}"

    def test_one_id_carries_one_utterance(self):
        """한 `sample_id` 가 두 문장을 가리키면 그 id 가 무엇인지 말할 수 없게 된다."""
        by_id: dict[str, set[str]] = {}
        for s in samples():
            by_id.setdefault(s["sample_id"], set()).add(s["utterance"])
        split = sorted(i for i, u in by_id.items() if len(u) > 1)
        assert split == [], f"한 sample_id 가 서로 다른 발화를 가리킨다: {split}"

    def test_distinct_ids_do_not_share_an_utterance(self):
        """같은 문장이 **다른 id 로** 두 번 들어가면 라벨러가 두 번 판단하고 표본만 부풀린다.

        같은 id 로 여러 항목에 걸리는 것은 의도된 설계라 여기서 세지 않는다 (위 두 테스트).
        """
        by_utt: dict[str, set[str]] = {}
        for s in samples():
            by_utt.setdefault(s["utterance"], set()).add(s["sample_id"])
        dupes = sorted(u for u, ids in by_utt.items() if len(ids) > 1)
        assert dupes == [], f"같은 발화가 여러 id 로 들어가 있다: {dupes}"


class TestLabelerNamingDoesNotDrift:
    """❗라벨러 이름이 **일곱 파일**에 흩어져 있다 — 하나만 고치면 두 벌이 된다.

    실제로 그렇게 났다(#350). `guideline.md` 하나만 고친 줄 알았는데 재보니 일곱 벌이었고,
    그중 하나는 **런타임 메시지**였다 — `run_eval.py` 가 라벨 디렉토리 없을 때 틀린 사람을
    가리키고 있었다. 라벨을 붙이려는 사람이 처음 만나는 문장이다.

    이번엔 손으로 전수를 맞췄지만, **다음에 라벨러가 바뀌면 또 손으로 맞춰야 한다.**
    #350 리뷰에서 윤지석·오준서가 각각 "그물을 여기 걸 수 있다" 고 제안한 자리다.
    """

    #: 라벨링 문맥에서 이 조합이 나오면 안 되는 파일들. `role-assignment` 는 **의도적
    #: 취소선**(배제 근거를 지우지 않는다는 규약)이 있어서 뺀다 — 거기가 정본이고,
    #: 취소선까지 검사하려면 이 테스트가 마크업을 알아야 해서 결합이 는다.
    NAMED = [
        "README.md",
        "eval/README.md",
        "eval/corpus/README.md",
        "eval/labeling/guideline.md",
        "eval/run_eval.py",
        "ai-service/tests/fixtures/README.md",
    ]

    def test_no_file_still_names_the_superseded_pair(self):
        """옛 조합(`강희진+오준서`)이 라벨링 문맥에 남아 있으면 안 된다."""
        stale = []
        for rel in self.NAMED:
            text = (ROOT / rel).read_text(encoding="utf-8")
            for token in ("강희진·오준서", "강희진+오준서"):
                if token in text:
                    stale.append(f"{rel}: {token!r}")
        assert stale == [], (
            f"라벨러 표기가 낡았다: {stale} — 바꿀 때 일곱 자리를 같이 고친다"
            " (eval/labeling/guideline.md §5 의 목록)"
        )

    def test_the_label_files_match_the_documented_pair(self):
        """❗문서가 말하는 라벨러와 `eval/data/labels/` 의 실물이 같아야 한다.

        문서만 고치고 파일을 안 바꾸면(또는 그 반대) **리포트가 다른 사람 이름을 찍는다.**
        라벨이 아직 없는 회차에서는 검사할 것이 없으므로 건너뛴다.
        """
        labels = sorted(p.stem for p in (ROOT / "eval" / "data" / "labels").glob("*.jsonl"))
        if not labels:
            pytest.skip("아직 라벨 파일이 없다 — 이 회차에서는 대조할 실물이 없다")
        doc = (ROOT / "eval" / "labeling" / "guideline.md").read_text(encoding="utf-8")
        missing = [n for n in labels if n not in doc]
        assert missing == [], (
            f"라벨 파일은 있는데 guideline.md 가 그 이름을 말하지 않는다: {missing}"
        )
