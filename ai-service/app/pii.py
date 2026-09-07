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

#: 검사 범위. 무엇을 보내는지에 따라 넓은 휴리스틱의 의미가 달라진다.
#:
#: - `customer`        고객 발화·설문. **기본값.** 좁은 패턴 + 넓은 휴리스틱 전부.
#:                     거짓양성은 상류(P3) 버그 신호이므로 비용이 낮다.
#: - `public_document` 공시 상품문서. 기획서 7-3 이 *"상품설명서(공시 자료이므로 개인정보가
#:                     아니다)"* 라고 명시한 대상이다. 발행사 민원부서 번호처럼 법인 연락처가
#:                     인쇄돼 있어 넓은 휴리스틱이 **정상 문서를 막는다** — 실제로
#:                     `02-785-7424` 가 ACCOUNT 패턴에 걸려 F-EXT-002 추출이 멈췄다.
#:                     좁은 패턴(RRN·PHONE)은 그대로 검사한다. 공시 문서에 주민번호나
#:                     개인 휴대번호가 있다면 그건 문서 쪽 사고이므로 막아야 한다.
SCOPES = ("customer", "public_document")

#: `public_document` 에서 **끄는 넓은 패턴.** 나머지 넓은 패턴은 이 범위에서도 검사한다.
#:
#: ❗**완화를 측정된 오탐만큼만 준다.** 예전에는 `BROAD` 를 통째로 껐는데, 실제로 오탐을
#: 내는 것이 무엇인지 재 본 적이 없었다. 커밋된 공시 문서 4건 전문을 훑으니 이랬다
#: (재현: `tools/measure_public_document_pii.py`).
#:
#:     ACCOUNT  2건   '02-785-7424' · '02-2262-6600'  ← 둘 다 서울 지역번호다. 완화의 근거 그대로
#:     EMAIL    0건
#:     CARD     0건
#:
#: 그래서 `CARD` 는 이 범위에서도 검사한다. 16자리 카드번호가 상품설명서에 인쇄될
#: 이유가 없고, 걸린다면 **올린 파일이 설명서가 아니라는 신호**다.
#:
#: `EMAIL` 은 끈 채로 둔다 — 발행사 문의 이메일은 법인 연락처라 `ACCOUNT` 와 같은 성격이고,
#: 4건에서 0 이라는 것이 「없다」의 증거는 아니다(표본이 작다).
#:
#: ❗**이 완화의 모집단이 `#527`(업로드 실배선) 이후 바뀌었다.** 예전에는 이 자리에
#: 사람이 고른 공시 문서만 왔고, 지금은 **ADMIN 이 올린 임의의 PDF** 가 온다. 완화를
#: 좁히는 이유가 그것이다 — *"공시 자료라서 안전하다"* 가 이제 조건부다.
RELAXED_IN_PUBLIC_DOCUMENT = frozenset({"EMAIL", "ACCOUNT"})


class PiiDetected(Exception):
    """상류(P3) 위반. 요청을 거부하고 어떤 패턴인지 알린다 — 원문은 절대 담지 않는다."""

    def __init__(self, kinds: list[str], where: str = "") -> None:
        self.kinds = kinds
        self.where = where
        super().__init__(f"PII detected: {', '.join(kinds)}" + (f" at {where}" if where else ""))


def detect(text: str, scope: str = "customer") -> list[str]:
    """걸린 패턴 이름 목록. 자리표시자는 제거한 뒤 검사한다."""
    if scope not in SCOPES:
        raise ValueError(f"알 수 없는 검사 범위 {scope!r}. 허용: {list(SCOPES)}")
    if not text:
        return []
    stripped = PLACEHOLDER.sub("", text)

    kinds = []
    residual = stripped
    for name, pat in SPECIFIC.items():
        if pat.search(residual):
            kinds.append(name)
            residual = pat.sub(" ", residual)
    relaxed = RELAXED_IN_PUBLIC_DOCUMENT if scope == "public_document" else frozenset()
    kinds.extend(name for name, pat in BROAD.items()
                 if name not in relaxed and pat.search(residual))
    return kinds


def assert_clean(text: str, where: str = "", scope: str = "customer") -> None:
    kinds = detect(text, scope)
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


def assert_payload_clean(payload: Any, scope: str = "customer") -> None:
    """요청 본문 전체의 모든 문자열을 검사한다. 필드 추가를 깜빡해도 새는 곳이 없게."""
    for path, text in _walk_strings(payload):
        assert_clean(text, path, scope=scope)
