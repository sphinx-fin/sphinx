"""`public_document` 완화를 **측정된 오탐만큼만** 주기 위한 근거 (F-CMN-001 · P3).

## 왜

`PiiGuardMiddleware` 가 `/internal/parse`·`/internal/extract` 등에서 넓은 휴리스틱을 끈다.
근거는 기획서 7-3(*"상품설명서(공시 자료이므로 개인정보가 아니다)"*)과 실측 하나였다 —
발행사 민원부서 번호 `02-785-7424` 가 `ACCOUNT` 패턴에 걸려 추출이 422 로 막혔다.

그런데 **그 한 건으로 넓은 패턴을 통째로 껐다.** 어느 패턴이 실제로 오탐을 내는지 잰
적이 없다. 이 도구가 그것을 잰다.

## ❗모집단이 바뀌었다

`#527`(업로드 실배선) 전에는 이 자리에 **사람이 고른 공시 문서 4건**만 왔다. 지금은
**ADMIN 이 올린 임의의 PDF** 가 온다. 완화의 전제가 *"공시 자료라서 안전하다"* 에서
*"공시 자료일 것이라고 기대하는 무엇이든"* 으로 바뀌었으므로, 완화를 필요한 만큼으로
좁힐 근거가 필요하다.

## 무엇을 재는가

`data/documents/*.pdf` 를 파싱해 전문을 얻고, 좁은 패턴(RRN·PHONE)이 먼저 먹은 자리를
`detect()` 와 **같은 순서로** 지운 뒤 넓은 패턴을 센다. 순서를 안 맞추면 전화번호가
`ACCOUNT` 로도 세어져 실제보다 많아 보인다(`pii.detect` 가 그 순서를 두는 이유와 같다).

**LLM 을 부르지 않는다.** 결정론이고 커밋된 문서만 읽는다.
"""

from __future__ import annotations

import collections
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from app import parsing, pii  # noqa: E402

#: ❗**파싱이 읽는 뿌리에서 글롭한다.** 레포 경로로 글롭하고 파싱은 `documents_root()`
#: (= `SPHINX_DATA_DIR`)로 하면, 그 둘이 다른 데를 가리킬 때 **전부 실패하는데 결론은
#: 정상처럼 인쇄된다**(`#534` 리뷰, 오준서).
#: 완화의 **이유**. 세는 것이 아니라 적는 것이다 — 0건은 근거가 아니다.
RELAXED_BECAUSE = {
    "CARD": "실증된 오탐: 공백으로 나뉜 4자리 넷(연도 표·지수 표)이 전부 걸린다",
    "EMAIL": "법인 문의 이메일이 정상 인쇄된다. 이 코퍼스 0건은 「없다」의 증거가 아니다",
}

#: 실측된 법인 대표번호 — `CORPORATE_CONTACT` 가 지워야 하는 것.
CORPORATE_SAMPLES = ("문의 02-785-7424", "문의 02-2262-6600")

#: `CARD` 를 켜면 죽는 문면. 이 코퍼스에는 없지만 **ELS 설명서에 흔하다.**
CARD_FALSE_POSITIVES = (
    "평가일 2024 2025 2026 2027 만기",
    "기초자산 지수 3245 1180 2870 4410 종가",
)


def documents_dir() -> pathlib.Path:
    return parsing.documents_root() / "documents"


def broad_hits(text: str) -> dict[str, list[str]]:
    """넓은 패턴이 실제로 무엇을 물었나.

    ❗**파이프라인을 베끼지 않고 `pii.residual_for_broad()` 를 부른다.** 베끼면
    `SPECIFIC`·`CORPORATE_CONTACT` 가 늘어도 **도구는 옛 숫자를 계속 보고하고 아무
    테스트도 안 깨진다**(`#534` 리뷰, 오준서).
    """
    residual = pii.residual_for_broad(text, scope="public_document")
    return {name: found for name, pattern in pii.BROAD.items()
            if (found := pattern.findall(residual))}


def checked_surface(parsed: dict) -> str:
    """미들웨어가 **실제로 훑는** 문자열 전부.

    ❗`pages[].text` 만 보면 안 된다. `assert_payload_clean` 은 `_walk_strings` 로 본문의
    **모든** 문자열을 훑고, `parsed_document.tables` 도 `/internal/extract` 본문의 일부다.
    표 셀에서 나는 오탐을 0 으로 보고하면서 미들웨어는 422 를 내는 상태가 된다
    (`#534` 리뷰, 오준서).
    """
    return "\n".join(text for _, text in pii._walk_strings(parsed))


