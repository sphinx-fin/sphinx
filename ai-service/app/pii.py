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
#: ❗**`public_document` 에서만 지운다** — 어느 범위에서 도는지는 `SCOPE_RULES` 가 정한다.
#: 고객 텍스트의 유선번호는 개인 집전화일 수 있고, 그 범위는 거짓양성 비용이 낮은 쪽이다(P3).
#:
#: ❗**이 패턴은 일부 계좌번호 꼴과 겹친다**(`#534` 리뷰, 오준서). `031-2345-6789` 처럼
#: 지역번호 꼴로 시작하는 값은 유선번호로 **먼저 지워져** `ACCOUNT` 로 보고되지 않는다 —
#: *"`ACCOUNT` 를 켰는데 왜 이건 안 잡히나"* 의 답이 여기다.
#:
#: 그래도 이 형태로 받는다. 좁히려면 실제 계좌번호와 유선번호를 **자릿수로 가르는 규칙**이
#: 필요한데 국내 은행 계좌 형식이 은행마다 달라 그 규칙이 없다. 이 겹침은
#: `public_document` 범위에서만 생기고, 그 범위의 잔여 위험은 `SCOPE_RULES` 에 적혀
#: 있다(모집단이 «ADMIN 이 올린 임의의 PDF» 로 바뀌었다는 것).
CORPORATE_CONTACT: dict[str, re.Pattern[str]] = {
    # 지역번호(02 · 030~069)에 더해 070(인터넷전화)·080(수신자부담)까지 — 셋 다 법인
    # 대표번호 형식이고 셋 다 `ACCOUNT` 에 걸린다. 휴대폰은 SPECIFIC 의 PHONE 이 먼저 먹는다.
    #
    # ❗**070·080 은 이 PR 이 만든 회귀였다** (`#534` 리뷰 ①, 오준서). 예전 완화는
    # `BROAD` 를 통째로 꺼서 이 둘도 그냥 통과했는데, `ACCOUNT` 를 되켜면서 **선지우기가
    # 못 따라간 형식만 새로 422** 가 됐다 — `02-785-7424` 가 F-EXT-002 를 멈춘 그 사고와
    # 글자 그대로 같은 모양이고 지역번호만 달랐다.
    #
    #     public_document   이 PR 이전   고치기 전   지금
    #     080-123-4567      통과         ❌ 422      통과
    #     070-1234-5678     통과         ❌ 422      통과
    #     110-234-567890    통과(구멍)    ACCOUNT    ACCOUNT
    #
    # 0505(평생번호)·1588(대표번호)은 여기 없어도 된다 — 앞이 4자리라 `ACCOUNT` 의
    # `\d{2,3}` 에 애초에 안 걸린다(실측).
    "landline": re.compile(r"\b0(?:2|[3-6]\d|70|80)-\d{3,4}-\d{4}\b"),
}

