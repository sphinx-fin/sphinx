"""평가 실행이 자기일관성 재질의를 **순차로** 돌린다 (이슈 #437 · PR #447). 소유: 정세현

## 왜 이 그물이 필요한가

`#447` 이 재질의를 첫 채점과 동시에 던져 통과 답변을 4.5초 → 2.1초로 줄였다. 그 대가는
**투기 호출**이고, 두 경로의 셈이 정반대다.

    데모      대부분 U1  → 거의 다 적중. 아끼는 것은 벽시계
    평가      U1 30%     → ❗70% 가 낭비. 아끼는 것은 쿼터 (70건이면 70 → 119회)

`run_scoring.py` 가 임포트 시점에 끄는데, **끄는 자리가 임포트보다 뒤로 밀리면 조용히
안 먹는다** — `config.settings()` 가 `@lru_cache` 이고 `scoring` 이 모듈 로드 때 임계값을
읽기 때문이다. 그때 나는 증상은 *"쿼터가 예상의 1.7배로 나갔다"* 뿐이라 사후에나 안다.

❗**끄는 것이 판정을 바꾸지 않는다**는 것도 같이 잠근다. 그게 이 스위치의 성립 조건이다 —
병렬은 호출 타이밍만 바꾸고 등급·확신도·캡 규칙을 안 바꾼다. 판정이 바뀐다면 여기서 끄는
순간 *"평가한 것과 서비스하는 것이 다르다"* 가 되어 스위치를 못 쓴다.
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

#: 별도 프로세스로 잰다 — `@lru_cache` 된 `settings()` 는 한 번 굳으면 이 프로세스에서 못 되돌린다.
PROBE = """
import sys; sys.path.insert(0, {tools!r})
import run_scoring                      # noqa: F401 — 임포트 부작용이 이 테스트의 대상이다
from app.config import settings
print(settings().parallel_consistency)
"""


def _probe(env: dict[str, str]) -> str:
    out = subprocess.run(
        [sys.executable, "-c", PROBE.format(tools=str(ROOT / "eval" / "tools"))],
        capture_output=True, text=True, cwd=ROOT,
        env={**os.environ, **env},
    )
    assert out.returncode == 0, out.stderr
    return out.stdout.strip()


def test_the_eval_runner_turns_parallel_consistency_off() -> None:
    """★ `run_scoring` 을 임포트하면 병렬이 꺼진다 — 아무 설정도 안 했을 때."""
    env = {k: v for k, v in os.environ.items() if k != "SPHINX_PARALLEL_CONSISTENCY"}
    got = subprocess.run(
        [sys.executable, "-c", PROBE.format(tools=str(ROOT / "eval" / "tools"))],
        capture_output=True, text=True, cwd=ROOT, env=env,
    )
    assert got.returncode == 0, got.stderr
    assert got.stdout.strip() == "False", (
        "평가 실행이 투기 호출을 켠 채로 돈다 — 70건 회차에서 호출이 70 → 119회가 된다. "
        "끄는 줄이 임포트보다 뒤로 밀렸는지 본다(settings() 는 @lru_cache 다)")


def test_an_empty_value_is_not_unset() -> None:
    """❗**빈 문자열은 미설정이 아니다.** `setdefault` 가 안 덮으므로 병렬이 켜진 채로 돈다.

    이 레포가 이미 겪은 함정이다 — `SimulatorProperties` 주석: *"Spring 의 `${VAR:기본값}` 은
    환경변수가 빈 문자열로 존재하면 그것을 값으로 취급해 기본값이 죽는다"*. `.env` 나 배포
    스크립트에 `SPHINX_PARALLEL_CONSISTENCY=` 를 적어 두면 여기서도 같은 일이 난다.

    **고치라는 것이 아니라 사실을 고정한다** — 끄고 싶으면 `0` 을 명시한다. `#122` 가
    *".env.example 에 빈 값을 적어두지 않는다" 를 규약으로 세운 것과 같은 자리다.
    """
    assert _probe({"SPHINX_PARALLEL_CONSISTENCY": ""}) == "True"


def test_an_explicit_value_still_wins() -> None:
    """❗`setdefault` 라 명시값이 이긴다 — 병렬 경로를 평가로 재 보는 길을 막지 않는다."""
    assert _probe({"SPHINX_PARALLEL_CONSISTENCY": "1"}) == "True"
