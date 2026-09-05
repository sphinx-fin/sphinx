"""채점 임계값이 **한 파일에서만** 오고, 코드와 파일이 갈리지 않는다. 소유: 윤지석

## 왜 생겼나

임계값 여섯이 파이썬 상수로 흩어져 있었다. `gate_rules.yaml` 은 게이트 판정을 선언적으로
두는데 **채점 쪽은 코드를 열어야 알 수 있었다** — 심사에서 *"이 숫자는 왜 이 값입니까"* 에
답하려면 소스를 보여 줘야 했고, 튜닝이 코드 변경이었다.

## 이 파일이 잡는 것

    (가)  파일 ↔ 코드가 **양방향**으로 맞는다      한 방향만 보면 지워도·더해도 조용하다
    (나)  네 필드가 다 있다                       숫자만 남으면 왜인지가 사라진다
    (다)  모듈 상수가 파일 값과 같다               상수에 숫자를 다시 박으면 두 벌이 된다
    (라)  복창 캡 < R-05 임계값                    ❗파이썬 쪽에서도 부등호를 잰다
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest
import yaml

from app import misconception, scoring, thresholds

REPO = Path(__file__).resolve().parents[2]
GATE_RULES = REPO / "server" / "src" / "main" / "resources" / "gate_rules.yaml"
ECHO_CAP_TEST = (REPO / "server" / "src" / "test" / "java" / "com" / "sphinxfin"
                 / "sphinx" / "core" / "gate" / "EchoCapBelowR05Test.java")
BUILD_GRADLE = REPO / "server" / "build.gradle"
CI_YML = REPO / ".github" / "workflows" / "ci.yml"


# ── (가) 양방향 ──────────────────────────────────────────────────────────────
def test_the_file_and_the_code_agree_both_ways() -> None:
    """★ 파일에만 있는 것도, 코드에만 있는 것도 없다.

    한 방향만 보면 **파일에서 한 줄을 지워도, 안 쓰는 줄을 더해도 조용하다.**
    """
    raw = yaml.safe_load(thresholds.THRESHOLDS_PATH.read_text(encoding="utf-8"))
    assert set(raw["thresholds"]) == set(thresholds.REQUIRED)


@pytest.mark.parametrize("missing", sorted(thresholds.REQUIRED))
def test_removing_one_entry_is_refused(tmp_path, monkeypatch, missing) -> None:
    """★ 한 줄을 지우면 **로딩 시점에** 터진다."""
    raw = yaml.safe_load(thresholds.THRESHOLDS_PATH.read_text(encoding="utf-8"))
    del raw["thresholds"][missing]
    f = tmp_path / "t.yaml"
    f.write_text(yaml.safe_dump(raw, allow_unicode=True), encoding="utf-8")
    monkeypatch.setattr(thresholds, "THRESHOLDS_PATH", f)
    thresholds._load.cache_clear()
    with pytest.raises(thresholds.ThresholdError, match=missing):
        thresholds.get("ngram_match")


def test_an_unused_entry_is_refused(tmp_path, monkeypatch) -> None:
    """★ 코드가 안 읽는 값을 더하면 터진다 — **죽은 값이 설명처럼 남는다.**"""
    raw = yaml.safe_load(thresholds.THRESHOLDS_PATH.read_text(encoding="utf-8"))
    raw["thresholds"]["nobody_reads_this"] = {
        "value": 0.5, "used_by": "-", "reacts_to": "-", "why": "-"}
    f = tmp_path / "t.yaml"
    f.write_text(yaml.safe_dump(raw, allow_unicode=True), encoding="utf-8")
    monkeypatch.setattr(thresholds, "THRESHOLDS_PATH", f)
    thresholds._load.cache_clear()
    with pytest.raises(thresholds.ThresholdError, match="nobody_reads_this"):
        thresholds.get("ngram_match")


# ── (나) 네 필드 ─────────────────────────────────────────────────────────────
@pytest.mark.parametrize("field", thresholds.FIELDS)
@pytest.mark.parametrize("name", sorted(thresholds.REQUIRED))
def test_every_entry_states_all_four_fields(name, field) -> None:
    """★ `value` 만 코드가 읽지만 나머지 셋이 **파일로 뺀 이유**다."""
    raw = yaml.safe_load(thresholds.THRESHOLDS_PATH.read_text(encoding="utf-8"))
    assert raw["thresholds"][name].get(field), f"{name}: {field} 가 비었다"


def test_a_missing_rationale_is_refused(tmp_path, monkeypatch) -> None:
    """★ 근거를 빼면 터진다 — 숫자만 남으면 `gate_rules.yaml` 과 같은 물건이 못 된다."""
    raw = yaml.safe_load(thresholds.THRESHOLDS_PATH.read_text(encoding="utf-8"))
    del raw["thresholds"]["ngram_match"]["why"]
    f = tmp_path / "t.yaml"
    f.write_text(yaml.safe_dump(raw, allow_unicode=True), encoding="utf-8")
    monkeypatch.setattr(thresholds, "THRESHOLDS_PATH", f)
    thresholds._load.cache_clear()
    with pytest.raises(thresholds.ThresholdError, match="why"):
        thresholds.get("ngram_match")


# ── (다) 상수가 파일에서 온다 ────────────────────────────────────────────────
@pytest.mark.parametrize(("const", "name"), [
    (lambda: misconception.NGRAM_THRESHOLD, "ngram_match"),
    (lambda: misconception.REVIEW_THRESHOLD, "ngram_review"),
    (lambda: scoring.ECHO_THRESHOLD, "echo_match"),
    (lambda: scoring.ECHO_MARGIN_MIN, "echo_margin_min"),
    (lambda: scoring.ECHO_CONFIDENCE_CAP, "echo_confidence_cap"),
    (lambda: scoring.MAX_SCORING_ATTEMPTS, "max_scoring_attempts"),
], ids=lambda x: x if isinstance(x, str) else "")
def test_the_module_constant_comes_from_the_file(const, name) -> None:
    assert const() == thresholds.get(name)


def test_no_module_still_hardcodes_a_threshold() -> None:
    """★ 상수 줄에 **숫자 리터럴이 없다** — 박아 두면 파일을 고쳐도 안 따라온다.

    파일로 뺐다는 말이 참인지를 문면으로 잰다. 이 단정이 없으면 누가 값을 되돌려 박아도
    (다)가 통과한다 — 그 사람이 파일 값도 같이 바꿨을 것이기 때문이다.

    ❗**손목록으로 두지 않는다.** 이름을 하나씩 적으면 *"다음 임계값을 만드는 사람이 이
    목록을 모른 채 지나간다"* — 실제로 그렇게 캡이 셋이 되는 동안 이 목록은 하나만
    보고 있었다(`#370` 리뷰). 이름 **꼴**로 잡는다.
    """
    # 임계값처럼 생긴 상수 이름. 새 캡이 규칙만 지키면 자동으로 들어온다.
    #
    # ❗**이 파일이 소유하는 범위는 「채점」이다.** `_ATTEMPTS` 를 통째로 물면
    # `question_gen.MAX_ATTEMPTS`(질문 재생성)·`extraction.MAX_RESCUE_ATTEMPTS`(추출
    # 구제) 같은 **다른 기능의 손잡이**가 끌려온다. 그 값들은 채점 임계값이 아니라서
    # 여기 오면 안 되고, 억지로 넣으면 이 파일이 "채점"을 말한다는 사실이 흐려진다.
    SHAPE = re.compile(
        r"^(MAX_SCORING_ATTEMPTS"
        r"|[A-Z][A-Z0-9_]*(?:_CONFIDENCE_CAP|_THRESHOLD|_MARGIN_MIN|_MS|_MIN_CHARS))"
        r"\s*=\s*[0-9.]+\s*$"
    )
    assert SHAPE.match("PASTED_CONFIDENCE_CAP = 0.4"), (
        "★ 꼴 자체가 안 맞으면 아래 스캔이 0줄을 보고 조용히 통과한다"
    )

    app = Path(scoring.__file__).resolve().parent
    hard = [
        f"{path.name}: {line.strip()}"
        for path in sorted(app.glob("*.py"))
        for line in path.read_text(encoding="utf-8").splitlines()
        if SHAPE.match(line)
    ]
    assert not hard, (
        f"임계값을 코드에 다시 박았다: {hard} — scoring_thresholds.yaml 에 "
        f"value·used_by·reacts_to·why 를 적고 thresholds.get() 으로 읽는다"
    )


# ── (라) 복창 캡 < R-05 ──────────────────────────────────────────────────────
def test_the_echo_cap_still_trips_r05() -> None:
    """★ 파이썬 쪽에서도 부등호를 잰다.

    ❗**서버의 `EchoCapBelowR05Test` 와 같은 사실을 두 언어에서 센다.** 두 벌 같지만
    아니다 — 그쪽은 **Java 가 이 파일을 읽는 경로**가 사는지를 잰다(정규식이 낡으면 0건을
    검사하고 조용히 통과하는 것을 막는다). 여기서는 **값 자체**를 잰다. 파이썬 쪽만 고치고
    저쪽을 잊으면 저기서 빨개지고, 그 반대도 마찬가지다.

    모델 자기보고는 이 룰을 발동시킨 적이 없다(70건 실측: 1.0·0.9·0.7 셋뿐, 0.7 미만 0건).
    그래서 캡이 임계값 이상이면 R-05 는 **아무것도 안 잡는 룰**이 된다.
    """
    r05 = re.search(r"anyConfidenceBelow\s+([0-9.]+)",
                    GATE_RULES.read_text(encoding="utf-8"))
    assert r05, "gate_rules.yaml 에서 R-05 임계값을 못 읽었다"
    assert thresholds.get("echo_confidence_cap") < float(r05.group(1))


def test_the_server_side_check_points_at_this_file() -> None:
    """★ 서버 테스트가 **옮긴 자리**를 가리킨다 — 안 고치면 거기서 0건을 읽는다.

    `EchoCapBelowR05Test` 의 `read()` 는 못 읽으면 실패시키므로 조용히 통과하지는 않는다.
    다만 **빨개지는 자리가 남의 모듈**이라, 옮긴 쪽에서 같이 재 둔다.
    """
    java = ECHO_CAP_TEST.read_text(encoding="utf-8")
    assert thresholds.THRESHOLDS_PATH.name in java, (
        f"서버 대조가 아직 옛 자리를 읽는다 — {thresholds.THRESHOLDS_PATH.name} 를 가리켜야 한다")
    assert "scoring.py" not in java, "옛 경로가 남아 있다"


def _effective_lines(path: Path) -> list[str]:
    """판별에 **실제로 쓰이는** 줄만. 주석은 뺀다.

    ❗주석을 같이 세면 **내가 단 설명 한 줄이 검사를 만족시킨다.** 실제로 그랬다 —
    `ci.yml` 의 `server_extra` 를 옛 경로로 되돌렸는데도 초록이었고, 원인이 바로 위에
    적어 둔 주석이었다(변이 ⓗ).
    """
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("#") or stripped.startswith("//"):
            continue
        if "inputs.file(" in line or "server_extra=" in line:
            out.append(line)
    return out


@pytest.mark.parametrize(("path", "what"), [
    (BUILD_GRADLE, "gradle 이 이 파일을 test 태스크 입력으로 든다"),
    (CI_YML, "CI 의 server 잡 판별이 이 파일을 센다"),
])
def test_the_guard_actually_runs_when_only_this_file_changes(path: Path, what: str) -> None:
    r"""★ **값만 바꾼 PR 에서 서버 가드가 실제로 돈다** (`#368` 리뷰, 강희진).

    ❗이 PR 의 취지에 정면으로 걸리는 자리다. 값을 파일로 빼는 목적이 *"튜닝이 코드 변경이
    아니게"* 인데, **코드 변경이 아니게 되는 순간 가드가 꺼져 있었다.**

        server/build.gradle   inputs.file('../ai-service/app/scoring.py')
        ci.yml server_extra   ai-service/app/scoring\.py$

    둘 다 옛 경로였다. 실측(강희진): 캡을 R-05 위로 올려 두고 `./gradlew test` 가
    **UP-TO-DATE 로 초록**이었고, `--rerun-tasks` 를 줘야 빨개졌다. CI 는 server 잡이
    아예 안 뜬다.

    **세 자리를 손으로 맞추던 것을 여기서 잠근다** — Java 대조가 읽는 파일 · gradle 입력 ·
    CI 필터가 같은 파일을 가리켜야 한다. 하나만 낡으면 나머지는 조용하다.
    """
    stem = thresholds.THRESHOLDS_PATH.stem          # scoring_thresholds
    lines = _effective_lines(path)
    assert lines, f"{path.name}: 판별에 쓰이는 줄을 못 찾았다 — 이 검사가 0건을 잰다"
    joined = "\n".join(lines)
    assert stem in joined, (
        f"{path.name}: {stem} 를 안 든다 — {what}. 값만 바꾼 PR 에서 "
        f"EchoCapBelowR05Test 가 안 돈다(초록으로 통과한다). 본 줄: {lines}")
    # ❗정규식 이스케이프(`scoring\.py`)를 지우고 본다. 안 지우면 `app/scoring.py` 부분열
    # 검사가 ci.yml 에서 **빗나간다** — 변이가 실제로 걸렸는데 초록이었다.
    assert "app/scoring.py" not in joined.replace("\\", ""), (
        f"{path.name}: 옛 경로(app/scoring.py)가 남아 있다 — 값은 이제 그 파일에 없다")
