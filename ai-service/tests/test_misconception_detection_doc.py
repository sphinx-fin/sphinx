"""`tools/MISCONCEPTION-DETECTION.md` 가 실물과 갈리지 않는지 본다.

이 문서는 문턱 값(`0.62`)과 재현 명령을 **본문에 박아** 놓았다. 그러면 상수나 파일명이
바뀔 때 문서가 조용히 낡는다 — 그리고 이 문서의 값은 *숫자 옆에 조건이 있다* 는 것
자체이므로, 낡은 조건은 없는 조건보다 나쁘다(`#385` 리뷰).

`#368` 이 세운 세 층 중 **「일관성」**(두 자리가 같은 것을 말하는가)에 해당한다.
「정확성」(그 값이 옳은가)은 `tools/tune_ngram_threshold.py` 가 재고,
「동작」(도구가 실제로 도는가)은 그 도구를 직접 돌려서 본다.
"""

from __future__ import annotations

import ast
import re
from pathlib import Path

import pytest

from app.misconception import NGRAM_THRESHOLD

TOOLS = Path(__file__).resolve().parents[1] / "tools"
DOC = TOOLS / "MISCONCEPTION-DETECTION.md"
APP = Path(__file__).resolve().parents[1] / "app"


def _effective_lines(markdown: str) -> str:
    """판별에 실제로 쓰이는 줄만 남긴다.

    `#368` 에서 **내가 방금 단 주석이 검사를 만족시켜** 검사가 초록인 채로 배선이 꺼져
    있었다. 여기서는 「어디서 나왔나」를 설명하는 **인용 블록**이 낡은 숫자를 일부러
    들고 있으므로, 그 줄이 대조를 만족시키면 정정이 되돌아가도 안 잡힌다.
    """
    return "\n".join(
        line for line in markdown.splitlines() if not line.lstrip().startswith(">")
    )


def importers_of(needle: str, root: Path) -> list[Path]:
    """`root` 아래에서 이름에 `needle` 이 든 모듈을 **import 하는** 파일들.

    원문 grep 이 아니라 `ast` 로 import 문만 본다 — 주석에 이름을 적은 것까지 세면
    판별에 안 쓰이는 줄이 판별을 바꾼다(`#368` 에서 밟은 결함).

    ❗**함수로 빼 둔 이유가 있다.** 이 판별을 테스트 안에 두면 변이(app/ 에 import 를
    넣기)가 **수집 단계에서** 죽어서 — 이 테스트 모듈이 `app.misconception` 을 import
    하므로 — 단정문이 한 번도 돌지 않는다. `error` 는 `failed` 가 아니다. 아래
    `test_the_import_scanner_*` 가 판별기를 직접 건다.
    """
    found: list[Path] = []
    for path in sorted(root.rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            names: list[str] = []
            if isinstance(node, ast.Import):
                names = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom):
                names = [node.module or ""] + [alias.name for alias in node.names]
            if any(needle in name for name in names):
                found.append(path)
                break
    return found


def test_doc_cites_the_live_threshold() -> None:
    """문서가 인용한 문턱이 상수와 같아야 한다."""
    text = _effective_lines(DOC.read_text(encoding="utf-8"))
    cited = re.search(r"`NGRAM_THRESHOLD = ([0-9.]+)`", text)
    assert cited, "문서가 `NGRAM_THRESHOLD = <값>` 을 인용하지 않는다"
    assert float(cited.group(1)) == NGRAM_THRESHOLD, (
        f"문서는 {cited.group(1)} 을 인용하는데 상수는 {NGRAM_THRESHOLD} 다. "
        "문턱을 바꿨으면 tune_ngram_threshold.py 로 다시 재고 그 절을 갱신한다."
    )


def test_reproduction_commands_point_at_files_that_exist() -> None:
    """「재현」 절이 부르는 도구가 실재해야 한다.

    절 제목 인용이 조용히 끊긴 사례가 `#341` 이다. 파일명도 같은 방식으로 끊긴다.
    """
    text = DOC.read_text(encoding="utf-8")
    # ❗`(?<![\w/])` 가 있어야 한다. 없으면 `eval/tools/measure_suffix_leak.py`(정세현
    # 소유, 다른 모듈)의 **꼬리**를 `ai-service/tools/` 것으로 읽고 "없는 파일" 이라고
    # 빨개진다 — 실제로 그렇게 물렸다. 그물이 실물을 물면 실물을 먼저 본다.
    named = set(re.findall(r"(?<![\w/])tools/([A-Za-z0-9_]+\.py)", text))
    assert named, "「재현」 절에 이 모듈의 도구 파일명이 하나도 없다"
    missing = sorted(name for name in named if not (TOOLS / name).exists())
    assert not missing, f"문서가 부르는 도구가 없다: {missing}"


_TOOLS_NEVER_IMPORTED = ("tune_ngram_threshold", "measure_selfconsistency_rate")


