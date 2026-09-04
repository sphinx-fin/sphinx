#!/usr/bin/env python3
"""F-DET-001 3단계 임계값을 **실측으로** 정한다. 실제 임베딩을 부른다(요금 발생).

## ❗2026-09-04 실측 결론 — **임베딩은 이 일을 못 한다. 붙이지 않는다.**

이 파일은 그 근거의 **① 임베딩** 부분이다. 같은 날 여섯 방법을 다 재고 비교한 기록은
`tools/MISCONCEPTION-DETECTION.md` 에 있다 — **같은 시도를 다시 하기 전에 그것부터 읽는다.**

두 설계를 다 쟀고 둘 다 갈리지 않는다. 이 파일은 그 근거를 남기려고 유지한다 —
같은 시도를 다시 하기 전에 여기부터 읽는다.

### ① 코사인 유사도 + 임계값 하나 (원래 docstring 의 설계)

    양성 최저 0.308  ·  음성 최고 0.621  ·  간격 -0.313      겹친다
    유형까지 맞은 것 4/12

가장 높은 점수를 받은 것이 **오해가 아니라 올바른 이해**였다.

    「원금은 지켜지는」(오해 패턴) ↔ 「원금이 손실될 수 있다는 건 이해했습니다」  0.621
                                 ↔ 「수수료는 어떻게 되나요」                  0.320

**임베딩은 주제가 가까우면 붙고 찬반을 못 가른다.** 오해와 그 오해의 정정은 같은 주제라
제일 가깝다 — 이 과제에서 정확히 반대로 작동한다.

### ② 대조 극 두 개 (오해 극 ↔ 정면 반박 극) + topic·margin 두 신호

    오해   topic 0.200~0.902   margin -0.122~+0.656
    이해   topic 0.292~0.878   margin -0.601~+0.055     ← 둘 다 완전히 겹친다
    무관   topic 0.214~0.246   margin -0.098~-0.051

M02 의 **이해** 발화(「예금자보호가 안 된다는 점 알겠습니다」)가 margin `+0.055` 로
M01 의 **오해** 발화 전부(-0.12)보다 높다. **부정어가 임베딩에 안 보인다.**

### 그래서 어디로 가는가

찬반 판정은 이미 채점 모델이 루브릭을 들고 한다(F-CMN-003 실측 QWK 0.795). 라이브러리는
**결정론적 안전망**이고, 못 잡는 변형은 **패턴을 늘리는 것**이 싸고 감사 가능하다
(데이터 소유: 정세현). *"모델이 못 하는 판정은 계산으로"* 의 반대편 —
**계산이 못 하는 판정을 계산으로 밀어붙이지 않는다.**


    .venv/bin/python tools/tune_embedding_threshold.py

## ❗이 숫자의 조건

**양성 발화를 내가 썼다.** 라이브러리 패턴을 보고 *"뜻은 같고 글자는 다르게"* 지은
문장들이라, 내 바꿔쓰기 습관이 그대로 들어 있다. `#266` 에서 dev set 이 모델 차이에
둔감했던 것과 같은 함정이므로 **성능 수치의 근거로 쓰지 않는다.**

이 도구가 답하는 것은 하나다 — *"양성과 음성이 갈리는 자리가 어디인가."*
사람 라벨이 붙은 발화가 생기면(`eval/`) 그걸로 다시 잰다.

음성은 두 종류를 같이 넣는다. **무해한 발화만 넣으면 임계값이 낙관적으로 나온다** —
진짜 어려운 것은 *다른 오해 유형*과 갈라내는 일이다.
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import misconception as mc  # noqa: E402
from app.llm_client import client as default_client  # noqa: E402

#: (유형ID, 발화) — 뜻은 그 유형인데 **글자가 안 겹치게** 지었다.
POSITIVES = [
    ("M01-PRINCIPAL-GUARANTEE", "예금이랑 비슷한 거 아닌가요"),
    ("M01-PRINCIPAL-GUARANTEE", "설마 넣은 돈이 없어지기야 하겠어요"),
    ("M01-PRINCIPAL-GUARANTEE", "여기 은행인데 손해 볼 일은 없겠죠"),
    ("M02-DEPOSIT-INSURANCE", "잘못돼도 나라에서 오천만원까지 돌려주잖아요"),
    ("M03-PROBABILITY", "그렇게까지 떨어질 일이 있겠어요"),
    ("M03-PROBABILITY", "반토막이야 설마 나겠습니까"),
    ("M04-EARLY-REDEMPTION", "육 개월이면 끝나는 거죠"),
    ("M04-EARLY-REDEMPTION", "만기까지 안 가고 중간에 정리되는 거 아닌가요"),
    ("M05-SAVINGS", "적금 드는 셈 치는 거죠"),
    ("M06-SURRENDER-VALUE", "중간에 그만두면 낸 만큼은 나오겠죠"),
    ("M09-NO-LISTING", "급하면 팔면 되잖아요"),
    ("M10-MIDWAY-REDEMPTION-COST", "중간에 빼도 손해는 없는 거죠"),
]

#: 오해가 아닌 발화. **정상 진행을 막으면 안 된다.**
BENIGN = [
    "원금이 손실될 수 있다는 건 이해했습니다",
    "기초자산이 40% 넘게 떨어지면 손실이 난다는 거군요",
    "조기상환은 조건을 충족해야 되는 거고요",
    "만기가 3년이고 중간에 못 판다는 점 알겠습니다",
    "설명 잘 들었습니다. 위험은 감수하겠습니다",
    "수수료는 어떻게 되나요",
    "가입 절차가 어떻게 되죠",
]


def audit(client) -> None:
    """❗**구현과 눈금을 먼저 낸다.** 이게 없으면 0.62 를 "가깝다" 로 읽는다.

    사용자가 *"정반대 뜻이 0.621 은 말이 안 된다"* 고 의심해서 전수 감사를 했고,
    구현은 정확했다. 틀린 것은 **0 이 바닥이라는 가정**이었다.
    """
    v = client.embed(["원금이 손실될 수 있습니다"])[0]
    n = math.sqrt(sum(x * x for x in v))
    print(f"차원 {len(v)} · 노름 {n:.6f}  (단위벡터로 온다)")
    print(f"  자기 자신        cos = {mc._cosine(v, v):+.4f}   (1.0 이어야 한다)")
    print(f"  부호 반전        cos = {_raw_cos(v, [-x for x in v]):+.4f}   (-1.0 이어야 한다)")

    base = ["오늘 점심은 김치찌개를 먹었다", "서울 지하철 2호선은 순환선이다",
            "고양이는 하루에 열여섯 시간을 잔다", "파이썬은 들여쓰기로 블록을 나눈다",
            "이 노래는 1997년에 발매되었다"]
    bv = client.embed(base)
    pairs = [_raw_cos(bv[i], bv[j]) for i in range(len(bv)) for j in range(i + 1, len(bv))]
    print(f"\n무관한 문장 쌍 {len(pairs)}개  cos {min(pairs):+.3f}~{max(pairs):+.3f} "
          f"(평균 {sum(pairs)/len(pairs):+.3f})")
    print("  → ❗**0 이 바닥이 아니다.** 이 눈금 위에서 아래 숫자를 읽는다.\n")


def _raw_cos(a, b) -> float:
    """`mc._cosine` 은 음수를 0 으로 자른다 — 감사에서는 자르지 않는다."""
    num = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a)); nb = math.sqrt(sum(y * y for y in b))
    return num / (na * nb) if na and nb else 0.0


def main() -> int:
    client = default_client()
    audit(client)
    types = {t.type_id: t for t in mc.library()}
    pairs = [(t, p) for t in mc.library() for p in t.patterns]
    pat_vecs = client.embed([p for _, p in pairs])

    def top(text: str) -> tuple[str, float]:
        q = client.embed([text])[0]
        best = ("", 0.0)
        for (t, _), v in zip(pairs, pat_vecs):
            sc = mc._cosine(q, v)
            if sc > best[1]:
                best = (t.type_id, sc)
        return best

    print(f"패턴 {len(pairs)}개 · 유형 {len(types)}개\n")
    print("── 양성 (뜻은 같고 글자가 다르다)")
    pos_ok, pos_scores = [], []
    for want, text in POSITIVES:
        got, sc = top(text)
        pos_scores.append(sc)
        hit = got == want
        pos_ok.append(hit)
        print(f"  {sc:.3f}  {'✅' if hit else '❌ ' + got:12s}  {text}")

    print("\n── 음성 (오해가 아니다 — 여기 걸리면 정상 진행이 막힌다)")
    neg_scores = []
    for text in BENIGN:
        got, sc = top(text)
        neg_scores.append(sc)
        print(f"  {sc:.3f}  ({got})  {text}")

    print("\n── 갈리는 자리")
    print(f"  양성 최저   {min(pos_scores):.3f}   (유형까지 맞은 것 {sum(pos_ok)}/{len(pos_ok)})")
    print(f"  음성 최고   {max(neg_scores):.3f}")
    gap = min(pos_scores) - max(neg_scores)
    print(f"  간격        {gap:+.3f}")
    if gap > 0:
        print(f"  → 이 표본에서는 {max(neg_scores):.3f} ~ {min(pos_scores):.3f} 사이면 둘 다 만족한다")
    else:
        print("  ❗겹친다 — 임계값 하나로는 못 가른다. 어느 쪽 오류를 받을지 정해야 한다")
        print("     P5 는 미탐 최소화 우선이므로 낮은 쪽(오탐 허용)으로 간다")
    print(f"\n  현재 상수 EMBED_THRESHOLD = {mc.EMBED_THRESHOLD}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
