"""내 텍스트 자산이 **NFC 로만 저장된다** (PR #410 리뷰 후속 · 결정 10.2.1 · 10.74).

## 왜 — 지금 NFC 인 것은 **사실이지 보장이 아니다**

한글은 완성형(NFC)과 조합형(NFD)이 **눈으로 같고 바이트가 다르다.** 그리고 `#410` 으로
DB 가 MySQL 로 가면서 evidence 가 **영속**된다 — append-only 라 한 번 굳으면 못 고친다.

`#410` 리뷰에서 실제로 넣어 봤다.

    NFC 입력 → 저장될 utterance_quote: NFC
    NFD 입력 → 저장될 utterance_quote: NFD ❗  (len 6 → 15)

**내 `scoring._canonical()` 은 대조만 통과시키고 저장 형태를 안 바꾼다** — 결정 10.2.1 이
*"그때까지 유일한 방어선이라 지우면 안 된다"* 고 적어 둔 그 자리이고, 그것이 막는 것은
**대조**이지 **저장**이 아니다.

정본 경계(`AiServiceClient.score()` 안, 10.2.1 확정)는 아직 구현이 0건이다(10.74 의 1번,
`#410` 이 그걸 안 지키고 넘었고 나도 동의했다). 그러면 **상류가 형태를 보장하기 전까지
내 자산이 NFD 를 흘리지 않는 것**이 내가 할 수 있는 일이다.

## 무엇이 실패인가 — 「틀린 기록」이 아니라 「형태가 섞인 기록」

`#327` 이 실패 양식을 정정했다. 체인 검증도 리포트 재현 대조도 **성립한다**(같은 바이트를
다시 읽으니까). 깨지는 것은 **교차 검증**이다 — 같은 라벨이 환경마다 다른 바이트가 되면
같은 판정이 다른 `contentHash` 를 낸다. 그리고 **트리거는 파일 DB 가 아니라 NFD 로 저장하는
편집기**다(맥 IME·일부 편집기). 즉 **누가 맥에서 dev set 한 줄을 고치는 날** 들어온다.

증상이 *"테스트 초록 + 감사 시점 교차검증 실패"* 라 **미리 잠그는 것 말고 방법이 없다.**

## 범위 — 내 소유만

`data/`·`eval/data/` 는 정세현 소유라 여기서 안 본다(`#410` 리뷰에서 같은 걸 걸지 물었다).
여기가 잠그는 것은 **내가 고치는 파일**이다.
"""
from __future__ import annotations

import unicodedata
from pathlib import Path

import pytest

APP = Path(__file__).resolve().parents[1] / "app"
TESTS = Path(__file__).resolve().parent

#: 모집단을 **디렉토리에서 뽑는다.** 손목록이면 새 루브릭·새 프롬프트가 조용히 빠진다
#: (결정 9.35 와 같은 이유 — 이 파일 전체가 그 관용구다).
ASSET_GLOBS = (
    (APP / "rubrics", "*.yaml"),
    (APP / "prompts", "*.md"),
    (APP / "templates", "*.yaml"),
    (TESTS / "fixtures", "**/*"),
)


def _assets() -> list[Path]:
    out: list[Path] = []
    for root, pattern in ASSET_GLOBS:
        out += [p for p in root.glob(pattern) if p.is_file()]
    return sorted(out)


def test_the_population_is_not_empty() -> None:
    """❗**양성 대조.** 모집단이 비면 아래 파라미터화가 **0 회 돌고 조용히 통과한다.**

    경로 규약이 바뀌거나 디렉토리가 옮겨지면 여기서 먼저 실패한다 — 그때 글롭을 고친다.
    숫자는 *"이 정도는 있어야 한다"* 이지 정확한 개수가 아니다(자산이 늘 때마다 고치는
    상수는 그물이 아니라 잡일이다).
    """
    found = _assets()
    assert len(found) >= 20, (
        f"자산을 {len(found)}개밖에 못 찾았다 — 글롭이 낡았다. 찾은 것: "
        f"{[str(p.relative_to(APP.parent)) for p in found][:10]}")
    kinds = {p.suffix for p in found}
    assert {".yaml", ".md"} <= kinds, f"루브릭·프롬프트를 못 찾았다: {sorted(kinds)}"


@pytest.mark.parametrize("path", _assets(), ids=lambda p: str(p.name))
def test_the_asset_is_nfc(path: Path) -> None:
    """★ 파일 전체가 NFC 다.

    실패하면 **파일을 NFC 로 다시 저장한다**(내용을 바꾸는 게 아니라 형태만 바꾼다).

        python -c "import pathlib,unicodedata as u; p=pathlib.Path('<파일>'); \\
                   p.write_text(u.normalize('NFC', p.read_text(encoding='utf-8')), encoding='utf-8')"

    ❗**형태만 바뀌므로 리뷰 디프가 비어 보인다.** 그게 정상이고, 그래서 이 검사가 필요하다 —
    사람 눈으로는 안 보이는 변경이다.
    """
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        # 이진 자산(PDF 등)은 정규화 대상이 아니다. ❗`pytest.skip` 을 쓰지 않는다 —
        # `ci.yml` 의 `no_skip.py` 가 skip 을 **실패로** 바꾸므로(허용 목록 없음)
        # 정상 상태가 CI 빨강이 된다. `return` 으로 빠진다.
        return
    normalized = unicodedata.normalize("NFC", text)
    if text == normalized:
        return
    where = next(
        (i for i, (a, b) in enumerate(zip(text, normalized)) if a != b), min(len(text), len(normalized))
    )
    assert text == normalized, (
        f"{path.name} 이 NFC 가 아니다 (첫 차이 {where}번째 문자 근처: {text[max(0,where-12):where+12]!r}). "
        "한글 조합형이 섞이면 **눈으로는 같은데 바이트가 다르다** — 그대로 evidence 에 "
        "저장되면 같은 판정이 환경마다 다른 contentHash 를 낸다(결정 10.2.1 · 10.74). "
        "파일을 NFC 로 다시 저장한다 — docstring 참조"
    )
