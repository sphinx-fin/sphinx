"""문서에서 **어느 항목도 안 덮는 문면**을 찾는다 (이슈 #474 1번 칸). 소유: 윤지석

## 왜 — 체인의 첫 칸이 손이다

`#476` 이 «항목 → 조항» 을 열었는데, 그 입력인 **항목 목록은 여전히 사람이 쓴다.**

    routes.py  wanted = body.item_ids or known      # known = templates.get(...).items

**템플릿에 없는 항목은 추출도 안 되고 채점도 안 된다.** 재현율의 분모가 템플릿이라
(`#26`) **분모 밖은 구조적으로 안 보인다.** 그리고 루브릭 `required_elements` 가 하나
빠지면 고객이 그것을 몰라도 U1 이 나오고 게이트가 초록이다 — **채점은 성공하고 미탐만
조용히 는다**(P5 · 0.2절).

실제로 빠져 있었다. `VAR-FEE-DEDUCTION` 에 **펀드 층 비용**이 없었고(`#391`), 고객이
월공제액만 말해도 `2/2 → U1 → GREEN` 이었다.

## ❗「항목을 제안」하지 않는다 — 「안 덮는 문면」만 낸다

`tools/find_coverage_gaps.py` 가 처음에 위험 어휘 필터를 두었다가 **순환**을 실측으로
겪었다 — 어휘를 앵커(루브릭)에서 유도하니 그 어휘를 든 문장은 당연히 앵커와 겹쳤고,
**ELS 는 교집합이 0 이라 사각을 하나도 못 냈다.**

    도구가 그 판단을 대신하려다 판단을 지웠다.

그 결론이 여기에도 그대로 걸린다. **무엇이 위험 항목인지는 사람이 정한다** — 이 층은
*"아무도 안 덮고 있다"* 만 말한다. `#476` 이 `u1_requires` 를 **안 내는** 것과 같은 규율이다
(문턱은 규범이고, `importance` 도 결정 10.1 이 **근거**를 요구하는 규범이다).

## LLM 을 안 부른다

`textsim.containment` 뿐이다 — **결정론적이고 쿼터를 안 쓰고 스텁 없이 테스트된다.**
`#476` 이 검색·리랭킹으로 조항을 뽑는 것과 층이 다르다: 저기는 *"이 항목의 근거가 어디
있나"* 이고 여기는 그 반대, *"이 문면이 어느 항목에도 안 걸리나"* 다.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from . import rubrics, templates, textsim
from .schemas import ParsedDocument

#: 이 값 이상 겹치면 "그 항목이 이미 덮고 있다" 고 본다.
#:
#: `rubricgen.ALREADY_COVERED`(0.45)보다 **낮게** 잡는다. 목적이 반대라서다 — 거기서는
#: *"이건 새 조항이다"* 를 좁게 봐야 사람이 볼 후보가 진짜 새것이고, 여기는 *"이미 누가
#: 덮고 있다"* 를 넓게 봐야 목록이 조용하다. **놓치는 쪽이 아니라 시끄러운 쪽이 이 층의
#: 실패 모드다** — 목록이 길면 아무도 안 본다.
#:
#: ❗두 값을 합치려는 시도를 `tests/test_propose_rubric.py` 가 막는다. 합치면 커버리지가
#: 사각이라 부른 것을 생성기가 기존으로 보고 버려 **두 도구가 서로를 무력화한다.**
COVERED_MIN = 0.25

#: 문장 하나로 볼 최소 길이. 이보다 짧으면 표 셀 조각이거나 머리글이다.
MIN_SENTENCE_CHARS = 20

_SENTENCE_END = re.compile(r"(?<=[.。!?])\s+|\n{2,}")


@dataclass(frozen=True)
class Gap:
    """아무도 안 덮는 문면 하나.

    ❗`text` 는 **원문 그대로**이고 `start`/`end` 가 그 위치다 — `pages[page].text[start:end]`
    와 글자가 같다(P6 · 1절). 대조용 정규화(공백 접기)는 `textsim` 안에서만 하고 **밖으로
    내는 값은 안 건드린다.** 화면이 이 스팬으로 원문을 하이라이트할 수 있어야 하고,
    가공해서 내보내면 *"문서에 이렇게 적혀 있다"* 가 *"우리가 이렇게 읽었다"* 가 된다.
    """

    page: int
    start: int
    end: int
    text: str
    best_overlap: float
    covered_by: str


def anchors(product_type: str) -> list[tuple[str, str]]:
    """이미 덮고 있는 문면 — (출처, 문면). 템플릿 `cue` + 루브릭 조항 전부.

    루브릭만 보면 **템플릿에는 있는데 루브릭이 없는 항목**(`recommended` 6개 — `#435`)이
    덮는 문면까지 사각으로 나온다. 둘 다 앵커로 둔다.
    """
    out: list[tuple[str, str]] = []
    for item in templates.get(product_type).items:
        out.append((f"template:{item.item_id}", f"{item.name} {item.cue}"))
    for item_id, rubric in rubrics.all_rubrics().items():
        if rubric.product_type != product_type:
            continue
        for element in rubric.required_elements:
            out.append((f"rubric:{item_id}", element))
        for cond in rubric.misconception_conditions:
            out.append((f"rubric:{item_id}", cond))
    return out


def sentences(doc: ParsedDocument) -> list[tuple[int, int, int, str]]:
    """(page, start, end, 원문) — **오프셋을 잃지 않고** 문장으로 가른다.

    ❗`" ".join(s.split())` 로 접어서 내면 스팬 항등식이 깨진다. 접는 것은 대조할 때만
    하고(`textsim` 안), 여기서 내는 것은 **원문 슬라이스**다.
    """
    out: list[tuple[int, int, int, str]] = []
    for page in doc.pages:
        cursor = 0
        for raw in _SENTENCE_END.split(page.text):
            start = page.text.index(raw, cursor)
            cursor = start + len(raw)
            if len(raw.strip()) >= MIN_SENTENCE_CHARS:
                lead = len(raw) - len(raw.lstrip())
                tail = len(raw) - len(raw.rstrip())
                out.append((page.page, start + lead, cursor - tail,
                            page.text[start + lead:cursor - tail]))
    return out


@dataclass(frozen=True)
class Scan:
    """한 번의 훑기 — 사각 **과 그 분모**.

    ❗**분모를 따로 다시 세지 않는다** (`#499` 리뷰, 정세현). 라우트가
    `len(sentences(doc))` 를 다시 부르면 *"`find_gaps` 가 실제로 몇 개를 봤나"* 가 아니라
    *"같은 함수를 한 번 더 부르면 몇 개가 나오나"* 를 말하게 된다. 오늘은 순수 함수라
    값이 같지만, `find_gaps` 가 나중에 문장을 걸러내면(표 셀 제외 같은 것) **분모만 옛
    값으로 남고 그 사실이 안 보인다** — 사각 0건인데 분모가 91 로 찍히면 *"91개를 다
    봤는데 깨끗하다"* 로 읽힌다.

    **분모를 지키려고 만든 값이 분모를 못 지키는 자리**라 한 번의 훑기가 셋을 같이 낸다.
    """

    gaps: list[Gap]
    sentences_scanned: int
    anchors_used: int


def scan(doc: ParsedDocument, product_type: str, *, limit: float = COVERED_MIN) -> Scan:
    """겹침이 낮은 것부터. **필터가 없다** — 무엇이 위험인지는 사람이 판단한다."""
    anc = anchors(product_type)
    seen = sentences(doc)
    return Scan(gaps=_gaps_from(seen, anc, limit), sentences_scanned=len(seen),
                anchors_used=len(anc))


def find_gaps(doc: ParsedDocument, product_type: str, *, limit: float = COVERED_MIN) -> list[Gap]:
    """사각만. 분모가 필요하면 `scan()` 을 쓴다."""
    return scan(doc, product_type, limit=limit).gaps


def find_gaps_with_anchors(
    doc: ParsedDocument, anc: list[tuple[str, str]], *, limit: float = COVERED_MIN
) -> list[Gap]:
    """앵커를 **인자로 받는다.**

    ❗`find_gaps` 가 앵커를 자기가 만들면 «앵커가 비었을 때» 를 테스트가 못 만든다 —
    그런데 그 경우가 결과를 **뒤집는다**(아무것도 안 덮으니 전부 사각이고, 그것을
    "사각이 65건이나 된다" 로 읽으면 정반대 결론이 난다). 실물 데이터에만 기대면 그
    경로가 영영 안 돈다 — `#396`·`#493` 에서 두 번 밟은 자리라 처음부터 갈라 둔다.
    """
    return _gaps_from(sentences(doc), anc, limit)


def _gaps_from(seen: list[tuple[int, int, int, str]], anc: list[tuple[str, str]],
               limit: float) -> list[Gap]:
    found: list[Gap] = []
    for page, start, end, text in seen:
        best, src = 0.0, "-"
        for source, anchor in anc:
            score = textsim.containment(anchor, text)
            if score > best:
                best, src = score, source
        if best < limit:
            found.append(Gap(page=page, start=start, end=end, text=text,
                             best_overlap=round(best, 4), covered_by=src))
    return sorted(found, key=lambda g: g.best_overlap)
