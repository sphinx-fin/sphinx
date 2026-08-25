"""dev set 채점 실행기. 소유: 윤지석

프롬프트를 고칠 때마다 회귀를 눈으로 보기 위한 도구다. **성능 수치의 근거가 아니다** —
공식 지표는 `eval/`(F-CMN-003, 정세현)에서만 나온다. tests/fixtures/README.md 참고.

    python tools/run_devset.py            # 전체
    python tools/run_devset.py DEMO-MAIN  # id 부분일치
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import misconception, scoring, templates  # noqa: E402
from app.llm_client import LlmError  # noqa: E402
from app.schemas import Condition, RiskItem, SourceSpan  # noqa: E402

FIXTURES = Path(__file__).resolve().parents[1] / "tests" / "fixtures"
CONTRACT_SAMPLES = Path(__file__).resolve().parents[2] / "contracts" / "samples"

#: dev set 이 쓰는 RiskItem 은 **계약 샘플에서 직접 만든다.**
#: 조항 문면을 픽스처에 복사해두면 낡는다 — 실제로 그랬다. 이전 픽스처는 내가 개별로
#: 받은 삼성증권 회차를 인용했는데 팀 데모 문서는 키움 4181 로 정해졌고, 그 결과 스팬이
#: 다른 사람이 검증할 수 없는 문서를 가리켰다. 계약 샘플은 git 추적 대상이므로 누구나
#: 등식 pages[page].text[start:end] == value_text 를 확인할 수 있다.
SAMPLE_BY_PRODUCT = {"ELS": "parsed_els_sample.json",
                     "VARIABLE_INSURANCE": "parsed_variable_sample.json"}

#: RiskItem.importance 는 계약상 required 필드인데 템플릿에 아직 미부여다(이슈 #26).
#: dev set 은 채점 튜닝용이므로 자리표시자를 쓰고, 부여되면 템플릿에서 읽는다.
IMPORTANCE_PLACEHOLDER = "required"


def load_sample(product_type: str) -> dict:
    path = CONTRACT_SAMPLES / SAMPLE_BY_PRODUCT[product_type]
    return json.loads(path.read_text(encoding="utf-8"))


def load_risk_items(product_type: str = "ELS") -> dict[str, RiskItem]:
    """계약 샘플의 `_expected_risk_items` → RiskItem. 스팬은 페이지 상대 그대로 쓴다."""
    raw = load_sample(product_type)
    try:
        tpl = templates.get(product_type)
        importance_by_id = {i.item_id: i.importance for i in tpl.items}
    except templates.TemplateNotFound:
        importance_by_id = {}

    items: dict[str, RiskItem] = {}
    for entry in raw["_expected_risk_items"]:
        items[entry["item_id"]] = RiskItem(
            item_id=entry["item_id"],
            product_id=raw["document_id"],
            name=entry["name"],
            importance=importance_by_id.get(entry["item_id"]) or IMPORTANCE_PLACEHOLDER,
            status="extracted",
            condition=Condition(
                value_text=entry["value_text"],
                source_span=SourceSpan(**entry["source_span"]),
            ),
        )
    return items


def verify_spans(product_type: str = "ELS") -> list[str]:
    """계약 규약 등식을 실제로 확인한다. 어긋난 item_id 목록을 돌려준다."""
    raw = load_sample(product_type)
    pages = {p["page"]: p["text"] for p in raw["pages"]}
    broken = []
    for entry in raw["_expected_risk_items"]:
        sp = entry["source_span"]
        text = pages.get(sp["page"], "")
        if text[sp["start"]:sp["end"]] != entry["value_text"]:
            broken.append(entry["item_id"])
    return broken


def main() -> int:
    needle = sys.argv[1] if len(sys.argv) > 1 else ""
    spec = yaml.safe_load((FIXTURES / "utterances" / "els.yaml").read_text(encoding="utf-8"))
    product_type = spec["product_type"]
    items = load_risk_items(product_type)

    broken = verify_spans(product_type)
    if broken:
        print(f"★ 계약 스팬 등식 불일치: {broken}")
        return 1
    sample = load_sample(product_type)
    print(f"문서: {sample['document_id']}  파서 {sample['parser_version']}  "
          f"페이지 {len(sample['pages'])}  이해항목 {len(sample['_expected_risk_items'])}종")

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