@pytest.mark.parametrize("tool", _TOOLS_NEVER_IMPORTED)
def test_the_measurement_tool_is_not_wired_into_scoring(tool: str) -> None:
    """측정 도구가 채점 경로에 들어가면 안 된다.

    `condition_counters` 와 같은 규약이다 — 도구는 근거를 만들 뿐 판정하지 않는다(P1).

    ❗**목록으로 둔다.** 도구가 둘이 되는 순간 이름 하나만 보는 검사는 나머지를 안 본다 —
    `EchoCapBelowR05Test` 가 캡이 셋이 되는 동안 하나만 보고 있던 것과 같은 모양이다.
    그리고 이 도구들은 **실 LLM 을 호출**하므로, 배선되면 채점이 느려지고 요금이 는다
    (결과는 맞게 나와서 **지연과 요금으로만** 드러난다).

    원문 grep 이 아니라 `ast` 로 **import 문만** 본다. 문면을 세면 주석에 도구 이름을 적은
    것까지 걸려서, 판별에 안 쓰이는 줄이 판별을 바꾼다(`#368` 에서 밟은 그 결함).
    """
    offenders = importers_of(tool, APP)
    assert not offenders, f"app/ 이 측정 도구를 import 한다: {offenders}"


def test_the_corrected_row_does_not_claim_a_safety_net() -> None:
    """정정의 요지가 되돌아가면 잡는다.

    첫 판은 어휘 매칭을 *"안전망으로 유지"* 로 적었고 그 근거 숫자는 **다른 방법의
    것**이었다(v2 프롬프트 미탐 `3/18`). 51건에서 발동이 0 이므로 안전망이 아니다.
    """
    text = _effective_lines(DOC.read_text(encoding="utf-8"))
    row = next(
        (line for line in text.splitlines() if line.startswith("| 어휘 매칭")), None
    )
    assert row, "결과 표에 「어휘 매칭」 행이 없다"
    assert "안전망으로 유지" not in row, (
        "어휘 매칭을 다시 안전망으로 적었다 — 51건 중 발동 0건이라는 실측과 어긋난다"
    )


@pytest.mark.parametrize("needle", ["leave-one-out", "정세현 라벨", "강희진 라벨"])
def test_the_leak_number_carries_its_protocol(needle: str) -> None:
    """종결어미 누설 숫자 옆에 프로토콜과 라벨러가 같이 있어야 한다.

    `#385` 리뷰의 요청이 정확히 이것이다 — *"어느 쪽이든 숫자 옆에 조건이 있으면 된다."*
    한 라벨러 값만 적으면 *"어느 라벨로 잰 건가"* 가 다시 빠진다.
    """
    text = _effective_lines(DOC.read_text(encoding="utf-8"))
    assert needle in text, f"누설 절에 「{needle}」 가 없다"


def test_the_import_scanner_catches_a_real_import(tmp_path: Path) -> None:
    """판별기가 실제 import 를 잡는가 — 위 가드의 단정문을 직접 건다."""
    (tmp_path / "a.py").write_text("import tools.tune_ngram_threshold\n", encoding="utf-8")
    (tmp_path / "b.py").write_text(
        "from tools.tune_ngram_threshold import main\n", encoding="utf-8"
    )
    assert {p.name for p in importers_of("tune_ngram_threshold", tmp_path)} == {"a.py", "b.py"}


def test_the_import_scanner_ignores_mere_mentions(tmp_path: Path) -> None:
    """주석·문자열의 언급은 import 가 아니다 — 문서가 도구를 가리키는 것은 정상이다."""
    (tmp_path / "c.py").write_text(
        '# 근거: tools/tune_ngram_threshold.py\nDOC = "tune_ngram_threshold"\n',
        encoding="utf-8",
    )
    assert importers_of("tune_ngram_threshold", tmp_path) == []


_NUMERALS = {"셋": 3, "넷": 4, "다섯": 5, "여섯": 6, "일곱": 7, "여덟": 8}


def _result_table_rows(markdown: str) -> list[list[str]]:
    """「결과 표」의 데이터 행만. 헤더·구분선·다른 표는 뺀다."""
    rows: list[list[str]] = []
    inside = False
    for line in markdown.splitlines():
        if line.startswith("## 결과 표"):
            inside = True
            continue
        if inside and line.startswith("## "):
            break
        if inside and line.startswith("|") and not set(line) <= set("|-: "):
            cells = [c.strip() for c in line.strip("|").split("|")]
            if cells[0] not in ("방법",):
                rows.append(cells)
    return rows


