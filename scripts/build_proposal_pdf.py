#!/usr/bin/env python3
"""`docs/proposal.md` → 제출용 PDF. 소유: 정세현(scripts·docs) · 최초 작성: 윤지석

## 왜 있나

`docs/proposal.md` 머리말이 **"이 파일이 기획서 정본이다. 제출용 PDF 는 여기서 만든다"**
로 적어 뒀는데, 그 「만든다」의 수단이 레포에 없었다. 그래서 8/22 제출본 PDF 와 마크다운이
2주 넘게 갈렸고, 어긋난 지점을 **머리말에 손으로 적어 두는 것**으로 버티고 있었다.

    #473 에서 내가 센 것과 같은 모양이다 — 도구가 있고 아무 데도 안 걸려 있으면
    사람이 기억해야만 돌고, 기억은 갈린다.

## 쓰기

    python3 scripts/build_proposal_pdf.py                      # docs/proposal.pdf
    python3 scripts/build_proposal_pdf.py ~/Downloads/제출본.pdf

## ❗제출본에 «안» 들어가는 것

머리말 인용구(`> ...`)는 **레포 내부 메모**다 — 정본 선언·PDF 와의 차이 기록·이력.
심사자가 읽을 문서가 아니므로 걷어낸다. 이 스크립트가 그 경계다.

## 의존

`markdown`(표·중첩목록) 과 Chrome(HTML→PDF). 둘 다 없으면 **무엇이 없어서 멈췄는지**
말하고 죽는다 — 조용히 반쪽짜리를 내지 않는다.
"""
from __future__ import annotations

import base64
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "docs" / "proposal.md"
FONT = ROOT / "server" / "src" / "main" / "resources" / "fonts" / "Pretendard-Regular.ttf"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

#: 제출본에 반드시 있어야 하는 문면. 생성 뒤 PDF 에서 다시 읽어 대조한다.
#: ❗**한 절이라도 통째로 빠지면 조용히 짧은 PDF 가 나온다** — 쪽수만 보면 모른다.
MUST_HAVE = ("1. 서비스 명칭", "2. 아이디어 기획 핵심내용", "3. 문제 정의 및 제안 배경",
             "4. 서비스 컨셉 및 차별성", "5. 활용 데이터", "6. 기대 효과",
             "7. 구현 계획과 오용 방지 설계")
#: 제출본에 **있으면 안 되는** 문면(내부 메모). 없는 것만 재면 걷어내기가 헛돌아도 모른다.
MUST_NOT = ("이 파일이 기획서 정본이다", "PDF 와 어긋나는 지점")


def _require_markdown():
    try:
        import markdown  # noqa: F401
        return markdown
    except ModuleNotFoundError:
        sys.exit("FAIL: `markdown` 이 없다.  python3 -m pip install markdown")


def strip_internal_notes(raw: str) -> tuple[str, str]:
    """머리말 인용구를 걷고 제목·팀표를 헤더로 뽑는다. → (본문, 구성원)"""
    body = "\n".join(l for l in raw.splitlines() if not l.lstrip().startswith(">"))
    body = body.replace("# 2026 금융 AI Challenge 기획서 — 스FIN크스\n", "", 1)
    m = re.search(r"\| \| \|\n\|---\|---\|\n\| 팀명 \|.*\n\| 구성원 성명 \|(.*?)\|\n", body)
    members = m.group(1).strip() if m else "정세현, 윤지석, 오준서, 강희진"
    if m:
        body = body[:m.start()] + body[m.end():]
    body = body.replace("(\\* 필수항목)\n", "", 1).replace("\\*", "*")
    return body, members


