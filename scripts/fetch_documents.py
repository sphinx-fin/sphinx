"""데모 대상 문서 수집 (F-EXT-001 입력). 소유: 정세현

    python3 scripts/fetch_documents.py            # 등록된 문서 전부
    python3 scripts/fetch_documents.py els-4181   # 하나만

저장 위치는 `data/documents/` (git 추적). 받은 파일의 sha256을 찍는다 — 파싱 결과가 달라졌을 때
파서가 바뀐 건지 문서가 바뀐 건지 구분하려면 이게 있어야 한다(P2).

## 출처: DART 아님 (2026-08-24 확인)

DART 경로는 막혀 있다. `일괄신고추가서류`를 1년치(1,056건) 훑으면 전부 회사채이고, 발행사를
좁혀도 마찬가지다 — 미래에셋증권 4건은 첨부가 원리금지급대행계약서·신용평가서, 한국투자증권
2건은 본문이 "제34회 무기명식 이권부 무보증사채"다. 어느 건에도 간이투자설명서 첨부가 없다.
공모 ELS 간이투자설명서는 **금융투자협회 전자공시**에 있다:

    기타공시 > 파생결합증권등 청약정보 비교공시 > 청약중인상품
    https://dis.kofia.or.kr/websquare/index.jsp?w2xPath=/wq/etcann/DISDLSSubscribing.xml
      &divisionId=MDIS04007001000000&serviceId=SDIS04007001000

목록 표의 `간이투자설명서` 열이 PDF다. 다운로드 팝업이 실제로 때리는 엔드포인트가 아래
`_KOFIA_DOWNLOAD`이고, 필요한 세 값(`serverPath`·`serverFileNm`·`filename`)은 그 표에
`서버경로`·`파일명`·`원본문서파일명` 열로 그대로 노출돼 있다. 청약이 끝나면 목록에서 빠지므로
(`청약종료상품` 메뉴로 이동) 데모 문서는 받아서 보관한다.

## 변액보험: 자동 수집 불가 (수동 취득)

금투협에는 없다 — 좌측 `변액보험펀드`는 펀드 운용 공시이지 상품설명서가 아니다. 생명보험협회
공시실(pub.insure.or.kr)에 있고, 경로는:

    상품비교공시 > 변액보험 > 저축성 상품비교 > (해당 상품 행) > 상품운영정보 > 상품요약서

여기 메뉴·다운로드가 전부 `javascript:void(0)` 팝업이라 안정적인 URL이 없다. 그래서 이 문서는
`manual: True`로 두고 스크립트가 자동으로 받지 않는다 — 없는 URL을 지어내면 다음 사람이
왜 안 되는지부터 파야 한다. 사람이 받아서 `data/documents/`에 넣으면 sha256만 검증한다. 받은 문서는 레포에 커밋한다.

주의: 같은 행의 `공제금액 구분공시` PDF는 **파싱 불가**다. 2페이지 모두 텍스트가 벡터로
아웃라인 처리돼 `chars=0`이고 `images=0`이라 OCR할 이미지조차 없다. 사업비 근거는 상품요약서
본문(p10 계약체결·계약관리비용, p12 월공제액·해약환급금 예시)에서 뽑는다.
"""
import argparse
import hashlib
import pathlib
import shutil
import subprocess
import sys
import urllib.parse

OUT_DIR = pathlib.Path(__file__).resolve().parent.parent / "data" / "documents"

_KOFIA_DOWNLOAD = "https://disdown.kofia.or.kr/COMFSFileDownload.jsp"
_KOFIA_REFERER = "https://dis.kofia.or.kr/"
_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/140.0 Safari/537.36"
)

# 파서 대응은 데모 대상 2종 한정(역할분담표 v1.2, 타임박스). 범용 수집기가 아니다.
DOCUMENTS = {
    "els-4181": {
        "save_as": "els_kiwoom_4181_simple_prospectus.pdf",
        "product_type": "ELS",
        # 키움증권 제4181회 파생결합증권(주가연계증권)
        # [스텝다운] 3년/6개월/85-85-85-80-75-70/KI45, 연 11.00%, 최대손실률 -100%, 원화
        # 기초자산 S&P500·NIKKEI225·EuroStoxx50 — 지수 3종이라 F-SIM-001의 2008·2020
        # 시나리오가 성립한다(개별주 기초자산은 그 기간 시계열이 없다).
        "server_path": "/fsfile/report/receipt",
        "server_file": "e4d388e657fcc4ea-2bb6094f19fd7d2d21c-d20-20260821155336.pdf",
        "original_name": "간이투자설명서(ELS 4181).pdf",
        "sha256": "6b95fec7d5c8aee6e28a620bf569ee6c179e926bf5a365ddd00bab0209cd18eb",
    },
    "var-b2601": {
        "save_as": "var_samsung_b2601_product_summary.pdf",
        "product_type": "VARIABLE_INSURANCE",
        # 삼성생명 삼성 탄탄한 변액연금보험(B2601)(무배당)[최저연금보증형] 상품요약서 18p
        # 판매채널 방카슈랑스 — 기획서 데모가 창구 판매 상황이고, 오해 라이브러리
        # M01("은행에서 파니까 원금은 보장")·M05(저축성 오인)가 은행 창구 변액보험
        # 분쟁에서 나온 패턴이다. p12에 "보험은 은행의 저축과는 달리" 문면과
        # 해약환급금 예시표(3개월 환급률 58.4%, 20년 43.9%)가 있다.
        "manual": True,
        "source": "생명보험협회 공시실 > 상품비교공시 > 변액보험 > 저축성 상품비교 > 상품요약서",
        "sha256": "2e993c829820cf270bd6304ddaa5e9f64bb92fdc7ac685c6d799f8ec24e463ab",
    },
}


