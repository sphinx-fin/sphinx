"""P3 방어선 — ai-service 입구 PII 재검사. 소유: 윤지석

전제: 고객 텍스트는 Spring `PiiGateway.mask()`를 이미 통과했다.
그래도 방어적으로 여기서 한 번 더 검사하고, **걸리면 마스킹이 아니라 거부**한다.
(마스킹하면 상류의 P3 위반이 조용히 덮인다 — 거부해야 드러난다.)

패턴은 `server/.../core/PiiGateway.java`의 집합을 최소 기준으로 삼고, 방어 목적이라
더 넓게 잡는다. 거짓양성은 상류 버그 신호이므로 비용이 낮다.
"""
from __future__ import annotations

import re
from typing import Any, Iterator

# Spring PiiGateway가 치환한 자리표시자 — 통과시켜야 한다
PLACEHOLDER = re.compile(r"\[(?:RRN|PHONE|ACCOUNT|NAME|ADDRESS|EMAIL|CARD)\]")

# 좁고 확실한 패턴 — PiiGateway 동등 집합
SPECIFIC: dict[str, re.Pattern[str]] = {
    "RRN": re.compile(r"\d{6}[-\s]?[1-4]\d{6}"),
    "PHONE": re.compile(r"01[016789][-\s]?\d{3,4}[-\s]?\d{4}"),
}

# 넓은 방어선 — SPECIFIC과 겹치므로 그쪽 매치를 제거한 뒤에 본다.
# (겹친 채로 두면 전화번호가 ACCOUNT로도 보고돼 상류 P3 위반을 추적할 때 오도한다)
BROAD: dict[str, re.Pattern[str]] = {
    "EMAIL": re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+"),
    "CARD": re.compile(r"\b(?:\d{4}[-\s]?){3}\d{4}\b"),
    "ACCOUNT": re.compile(r"\b\d{2,3}-\d{2,6}-\d{2,6}(?:-\d{1,3})?\b"),
}

PATTERNS: dict[str, re.Pattern[str]] = {**SPECIFIC, **BROAD}


class PiiDetected(Exception):
    """상류(P3) 위반. 요청을 거부하고 어떤 패턴인지 알린다 — 원문은 절대 담지 않는다."""

    def __init__(self, kinds: list[str], where: str = "") -> None:
        self.kinds = kinds
        self.where = where
        super().__init__(f"PII detected: {', '.join(kinds)}" + (f" at {where}" if where else ""))


def detect(text: str) -> list[str]:
    """걸린 패턴 이름 목록. 자리표시자는 제거한 뒤 검사한다."""
    if not text:
        return []
    stripped = PLACEHOLDER.sub("", text)

    kinds = []
    residual = stripped
    for name, pat in SPECIFIC.items():
        if pat.search(residual):
            kinds.append(name)
            residual = pat.sub(" ", residual)
    kinds.extend(name for name, pat in BROAD.items() if pat.search(residual))
    return kinds


def assert_clean(text: str, where: str = "") -> None:
    kinds = detect(text)
    if kinds:
        raise PiiDetected(kinds, where)


def _walk_strings(node: Any, path: str = "") -> Iterator[tuple[str, str]]:
    if isinstance(node, str):
        yield path or "$", node
    elif isinstance(node, dict):
        for k, v in node.items():
            yield from _walk_strings(v, f"{path}.{k}" if path else str(k))
    elif isinstance(node, (list, tuple)):
        for i, v in enumerate(node):
            yield from _walk_strings(v, f"{path}[{i}]")


def assert_payload_clean(payload: Any) -> None:
    """요청 본문 전체의 모든 문자열을 검사한다. 필드 추가를 깜빡해도 새는 곳이 없게."""
    for path, text in _walk_strings(payload):
        assert_clean(text, path)
