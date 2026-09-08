"""NGRAM_THRESHOLD(0.62) 를 사람 라벨 코퍼스로 재측정한다 — F-DET-001 2단계.

`0.62` 는 첫 커밋(`96ab471`)에 손으로 들어간 값이고, 그때 붙인 주석이 스스로
*"튜닝 시 dev set으로 재측정한다"* 고 적었다. **그 재측정을 한 번도 하지 않았다.**
`#368` 이 값을 `app/scoring_thresholds.yaml` 로 옮겼지만 *"왜 0.62 인가"* 는 여전히
빈 칸이었다. 이 도구가 그 칸을 채운다.

## 무엇을 재는가 — 모집단을 문면에 적는다

    발화    eval/corpus/els.jsonl
            ❗키는 (sample_id, item_id) 짝이다. sample_id 로만 잡으면 3건을 조용히
            잃는다 — 한 발화가 두 항목에 걸린 건이 있다(els-0048·0060·0062).
    라벨    eval/data/labels/{정세현,강희진}.jsonl 의 **합의분만**
            (두 라벨러 등급이 같은 것. 불일치 건은 정답이 없으므로 분모에서 뺀다)
    양성    합의 U4 = "틀리게 안다"
            ❗라벨에 오해 **유형**은 없다. 그래서 재는 것은 유형 정확도가 아니라
            `scoring.apply_misconception_floor` 가 실제로 소비하는 이진 —
            「루브릭 `related_misconceptions` 유형의 패턴 중 하나라도 문턱을 넘나」.
            floor 가 그 필터를 걸고, 걸리면 등급을 U4 로 올린다.

## 이 측정이 대답하는 질문

문턱을 바꾸면 **판정이 바뀌는가.** 바뀌지 않으면 값은 임의여도 무해하고, 바뀌면
어느 방향으로 바뀌는지가 P5(미탐 최소화)와 오탐 비용의 저울에 올라간다.

오탐 하나는 floor → `U4` → 게이트 `R-01` → **RED(판매 차단)** 로 직결된다.
그래서 문턱을 **낮추는** 변경은 미탐 감소분보다 오탐 증가분이 작아야만 정당하다.
"""

from __future__ import annotations

import contextlib
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from app import misconception, rubrics  # noqa: E402
from app.misconception import NGRAM_THRESHOLD  # noqa: E402

EVAL = pathlib.Path(__file__).resolve().parents[2] / "eval"


def _jsonl(path: pathlib.Path) -> list[dict]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def _key(row: dict) -> tuple[str, str]:
    return (row["sample_id"], row["item_id"])


def load() -> tuple[dict, dict, dict]:
    corpus = {_key(r): r for r in _jsonl(EVAL / "corpus/els.jsonl")}
    a = {_key(r): r["grade"] for r in _jsonl(EVAL / "data/labels/정세현.jsonl")}
    b = {_key(r): r["grade"] for r in _jsonl(EVAL / "data/labels/강희진.jsonl")}
    consensus = {k: g for k, g in a.items() if b.get(k) == g}
    # 모델 판정 — floor 는 **모델이 U4 를 안 냈을 때만** 등급을 움직인다. 사람 라벨만
    # 보면 "미탐 구제" 를 과대 계상한다(모델이 이미 U4 인 건까지 센다).
    # ❗eval/data/model.jsonl 은 정세현 소유다. 읽기만 한다 — 절대 덮어쓰지 않는다.
    #
    # ❗**행 전체를 들고 온다.** 예전에는 여기서 `grade` 만 뽑고 판은 파일 전체에서
    #   따로 모았는데, 그러면 **이 측정이 안 쓴 행의 판까지** 조건 줄에 찍힌다
    #   (`#543` ⓐ). 판은 «무엇으로 잰 값인가» 를 말하는 자리라 모집단이 같아야 한다.
    model = {_key(r): r for r in _jsonl(EVAL / "data/model.jsonl")}
    return corpus, consensus, model


def _natural_sorted(values: set[str]) -> list[str]:
    """`v10` 이 `v2` 앞에 오지 않게 (`#543` ⓓ). 숫자 조각은 숫자로 비교한다."""
    def parts(text: str):
        return [int(c) if c.isdigit() else c for c in re.split(r"(\d+)", text)]

    return sorted(values, key=parts)