def _download(url):
    """curl로 받는다. Python urllib으로는 이 호스트에 붙지 못한다.

    `disdown.kofia.or.kr`가 리프 인증서만 보내고 중간 인증서(GlobalSign RSA OV SSL CA 2018)를
    체인에 넣지 않는다. curl은 AIA를 따라가 중간 인증서를 스스로 구해오지만 Python은 안 한다 —
    그래서 urllib는 "unable to get local issuer certificate"로 죽는다.

    검증은 끄지 않는다(`-k` 금지). 공시 문서를 받는 경로에서 인증서 검증을 빼면 받은 파일이
    진짜 금투협에서 온 것인지 보장할 수 없다. 서버 설정 문제를 우회하는 것과 검증을 포기하는
    것은 다르다.
    """
    if not shutil.which("curl"):
        raise RuntimeError("curl이 필요하다 (Python urllib은 이 호스트의 불완전한 인증서 체인을 못 넘는다)")
    proc = subprocess.run(
        ["curl", "-sS", "--fail", "--location", "--max-time", "60",
         "-A", _UA, "-e", _KOFIA_REFERER, url],
        capture_output=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"curl 실패 (exit {proc.returncode}): {proc.stderr.decode(errors='replace').strip()}")
    return proc.stdout


def kofia_url(doc):
    query = urllib.parse.urlencode({
        "serverPath": doc["server_path"],
        "serverFileNm": doc["server_file"],
        "filename": doc["original_name"],
    })
    return f"{_KOFIA_DOWNLOAD}?{query}"


def fetch(key, doc, force=False):
    dest = OUT_DIR / doc["save_as"]

    if doc.get("manual"):
        if dest.exists():
            print(f"[skip] {key}: 수동 취득 문서, 이미 있음 {dest.name} "
                  f"({dest.stat().st_size:,} bytes)")
            return _verify(key, doc, dest)
        print(f"[TODO] {key}: 수동으로 받아 {dest.relative_to(OUT_DIR.parent.parent)} 에 두어야 한다.\n"
              f"       {doc['source']}", file=sys.stderr)
        return False

    if dest.exists() and not force:
        print(f"[skip] {key}: 이미 있음 {dest.name} ({dest.stat().st_size:,} bytes)")
        return _verify(key, doc, dest)

    try:
        body = _download(kofia_url(doc))
    except RuntimeError as exc:
        print(f"[FAIL] {key}: {exc}", file=sys.stderr)
        return False

    # 이 엔드포인트는 실패해도 200에 HTML을 준다. 확장자·상태코드로는 못 걸러진다.
    if not body.startswith(b"%PDF"):
        print(f"[FAIL] {key}: PDF가 아니다 ({len(body):,} bytes). 청약종료로 내려갔을 수 있다 — "
              f"금투협 '청약종료상품' 목록에서 파일명을 다시 확인해야 한다.", file=sys.stderr)
        return False

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(body)
    print(f"[ok]   {key}: {dest.name} ({len(body):,} bytes)")
    return _verify(key, doc, dest)


def _verify(key, doc, dest):
    digest = hashlib.sha256(dest.read_bytes()).hexdigest()
    expected = doc.get("sha256")
    if expected and digest != expected:
        print(f"[WARN] {key}: sha256 불일치 — 문서가 교체됐다.\n"
              f"       기대 {expected}\n       실제 {digest}\n"
              f"       파싱 결과가 달라지면 파서가 아니라 문서가 원인이다.", file=sys.stderr)
        return False
    print(f"       sha256 {digest}")
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("keys", nargs="*", help=f"수집 대상 (기본: 전부). 선택지: {', '.join(DOCUMENTS)}")
    ap.add_argument("--force", action="store_true", help="이미 있어도 다시 받는다")
    args = ap.parse_args()

    keys = args.keys or list(DOCUMENTS)
    unknown = [k for k in keys if k not in DOCUMENTS]
    if unknown:
        ap.error(f"등록되지 않은 문서: {unknown}. 선택지: {list(DOCUMENTS)}")

    ok = all(fetch(k, DOCUMENTS[k], force=args.force) for k in keys)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
