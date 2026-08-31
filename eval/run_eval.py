#!/usr/bin/env python3
"""QWK·혼동행렬·미탐율(U4→U1/U2) 산출. 소유: 정세현 (F-CMN-003)

    python eval/run_eval.py                      # eval/data/ 를 읽어 리포트를 낸다
    python eval/run_eval.py --out report.md      # 파일로도 저장한다

── ❗이 스크립트가 하지 않는 것 ────────────────────────────────────────────────

**라벨을 만들지 않는다.** 라벨은 강희진·오준서가 독립으로 붙이고, 프롬프트 당사자(윤지석)와
운영자(정세현)는 라벨링에서 빠진다(eval/README.md · 역할표 F-EXT-003 *"평가자와 피평가자를
분리"*). 여기서 라벨을 채우면 그 분리가 무너지고, **무너진 사실이 숫자 어디에도 안 남는다.**

그래서 입력이 없으면 **비영점으로 죽는다.** 0.0 이나 빈 리포트를 내지 않는다 — 파이프라인이
"돌긴 돌았다"로 읽히는 상태가 제일 나쁘다.

── 무엇을 읽나 ────────────────────────────────────────────────────────────────

JSONL 세 종류. 한 줄이 한 (표본, 항목) 이다.

    eval/data/model.jsonl            {"sample_id": "...", "item_id": "...", "grade": "U4"}
    eval/data/labels/강희진.jsonl     {"sample_id": "...", "item_id": "...", "grade": "U3"}
    eval/data/labels/오준서.jsonl     같은 모양

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


def load_labelers() -> "OrderedDict[str, OrderedDict[tuple[str, str], str]]":
    if not LABELS_DIR.is_dir():
        raise InputError(
            f"라벨 디렉토리가 없다: {LABELS_DIR.relative_to(ROOT.parent)}\n"
            "  라벨은 강희진·오준서가 독립으로 붙인다(eval/README.md). "
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
    lines.append("")

    # ── 1. 평가자 간 일치도 = 상한 ────────────────────────────────────────────
    lines.append("## 1. 평가자 간 일치도 — 이것이 상한이다")
    lines.append("")
    lines.append("사람도 안 맞는 항목에서 모델이 사람과 맞기를 기대할 수 없다. 모델 점수는")
    lines.append("아래 값과 **나란히** 읽어야 의미가 있다.")
    lines.append("")
    a, b = names[0], names[1]
    keys_ab = aligned(labelers[a], labelers[b])
    gold_a = [labelers[a][k] for k in keys_ab]
    gold_b = [labelers[b][k] for k in keys_ab]
    ceiling = fmt_kappa(gold_a, gold_b, f"{a} ↔ {b}", lines)
    lines += render_confusion(gold_a, gold_b)

    # ── 2. 모델 vs 각 라벨러 ─────────────────────────────────────────────────
    lines += ["", "## 2. 모델 vs 각 라벨러", ""]
    for name in names:
        keys = aligned(labelers[name], model)
        fmt_kappa([labelers[name][k] for k in keys], [model[k] for k in keys], f"모델 ↔ {name}", lines)

    # ── 3. 모델 vs 합의 ─────────────────────────────────────────────────────
    lines += ["", "## 3. 모델 vs 합의 — 두 사람이 같은 등급을 준 항목만", ""]
    consensus_keys = [k for k in aligned(labelers[a], labelers[b], model) if labelers[a][k] == labelers[b][k]]
    disputed = len(aligned(labelers[a], labelers[b], model)) - len(consensus_keys)
    lines.append(f"  합의 {len(consensus_keys)}건 · 불일치 제외 {disputed}건")
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
            lines.append(f"  미탐 {missed}/{total} = {rate:.1%}")
    lines += ["", f"  정답 분포 {distribution(gold)}", f"  모델 분포 {distribution(pred)}"]

    # ── 5. 목표선 ───────────────────────────────────────────────────────────
    lines += ["", "## 5. 목표선", ""]
    if model_q is None:
        lines.append(f"  QWK 를 못 냈다 — 위 사유 참조. 목표선(≥{TARGET_QWK}) 판정 보류.")
    else:
        verdict = "달성" if model_q >= TARGET_QWK else "미달"
        lines.append(f"  모델 QWK {model_q:+.3f} vs 목표 {TARGET_QWK:+.3f} → **{verdict}**")
        if ceiling is not None:
            lines.append(f"  상한(평가자 간) {ceiling:+.3f} — 모델이 상한을 넘으면 표본·라벨을 먼저 의심한다")

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