def test_the_fallen_count_matches_the_table() -> None:
    """「N이 떨어진 이유」의 N 이 결과 표에서 실제로 떨어진 행 수와 같아야 한다.

    ❗**이 대조가 없어서 실제로 틀렸다.** 첫 판은 어휘 매칭을 *"안전망으로 유지"* 로
    두고 「다섯이 떨어졌다」고 셌는데, `#385` 리뷰 반영으로 그 행이 실패로 바뀌자
    **제목의 셈만 낡았다** — 그 절 밑의 하위절은 여섯인데 제목은 다섯이었다.
    문면이 스스로와 어긋나는 종류라 사람이 읽어도 잘 안 보인다.
    """
    text = DOC.read_text(encoding="utf-8")
    rows = _result_table_rows(text)
    assert rows, "「결과 표」의 행을 못 읽었다"

    stood = [r for r in rows if "✅" in r[3]]
    fallen = [r for r in rows if "✅" not in r[3]]
    assert stood, "표에 서 있는 방법이 하나도 없다 — 표를 못 읽은 것이다"

    heading = re.search(r"^## (\S+?)이 떨어진 이유", text, re.MULTILINE)
    assert heading, "「N이 떨어진 이유」 제목이 없다"
    claimed = _NUMERALS.get(heading.group(1))
    assert claimed is not None, f"셀 수 없는 수사: {heading.group(1)!r}"
    assert claimed == len(fallen), (
        f"제목은 {heading.group(1)}({claimed})이 떨어졌다는데 표에서는 {len(fallen)} 행이다 "
        f"(서는 것 {len(stood)} · 전체 {len(rows)}). 표를 고쳤으면 제목·머리말도 같이 센다."
    )

    total = re.search(r"^# 오해 탐지 — (\S+?) 방법", text, re.MULTILINE)
    assert total and _NUMERALS.get(total.group(1)) == len(rows), (
        f"제목이 {total.group(1) if total else '?'} 방법이라는데 표는 {len(rows)} 행이다"
    )


# ── 인용 자리: 숫자를 든 곳은 재현 경로를 같이 든다 ──────────────────────────
#
# `#377` 의 95% 가 문서 한 곳이 아니라 **네 곳**에 복사돼 있었다(이 문서 · 이 파일들 둘 ·
# 이슈 제목). 한 곳을 고치면 나머지가 낡는데 **아무 검사도 그걸 안 물었다.**
# 방어는 「숫자를 없애는 것」이 아니라 **숫자 옆에 재현 경로를 두는 것**이다 —
# 다음 사람이 값을 의심하면 옮겨 적을 출처가 바로 있다.
_CITING = ("tests/test_devset_contract.py", "tests/fixtures/utterances/els.yaml")
_REPRO = "measure_suffix_leak.py"


@pytest.mark.parametrize("relative", _CITING)
def test_leak_citations_name_their_reproduction(relative: str) -> None:
    """종결어미 누설을 백분율로 말하는 파일은 재현 스크립트를 이름 대야 한다."""
    path = Path(__file__).resolve().parents[1] / relative
    text = path.read_text(encoding="utf-8")
    if "종결" not in text or not re.search(r"\d{1,3}%", text):
        # ❗`pytest.skip` 을 쓰지 않는다. `ci.yml` 의 `no_skip.py` 가 skip 을 **실패로**
        # 바꾸므로(허용 목록 없음), 인용이 사라진 정상 상태가 CI 빨강이 된다.
        return
    assert _REPRO in text, (
        f"{relative} 가 누설 수치를 들면서 {_REPRO} 를 안 가리킨다 — "
        "값을 의심하는 다음 사람이 옮겨 적을 출처가 없다"
    )


# ── 세션 확률의 분모는 게이트의 분모여야 한다 ────────────────────────────────
#
# `R-06`(`allGrade == 'U1'`)이 보는 것은 **판정**이고, 판정은 면담이 물은 항목에만 생긴다.
# 그 집합이 `ProductRiskItems.interviewItemsOf` = `required ∧ extracted` 라
# **`recommended` 는 안 물어지고 판정도 없다.** 컨텍스트 항목 수를 그대로 쓰면 세션
# 확률이 과대해지는데, 숫자가 그럴듯해서 눈으로는 안 보인다 (`#533` 리뷰, 강희진).
def _rate_tool():
    import importlib.util

    path = Path(__file__).resolve().parents[1] / "tools" / "measure_selfconsistency_rate.py"
    spec = importlib.util.spec_from_file_location("measure_selfconsistency_rate", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_the_session_denominator_counts_only_interviewed_items() -> None:
    """★ `recommended` 는 분모에서 빠진다 — 판정이 안 생기는 항목이다."""
    items = {
        "A": {"importance": "required"},
        "B": {"importance": "required"},
        "C": {"importance": "recommended"},
        "D": {},                              # importance 누락도 required 가 아니다
    }
    assert set(_rate_tool().interview_items(items)) == {"A", "B"}


def test_the_session_denominator_matches_the_eval_context() -> None:
    """실물 컨텍스트에서도 게이트 분모와 같아야 한다 — 13 이 아니라 10 이다."""
    import json

    context = json.loads(
        (Path(__file__).resolve().parents[2] / "eval" / "data" / "context" / "els.json")
        .read_text(encoding="utf-8")
    )
    items = context["risk_items"]
    required = _rate_tool().interview_items(items)
    assert len(required) < len(items), (
        "recommended 가 하나도 없으면 이 대조가 아무것도 안 잰다 — 컨텍스트가 바뀌었나"
    )
    assert len(required) == 10, f"required 가 {len(required)}건이다 — 도구의 분모를 다시 본다"
