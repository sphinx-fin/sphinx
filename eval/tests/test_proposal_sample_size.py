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

#: 기획서가 평가 표본 크기를 인용하는 규약 — **두 수를 각자의 낱말과 함께** 적는다
#: (이슈 #577). 「세션」이 아닌 이유는 아래 `test_the_unit_is_utterances_not_sessions` 에 있다.
#:
#: ❗**한 수로는 못 적는다.** 고유 발화 67 과 채점 단위 70 이 다르므로, 「합성 발화 70건」은
#: 낱말과 수가 어긋난다 — 「합성 세션 100건」이 틀렸던 방식과 같고 차이가 3 일 뿐이다.
#: 표본이 늘면(v1.2 「남은 것」) 그 간격이 벌어진다. 그래서 둘을 따로 물어 둔다.
#:
#:     합성 발화 N건    고유 발화 수  = 서로 다른 sample_id
#:     채점 단위 N건    표본 행 수    = (발화, 항목) 짝. 성능 수치의 모집단이다
UTTERANCES = re.compile(r"합성 발화 (\d+)건")
SCORING_UNITS = re.compile(r"채점 단위 (\d+)건")


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
    body = _body_text()
    rows = _corpus_rows()
    expected = {
        "합성 발화": (UTTERANCES, len({r["sample_id"] for r in rows}), "고유 발화(서로 다른 sample_id)"),
        "채점 단위": (SCORING_UNITS, len(rows), "표본 행 = (발화, 항목) 짝"),
    }

    for label, (pattern, actual, what) in expected.items():
        cited = [int(m) for m in pattern.findall(body)]

        assert cited, (
            f"docs/proposal.md 본문에서 「{label} N건」 인용을 하나도 못 찾았다 — 문면이 "
            f"바뀌었으면 이 파일의 정규식({pattern.pattern!r})도 같이 고친다. 안 그러면 "
            "0건을 검사하고 조용히 통과한다"
        )

        wrong = [n for n in cited if n != actual]
        assert not wrong, (
            f"기획서가 「{label}」를 {wrong} 건으로 적었는데 실물은 {actual} 건이다({what}). "
            "기획서는 제출 PDF 의 정본이고 성능 수치가 전부 이 표본에서 나온다 — 숫자와 "
            "모집단이 갈리면 인용한 값이 조용히 거짓이 된다(이슈 #566). ❗두 수를 섞지 "
            "않는다: 발화 뒤에 채점 단위 수를 적으면 낱말과 수가 어긋난다(이슈 #577)"
        )