TEMPLATE = """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<style>
@font-face {{ font-family: Pretendard; src: url(data:font/ttf;base64,{font}) format('truetype'); }}
@page {{ size: A4; margin: 18mm 16mm 16mm 16mm; }}
body {{ font-family: Pretendard, sans-serif; font-size: 9.3pt; line-height: 1.62; color:#111; margin:0; }}
.head {{ border:1px solid #333; margin-bottom:9mm; }}
.head .t {{ display:flex; border-bottom:1px solid #333; }}
.head .t b {{ width:22mm; padding:2.6mm 3mm; border-right:1px solid #333; background:#f2f2f2; font-weight:600; }}
.head .t span {{ padding:2.6mm 3mm; }}
h2 {{ font-size:12pt; margin:7.5mm 0 2.5mm; padding-bottom:1.4mm; border-bottom:1.6px solid #222; page-break-after:avoid; }}
h3 {{ font-size:10.2pt; margin:5mm 0 1.8mm; page-break-after:avoid; }}
h4 {{ font-size:9.6pt; margin:3.6mm 0 1.4mm; color:#333; page-break-after:avoid; }}
p, li {{ margin:1.1mm 0; }}
ul {{ margin:1.2mm 0; padding-left:5.2mm; }}
li > ul {{ margin:0.6mm 0; }}
table {{ border-collapse:collapse; width:100%; margin:2.4mm 0 3mm; font-size:8.6pt; page-break-inside:avoid; }}
th, td {{ border:0.7px solid #999; padding:1.6mm 2.2mm; text-align:left; vertical-align:top; }}
th {{ background:#f4f4f4; font-weight:600; }}
code {{ font-family:inherit; background:#f0f0f0; padding:0 0.8mm; border-radius:1px; }}
hr {{ border:0; border-top:0.7px solid #ccc; margin:5mm 0; }}
h2, h3, table, li {{ orphans:2; widows:2; }}
.note {{ font-size:8.6pt; color:#444; margin:0 0 6mm; }}
</style></head><body>
<div class="head">
  <div class="t"><b>첨부 1</b><span>2026 금융 AI Challenge 기획서</span></div>
  <div class="t"><b>팀명</b><span>스FIN크스</span></div>
  <div class="t" style="border-bottom:none"><b>구성원 성명</b><span>{members}</span></div>
</div>
<p class="note">( * 필수항목)</p>
{body}
</body></html>"""


def verify(pdf: pathlib.Path) -> None:
    """생성물을 다시 읽어 대조한다. ❗**만든 것과 맞는 것은 다르다.**"""
    try:
        import pdfplumber
    except ModuleNotFoundError:
        print("  ⚠ 검산 안 함 — pdfplumber 가 없다(`0건`이 아니라 `모른다`다).")
        return
    with pdfplumber.open(pdf) as doc:
        text = "\n".join((p.extract_text() or "") for p in doc.pages)
        pages = len(doc.pages)
    missing = [s for s in MUST_HAVE if s not in text]
    leaked = [s for s in MUST_NOT if s in text]
    if missing:
        sys.exit(f"FAIL: 절이 빠졌다 {missing} — 쪽수만 보면 모른다")
    if leaked:
        sys.exit(f"FAIL: 내부 메모가 제출본에 실렸다 {leaked}")
    print(f"  검산 ✅ 절 {len(MUST_HAVE)}/{len(MUST_HAVE)} · 내부 메모 0건 · {pages}쪽 · {len(text):,}자")


def main() -> int:
    md = _require_markdown()
    if not pathlib.Path(CHROME).exists():
        sys.exit(f"FAIL: Chrome 이 없다 — {CHROME}")
    if not FONT.exists():
        sys.exit(f"FAIL: 폰트가 없다 — {FONT.relative_to(ROOT)}")

    out = pathlib.Path(sys.argv[1]).expanduser() if len(sys.argv) > 1 else ROOT / "docs" / "proposal.pdf"
    body, members = strip_internal_notes(SRC.read_text(encoding="utf-8"))
    html = TEMPLATE.format(
        font=base64.b64encode(FONT.read_bytes()).decode(),
        members=members,
        body=md.markdown(body, extensions=["tables", "sane_lists"]),
    )
    tmp = out.with_suffix(".build.html")
    tmp.write_text(html, encoding="utf-8")
    try:
        subprocess.run([CHROME, "--headless", "--disable-gpu", "--no-pdf-header-footer",
                        f"--print-to-pdf={out}", tmp.as_uri()], check=True, capture_output=True)
    finally:
        tmp.unlink(missing_ok=True)
    print(f"  → {out}")
    verify(out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
