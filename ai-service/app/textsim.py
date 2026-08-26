"""문자 바이그램 유사도. 소유: 윤지석

F-DET-001(오해 매칭)과 F-SCR-001(문면 복창 판정)이 같은 계산을 쓴다. 두 곳에서 따로
정규화하면 미묘하게 다른 두 정규화가 생기고, 임계값 숫자를 서로 비교할 수 없게 된다
(`CanonicalJson` 을 한 벌로 둔 것과 같은 이유 — CLAUDE.md `evidence/` 절).

한국어라 형태소 분석 없이 문자 바이그램을 쓴다. 조사·어미가 붙어도 어간 바이그램이 남고,
외부 사전이 없어 재현성이 있다. 임계값이 숫자로 드러나 심사에서 설명 가능하다.
"""
from __future__ import annotations

import re
import unicodedata

#: 숫자·한글·라틴 문자만 남긴다. 공백·문장부호는 지운다 — 같은 말이 띄어쓰기만 다른 경우가
#: 실제 발화에서 흔하다.
_NOISE = re.compile(r"[^0-9가-힣a-zA-Z]+")


def normalize(text: str) -> str:
    """NFC + 노이즈 제거. 조합형/완성형은 눈으로 같고 바이트가 다르다."""
    return _NOISE.sub("", unicodedata.normalize("NFC", text))


def bigrams(text: str) -> set[str]:
    return {text[i:i + 2] for i in range(len(text) - 1)} or ({text} if text else set())


def containment(needle: str, haystack: str) -> float:
    """`needle` 의 바이그램이 `haystack` 에 얼마나 들어있는지. 0.0~1.0.

    자카드가 아니라 포함도인 이유: `haystack` 이 길다고 점수가 깎이면 안 된다. 짧은 패턴이
    긴 발화 안에 있는 경우(오해 매칭)와 짧은 발화가 긴 원문에서 잘려나온 경우(복창 판정)가
    둘 다 이 방향이다.
    """
    nb = bigrams(normalize(needle))
    if not nb:
        return 0.0
    return len(nb & bigrams(normalize(haystack))) / len(nb)
