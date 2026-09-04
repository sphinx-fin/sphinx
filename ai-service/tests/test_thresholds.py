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
    """
    names = ("NGRAM_THRESHOLD", "REVIEW_THRESHOLD", "ECHO_THRESHOLD",
             "ECHO_MARGIN_MIN", "ECHO_CONFIDENCE_CAP", "MAX_SCORING_ATTEMPTS")
    app = Path(scoring.__file__).resolve().parent
    hard = []
    for path in sorted(app.glob("*.py")):
        for line in path.read_text(encoding="utf-8").splitlines():
            for n in names:
                if re.match(rf"^{n}\s*=\s*[0-9.]+\s*$", line):
                    hard.append(f"{path.name}: {line.strip()}")
    assert not hard, f"임계값을 코드에 다시 박았다: {hard}"


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
