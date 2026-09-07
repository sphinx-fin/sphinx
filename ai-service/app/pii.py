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

#: ❗**지우기만 하고 보고하지 않는 것** — 공시 문서에 정상적으로 인쇄되는 법인 연락처.
#:
#: 실측된 오탐 둘이 **모두 유선전화**였다(`02-785-7424` · `02-2262-6600`). 둘 다
#: `ACCOUNT` 패턴(`\d{2,3}-\d{2,6}-\d{2,6}`)에 걸린 것이고, 그래서 예전에는 `ACCOUNT`
#: 자체를 껐다 — **오탐 하나 때문에 진짜 계좌번호까지 검사 밖**이 됐다.
#:
#: `SPECIFIC` 에 넣는 것으로는 안 된다. 거기 넣으면 `detect()` 가 **지우면서 보고도**
#: 하므로 법인 대표번호가 `LANDLINE` 으로 **차단된다**(실측 확인). 지워지되 보고되지
#: 않아야 한다 — `PLACEHOLDER` 와 같은 자리다.
#:
#: ❗**`public_document` 에서만 지운다.** 고객 텍스트의 유선번호는 개인 집전화일 수 있고,
#: 그 범위는 거짓양성 비용이 낮은 쪽이다(P3).
CORPORATE_CONTACT: dict[str, re.Pattern[str]] = {
    # 지역번호(02 · 031~069) + 국번 + 4자리. 휴대폰은 SPECIFIC 의 PHONE 이 먼저 먹는다.
    "landline": re.compile(r"\b0(?:2|[3-6]\d)-\d{3,4}-\d{4}\b"),
}

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

#: `public_document` 에서 **끄는 넓은 패턴.** 나머지는 이 범위에서도 검사한다.
#:
#: ❗**완화를 측정된 오탐만큼만 준다.** 예전에는 `BROAD` 를 통째로 껐는데, 어느 패턴이
#: 실제로 오탐을 내는지 잰 적이 없었다. 커밋된 공시 문서 전문(**표 셀 포함**)을 훑으면
#: `ACCOUNT` 만 걸리고 둘 다 **법인 유선번호**다 — 그건 이제 `CORPORATE_CONTACT` 가
#: 미리 지우므로 **`ACCOUNT` 를 켠 채로 둘 수 있다.** 재현:
#: `tools/measure_public_document_pii.py`.
#:
#: ❗**`CARD` 는 끈 채로 둔다.** 처음엔 *"16자리 카드번호가 설명서에 인쇄될 이유가 없다"* 로
#: 켰는데 **틀렸다**(`#534` 리뷰, 오준서). 패턴이 `(?:\d{4}[-\s]?){3}\d{4}` 라
#: **공백으로 나뉜 4자리 넷이면 전부 걸린다.**
#:
#:     "평가일 2024 2025 2026 2027 만기"        → 매치
#:     "기초자산 지수 3245 1180 2870 4410"       → 매치
#:
#: ELS 상품설명서의 조기상환 평가일 표·지수 레벨 행이면 바로 닿고, pdfplumber 는 표 한 행을
#: 공백으로 이어 붙인다. 켜면 **운영자가 올린 정상 문서의 추출이 422 로 죽는다** — 완화가
#: 원래 막으려던 그 장애다.
#:
#: ❗**`EMAIL` 도 끈 채로 둔다.** 발행사 문의 이메일은 법인 연락처라 `ACCOUNT` 와 같은
#: 성격이고, 코퍼스에서 0 이라는 것이 「없다」의 증거는 아니다(표본이 작다).
#:
#: ❗**이 완화의 모집단이 `#527`(업로드 실배선) 이후 바뀌었다** — 사람이 고른 공시 문서에서
#: **ADMIN 이 올린 임의의 PDF** 로. *"공시 자료라서 안전하다"* 가 조건부가 됐다.
RELAXED_IN_PUBLIC_DOCUMENT = frozenset({"EMAIL", "CARD"})


class PiiDetected(Exception):
    """상류(P3) 위반. 요청을 거부하고 어떤 패턴인지 알린다 — 원문은 절대 담지 않는다."""

    def __init__(self, kinds: list[str], where: str = "") -> None:
        self.kinds = kinds
        self.where = where
        super().__init__(f"PII detected: {', '.join(kinds)}" + (f" at {where}" if where else ""))


def _prestrip(text: str, scope: str) -> str:
    """**보고하지 않고 지우는 것** — 자리표시자와 (공시 문서일 때) 법인 연락처.

    ❗**한 벌이어야 한다.** 처음에 `detect()` 와 `residual_for_broad()` 가 각자 복사본을
    들고 있었고, 한쪽만 고치는 변이가 **아무 테스트도 안 깨뜨렸다** — 도구가 파이프라인을
    베끼면 안 된다는 `#534` 리뷰의 지적이 내 코드 안에도 있었다.
    """
    stripped = PLACEHOLDER.sub("", text)
    if scope == "public_document":
        # 공시 문서에 정상적으로 인쇄되는 법인 연락처를 먼저 걷어야 `ACCOUNT` 를 이
        # 범위에서도 켤 수 있다 — 안 걷으면 대표번호가 계좌번호로 보고돼 추출이 죽는다.
        for pattern in CORPORATE_CONTACT.values():
            stripped = pattern.sub(" ", stripped)
    return stripped


def residual_for_broad(text: str, scope: str = "customer") -> str:
    """넓은 패턴이 실제로 보게 되는 잔여 문자열.

    ❗**`detect()` 와 도구가 이 함수를 같이 쓴다.** 예전에는 도구가 이 파이프라인을
    베껴서, `SPECIFIC` 이나 `CORPORATE_CONTACT` 가 늘어도 **도구는 옛 숫자를 계속
    보고하고 아무 테스트도 안 깨졌다**(`#534` 리뷰, 오준서).
    """
    stripped = _prestrip(text, scope)
    for pattern in SPECIFIC.values():
        stripped = pattern.sub(" ", stripped)
    return stripped


def detect(text: str, scope: str = "customer") -> list[str]:
    """걸린 패턴 이름 목록. 자리표시자는 제거한 뒤 검사한다."""
    if scope not in SCOPES:
        raise ValueError(f"알 수 없는 검사 범위 {scope!r}. 허용: {list(SCOPES)}")
    if not text:
        return []
    kinds = []
    residual = _prestrip(text, scope)
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