#: ★ **범위마다 무엇을 완화하고 무엇을 선지우는지의 단일 표** (`#534` 리뷰, 오준서).
#:
#: ❗예전에는 이 사실이 **세 곳의 `scope == "public_document"` 조건**으로 흩어져 있었다
#: (`_prestrip` · `detect` · 그리고 완화 집합 이름 자체가 범위를 담고 있었다). 범위를
#: 하나 더 들이는 날 **세 곳을 다 고쳐야 하는데 두 곳만 고쳐도 조용히 돈다** — 이 PR 이
#: 규탄한 것과 같은 모양이라 여기서 접는다.
#:
#: 표를 읽는 방법:
#:
#:     relaxed    이 범위에서 **끄는** 넓은 패턴. 나머지 `BROAD` 는 이 범위에서도 본다.
#:     prestrip   **지우기만 하고 보고하지 않는** 패턴. `PLACEHOLDER` 와 같은 자리다.
#:
#: ❗**범위를 늘리면 여기에 항목을 더해야 한다** — `SCOPES` 가 이 표에서 파생되므로
#: 빠뜨리면 `detect()` 가 `ValueError` 로 죽고, `main.PiiGuardMiddleware._DETAIL` 대조도
#: 같이 빨개진다(`test_every_scope_has_its_own_detail`). 조용히 통과하는 경로가 없다.
SCOPE_RULES: dict[str, dict[str, Any]] = {
    #: 고객 발화·설문. **기본값.** 좁은 패턴 + 넓은 휴리스틱 전부, 선지우기 없음.
    #: 거짓양성은 상류(P3) 버그 신호이므로 비용이 낮다. 유선번호도 지우지 않는다 —
    #: 고객의 그것은 개인 집전화일 수 있다.
    "customer": {"relaxed": frozenset(), "prestrip": {}},
    #: 공시 상품문서. 기획서 7-3 이 *"상품설명서(공시 자료이므로 개인정보가 아니다)"* 라고
    #: 명시한 대상이다. 좁은 패턴(RRN·PHONE)은 그대로 검사한다 — 공시 문서에 주민번호나
    #: 개인 휴대번호가 있다면 그건 문서 쪽 사고이므로 막아야 한다.
    #:
    #: ❗**완화를 측정된 오탐만큼만 준다.** 예전에는 `BROAD` 를 통째로 껐는데, 어느 패턴이
    #: 실제로 오탐을 내는지 잰 적이 없었다. 커밋된 공시 문서 전문(**표 셀 포함**)을 훑으면
    #: `ACCOUNT` 만 걸리고 둘 다 **법인 유선번호**다 — 그건 `prestrip` 이 미리 지우므로
    #: **`ACCOUNT` 를 켠 채로 둘 수 있다.** 재현: `tools/measure_public_document_pii.py`.
    #:
    #: ❗**`CARD` 는 끈 채로 둔다.** 처음엔 *"16자리 카드번호가 설명서에 인쇄될 이유가
    #: 없다"* 로 켰는데 **틀렸다**(`#534` 리뷰, 오준서). 패턴이 `(?:\d{4}[-\s]?){3}\d{4}`
    #: 라 **공백으로 나뉜 4자리 넷이면 전부 걸린다.**
    #:
    #:     "평가일 2024 2025 2026 2027 만기"        → 매치
    #:     "기초자산 지수 3245 1180 2870 4410"       → 매치
    #:
    #: ELS 상품설명서의 조기상환 평가일 표·지수 레벨 행이면 바로 닿고, pdfplumber 는 표 한
    #: 행을 공백으로 이어 붙인다. 켜면 **운영자가 올린 정상 문서의 추출이 422 로 죽는다** —
    #: 완화가 원래 막으려던 그 장애다.
    #:
    #: ❗**`EMAIL` 도 끈 채로 둔다.** 발행사 문의 이메일은 법인 연락처라 `ACCOUNT` 와 같은
    #: 성격이고, 코퍼스에서 0 이라는 것이 「없다」의 증거는 아니다(표본이 작다).
    #:
    #: ❗**이 완화의 모집단이 `#527`(업로드 실배선) 이후 바뀌었다** — 사람이 고른 공시
    #: 문서에서 **ADMIN 이 올린 임의의 PDF** 로. *"공시 자료라서 안전하다"* 가 조건부가 됐다.
    "public_document": {"relaxed": frozenset({"EMAIL", "CARD"}), "prestrip": CORPORATE_CONTACT},
}

#: 검사 범위 — **표에서 파생한다.** 두 벌이 되면 갈린다.
SCOPES = tuple(SCOPE_RULES)


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
    # 공시 문서에 정상적으로 인쇄되는 법인 연락처를 먼저 걷어야 `ACCOUNT` 를 그
    # 범위에서도 켤 수 있다 — 안 걷으면 대표번호가 계좌번호로 보고돼 추출이 죽는다.
    for pattern in SCOPE_RULES[scope]["prestrip"].values():
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
    if scope not in SCOPE_RULES:
        raise ValueError(f"알 수 없는 검사 범위 {scope!r}. 허용: {list(SCOPES)}")
    if not text:
        return []
    # ❗**좁은 패턴끼리도 「지워 가며」 본다** (`#534` 리뷰 ③, 오준서). 한 문자열에 전부
    #   걸면 `RRN` 하나가 `PHONE` 으로도 보고된다 — `010123-1234567`(2001-01-23 생)에서
    #   `PHONE` 이 `010123-1234` 를 문다(실측). `PHONE` 이 `01[016789]` 로 시작하므로
    #   **2001년 1·6~12월 출생분이 통째로** 이 겹침에 들어간다.
    #
    #   `BROAD` 주석이 적어 둔 *"겹친 채로 두면 상류 P3 위반을 추적할 때 오도한다"* 가
    #   SPECIFIC 안에서도 성립한다. 거부되는 것은 어느 쪽이든 같으니 구멍은 아니고
    #   **진단이 틀리는 자리**다 — 주민번호만 든 필드에 「PHONE 도 있다」가 붙으면
    #   운영자가 없는 전화번호를 찾으러 간다.
    specific_residual = _prestrip(text, scope)
    kinds = []
    for name, pat in SPECIFIC.items():
        if pat.search(specific_residual):
            kinds.append(name)
            specific_residual = pat.sub(" ", specific_residual)

    # ❗**넓은 패턴이 보는 잔여는 `residual_for_broad()` 가 만든다** (`#534` 리뷰 ②).
    #   ❗**한 번만 부른다** (같은 리뷰 ⓐ). 제너레이터 안에 두면 `customer` 에서 3회 돌고
    #   (완화가 없어 `and` 가 단락되지 않는다) 31,600자 입력에서 3.3ms → 2.0ms 차이였다.
    broad_residual = residual_for_broad(text, scope)
    relaxed = SCOPE_RULES[scope]["relaxed"]
    kinds.extend(name for name, pat in BROAD.items()
                 if name not in relaxed and pat.search(broad_residual))
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
