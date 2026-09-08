"""데모 답지 산출물이 라벨과 어긋나지 않는다 (이슈 #539 ② · `build_demo_answers.py`).

이 파일이 지키는 것은 **산출물이 낡지 않는 것**이다. 라벨이 바뀌면 커밋된 JSON 이 조용히
과거를 말하는데, `#525` 가 정확히 그 결함이었다 — 설문 선택지가 바뀌었는데 픽스처가 열흘
동안 죽은 문면을 들고 있었고 ID·텍스트는 안 바뀌어서 대조 셋이 전부 초록이었다.
"""
from __future__ import annotations

import collections
import json
import pathlib
import sys

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "eval" / "tools"))

import build_demo_answers as bda  # noqa: E402


@pytest.fixture(scope="module")
def built():
    return bda.build()


@pytest.fixture(scope="module")
def committed():
    return json.loads(bda.OUT.read_text(encoding="utf-8"))


def test_the_committed_file_is_current(built, committed):
    """❗커밋된 산출물이 지금 라벨에서 나온 값과 같다.

    다르면 라벨이 바뀐 뒤 `--write` 를 안 돌린 것이다. 그때는 이 테스트를 고치는 게 아니라
    도구를 다시 돌린다.
    """
    answers, _ = built
    assert committed["answers"] == answers, (
        "산출물이 라벨과 어긋났다 — "
        "python3 eval/tools/build_demo_answers.py --write 를 돌린다")


def test_the_traversal_itself_is_measured(built):
    """★ 양성 대조. 0종을 담고도 통과하면 위 단정이 아무것도 안 잰다."""
    answers, diag = built
    assert len(answers) >= 8, f"답지가 선 항목이 너무 적다: {sorted(answers)}"
    assert diag["consensus"] > 0
    assert any("u1" in v for v in answers.values()), "U1 이 하나도 없다"
    assert any("u4" in v for v in answers.values()), "U4 가 하나도 없다"


def test_only_consensus_rows_are_included(built):
    """불일치 발화는 담지 않는다 — 사람도 갈린 것을 답지로 쓸 수 없다."""
    answers, _ = built
    consensus = bda.agreed()
    for item, entry in answers.items():
        for grade_key, payload in entry.items():
            key = (payload["sampleId"], item)
            assert key in consensus, f"{key} 가 합의분이 아니다"
            assert consensus[key] == grade_key.upper()


def test_the_key_is_the_sample_item_pair(built):
    """❗`sample_id` 만 키로 쓰면 값이 달라진다 — 그것을 이 단정이 붙든다.

    한 발화가 여러 항목에 라벨될 수 있어서, `sample_id` 로 묶으면 뒤에 오는 항목이 앞을
    덮어쓴다. `#541` 이 그 방식으로 세다가 합의를 49 로 읽고 `ELS-ISSUER-CREDIT-RISK` 의
    U1 합의를 «없음» 으로 판단했다.
    """
    _, diag = built
    assert diag["multiItemUtterances"], (
        "한 발화가 여러 항목에 붙은 경우가 사라졌다 — 그렇다면 이 단정의 전제가 없어졌으니 "
        "도구의 키 설명을 같이 고친다")

    # sample_id 로 묶으면 실제로 줄어든다
    consensus = bda.agreed()
    by_pair = len(consensus)
    by_sample = len({sid for sid, _ in consensus})
    assert by_sample < by_pair, "짝 키와 sample_id 키가 같은 수를 낸다 — 전제가 바뀌었다"


def test_a_multi_item_utterance_serves_every_item_it_is_labeled_for(built):
    """덮어쓰기가 났다면 여기서 걸린다.

    같은 발화가 두 항목에서 합의 U1 이면 **두 항목 모두** 답지를 가져야 한다. 하나만
    있으면 키가 뭉쳐진 것이다.
    """
    answers, diag = built
    consensus = bda.agreed()
    for sid, items in diag["multiItemUtterances"].items():
        for item in items:
            grade = consensus.get((sid, item))
            if grade not in bda.GRADES:
                continue
            entry = answers.get(item, {})
            picked = entry.get(grade.lower(), {}).get("sampleId")
            # 그 항목에 더 나은 후보가 있어 안 뽑혔을 수는 있다. 다만 항목 자체는 서야 한다.
            assert grade.lower() in entry, (
                f"{item} 의 {grade} 합의({sid})가 답지에 아예 없다 — 키가 뭉쳐진 신호다")
            assert picked is not None


def test_selection_is_deterministic():
    """두 번 돌려 같은 값이다 — 산출물이 재현 가능해야 커밋할 값이 된다."""
    first, _ = bda.build()
    second, _ = bda.build()
    assert first == second


def test_every_answer_text_is_in_the_corpus(built):
    """담긴 문면이 코퍼스 원문 그대로다 — 손으로 다듬으면 라벨과 대응이 끊긴다."""
    answers, _ = built
    corpus = {(r["sample_id"], r["item_id"]): r["utterance"]
              for r in bda._read_jsonl(bda.CORPUS)}
    for item, entry in answers.items():
        for payload in entry.values():
            assert payload["text"] == corpus[(payload["sampleId"], item)]
