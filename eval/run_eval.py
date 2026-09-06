#!/usr/bin/env python3
"""QWK·혼동행렬·미탐율(U4→U1/U2) 산출. 소유: 정세현 (F-CMN-003)

    python eval/run_eval.py                      # eval/data/ 를 읽어 리포트를 낸다
    python eval/run_eval.py --out report.md      # 파일로도 저장한다

── ❗이 스크립트가 하지 않는 것 ────────────────────────────────────────────────

**라벨을 만들지 않는다.** 라벨은 사람이 붙이고 프롬프트 당사자(윤지석)는 라벨링에서
빠진다(eval/README.md · 역할표 F-EXT-003 *"평가자와 피평가자를 분리"*). 여기서 라벨을
채우면 그 분리가 무너지고, **무너진 사실이 숫자 어디에도 안 남는다.**

❗이 회차 라벨러는 **정세현 · 강희진**이고 정세현이 운영자다 — 분리가 완전하지 않다.
그 조건은 `eval/labeling/guideline.md` §5 에 있고, 아래 1번 수치를 조건 없이
「상한」으로 인용하지 않는 이유다.

그래서 입력이 없으면 **비영점으로 죽는다.** 0.0 이나 빈 리포트를 내지 않는다 — 파이프라인이
"돌긴 돌았다"로 읽히는 상태가 제일 나쁘다.

── 무엇을 읽나 ────────────────────────────────────────────────────────────────

JSONL 세 종류. 한 줄이 한 (표본, 항목) 이다.

    eval/data/model.jsonl            {"sample_id": "...", "item_id": "...", "grade": "U4"}
    eval/data/labels/강희진.jsonl     {"sample_id": "...", "item_id": "...", "grade": "U3"}
    eval/data/labels/정세현.jsonl     같은 모양

JSONL 인 이유는 라벨링이 이어붙이는 작업이라서다 — 한 줄씩 늘고, diff 가 사람이 읽을 수
있는 모양으로 남는다(라벨은 감사 대상은 아니지만 심사에서 근거를 물을 수 있다).

── 무엇을 내나 ────────────────────────────────────────────────────────────────

    평가자 간 일치도    두 사람 사이. **이것이 상한이다** — 사람도 안 맞는 항목에서
                        모델이 사람과 맞기를 기대할 수 없다
    모델 vs 각 라벨러   상한과 나란히 놓고 본다
    모델 vs 합의        두 사람이 같은 등급을 준 항목만. 정답이 제일 단단한 부분집합
    미탐율              U4 → U1·U2. **카파와 따로 낸다**(metrics.py 머리말)
    혼동행렬 · 분포     쏠림을 눈으로 본다
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import OrderedDict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from metrics import (  # noqa: E402
    GRADES,
    EvalError,
    agreement_rate,
    confusion,
    distribution,
    miss_breakdown,
    miss_rate,
    weighted_kappa,
)

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
MODEL_FILE = DATA / "model.jsonl"
LABELS_DIR = DATA / "labels"

#: 이 미만이면 카파를 **찍지 않는다.** 통계적 보장선이 아니라 하한이다 — 표본이 한 줌이면
#: 항목 하나가 소수점 둘째 자리를 흔들어서, 회차 비교가 의미를 잃는데 숫자는 그대로
#: 그럴듯하게 보인다. 계획 표본은 100건이다(eval/README.md).
MIN_SAMPLES = 30

TARGET_QWK = 0.75  # eval/README.md 의 목표선


class InputError(SystemExit):
    """입력이 없거나 모양이 아니다. 메시지에 **다음에 할 일**을 적는다."""

    def __init__(self, message: str) -> None:
        super().__init__(f"eval: {message}")


def load_jsonl(path: Path, who: str) -> "OrderedDict[tuple[str, str], str]":
    if not path.exists():
        raise InputError(f"{who} 파일이 없다: {path.relative_to(ROOT.parent)}")
    out: OrderedDict[tuple[str, str], str] = OrderedDict()
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        raw = raw.strip()
        if not raw or raw.startswith("#"):
            continue
        try:
            row = json.loads(raw)
        except json.JSONDecodeError as e:
            raise InputError(f"{path.name}:{lineno} JSON 이 아니다 — {e}") from e
        try:
            key = (str(row["sample_id"]), str(row["item_id"]))
            grade = str(row["grade"])
        except KeyError as e:
            raise InputError(
                f"{path.name}:{lineno} 필수 키가 없다: {e}. "
                "한 줄은 {\"sample_id\":…, \"item_id\":…, \"grade\":\"U1\"~\"U4\"} 다"
            ) from e
        if grade not in GRADES:
            raise InputError(f"{path.name}:{lineno} 등급 enum 밖이다: {grade!r} (허용 {list(GRADES)})")
        if key in out:
            # ❗조용히 덮어쓰지 않는다. 같은 항목에 두 라벨이 있으면 어느 쪽이 그 사람의
            # 판단인지 알 수 없고, 마지막 줄이 이기는 규칙은 파일 순서에 의존한다.
            raise InputError(f"{path.name}:{lineno} 같은 (sample_id, item_id) 가 두 번 나온다: {key}")
        out[key] = grade
    if not out:
        raise InputError(f"{who} 파일이 비어 있다: {path.relative_to(ROOT.parent)}")
    return out


#: 이 회차 수치에 붙는 **알려진 단서**. 각 줄은 열린 이슈 하나를 가리킨다.
#:
#: ❗**리포트가 이걸 안 찍으면 다음 사람이 낮은 등급을 「고객 이해도」로 읽는다.** 숫자 옆에
#: 재현 경로를 둔다(`#388`)와 같은 자리이고, `model_provenance` 가 *"어느 판으로 쟀나"* 를
#: 답하는 것과 짝이다 — 이쪽은 *"그 판에 무엇이 덜 실렸나"* 를 답한다.
#:
#: 여기 손으로 적는 이유는 **기계가 못 재기 때문**이다. "인용이 루브릭 필수요소를 덮는가" 는
#: 의미 판단이고, 그것을 재는 그물을 만드는 것이 `#456` 자신의 과제다. 그때까지는 사람이
#: 적고, **이슈가 닫히면 이 줄도 같이 지운다.**
KNOWN_CAVEATS: tuple[str, ...] = (
    "`ELS-MATURITY-LOSS-CONDITION`(표본 8행) — 고정 문맥의 인용이 루브릭 "
    "`required_elements` 2개 중 1개만 덮는다(`u1_requires: 2`). 근거가 반쪽이라 "
    "**U1 이 안 나오는 쪽으로 기운다** — 이 항목의 낮은 등급을 고객 이해도로 읽지 않는다. "
    "추출이 회차마다 조건절·결론 중 한쪽만 집는 것이 원인이다 (`#456`).",
)


def context_provenance(path: Path) -> list[str]:
    """모델 출력이 **어느 문맥 판**으로 나온 값인지. `run_scoring.py` 가 헤더에 찍는다.

    ❗`model_provenance` 와 같은 이유로 필요하다. 등급의 입력은 (루브릭, 이해항목, 질문,
    발화) 넷인데 프롬프트 버전만 찍으면 **이해항목이 어느 파서 판에서 나왔는지**가 안 남는다.
    실제로 `#458`(parser 0.2.0 → 0.3.0) 뒤에 문맥이 옛 판으로 굳어 있었고, 그 사실이
    산출물 어디에도 없었다(`#409`). 지금은 `run_scoring.py` 가 헤더에 적고 여기가 옮긴다.

    옛 판 파일에는 그 줄이 없다 — **없으면 없다고 적는다.** 조용히 비우면 *"찍혔는데 같다"*
    와 *"안 찍혔다"* 가 같아 보인다(결정 5.40).
    """
    marks = [raw.strip().lstrip("#").strip()
             for raw in path.read_text(encoding="utf-8").splitlines()
             if raw.strip().startswith("# 문맥 ")]
    return marks or ["(문맥 판 미기록 — run_scoring.py 를 다시 돌리면 찍힌다)"]


def model_provenance(path: Path) -> str:
    """모델 출력이 **어느 프롬프트·어느 모델**로 나온 값인지. 없으면 그렇게 적는다.

    ❗**리포트가 이걸 안 찍으면 숫자의 출처를 복원할 수 없다.** 채점 프롬프트는 산출물이라
    버전이 올라가는데(`ai-service/app/prompts/README.md` — *"어떤 버전으로 측정한 결과인지
    추적 가능해야 한다"*), 리포트에 그 값이 없으면 **같은 문서가 어느 판의 성능인지 말하지
    않는다.** `#366` 이 프롬프트 문면을 고치면서 드러난 자리다.

    여러 값이 섞여 있으면 **전부 적는다** — 한 회차 안에서 프롬프트가 갈렸다는 사실 자체가
    그 수치를 조건부로 만든다. 감춰서 한 줄로 만들면 그 사실이 사라진다.
    """
    versions: "OrderedDict[str, None]" = OrderedDict()
    models: "OrderedDict[str, None]" = OrderedDict()
    for raw in path.read_text(encoding="utf-8").splitlines():
        raw = raw.strip()
        if not raw or raw.startswith("#"):
            continue
        try:
            row = json.loads(raw)
        except json.JSONDecodeError:
            continue          # 형식 오류는 load_jsonl 이 이미 정확한 줄번호로 말한다
        if row.get("prompt_version"):
            versions[str(row["prompt_version"])] = None
        if row.get("model"):
            models[str(row["model"])] = None
    parts = [
        "모델 " + (" · ".join(models) if models else "미상"),
        "프롬프트 " + (" · ".join(versions) if versions else "미상(줄에 prompt_version 이 없다)"),
    ]
    return " / ".join(parts)


def load_labelers() -> "OrderedDict[str, OrderedDict[tuple[str, str], str]]":
    if not LABELS_DIR.is_dir():
        raise InputError(
            f"라벨 디렉토리가 없다: {LABELS_DIR.relative_to(ROOT.parent)}\n"
            "  라벨은 사람이 붙인다 — 이 회차는 정세현·강희진이다(eval/README.md). "
            "가이드라인: eval/labeling/guideline.md"
        )
    files = sorted(p for p in LABELS_DIR.glob("*.jsonl"))
    if len(files) < 2:
        raise InputError(
            f"라벨 파일이 {len(files)}개다 — **2인 독립 라벨이 전제다.** "
            "한 사람 라벨로는 평가자 간 일치도(상한)를 낼 수 없고, 상한을 모르면 "
            "모델 점수가 좋은 것인지 나쁜 것인지 말할 수 없다"
        )
    if len(files) > 2:
        # ❗**조용히 둘만 쓰지 않는다.** 아래 계산이 `names[0]`·`names[1]` 만 보므로,
        # 파일이 셋이면 한 명이 상한에서도 합의에서도 빠지는데 리포트 문면은 2인 회차와
        # 똑같다 — 25% 밖에 안 맞는 라벨러가 있어도 §5 가 "달성" 을 찍는다(실측, PR #225
        # 리뷰 오준서). 이 파일이 세운 원칙("못 잰 경우와 재서 나쁜 경우가 리포트에서
        # 반드시 달라 보여야 한다")이 여기서만 안 걸리던 자리다.
        #
        # 실제 방아쇠는 `오준서.v2.jsonl` 같은 **잔여 파일 하나**다. 그래서 거부하되
        # 무엇을 찾았는지 이름으로 보여 준다.
        found = " · ".join(p.name for p in files)
        raise InputError(
            f"라벨 파일이 {len(files)}개다 — **2인 독립이 전제다**(eval/README.md).\n"
            f"  찾은 파일: {found}\n"
            "  셋 이상이면 어느 둘로 상한을 낼지가 정해지지 않는다. 잔여 파일이면 지우고, "
            "라벨러를 늘리는 결정이면 이 스크립트의 상한·합의 계산부터 고친다"
        )
    return OrderedDict((p.stem, load_jsonl(p, f"라벨({p.stem})")) for p in files)


def aligned(*sets: "OrderedDict[tuple[str, str], str]") -> list[tuple[str, str]]:
    """모두가 라벨한 (표본, 항목) 만. 순서는 첫 인자 기준으로 고정한다(재현성)."""
    common = set(sets[0])
    for s in sets[1:]:
        common &= set(s)
    return [k for k in sets[0] if k in common]


def fmt_kappa(gold, pred, label: str, lines: list[str]) -> float | None:
    n = len(gold)
    if n < MIN_SAMPLES:
        lines.append(f"  {label:<28} 표본 {n}건 — {MIN_SAMPLES}건 미만이라 찍지 않는다")
        return None
    try:
        q = weighted_kappa(gold, pred, weights="quadratic")
        lin = weighted_kappa(gold, pred, weights="linear")
    except EvalError as e:
        lines.append(f"  {label:<28} 계산 불가 — {e}")
        return None
    lines.append(
        f"  {label:<28} QWK {q:+.3f} · linear {lin:+.3f} · 일치율 {agreement_rate(gold, pred):.1%} · n={n}"
    )
    return q


def render_confusion(gold, pred) -> list[str]:
    m = confusion(gold, pred)
    out = ["", "  혼동행렬 (행=정답 · 열=예측)", "        " + "".join(f"{g:>6}" for g in GRADES)]
    for i, g in enumerate(GRADES):
        out.append(f"    {g}  " + "".join(f"{m[i][j]:>6}" for j in range(len(GRADES))))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="F-CMN-003 채점 성능 평가")
    ap.add_argument("--out", type=Path, help="리포트를 이 경로에도 쓴다")
    args = ap.parse_args()

    model = load_jsonl(MODEL_FILE, "모델 출력")
    labelers = load_labelers()
    names = list(labelers)

    lines: list[str] = ["# F-CMN-003 채점 성능 평가", ""]
    lines.append(f"모델 출력 {len(model)}건 · 라벨러 {len(names)}명 ({', '.join(names)})")
    lines.append(f"채점: {model_provenance(MODEL_FILE)}")
    for mark in context_provenance(MODEL_FILE):
        lines.append(mark)
    lines.append("")
    if KNOWN_CAVEATS:
        lines.append("## 0. 이 수치에 붙는 단서")
        lines.append("")
        lines.append("아래는 **닫히지 않은 이슈** 때문에 이 회차 수치에 붙는 조건이다.")
        lines.append("숫자만 떼어 인용하지 않는다.")
        lines.append("")
        for caveat in KNOWN_CAVEATS:
            lines.append(f"- {caveat}")
        lines.append("")

    # ── 1. 평가자 간 일치도 = 상한 ────────────────────────────────────────────
    lines.append("## 1. 평가자 간 일치도 — 이것이 상한이다")
    lines.append("")
    lines.append("사람도 안 맞는 항목에서 모델이 사람과 맞기를 기대할 수 없다. 모델 점수는")
    lines.append("아래 값과 **나란히** 읽어야 의미가 있다.")
    lines.append("")
    # ❗상한은 두 라벨러가 서로 독립일 때만 상한이다. 그 조건은 회차마다 다르고 코드가
    # 알 수 없으므로(이름을 여기 박지 않는다) 문서를 가리킨다 — 조건을 안 적고 숫자만
    # 내면 그게 "표기가 거짓" 이다(PR #343).
    #
    # ❗**레포에 있는 문서만 가리킨다.** 처음엔 `eval/ai-reference-diff.md` 도 같이 걸었는데
    # 그 파일은 #343 에 있고 아직 머지되지 않았다 — 리포트가 **없는 파일을 근거로 드는**
    # 상태가 된다(오준서·윤지석이 각각 독립으로 짚었다). 그건 이 PR 이 잡으려던 결함과
    # 같은 종류다: 근거처럼 생겼는데 그 자리에 없는 것.
    lines.append("❗이 수치가 **상한 구실을 하려면 두 라벨러가 서로 독립**이어야 한다.")
    lines.append("이 회차의 독립 조건은 `eval/labeling/guideline.md` §5 에 있다 —")
    lines.append("**조건과 함께 인용한다.**")
    lines.append("")
    a, b_name = names[0], names[1]
    keys_ab = aligned(labelers[a], labelers[b_name])
    gold_a = [labelers[a][k] for k in keys_ab]
    gold_b = [labelers[b_name][k] for k in keys_ab]
    ceiling = fmt_kappa(gold_a, gold_b, f"{a} ↔ {b_name}", lines)
    lines += render_confusion(gold_a, gold_b)

    # ── 2. 모델 vs 각 라벨러 ─────────────────────────────────────────────────
    lines += ["", "## 2. 모델 vs 각 라벨러 — 상한과 **같은 표본**이다", ""]
    lines.append("1절의 상한과 이 값들만 직접 비교할 수 있다. 3절은 표본이 다르다(그쪽 설명 참조).")
    lines.append("")
    # ❗5절이 쓴다. 예전에는 여기서 값을 버리고 3절(합의 부분집합) 값만 상한과 비교해서,
    # **표본이 다른 두 값을 나란히 놓고** "상한을 넘었다" 경고를 찍었다.
    same_sample: dict[str, float] = {}
    for name in names:
        keys = aligned(labelers[name], model)
        q = fmt_kappa([labelers[name][k] for k in keys], [model[k] for k in keys], f"모델 ↔ {name}", lines)
        if q is not None:
            same_sample[name] = q

    # ── 3. 모델 vs 합의 ─────────────────────────────────────────────────────
    lines += ["", "## 3. 모델 vs 합의 — 두 사람이 같은 등급을 준 항목만", ""]
    consensus_keys = [k for k in aligned(labelers[a], labelers[b_name], model)
                      if labelers[a][k] == labelers[b_name][k]]
    disputed = len(aligned(labelers[a], labelers[b_name], model)) - len(consensus_keys)
    lines.append(f"  합의 {len(consensus_keys)}건 · 불일치 제외 {disputed}건")
    lines.append("")
    lines.append("  ❗**이 값을 1절 상한과 직접 비교하지 않는다.** 두 사람이 갈린 항목이 빠진")
    lines.append("  집합이라 남은 것은 사람도 쉽게 합의한 문제들이고, 모델은 그만큼 쉬운 시험을")
    lines.append("  본 것이 된다. 상한과 나란히 놓을 값은 2절이다(같은 표본).")
    lines.append("")
    gold = [labelers[a][k] for k in consensus_keys]
    pred = [model[k] for k in consensus_keys]
    model_q = fmt_kappa(gold, pred, "모델 ↔ 합의", lines) if consensus_keys else None
    if consensus_keys:
        lines += render_confusion(gold, pred)

    # ── 4. 미탐율 ───────────────────────────────────────────────────────────
    lines += ["", "## 4. 미탐율 — U4(오해)를 U1·U2 로 읽은 비율", ""]
    lines.append("❗카파와 **따로** 본다. 표본에서 U4 가 소수면 카파가 높은 채로 미탐율이")
    lines.append("나쁠 수 있고, 그 조합이 이 시스템에서 제일 나쁜 상태다.")
    lines.append("")
    if not consensus_keys:
        # ❗U4 0건과 같은 계열의 자리다. 합의가 0이면 아래 분포가 전부 0 으로 찍히는데,
        # 그 문면만으로는 "안 쟀다" 와 "재서 0" 이 구별되지 않는다.
        lines.append("  두 라벨러가 **합의한 항목이 하나도 없다** — 미탐율을 정의할 수 없다.")
        lines.append("  ❗표본이 나쁜 것이 아니라 라벨 기준이 갈렸다는 신호다. 위 1절의")
        lines.append("  평가자 간 일치도를 먼저 본다(guideline.md 로 기준을 맞춘 뒤 다시 붙인다).")
    if consensus_keys:
        rate, missed, total = miss_rate(gold, pred)
        if total == 0:
            lines.append("  합의 표본에 U4 가 **0건**이다 — 미탐율을 정의할 수 없다.")
            lines.append("  ❗표본이 오해 케이스를 안 담고 있다는 뜻이라, 이 회차로는 게이트의")
            lines.append("  핵심 실패 모드를 아예 재지 못한다. 표본 구성을 먼저 고친다.")
        else:
            brk = miss_breakdown(gold, pred)
            lines.append(f"  미탐 {missed}/{total} = {rate:.1%}   (U4 를 U4 로 읽은 것 {brk['caught']}건)")
            lines.append("")
            lines.append("  게이트 결과로 가른다 — 둘 다 오해를 못 잡은 것이지만 대가가 다르다.")
            lines.append(f"    U4 → U1     {brk['passes']:>3}건   게이트가 GREEN 까지 갈 수 있다 (R-06)")
            lines.append(f"    U4 → U2·U3  {brk['downgrades']:>3}건   RED 가 YELLOW 로 내려앉는다 (R-04)")
    lines += ["", f"  정답 분포 {distribution(gold)}", f"  모델 분포 {distribution(pred)}"]

    # ── 5. 목표선 ───────────────────────────────────────────────────────────
    lines += ["", "## 5. 목표선", ""]
    if model_q is None:
        lines.append(f"  QWK 를 못 냈다 — 위 사유 참조. 목표선(≥{TARGET_QWK}) 판정 보류.")
    else:
        verdict = "달성" if model_q >= TARGET_QWK else "미달"
        lines.append(f"  모델 QWK {model_q:+.3f} vs 목표 {TARGET_QWK:+.3f} → **{verdict}**"
                     f"   (3절 · 합의 {len(consensus_keys)}건 기준)")

    # ❗상한과의 비교는 **2절 값으로만** 한다.
    #
    # 예전에는 3절(합의 부분집합)의 값을 상한(전체)과 나란히 놓고 "넘으면 의심한다" 를
    # 찍었다. 두 값은 표본이 달라서 비교가 성립하지 않는다 — 합의 집합은 사람이 갈린
    # 항목이 빠진 쪽이라 모델에게 더 쉽고, 그래서 **라벨이 멀쩡해도 상한을 넘는다.**
    # 그러면 읽는 사람이 있지도 않은 라벨 누출을 찾으러 간다(실측으로 그렇게 났다:
    # 3절 0.795 > 상한 0.769 인데 같은 70건에서는 0.706·0.682 로 상한 아래였다).
    if ceiling is not None and same_sample:
        lines += ["", "  ### 상한과의 거리 — 같은 표본(2절)으로만 잰다", ""]
        lines.append(f"    상한  {a} ↔ {b_name}   {ceiling:+.3f}")
        for name, q in same_sample.items():
            pct = f"  (상한의 {q / ceiling:.0%})" if ceiling > 0 else ""
            lines.append(f"    모델 ↔ {name}   {q:+.3f}{pct}")
        lines.append("")
        over = [n for n, q in same_sample.items() if q > ceiling]
        if over:
            lines.append(f"  ❗모델이 상한을 넘었다 ({', '.join(over)} 기준) — **표본·라벨을 먼저 의심한다.**")
            lines.append("  라벨이 샜거나(guideline.md 0절) 표본이 쉬운 것만 담았다는 신호다.")
        else:
            lines.append("  모델이 상한 아래다 — 정상이다. 상한을 올리려면 모델이 아니라")
            lines.append("  가이드라인을 고쳐야 한다(사람끼리 갈리는 자리가 곧 천장이다).")

    report = "\n".join(lines)
    print(report)
    if args.out:
        args.out.write_text(report + "\n", encoding="utf-8")
        print(f"\n저장: {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except InputError:
        raise
    except EvalError as e:
        raise SystemExit(f"eval: {e}") from e