def provenance(model: dict, keys: list[tuple[str, str]]) -> str:
    """이 측정이 **실제로 읽은 행**의 판·모델. 조건 줄에 그대로 실린다.

    ❗**무르게 실패하지 않는다** (`#543` ⓑ). 예전에는 `r.get("prompt_version")` 이라
    필드가 없으면 `None` 이 조용히 빠지고 *"판 (기록 없음)"* 이 찍혔다 — 그 문면은
    「판이 없는 파일」과 「필드를 빠뜨린 파일」을 구별하지 못한다. 조용한 실패를
    읽는 시점으로 끌어올린다.
    """
    known = [k for k in keys if k in model]
    # ❗**두 필드를 같은 강도로 요구한다** (`#552` 리뷰 1, 정세현). 처음엔 `model` 만
    #   `or "(기록 없음)"` 로 무르게 넘겼는데, 그건 이 검사가 없애려던 바로 그 문면이다 —
    #   「기록이 없는 파일」과 「필드를 빠뜨린 파일」을 구별하지 못한다. 그리고 모델 칸을
    #   만든 이유가 *"`#266` 이 모델을 통째로 옮긴 프로젝트라 그 칸이 비면 못 되짚는다"*
    #   이므로, 무르게 넘기면 그 칸이 막으려던 것을 못 막는다.
    for field in ("prompt_version", "model"):
        missing = [k for k in known if not model[k].get(field)]
        if missing:
            raise SystemExit(
                f"❗model.jsonl 에 {field} 가 없는 행이 {len(missing)}건이다"
                f"(예: {missing[0]}). 이 측정의 조건을 적을 수 없으므로 멈춘다 — "
                "그 파일을 만든 도구를 본다"
            )
    if not known:
        raise SystemExit("❗model.jsonl 에서 이 측정이 쓸 행을 하나도 못 찾았다 — 키가 갈렸다")
    versions = _natural_sorted({model[k]["prompt_version"] for k in known})
    # ❗**모델도 같이 적는다** (`#543` ⓒ). 이 도구는 LLM 을 안 부르지만 model.jsonl 은
    #   모델 산물이라, 판만 적으면 «어느 모델의 v3 인가» 가 빠진다. `#266` 이 모델을
    #   통째로 옮긴 프로젝트라 그 칸이 비면 나중에 못 되짚는다.
    models = _natural_sorted({model[k]["model"] for k in known})
    return (f"판 {' · '.join(versions)} · 모델 {' · '.join(models)} · "
            f"이 측정이 읽은 행 {len(known)}/{len(keys)}")


@contextlib.contextmanager
def _threshold(value: float):
    """`match()` 가 읽는 문턱을 잠시 바꾼다.

    `misconception.match` 는 모듈 전역 `NGRAM_THRESHOLD` 를 **호출 시점에** 읽는다.
    0 으로 두면 아무것도 안 걸러 내므로 **유형별 최고점이 그대로 돌아온다** — 그 값이
    문턱 스윕에 필요한 전부다. 한 번만 걸고 되돌린다.
    """
    before = misconception.NGRAM_THRESHOLD
    misconception.NGRAM_THRESHOLD = value
    try:
        yield
    finally:
        misconception.NGRAM_THRESHOLD = before


