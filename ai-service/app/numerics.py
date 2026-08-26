"""조건값 수치 추출·대조. 소유: 윤지석

`F-INT-002`(질문에서 수치 **금지**)와 `F-INT-004`(원문 수치만 **허용**)가 같은 추출기를 써야
한다. 두 벌로 두면 한쪽만 고쳐지고 다른 쪽이 뚫린 채 남는다 — 실제로 그랬다.
`reexplain._NUMERIC` 은 단위를 통째로 버려 `45%` 원문에 `45년` 설명이 통과했고,
`question_gen._NUMERIC` 은 단위를 필수로 요구해 맨숫자 `45` 누출을 놓쳤다(PR #60 리뷰).

## 단위 동치

숫자가 같아도 단위가 다르면 **다른 값**이다. `45%` 배리어를 "45년 기다리면"으로 바꿔 말하는
것은 오해를 잡겠다면서 새 오해를 만드는 것이다(기획서 5절).

동치로 보는 것은 같은 값의 **표기 차이**뿐이다.

  %  ≡ 퍼센트          같은 단위의 기호/한글 표기
  억 ≡ 억원            금액 단위 생략
  년 ≡ 년간
  개월 ≡ 달

`원`·`만원`·`억원` 은 서로 동치가 **아니다** — 자릿수가 다르다.

## 맨숫자

단위 없는 숫자는 자기 부류다. 다만 **출력**의 맨숫자는 원문에 그 숫자가 어떤 단위로든
있으면 허용한다 — 숫자 자체는 문서에서 온 것이고 단위를 새로 주장하지 않았다.
반대로 단위가 붙은 출력은 원문에 같은 부류의 단위로 있어야 한다.
"""
from __future__ import annotations

import re
import unicodedata

#: 단위 표기 → 정규 부류. 여기 없는 단위는 표기 그대로가 부류다.
UNIT_CLASSES = {
    "%": "pct", "퍼센트": "pct",
    "억": "억원", "억원": "억원",
    "년": "년", "년간": "년",
    "개월": "개월", "달": "개월",
    "원": "원", "만원": "만원",
    "영업일": "영업일", "일": "일", "배": "배",
}

#: 단위는 긴 것부터 맞춰야 "만원"이 "원"으로 잘리지 않는다.
_UNITS = sorted(UNIT_CLASSES, key=len, reverse=True)
_NUMERIC = re.compile(r"(\d+(?:[.,]\d+)*)\s*(" + "|".join(map(re.escape, _UNITS)) + r")?")

#: 문장 번호는 조건값이 아니다.
ORDINALS = frozenset("①②③④⑤⑥⑦⑧⑨⑩⑪⑫")

_WS = re.compile(r"\s+")


def canonical(text: str) -> str:
    """대조용 정규화 — NFC + 공백 제거."""
    return _WS.sub("", unicodedata.normalize("NFC", text))


def extract(text: str) -> list[tuple[str, str | None]]:
    """`(숫자, 단위부류)` 목록. 단위가 없으면 부류는 None."""
    out: list[tuple[str, str | None]] = []
    for number, unit in _NUMERIC.findall(canonical(text)):
        if number in ORDINALS:
            continue
        out.append((number.replace(",", ""), UNIT_CLASSES.get(unit) if unit else None))
    return out


def source_values(text: str) -> frozenset[tuple[str, str | None]]:
    """원문에 있는 수치 집합. 설명에서 쓸 수 있는 것의 전부다."""
    return frozenset(extract(text))


def fabricated(content: str, allowed: frozenset[tuple[str, str | None]]) -> list[str]:
    """`content` 에 있는데 원문에는 없는 수치. 사람이 읽을 표기로 돌려준다."""
    numbers_in_source = {n for n, _ in allowed}
    found = []
    for number, unit in extract(content):
        if (number, unit) in allowed:
            continue
        # 단위 없는 출력은 숫자가 원문에 있으면 허용한다 — 단위를 새로 주장하지 않았다
        if unit is None and number in numbers_in_source:
            continue
        found.append(number if unit is None else f"{number}{_display(unit)}")
    return found


def _display(unit_class: str) -> str:
    return {"pct": "%"}.get(unit_class, unit_class)


def numbers(text: str) -> list[str]:
    """숫자만. **단위를 보지 않는다.**

    질문 누출 검사(F-INT-002)가 쓴다. 재설명(F-INT-004)과 방향이 반대다.

      재설명 — 출력 수치가 원문의 `(숫자, 단위)` 와 맞아야 한다. 단위가 다르면 다른 값이고
               환각이다. `45%` 원문에 `45년` 설명은 막는다.
      질문   — 원문의 **숫자 자체가 정답**이다. 단위를 떼고 말해도 답을 알려준 것이다.
               `45%` 원문에 `"45 아래로 떨어지면"` 질문은 막는다.

    같은 모듈에 두는 이유는 추출기가 하나여야 하기 때문이고, 판정 규칙은 용도별로 다르다.
    """
    seen: list[str] = []
    for number, _unit in _NUMERIC.findall(canonical(text)):
        if number in ORDINALS:
            continue
        n = number.replace(",", "")
        if n and n not in seen:
            seen.append(n)
    return seen