def _masked(value: str) -> str:
    """길이와 모양만 남긴다. 값은 안 남긴다."""
    return f"<{len(value)}자>"


def main(show_matches: bool = False) -> None:
    documents = sorted(documents_dir().glob("*.pdf"))
    if not documents:
        raise SystemExit(f"문서를 못 찾았다: {documents_dir()} — SPHINX_DATA_DIR 를 본다")

    print(f"공시 문서 후보 {len(documents)}건 · 넓은 패턴이 몇 번 걸리나\n")
    total: collections.Counter[str] = collections.Counter()
    parsed_ok = 0
    for path in documents:
        try:
            parsed = parsing.parse_upload(f"documents/{path.name}", product_type="ELS",
                                          document_id="probe", parsed_at=None)
        except Exception as exc:  # noqa: BLE001 — 한 건이 안 열려도 나머지를 재야 한다
            print(f"  ⚠ {path.name}: {type(exc).__name__} — 건너뛴다")
            continue
        parsed_ok += 1
        hits = broad_hits(checked_surface(parsed))
        total.update({name: len(found) for name, found in hits.items()})
        print(f"  {path.name[:46]:48} {({k: len(v) for k, v in hits.items()}) or '없음'}")
        for name, found in hits.items():
            # ❗**기본은 마스킹이다.** 이 도구의 전제가 «모집단이 임의의 ADMIN 업로드» 라,
            #   실제 업로드에 돌리면 진짜 카드번호·개인 이메일이 터미널·CI 로그·PR 본문으로
            #   나간다. `PiiDetected` 가 패턴 이름만 들고 다니는 이유와 같다(`#534` 리뷰).
            shown = found[:4] if show_matches else [_masked(x) for x in found[:4]]
            print(f"       {name}: {shown}")

    # ❗**「재서 0」과 「아무것도 못 쟀다」를 가른다.** 파싱이 전부 실패해도 아래 결론이
    #   정상처럼 인쇄되면, 보안 결정의 유일한 근거가 빈 측정 위에 선다(`#534` 리뷰).
    if parsed_ok == 0:
        raise SystemExit(
            f"❗파싱에 성공한 문서가 0건이다 ({len(documents)}건 시도). "
            f"SPHINX_DATA_DIR 가 {parsing.documents_root()} 를 가리키는지 본다 — "
            "이 도구의 결론을 인용하면 안 된다"
        )
    print(f"\n실제로 파싱된 문서 {parsed_ok}/{len(documents)}건 · 합계 {dict(total)}")
    # ❗**0 건은 판단 근거가 아니다.** 예전 판이 같은 0 을 「켤 근거」(CARD)와
    #   「끌 근거」(EMAIL) 양쪽으로 읽었다 — 다음 사람이 어느 규칙인지 알 수 없다
    #   (`#534` 리뷰, 오준서). 그래서 **완화의 이유를 세지 않고 적는다.**
    print("\n── 완화 목록과 그 이유 (0건이 근거가 아니다)")
    for name in sorted(pii.BROAD):
        relaxed = name in pii.RELAXED_IN_PUBLIC_DOCUMENT
        print(f"  {name:8} {'끔  ' if relaxed else '검사'} {RELAXED_BECAUSE.get(name, '실증된 오탐 없음 — 켜 둔다')}")

    print("\n── 법인 연락처 선지우기가 실제로 하는 일")
    for sample in CORPORATE_SAMPLES:
        raw = {name: len(found) for name, pattern in pii.BROAD.items()
               if (found := pattern.findall(sample))}
        after = pii.detect(sample, scope="public_document")
        print(f"  {sample:26} 지우기 전 {raw or '없음'} · 판정 {after or '통과'}")

    print("\n── ❗CARD 를 켜면 무엇이 죽나 (실증된 오탐 — 이 코퍼스엔 없다)")
    for sample in CARD_FALSE_POSITIVES:
        hit = bool(pii.BROAD["CARD"].search(sample))
        print(f"  {'걸린다' if hit else '안 걸린다'}  {sample}")
    print("  → 공백으로 나뉜 4자리 넷이면 전부 걸린다. pdfplumber 는 표 한 행을 그렇게 낸다.")


if __name__ == "__main__":
    main(show_matches="--show-matches" in sys.argv)