def best_related(corpus: dict, key: tuple[str, str]) -> tuple[float, str | None, str | None]:
    """루브릭이 related 로 선언한 유형의 최고점. **실물 매처를 부른다.**

    ## ❗예전에는 여기가 매처의 사본이었다 (`#543` ①)

    런타임 **두 층**을 손으로 합쳐 놓고 있었다.

        패턴 순회 · 1단계 상수 1.0    misconception.match()          ← 베낌
        related 필터                scoring.apply_misconception_floor():862  ← 겸함
        문턱 비교                    match() 가 NGRAM_THRESHOLD 로     ← 호출자가 대신

    그래서 `scoring_thresholds.yaml` 의 *"실제 매처를 돌려도 등급을 바꾼 횟수 0"* 이
    **이 도구로는 재현되지 않았다.** `match()` 에 단계가 하나 늘거나 floor 의 `related`
    규칙이 바뀌면 사본은 옛 답을 계속 보고하는데, `#369`(pgvector)·`#358`(리랭킹)이
    바로 그 자리를 대체 후보로 보고 있으니 가정이 아니다.

    지금은 `match()` 를 부르고 **floor 와 같은 자리에서** `related` 를 건다.

    ❗**`match()` 는 점수를 `round(score, 4)` 로 담는다**(`misconception.py:534`). 옛
    사본은 안 했다 — 이 코퍼스에서는 출력이 한 글자도 안 달라지지만, 문턱 경계에 4자리
    밖 값이 놓이면 **사본 시절의 기록과 갈릴 수 있다**(`#552` 리뷰 4, 정세현). 실물을
    부르는 쪽이 옳은 방향이므로 고치지 않고 적어 둔다.

    ## ❗이 값은 상한이다 — 3단계 게이트가 빠져 있다

    채점 경로는 `apply_polarity_gate` → `apply_misconception_floor` 순이다
    (`scoring.py:371~372`). 게이트는 후보를 **빼기만** 하므로, 게이트가 없는 이 값은
    floor 가 실제로 발동하는 횟수의 **상한**이다.

    뺄 수 없다 — 게이트는 LLM 을 부르고 이 도구는 결정론이어야 한다(문턱을 고르는
    근거이므로 회차마다 답이 달라지면 못 쓴다). 그래서 **상한이라는 사실을 적는다.**
    실제로 이 코퍼스에서는 현행 문턱 발동이 0건이라 게이트가 뺄 것도 없다.
    """
    record = corpus[key]
    related = set(rubrics.get(record["item_id"]).related_misconceptions)
    # 문턱 0 → 유형별 최고점이 전부 돌아온다. `client` 를 안 주므로 3단계는 안 돈다.
    with _threshold(0.0):
        response = misconception.match(record["utterance"], record["product_type"])
    top: tuple[float, str | None, str | None] = (0.0, None, None)
    for m in response.matches:
        if m.type_id in related and m.score > top[0]:
            top = (m.score, m.type_id, m.matched_pattern)
    return top


