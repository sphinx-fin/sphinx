"""평가 실행이 자기일관성 재질의를 **순차로** 돌린다 (이슈 #437 · PR #447). 소유: 정세현

## 왜 이 그물이 필요한가

`#447` 이 재질의를 첫 채점과 동시에 던져 통과 답변을 4.5초 → 2.1초로 줄였다. 그 대가는
**투기 호출**이고, 두 경로의 셈이 정반대다.

    데모      대부분 U1  → 거의 다 적중. 아끼는 것은 벽시계
    평가      U1 30%     → ❗70% 가 낭비. 아끼는 것은 쿼터 (70건 회차 실측 91 → 140회, 1.54배)

`run_scoring.py` 가 임포트 시점에 끈다. 그물이 두 겹인 이유가 있다.

**꺼져 있다**는 결과는 임포트해서 값을 읽으면 바로 보인다. 하지만 **끄는 줄이 임포트보다
앞에 있다**는 사실은 그 방법으로 안 보인다 — 오늘의 `run_scoring` 은 줄을 뒤로 옮겨도
여전히 꺼진 값이 나오기 때문이다(`scoring` 은 임계값을 `settings()` 가 아니라
`scoring_thresholds.yaml` 에서 읽고, 임포트만으로는 `settings()` 캐시가 안 굳는다 —
#449 리뷰에서 윤지석이 변이로 보였다). 그래서 자리는 **줄 번호로** 따로 잠근다.

자리가 밀린 채로 임포트 사슬 중 누가 `settings()` 를 한 번 부르는 날이 오면, 그때 나는
증상은 *"쿼터가 예상의 1.5배로 나갔다"* 뿐이라 회차가 끝난 뒤에나 안다.

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
        "평가 실행이 투기 호출을 켠 채로 돈다 — 70건 회차에서 호출이 91 → 140회가 된다")


def test_the_switch_is_set_before_the_first_app_import() -> None:
    """★ 끄는 줄이 **첫 `app` 임포트보다 앞에** 있다.

    위 테스트가 못 보는 자리다 — 오늘은 줄을 뒤로 옮겨도 값이 그대로 꺼져 나온다.
    #449 리뷰(윤지석)의 변이 ⓐ 가 그것을 보였고, 이 대조가 그 변이를 잡는다.
    """
    src = (ROOT / "eval" / "tools" / "run_scoring.py").read_text(encoding="utf-8").splitlines()
    setd = next(i for i, l in enumerate(src)
                if "SPHINX_PARALLEL_CONSISTENCY" in l and "setdefault" in l)
    first_app = next(i for i, l in enumerate(src)
                     if l.startswith(("from app", "import app")))
    assert setd < first_app, (
        f"끄는 줄(L{setd + 1})이 첫 app 임포트(L{first_app + 1})보다 뒤에 있다 — "
        "임포트 사슬이 settings() 를 부르는 날 조용히 안 먹는다")


def test_an_empty_value_is_not_unset() -> None:
    """❗**빈 문자열은 미설정이 아니다.** `setdefault` 가 안 덮으므로 병렬이 켜진 채로 돈다.

    이 레포가 이미 겪은 함정이다 — `SimulatorProperties` 주석: *"Spring 의 `${VAR:기본값}` 은
    환경변수가 빈 문자열로 존재하면 그것을 값으로 취급해 기본값이 죽는다"*.

    ❗**단, `.env` 로는 안 걸린다.** `setdefault` 가 먼저 키를 박고 `config._load_env_files()`
    는 `load_dotenv(path, override=False)` 라 이미 있는 키를 안 덮는다 — 걸리는 것은 배포
    스크립트나 셸이 `SPHINX_PARALLEL_CONSISTENCY=` 를 **프로세스 환경변수로** 내보낼 때다
    (#449 리뷰, 윤지석).

    **고치라는 것이 아니라 사실을 고정한다** — 끄고 싶으면 `0` 을 명시한다. `#122` 가
    *".env.example 에 빈 값을 적어두지 않는다" 를 규약으로 세운 것과 같은 자리다.
    """
    assert _probe({"SPHINX_PARALLEL_CONSISTENCY": ""}) == "True"


def test_an_explicit_value_still_wins() -> None:
    """❗`setdefault` 라 명시값이 이긴다 — 병렬 경로를 평가로 재 보는 길을 막지 않는다."""
    assert _probe({"SPHINX_PARALLEL_CONSISTENCY": "1"}) == "True"
