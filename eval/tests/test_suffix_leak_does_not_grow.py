"""종결어미 누설이 **지금보다 나빠지면** 빨개진다 (이슈 #377). 소유: 윤지석

`test_suffix_leak.py`(정세현, `#387`)가 잠그는 것은 **프로토콜의 성질**이다 — 행 순서
무관 · LOO 없으면 값이 커진다 · 만장일치는 구조적 100% · 기준선이 4단계 최빈.
그 판단은 맞다. **퍼센트를 박으면 표본이 바뀔 때마다 고쳐야 하고 그러면 그 파일이 낡는다.**

## ❗그래서 누설 자체는 아무도 안 막고 있었다

지금 값이 기준선의 1.6배인데, 표본을 늘리다 2.0배가 돼도 **아무 데도 안 걸린다.**
`#354`(위치 누설)를 고칠 때 세운 규칙이 *"다음에 표본을 늘릴 때 같은 누설이 다시 들어오면
그때 빨개진다"* 였고, 그 자리가 비어 있었다.

## 퍼센트가 아니라 **배수**를 잠근다

    누설 정확도 / 기준선

기준선이 같이 움직이므로 **표본이 바뀌어도 이 비율은 뜻이 유지된다.** 라벨 분포가 바뀌어
기준선이 오르면 누설도 같이 올라야 배수가 유지되는데, 그건 *"형태가 라벨을 흘리는 정도"*가
그대로라는 뜻이다 — 우리가 재려던 것이 그것이다.

    2026-09-05 실측 (끝 6자 · LOO · 백오프)

        강희진           기준선 0.314 · 누설 0.514 · 배수 **1.64**
        정세현           기준선 0.343 · 누설 0.543 · 배수 **1.58**
        dev set (윤지석)  기준선 0.559 · 누설 0.647 · 배수 **1.16**

## 상한을 여유 있게 둔 이유

**이 그물은 「좋아져라」가 아니라 「나빠지지 마라」다.** 표본을 손질하면(이슈 #377 의 ②,
`eval/` 소유는 정세현) 배수가 내려가고, 그때 상한을 같이 내리는 것이 맞다. 지금 값에 딱
붙여 두면 **정상적인 표본 확장에서도 빨개져** 사람이 상한을 올리는 습관이 든다 — 그러면
그물이 아니라 잡음이 된다.

`#388` 이 세운 규칙(*"숫자 옆에 재현 경로를 둔다"*)을 그대로 쓴다: 이 파일은 정본 도구
`eval/tools/measure_suffix_leak.py` 의 함수를 직접 부르고, 실패 메시지가 그 이름을 든다.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "eval" / "tools" / "measure_suffix_leak.py"

#: 끝에서 몇 자를 보는가. 정본 도구의 표가 6자를 대표값으로 쓴다.
SUFFIX_CHARS = 6

#: 배수 상한. 위 실측(1.16~1.64)에 여유를 둔다 — 근거는 모듈 docstring.
MAX_RATIO = 1.80

#: ❗**모집단 크기 단정**. 표가 비면 아래 루프가 0 회 돌고 조용히 통과한다
#: (`{} == set(())` 로 물린 전례가 `#396` 에 있다).
MIN_TABLES = 3


def _load():
    spec = importlib.util.spec_from_file_location("measure_suffix_leak", TOOL)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


leak = _load()


def _tables() -> dict[str, list[tuple[str, str]]]:
    return {**leak._rows_from_corpus(), **leak._rows_from_devset()}


def test_the_tables_are_actually_there() -> None:
    """★ 양성 대조 — 표가 비면 아래 검사가 아무것도 안 재고 초록이다."""
    tables = _tables()
    assert len(tables) >= MIN_TABLES, (
        f"누설을 잴 표가 {len(tables)}개뿐이다(기대 {MIN_TABLES} 이상) — "
        f"라벨·dev set 이 제자리에 있는지 본다. 정본 도구: {TOOL.relative_to(ROOT)}")
    for name, rows in tables.items():
        assert rows, f"{name}: 행이 0 개다"


@pytest.mark.parametrize("name", sorted(_tables()))
def test_the_suffix_leak_has_not_grown(name: str) -> None:
    """★ 형태만 보는 분류기가 기준선의 `MAX_RATIO` 배를 넘으면 실패.

    넘었다는 것은 **표본이 종결어미로 라벨을 더 흘리게 됐다**는 뜻이다. 표본을 늘리거나
    다시 쓸 때 이 그물이 그 순간에 빨개진다 — `#354` 가 위치 누설에서 세운 규칙과 같다.
    """
    rows = _tables()[name]
    _, base = leak.baseline(rows)
    acc = leak.suffix_accuracy(rows, SUFFIX_CHARS)
    ratio = acc / base

    assert ratio <= MAX_RATIO, (
        f"{name}: 종결어미 누설이 기준선의 {ratio:.2f}배다 (상한 {MAX_RATIO}). "
        f"기준선 {base:.3f} · 끝 {SUFFIX_CHARS}자 {acc:.3f}.\n"
        f"표본이 형태로 라벨을 흘린다 — 내용을 안 보는 분류기가 이만큼 맞힌다는 뜻이다.\n"
        f"재현: python {TOOL.relative_to(ROOT)}"
        + ("  --devset" if "dev set" in name else "")
        + "\n표본을 손질하는 쪽이 답이다(이슈 #377 ②) — 상한을 올리는 것이 아니다.")


def test_the_ratio_is_not_trivially_one(  # noqa: D401
) -> None:
    """★ 이 그물이 **실제로 누설을 재고 있다**는 양성 대조.

    지금 표본은 누설이 있다(1.16~1.64배). 만약 모든 표가 정확히 1.0 이면 `baseline` 과
    `suffix_accuracy` 가 같은 것을 계산하고 있다는 뜻이고, 그러면 위 단정은 영원히
    통과한다 — `#396` 에서 겪은 「빈 모집단이 단정을 참으로 만든다」의 다른 얼굴이다.
    """
    ratios = []
    for rows in _tables().values():
        _, base = leak.baseline(rows)
        ratios.append(leak.suffix_accuracy(rows, SUFFIX_CHARS) / base)
    assert max(ratios) > 1.0, (
        f"모든 표의 배수가 1.0 이하다 {[f'{r:.2f}' for r in ratios]} — "
        "두 계산이 같은 것을 재고 있는지 확인한다. 누설이 정말 0 이면 "
        "이 파일과 이슈 #377 을 같이 닫는다")