def devset_firing_rate() -> tuple[int, int]:
    """내 dev set 에서 1·2단계가 몇 번 발동하나 — 씨앗 오염의 크기를 재는 대조군."""
    import yaml

    from app.misconception import match

    fixtures = pathlib.Path(__file__).resolve().parents[1] / "tests/fixtures/utterances"
    hits = total = 0
    for path in sorted(fixtures.glob("*.yaml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        for case in data.get("cases") or data.get("utterances") or []:
            item_id, answer = case.get("item_id"), case.get("answer") or case.get("utterance")
            if not (item_id and answer):
                continue
            total += 1
            rubric = rubrics.get(item_id)
            related = set(rubric.related_misconceptions)
            if any(m.type_id in related for m in match(answer, rubric.product_type).matches):
                hits += 1
    return hits, total


def main() -> None:
    corpus, consensus, model = load()
    # ❗코퍼스에 없는 합의는 잴 수 없다 — 조용히 빠뜨리지 않고 센다.
    scored_keys = [k for k in sorted(consensus) if k in corpus]
    skipped = [k for k in sorted(consensus) if k not in corpus]
    positives = sum(1 for k in scored_keys if consensus[k] == "U4")
    print(
        f"코퍼스 {len(corpus)}쌍 · 합의 {len(consensus)}건 "
        f"(U4 {positives} / 비U4 {len(scored_keys) - positives})"
    )
    if skipped:
        print(f"❗합의인데 코퍼스에 없어 못 잰 것 {len(skipped)}건 — {skipped}")
    if not scored_keys:
        raise SystemExit("❗잴 행이 0건이다 — 코퍼스와 라벨의 키가 갈렸다. 셈을 낼 수 없다")

    rows = [(k, consensus[k], *best_related(corpus, k)) for k in scored_keys]
    stage1 = sum(1 for r in rows if r[2] >= 1.0)
    print(f"1단계(pattern)로 확정 {stage1}건 — 점수 1.0 이라 어떤 문턱에서도 걸린다")

    print("\n── 최고 포함도 분포 (문턱을 어디에 놓을 수 있는지가 여기서 정해진다)")
    for name, want in (("U4  ", True), ("비U4", False)):
        xs = sorted(r[2] for r in rows if (r[1] == "U4") is want)
        # ❗**한쪽이 비어도 리포트를 마저 찍는다** (`#543` ⓔ). 예전에는 `max(xs)` 가
        #   `ValueError` 로 죽어서 **절반만 찍힌 출력이 완전한 리포트로 읽혔다.**
        if not xs:
            print(f"  {name} n= 0  ❗이 쪽 라벨이 하나도 없다 — 분포를 못 낸다")
            continue
        print(f"  {name} n={len(xs):2}  최고 {max(xs):.3f}  상위5 {[f'{x:.3f}' for x in xs[-5:]]}")

    print(f"\n── 문턱 스윕 (현행 {NGRAM_THRESHOLD})")
    print(f"{'문턱':>6} {'미탐':>6} {'오탐':>6}")
    for step in range(30, 102, 2):
        t = step / 100
        fn = sum(1 for r in rows if r[1] == "U4" and r[2] < t)
        fp = sum(1 for r in rows if r[1] != "U4" and r[2] >= t)
        mark = "   ← 현행" if abs(t - NGRAM_THRESHOLD) < 1e-9 else ""
        print(f"{t:6.2f} {fn:6} {fp:6}{mark}")

    print("\n── 문턱을 낮추면 뒤집히는 건 (0.30 ≤ 점수 < 현행)")
    print("   floor 는 모델이 U4 를 안 냈을 때만 등급을 움직인다 — 그래서 모델 판정을 겹쳐 센다")
    tally = {"✅ 미탐 구제": 0, "❌ 오탐 신설 — floor → U4 → RED": 0, "무해 — 모델이 이미 U4": 0}
    for key, grade, score, type_id, pattern in rows:
        if not 0.30 <= score < NGRAM_THRESHOLD:
            continue
        seen = (model.get(key) or {}).get("grade", "—")
        if seen == "U4":
            effect = "무해 — 모델이 이미 U4"
        elif grade == "U4":
            effect = "✅ 미탐 구제"
        else:
            effect = "❌ 오탐 신설 — floor → U4 → RED"
        tally[effect] += 1
        print(f"  {key[0]} 사람 {grade} · 모델 {seen} · {score:.3f} · {type_id} {pattern!r}")
        print(f"      {effect}")
    print("  합계 " + " · ".join(f"{k} {v}" for k, v in tally.items()))
    # ❗**판을 파일에서 읽는다. 문자열로 박지 않는다.** 예전에는 여기 **v2 판**이
    # 박혀 있었는데 `#409`(9/6)가 v3 로 재채점하면서 **도구가 틀린 조건을 찍게 됐다** —
    # 조건을 적으라고 만든 도구가 조건을 틀리는 것이 제일 나쁘다.
    print(f"  ❗모델 판정의 조건 — {provenance(model, scored_keys)}")
    print("     다시 채점하면 이 셈이 바뀐다 — 인용할 때 이 줄을 같이 옮긴다.")

    hits, total = devset_firing_rate()
    print("\n── 씨앗 오염 대비 — 같은 매처를 내 dev set 에 돌린다")
    print(f"  eval 코퍼스 (독립 작성)     {len(rows)}건 중 발동 "
          f"{sum(1 for r in rows if r[2] >= NGRAM_THRESHOLD)}건")
    # ❗**0 으로 나누지 않는다** (`#543` ⓔ 두 번째 자리). 픽스처가 비면 여기서 죽어
    #   위의 셈이 절반만 남는다 — 그리고 그 절반이 완전한 리포트로 읽힌다.
    if not total:
        print("  내 dev set              ❗0건 — 픽스처를 못 읽었다. 대비를 못 낸다")
    else:
        print(f"  내 dev set (패턴에서 씨앗)  {total}건 중 발동 {hits}건 ({hits / total:.0%})")
    print("  ❗이 차이가 이 단계의 성격이다 — 라이브러리 문면을 되쓴 발화에만 걸린다.")
    print("     dev set 발동률을 성능 근거로 쓰지 않는다(씨앗이 패턴에서 왔다).")


if __name__ == "__main__":
    main()