def test_the_unit_is_utterances_not_sessions() -> None:
    """★ 「세션」으로 되돌리지 않는다 — 그 단위로는 이 수가 틀린다.

    표본 한 줄은 *(발화, 항목)* 짝이다. 실제 세션은 한 세션에 여러 항목이 들어가므로
    단위가 다르다. 고유 발화는 67건이고 **한 발화가 두 항목에 걸린 것이 3건**이라
    채점 단위가 70건이 된다 — 라벨링과 일치도의 모집단이 그 70이므로 그 수를 쓴다.

    ❗이 단정이 있는 이유는 이슈 #566 의 제안이 *"70건 기준으로 고친다"* 였기 때문이다.
    수만 바꾸면 **단위가 여전히 틀린 채로 남는다.**

    ❗**그래서 문면이 두 수를 든다**(이슈 #577). 「합성 발화 70건」처럼 한 수로 적으면
    낱말은 발화이고 수는 채점 단위라, 다음 사람이 67 을 발견하고 «틀린 것 아닌가» 에서
    멈춘다 — 그리고 고치려 들면 그물이 운다. 위 대조가 발화 67 · 채점 단위 70 을 **따로**
    물으므로, 정확한 문면이 통과하고 어긋난 문면이 걸린다.
    """
    rows = _corpus_rows()
    unique_utterances = {r["sample_id"] for r in rows}

    assert len(rows) != len(unique_utterances), (
        "한 발화가 여러 항목에 걸린 경우가 없어졌다 — 그러면 「발화」와 「채점 단위」가 같은 "
        "수가 되어 이 구분이 필요 없다. 기획서 문면과 이 테스트를 같이 정리한다"
    )

    body = _body_text().splitlines()

    # ❗**「합성 세션」이라는 말 자체는 금지가 아니다.** 이 문서에서 그 말은 **대시보드용**
    #   합성 세션(`data/synth_sessions/`)을 가리키는 자리가 다섯 곳 있고 그건 정당하다.
    #   막는 것은 **거기에 수가 붙는 것**이고, 두 뜻 중 어느 쪽이든 붙으면 안 된다.
    numbered = [ln for ln in body if re.search(r"합성 세션 \d+건", ln)]
    assert not numbered, (
        f"본문이 「합성 세션 N건」으로 수를 적었다: {numbered}\n"
        "❗**어느 뜻으로 쓴 것인지에 따라 고칠 자리가 다르다**(이슈 #577).\n"
        "  · 평가 표본이면 — 단위가 틀렸다. 표본 한 줄은 (발화, 항목) 짝이므로 "
        "「합성 발화 N건」·「채점 단위 N건」으로 각자의 낱말과 함께 적는다(이슈 #566).\n"
        "  · 대시보드 합성 세션이면 — 그 수는 이 문서에 적지 않는다. 생성 파라미터는 "
        "커밋돼 있지만 산출물(sessions.json)은 .gitignore 가 물고, 무엇보다 그 수가 "
        "**파라미터 밖의 입력에 얽혀 있다**: 루브릭 등급 추출이 지터 추출과 같은 난수열을 "
        "쓰므로 템플릿의 required 항목이 하나 바뀌면 세션 수가 66 → 73 으로 움직인다"
        "(이슈 #577 실측). 수 대신 data/synth_sessions/distribution.yaml 과 "
        "scripts/gen_synth_sessions.py 를 가리킨다."
    )

    # ❗**대시보드 합성 세션의 «수» 는 이 문서에 적지 않는다**(이슈 #580 · PR #579 리뷰, 강희진).
    #
    #   위 단정으로는 안 잡힌다 — 낡았던 문면이 「합성 세션」 뒤가 아니라 **파일명 뒤**에
    #   수를 달고 있었고, 그래서 `합성 세션 \d+건` 에 안 걸렸다.
    #
    #       >    합성 데이터이고(`data/synth_sessions/`, 배포마다 `gen_synth_sessions.py` 가 68건)
    #
    # ❗**그리고 `_body_text()` 가 아니라 전문을 본다.** 그 문면은 **머리말**에 있었다 —
    #   인용 대조에서 머리말을 빼는 것은 맞지만(정정 이력이 옛 문면을 인용한다), 「이 수를
    #   적지 마라」 는 금지에서는 머리말이 제일 위험한 자리다. 낡은 값이 실제로 거기 살았다.
    #
    #   정정 이력이 «예전에 「68건」이라고 적혀 있었다» 로 그 수를 인용하는 것은 정당하다.
    #   그건 경로·스크립트 이름과 같은 줄에 없으므로 이 규칙에 안 걸린다 — 이력을 적을 때
    #   그 줄에 경로를 같이 두지 않는다.
    raw = PROPOSAL.read_text(encoding="utf-8")
    counted = [
        ln for ln in raw.splitlines()
        if re.search(r"synth_sessions", ln) and re.search(r"\d+\s*건", ln)
    ]
    assert not counted, (
        f"대시보드 합성 세션의 수를 적었다: {counted}\n"
        "❗그 수는 이 문서에 적지 않는다 — 생성 파라미터는 커밋돼 있지만 산출물"
        "(sessions.json)은 .gitignore 가 물고, 무엇보다 그 수가 **파라미터 밖의 입력에 "
        "얽혀 있다**: 등급 추출과 지터 추출이 같은 난수열을 쓰므로 템플릿의 required 항목이 "
        "하나 바뀌면 세션 수가 움직인다(66 → 73, 이슈 #577 실측). 실제로 세 곳이 서로 다른 "
        "값을 들고 있었다(재생성 66 · 로컬 68 · 로컬 71, 이슈 #580). 수 대신 "
        "data/synth_sessions/distribution.yaml 과 scripts/gen_synth_sessions.py 를 가리킨다.\n"
        "· 정정 이력으로 옛 수를 인용하는 것이면 — 그 줄에 경로·스크립트 이름을 같이 두지 "
        "않는다. 그러면 이력은 남고 이 규칙에 안 걸린다."
    )
