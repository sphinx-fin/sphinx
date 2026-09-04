"""채점 임계값 로더. 값은 `scoring_thresholds.yaml` 하나에서만 온다. 소유: 윤지석

## 왜 파일로 뺐나

임계값 여섯이 파이썬 상수로 흩어져 있었다. `gate_rules.yaml` 은 게이트 판정을 선언적으로
두는데 **채점 쪽은 코드를 열어야 알 수 있었다** — 심사에서 *"이 숫자는 왜 이 값입니까"* 에
답하려면 소스를 보여 줘야 했고, 튜닝이 코드 변경이었다.

루브릭을 공개 의무로 두고 `u1_requires` 를 파일에 적게 한 것(`#366`)과 같은 층이다.

## 두 방향으로 대조한다 — 선언과 강제가 갈리지 않게

    파일에 있는데 코드가 안 쓴다   →  죽은 값이 설명처럼 남는다
    코드가 쓰는데 파일에 없다      →  로딩 시점에 터진다

`REQUIRED` 가 코드 쪽 목록이고 로더가 양방향으로 센다. 한 방향만 보면 **파일에서 한 줄을
지워도, 안 쓰는 줄을 더해도 조용하다.**

## 값을 옮길 때 같이 볼 것

`echo_confidence_cap` 은 **서버 테스트가 이 파일을 읽는다**(`EchoCapBelowR05Test`).
게이트 R-05 를 실제로 발동시키는 유일한 값이라, 올리면 그 룰이 아무것도 안 잡는 상태가
된다. 이 파일의 `why` 에 적어 뒀다.
"""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

import yaml

THRESHOLDS_PATH = Path(__file__).resolve().parent / "scoring_thresholds.yaml"

#: 각 항목이 반드시 가져야 하는 필드. `value` 만 코드가 읽지만 나머지 셋이 없으면
#: **숫자만 남고 왜인지가 사라진다** — 그러면 파일로 뺀 이유가 없어진다.
FIELDS = ("value", "used_by", "reacts_to", "why")

#: 코드가 실제로 요구하는 id. 파일과 **양방향으로** 대조한다.
REQUIRED = frozenset({
    "ngram_match", "ngram_review",
    "echo_match", "echo_margin_min", "echo_confidence_cap",
    "max_scoring_attempts",
})


class ThresholdError(ValueError):
    """임계값 파일이 규약을 어겼다. 로딩 시점에 터진다 — 조용한 기본값이 없다."""


@lru_cache(maxsize=1)
def _load() -> dict[str, float | int]:
    raw = yaml.safe_load(THRESHOLDS_PATH.read_text(encoding="utf-8")) or {}
    entries = raw.get("thresholds")
    if not isinstance(entries, dict):
        raise ThresholdError(f"{THRESHOLDS_PATH.name}: thresholds 매핑이 없다")

    out: dict[str, float | int] = {}
    for name, body in entries.items():
        if not isinstance(body, dict):
            raise ThresholdError(f"{name}: 매핑이어야 한다 — 받은 값 {body!r}")
        missing = [f for f in FIELDS if not body.get(f)]
        if missing:
            raise ThresholdError(
                f"{name}: 필드 누락 {missing}. 넷은 전부 필수다 — 숫자만 남으면 "
                "왜 이 값인지가 사라지고 파일로 뺀 이유가 없어진다"
            )
        value = body["value"]
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ThresholdError(f"{name}: value 는 숫자여야 한다 — 받은 값 {value!r}")
        out[name] = value

    unknown = sorted(set(out) - REQUIRED)
    if unknown:
        raise ThresholdError(
            f"코드가 안 읽는 임계값이 있다: {unknown} — 죽은 값이 설명처럼 남는다. "
            "쓰려면 thresholds.REQUIRED 에 넣고, 아니면 파일에서 지운다"
        )
    absent = sorted(REQUIRED - set(out))
    if absent:
        raise ThresholdError(
            f"코드가 요구하는 임계값이 파일에 없다: {absent} — "
            f"{THRESHOLDS_PATH.name} 에 value·used_by·reacts_to·why 를 적는다"
        )
    return out


def get(name: str) -> float | int:
    """임계값 하나. 없는 이름은 조용히 넘어가지 않는다."""
    values = _load()
    if name not in values:
        raise ThresholdError(f"모르는 임계값: {name!r} — 아는 것 {sorted(values)}")
    return values[name]
