"""기획서가 인용하는 표본 수 ≡ `eval/corpus` 실물. 소유: 정세현 (이슈 #566)

## 왜 이 파일이 있나

`docs/proposal.md` 는 **기획서 정본**이고 제출 PDF 를 거기서 뽑는다(그 파일 머리말).
그 정본이 두 자리에서 「합성 세션 100건」이라고 적고 있었는데 실물은 70건이었다.
기능 명세서 v1.2 §7 은 이미 70 기준으로 정정돼 있었으므로 **두 문서가 다른 표본 크기를
말하는 상태**였고, 성능 수치(상한 QWK +0.769 · 모델↔합의 51건 · 미탐 분모 19)는 전부
70건에서 나온 값이라 **숫자와 그 숫자의 모집단이 서로 다른 문서에 갈려 있었다.**

방향도 나쁘다 — 정본이 실물보다 **큰** 표본을 주장했고, 그 정본에서 심사 PDF 가 나온다.

## 왜 안 걸렸나 — `docs/proposal.md` 를 읽는 검사가 하나도 없었다

v1.2 를 정정한 회차에 기획서가 같이 안 갔고 **아무것도 빨개지지 않았다**. 표본은 앞으로
늘어날 예정이라(v1.2 §「남은 것」: 「표본 70발화 → 항목당 30발화 이상」) 이 수는 **또
바뀐다.** 그때 문면만 남는 것을 여기서 막는다.

## ❗0건을 검사하고 통과하지 않는다

인용이 전부 사라지면 이 대조는 **아무것도 안 재면서 초록**이 된다(`wilson(0,0)` 과 같은
모양). 그래서 «몇 건을 봤는가» 를 같이 단정한다 — 문면을 고쳐 인용을 없앨 거라면 이
검사도 같이 지우거나 규약을 고쳐야 하고, 그 판단이 PR 에 남는다.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROPOSAL = ROOT / "docs" / "proposal.md"
CORPUS = ROOT / "eval" / "corpus" / "els.jsonl"

#: 기획서가 평가 표본 크기를 인용하는 규약. 「합성 발화 N건」으로 적는다 —
#: 「세션」이 아닌 이유는 아래 `test_the_unit_is_utterances_not_sessions` 에 있다.
CITATION = re.compile(r"합성 발화 (\d+)건")


def _body_text() -> str:
    """머리말(인용 블록)을 뺀 본문. 정정 이력이 옛·새 문면을 둘 다 인용하므로,
    그것까지 세면 «인용이 남아 있는가» 를 재는 단정이 항상 참이 된다."""
    return "\n".join(
        ln for ln in PROPOSAL.read_text(encoding="utf-8").splitlines()
        if not ln.lstrip().startswith(">")
    )


def _corpus_rows() -> list[dict]:
    return [
        json.loads(line)
        for line in CORPUS.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    ]


def test_the_proposal_cites_the_real_sample_size() -> None:
    """★ 기획서가 인용하는 수가 실물과 같다.

    ❗**본 적 없는 것을 통과시키지 않는다** — 인용이 0건이면 실패다. 그게 이 결함이
    17시간이 아니라 한 회차 통째로 살아남은 이유다(읽는 검사가 아예 없었다).
    """
    # ❗**머리말은 세지 않는다.** 「PDF 와 어긋나는 지점」 4번이 정정 이력으로 새 문면을
    #   인용하고 있어서, 그 줄까지 세면 **본문 인용이 전부 사라져도 이 단정이 통과한다.**
    #   실제로 변이로 확인했다(본문 두 자리를 지웠는데 초록이었다) — 0건 방지가 머리말
    #   하나에 무력화되던 자리다.
    cited = [int(m) for m in CITATION.findall(_body_text())]

    assert cited, (
        "docs/proposal.md 본문에서 「합성 발화 N건」 인용을 하나도 못 찾았다 — 문면이 "
        f"바뀌었으면 이 파일의 CITATION 정규식({CITATION.pattern!r})도 같이 고친다. 안 "
        "그러면 0건을 검사하고 조용히 통과한다"
    )

    actual = len(_corpus_rows())
    wrong = [n for n in cited if n != actual]
    assert not wrong, (
        f"기획서가 표본을 {wrong} 건으로 적었는데 eval/corpus/els.jsonl 은 {actual} 건이다. "
        "기획서는 제출 PDF 의 정본이고, 성능 수치가 전부 이 표본에서 나온다 — 숫자와 "
        "모집단이 갈리면 인용한 값이 조용히 거짓이 된다(이슈 #566)"
    )


def test_the_unit_is_utterances_not_sessions() -> None:
    """★ 「세션」으로 되돌리지 않는다 — 그 단위로는 이 수가 틀린다.

    표본 한 줄은 *(발화, 항목)* 짝이다. 실제 세션은 한 세션에 여러 항목이 들어가므로
    단위가 다르다. 고유 발화는 67건이고 **한 발화가 두 항목에 걸린 것이 3건**이라
    채점 단위가 70건이 된다 — 라벨링과 일치도의 모집단이 그 70이므로 그 수를 쓴다.

    ❗이 단정이 있는 이유는 이슈 #566 의 제안이 *"70건 기준으로 고친다"* 였기 때문이다.
    수만 바꾸면 **단위가 여전히 틀린 채로 남는다.**
    """
    rows = _corpus_rows()
    unique_utterances = {r["sample_id"] for r in rows}

    assert len(rows) != len(unique_utterances), (
        "한 발화가 여러 항목에 걸린 경우가 없어졌다 — 그러면 「발화」와 「채점 단위」가 같은 "
        "수가 되어 이 구분이 필요 없다. 기획서 문면과 이 테스트를 같이 정리한다"
    )

    body = _body_text().splitlines()

    # ❗**「합성 세션」이라는 말 자체는 금지가 아니다.** 이 문서에서 그 말은 **대시보드용**
    #   합성 세션(`data/synth_sessions/`, 배포마다 `gen_synth_sessions.py` 가 68건)을
    #   가리키는 자리가 다섯 곳 있고 그건 정당하다. 막는 것은 **거기에 수가 붙는 것**이다 —
    #   평가 표본을 그 이름으로 부르면 68 과 70 이 같은 낱말 아래 섞이고, 두 수가 가까워서
    #   어느 쪽을 인용한 것인지 아무도 못 가른다.
    numbered = [ln for ln in body if re.search(r"합성 세션 \d+건", ln)]
    assert not numbered, (
        f"본문이 「합성 세션 N건」으로 수를 적었다: {numbered} — 이 문서에서 그 말은 대시보드 "
        "합성 세션(68건)도 가리킨다. 평가 표본은 (발화, 항목) 짝이라 단위가 다르므로 "
        "「합성 발화 N건」으로 적는다(이슈 #566)"
    )
