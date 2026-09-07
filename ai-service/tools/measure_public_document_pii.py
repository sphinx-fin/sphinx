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

DOCUMENTS = pathlib.Path(__file__).resolve().parents[2] / "data" / "documents"


def broad_hits(text: str) -> dict[str, list[str]]:
    """`detect()` 와 같은 순서로 좁은 패턴을 지운 뒤 넓은 패턴을 센다."""
    residual = pii.PLACEHOLDER.sub("", text)
    for pattern in pii.SPECIFIC.values():
        residual = pattern.sub(" ", residual)
    return {name: found for name, pattern in pii.BROAD.items()
            if (found := pattern.findall(residual))}


def main() -> None:
    documents = sorted(DOCUMENTS.glob("*.pdf"))
    if not documents:
        raise SystemExit(f"문서를 못 찾았다: {DOCUMENTS} — data/documents 가 커밋돼 있어야 한다")

    print(f"커밋된 공시 문서 {len(documents)}건 · 넓은 패턴이 몇 번 걸리나\n")
    total: collections.Counter[str] = collections.Counter()
    for path in documents:
        try:
            parsed = parsing.parse_upload(f"documents/{path.name}", product_type="ELS",
                                          document_id="probe", parsed_at=None)
        except Exception as exc:  # noqa: BLE001 — 한 건이 안 열려도 나머지를 재야 한다
            print(f"  ⚠ {path.name}: {type(exc).__name__} — 건너뛴다")
            continue
        hits = broad_hits("\n".join(page["text"] for page in parsed["pages"]))
        total.update({name: len(found) for name, found in hits.items()})
        print(f"  {path.name[:46]:48} {({k: len(v) for k, v in hits.items()}) or '없음'}")
        for name, found in hits.items():
            # ❗원문 전체를 찍지 않는다. 걸린 조각만, 그리고 넉넉히 자른다.
            print(f"       {name}: {found[:4]}")

    print(f"\n합계 {dict(total)}")
    print(f"\n지금 끄는 것: {sorted(pii.RELAXED_IN_PUBLIC_DOCUMENT)}")
    unnecessary = sorted(pii.RELAXED_IN_PUBLIC_DOCUMENT - set(total))
    if unnecessary:
        print(f"  ❗{unnecessary} 는 이 코퍼스에서 한 번도 안 걸렸다 — 끌 이유가 아직 없다.")
        print("     표본이 작아 「없다」의 증거는 아니지만, 완화를 넓힐 때 근거로 쓰지 않는다.")
    still_checked = sorted(set(pii.BROAD) - pii.RELAXED_IN_PUBLIC_DOCUMENT)
    print(f"이 범위에서도 검사하는 것: {still_checked}")
    for name in still_checked:
        if name in total:
            print(f"  ❗{name} 이 정상 문서에서 {total[name]}건 걸린다 — 추출이 422 로 막힌다.")
            print("     완화에 넣을지 판단이 필요하다(막는 쪽이 P5 방향이지만 기능이 죽는다).")


if __name__ == "__main__":
    main()
