"""dev set 채점 실행기. 소유: 윤지석

프롬프트를 고칠 때마다 회귀를 눈으로 보기 위한 도구다. **성능 수치의 근거가 아니다** —
공식 지표는 `eval/`(F-CMN-003, 정세현)에서만 나온다. tests/fixtures/README.md 참고.

    python tools/run_devset.py            # 전체
    python tools/run_devset.py DEMO-MAIN  # id 부분일치
"""
from __future__ import annotations

import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import misconception, scoring  # noqa: E402
from app.llm_client import LlmError  # noqa: E402
from app.schemas import Condition, RiskItem, SourceSpan  # noqa: E402

FIXTURES = Path(__file__).resolve().parents[1] / "tests" / "fixtures"


def load_risk_items() -> dict[str, RiskItem]:
    raw = yaml.safe_load((FIXTURES / "risk_items" / "els.yaml").read_text(encoding="utf-8"))
    return {
        it["item_id"]: RiskItem(
            item_id=it["item_id"], product_id=raw["product_id"], name=it["name"],
            importance=it["importance"], status="extracted",
            condition=Condition(
                value_text=it["value_text"].strip(),
                source_span=SourceSpan(**it["source_span"]),
            ),
        )
        for it in raw["items"]
    }


def main() -> int:
    needle = sys.argv[1] if len(sys.argv) > 1 else ""
    spec = yaml.safe_load((FIXTURES / "utterances" / "els.yaml").read_text(encoding="utf-8"))
    items = load_risk_items()
    product_type = spec["product_type"]

    passed = failed = errored = 0
    for case in spec["cases"]:
        if needle and needle not in case["id"]:
            continue
        print("─" * 78)
        print(f"{case['id']}  ({'결정론' if case.get('deterministic') else 'LLM 의존'})")
        print(f"  근거   {case['source']}")
        print(f"  발화   {case['answer']}")

        lib = misconception.match(case["answer"], product_type)
        print(f"  라이브러리  {[f'{m.type_id}({m.stage} {m.score})' for m in lib.matches] or '매칭 없음'}"
              f"  escalate={lib.escalate}  후보큐={lib.unclassified_candidate}")

        if case.get("expected_escalate"):
            ok = lib.escalate is True
            print(f"  {'PASS' if ok else 'FAIL'}  escalate 기대={case['expected_escalate']} 실제={lib.escalate}")
            passed += ok; failed += not ok
            continue

        try:
            j = scoring.score(case["item_id"], case["question"], case["answer"],
                              items[case["item_id"]], product_type)
        except LlmError as exc:
            print(f"  ERROR  {type(exc).__name__}: {str(exc)[:200]}")
            errored += 1
            continue

        exp_g, exp_m = case.get("expected_grade"), case.get("expected_misconception")
        ok = (exp_g is None or j.grade.value == exp_g) and \
             (exp_m is None or j.misconception_type == exp_m)
        print(f"  판정   {j.grade.value}  conf={j.confidence:.2f}  type={j.misconception_type}")
        print(f"  근거   \"{j.evidence.utterance_quote}\"")
        print(f"         ← {j.evidence.rubric_clause}")
        print(f"  사유   {j.reason}")
        print(f"  {'PASS' if ok else 'FAIL'}  기대 grade={exp_g} type={exp_m}")
        passed += ok; failed += not ok

    print("═" * 78)
    print(f"PASS {passed} · FAIL {failed} · ERROR {errored}")
    return 1 if (failed or errored) else 0


if __name__ == "__main__":
    raise SystemExit(main())
