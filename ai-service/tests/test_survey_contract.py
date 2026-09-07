"""설문 문항 세트 ↔ 축 매핑 교차 검증. 소유: 윤지석

`web/src/lib/survey.ts` 의 문항 ID 집합과 `mismatch.AXIS_BY_QUESTION` 의 키가 **문자
그대로 일치해야** 모순 판정이 성립한다. 그런데 둘을 묶는 것이 문자열뿐이다 —
PR #113 리뷰(정세현)의 지적이다.

    AXIS_BY_QUESTION ↔ Contradiction.axis   assert_axis_map_matches_contract() 가 대조
    AXIS_BY_QUESTION ↔ web 문항 ID          ← 여기가 비어 있었다

문항이 바뀔 때 두 갈래가 있고 위험이 다르다.

| | 결과 |
|---|---|
| 새 키 추가·개명 | `UnknownSurveyQuestion` → 422. **드러난다** (다만 요청 시점에) |
| 기존 키를 **다른 뜻으로 재사용** | 에러도 없이 **틀린 축으로 판정** |

두 번째가 결함이 정상과 같은 모양이다. 이름이 같으니 집합 비교로는 안 잡힌다. 그래서
**문항 문면까지 고정**한다 — 문면이 바뀌면 여기서 깨지고, 그때 사람이 "축이 그대로인가" 를
판단한다. 문면 전체를 스냅샷으로 두면 노이즈가 크므로 **해시**만 둔다.

CI(이슈 #73)가 없어서 교차 언어 검사를 CI 에 넣을 수 없다. 같은 레포에 파일이 있으므로
pytest 가 **읽기만** 한다 — `web/` 은 오준서 소유고 고치지 않는다.
"""
from __future__ import annotations

import hashlib
import re
from pathlib import Path

import pytest

from app import mismatch

SURVEY_TS = Path(__file__).resolve().parents[2] / "web" / "src" / "lib" / "survey.ts"

#: `SURVEY_QUESTIONS` 항목의 `id` / `text`. TypeScript 를 파싱하지 않고 리터럴만 긁는다 —
#: 파서를 들이면 그것이 또 하나의 유지 대상이 된다.
_ENTRY = re.compile(r'id:\s*"(SUIT-[A-Z-]+)"\s*,\s*\n\s*text:\s*"([^"]+)"')

#: 문항별 `options` 블록. **문면 해시(`_ENTRY`)가 안 보는 곳**이라 따로 읽는다 —
#: 해시는 `id + text` 만 먹으므로 선택지만 바뀌면 초록으로 지나간다. 실제로 그랬다:
#: `b84eb45`(8/28)가 `SUIT-PRODUCT-EXPERIENCE` 의 선택지를 바꿨는데 dev set 픽스처가
#: 열흘 동안 죽은 문면을 들고 있었고 **아무 대조도 안 물었다.**
#: ❗**`id` 와 `options` 사이에 다른 `id` 가 없어야 한다.** 앵커가 없으면 `options` 가 없는
#: 문항이 하나 생기는 순간 그 id 가 **다음 문항의 선택지와 짝지어지고**, 짝을 잃은 문항은
#: `live` 에서 통째로 사라진다 (`#525` 리뷰, 오준서 — 넣어 보고 확인했다).
_OPTIONS = re.compile(
    r'id:\s*"(SUIT-[A-Z-]+)"(?:(?!id:\s*")[\s\S])*?options:\s*\[([\s\S]*?)\]')

#: ❗**주석을 먼저 걷는다.** 안 걷으면 주석에 적힌 옛 문면이 「살아 있는 선택지」로
#: 부활하고, 죽은 문면을 든 픽스처가 통과한다. 이 레포가 바로 그 관례를 쓴다 —
#: `survey.ts` doc 주석이 v1 선택지 둘을 들고 있다 (`#525` 리뷰, 오준서).
_COMMENT = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/')

#: 화면이 선언한 세트 버전. 픽스처가 든 값과 같아야 한다.
_VERSION = re.compile(r'SURVEY_SCHEMA_VERSION\s*=\s*"([^"]+)"')

#: 문항 ID + 문면의 해시. **문면이 바뀌면 이 값이 바뀌고 테스트가 깨진다.**
#: 갱신은 손으로 한다 — 자동 갱신되면 검사의 뜻이 없어진다. 깨졌을 때 물어야 하는 것은
#: "문면이 바뀌었는데 그 문항의 축이 그대로인가" 다.
#: ❗**payload 가 `id + text + 선택지` 다** (`#525` 리뷰). 예전 값 `d74035c2` 는 선택지를
#: 안 먹었고, 그래서 `b84eb45`(8/28)가 선택지를 바꿨을 때 **이 핀이 안 움직였다.**
EXPECTED_DIGEST = "0a32107a"   # s02-survey-v2 · 6문항 · 선택지 22개 (2026-09-07)


def _entries() -> list[tuple[str, str]]:
    if not SURVEY_TS.is_file():
        pytest.fail(
            f"설문 세트 파일을 찾지 못했다: {SURVEY_TS}. "
            "web 이 옮겨졌으면 이 경로를 갱신한다 — 조용히 skip 하면 대조가 없어진다"
        )
    found = _ENTRY.findall(SURVEY_TS.read_text(encoding="utf-8"))
    assert found, "survey.ts 에서 문항을 하나도 못 읽었다 — 리터럴 형태가 바뀌었나"
    return found


def test_axis_map_covers_exactly_the_web_question_set():
    """한쪽만 늘면 422(추가) 또는 영구 미사용(삭제)이 된다. 배포 전에 알아야 한다."""
    web_ids = {qid for qid, _ in _entries()}
    mapped = set(mismatch.AXIS_BY_QUESTION)
    assert web_ids == mapped, (
        f"web 에만: {sorted(web_ids - mapped)} · 매핑에만: {sorted(mapped - web_ids)}"
    )


def test_question_wording_is_pinned():
    """★ 기존 ID 를 다른 뜻으로 재사용하면 집합 비교로는 안 잡힌다.

    문면 해시가 바뀌면 여기서 깨진다. 깨졌다고 자동으로 문제인 것은 아니다 — 문구를 다듬은
    것일 수 있다. **물어야 하는 것은 그 문항의 축이 여전히 같은가** 이고, 확인 뒤
    `EXPECTED_DIGEST` 를 갱신한다.
    """
    # ❗선택지까지 먹인다. 곁가지 검사로는 **인용되는 것만** 덮인다 — live 22개 중 8개는
    #   dev set 이 안 쓴다. 뿌리는 이 핀이다 (`#525` 리뷰).
    options = _live_options()
    payload = "\n".join(f"{qid}\t{text}\t{'|'.join(options[qid])}"
                        for qid, text in sorted(_entries()))
    digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()[:8]
    assert digest == EXPECTED_DIGEST, (
        f"설문 문항 ID·문면이 바뀌었다 (해시 {EXPECTED_DIGEST} → {digest}).\n"
        "각 문항의 축이 그대로인지 확인하고 AXIS_BY_QUESTION 을 맞춘 뒤 이 값을 갱신한다.\n"
        + payload
    )


def test_devset_uses_the_same_question_set():
    """세션 dev set 이 web 세트와 다른 키를 쓰면 검증하는 대상이 실물이 아니다."""
    import yaml

    web_ids = {qid for qid, _ in _entries()}
    sessions = Path(__file__).resolve().parent / "fixtures" / "sessions"
    if not sessions.is_dir():
        pytest.skip("세션 dev set 은 별건(PR #117)이다")
    for path in sorted(sessions.glob("*.yaml")):
        spec = yaml.safe_load(path.read_text(encoding="utf-8"))
        for case in spec["cases"]:
            used = {k for k in case["survey"] if k.startswith(mismatch.QUESTION_KEY_PREFIX)}
            assert used <= web_ids, f"{path.name}/{case['id']}: {sorted(used - web_ids)}"


# ── 값이 살아 있는 선택지인가 ────────────────────────────────────────────────
#
# ❗**여기가 비어 있었다.** 기존 셋은 문항 **ID**(축 매핑)와 문항 **문면**(해시)을 본다.
# 설문 값 — 즉 사용자가 고른 **선택지 문장** — 은 아무도 안 봤다. 그런데 그 문장이 곧
# `recorded_answer` 이고 모순 판정(F-DET-002)이 대조하는 대상이다.
#
# `b84eb45` 가 `"있다 — 손실을 본 적은 없다"` → `"있고 이득을 봤다"` 로 바꿨을 때
# ID 도 문항 텍스트도 안 바뀌어서 **대조 셋이 전부 초록이었고**, dev set 이 열흘 동안
# 제품에 없는 문면으로 모순 판정을 재고 있었다.
#: ❗**글롭으로 돈다 — 파일명을 박지 않는다** (`#525` 리뷰, 오준서). 형제 검사들이 전부
#: 글롭이라(`test_devset_uses_the_same_question_set` · `run_devset.py`) 여기만 이름을
#: 박으면 **두 번째 yaml 이 검사 밖으로 샌다.** 그리고 판을 올릴 때 이름을 먼저 바꾸면
#: 하드코딩은 `FileNotFoundError` 로 죽어 **그 경우를 위해 쓴 안내 문면이 안 뜬다.**
FIXTURES = Path(__file__).resolve().parent / "fixtures" / "sessions"


def _live_options() -> dict[str, list[str]]:
    """`survey.ts` 의 문항별 선택지. **주석을 걷고 읽는다.**

    순서를 지키는 리스트다 — 다이제스트가 이 값을 먹으므로 집합이면 **선택지 순서를 바꾸는
    변경이 조용히 지나간다.** 화면이 그 순서대로 그린다.
    """
    text = _COMMENT.sub("", SURVEY_TS.read_text(encoding="utf-8"))
    found = {qid: re.findall(r'"([^"]+)"', block) for qid, block in _OPTIONS.findall(text)}
    assert found, "survey.ts 에서 선택지를 하나도 못 읽었다 — 리터럴 형태가 바뀌었나"

    # ❗문항 집합과 선택지 집합이 같아야 한다. 다르면 짝을 못 지은 문항이 생긴 것이고,
    #   그 문항은 아래 대조에서 **조용히 빠진다** (`#525` 리뷰).
    questions = {qid for qid, _ in _entries()}
    assert set(found) == questions, (
        f"문항과 선택지의 짝이 안 맞는다 — 문항만 {sorted(questions - set(found))} · "
        f"선택지만 {sorted(set(found) - questions)}"
    )
    return found


def _fixtures() -> list[tuple[Path, dict]]:
    import yaml

    found = [(path, yaml.safe_load(path.read_text(encoding="utf-8")))
             for path in sorted(FIXTURES.glob("*.yaml"))]
    assert found, f"세션 픽스처를 하나도 못 찾았다: {FIXTURES}"
    return found


def test_the_options_are_actually_read() -> None:
    """★ 양성 대조. 선택지를 못 읽으면 아래 단정이 **빈 집합끼리** 견주고 통과한다."""
    live = _live_options()
    assert len(live) >= 6, f"문항이 6개 이상이어야 한다 — 읽은 것 {sorted(live)}"
    for qid, options in live.items():
        assert len(options) >= 2, f"{qid}: 선택지가 {options} — 하나뿐일 수 없다"


def test_every_fixture_answer_is_a_live_option() -> None:
    """★ dev set 의 설문 값이 **지금 화면에 있는 선택지**여야 한다."""
    live = _live_options()
    stale: list[str] = []
    for path, data in _fixtures():
      for case in data["cases"]:
        for qid, value in case["survey"].items():
            # ⑥ 메타키 판별은 `mismatch.QUESTION_KEY_PREFIX` 하나가 정본이다(#44 관례).
            if not qid.startswith(mismatch.QUESTION_KEY_PREFIX):
                continue
            # ① 가드가 아니라 **단정**이다. 가드로 두면 그 문항이 조용히 빠진다.
            assert qid in live, f"{case['id']}: {qid} 의 선택지를 못 읽었다"
            if value not in live[qid]:
                stale.append(f"{path.name} {case['id']}: {qid} = {value!r} "
                             f"(지금 있는 것: {sorted(live[qid])})")
    assert not stale, (
        "제품에 없는 선택지 문면을 쓰고 있다 — 그 값은 실세션이 만들 수 없다:\n  "
        + "\n  ".join(stale)
    )


def test_the_fixture_declares_the_live_set_version() -> None:
    """픽스처가 든 세트 버전이 화면 정본과 같아야 한다.

    위 단정이 값을 잡으므로 이건 **표기**를 맞추는 것이다 — 파일명·머리말이 낡으면
    다음 사람이 어느 세트를 보는지 헷갈린다.
    """
    declared = _VERSION.search(SURVEY_TS.read_text(encoding="utf-8"))
    assert declared, "survey.ts 에서 SURVEY_SCHEMA_VERSION 을 못 읽었다"
    live = declared.group(1)
    for path, data in _fixtures():
        declared_in_file = data["survey_schema_version"]
        assert declared_in_file == live, (
            f"{path.name} 은 {declared_in_file} 을 선언하는데 화면은 {live} 다 — "
            "옛 판 픽스처가 남아 있으면 run_devset 이 그것도 돌린다"
        )
        # 파일명을 **파일이 선언한 값에서 유도한다.** 이름을 먼저 바꿔도 이 문면이 뜬다.
        assert path.name == f"{declared_in_file}.yaml", (
            f"파일명이 세트 버전이다 — {path.name} 인데 선언은 {declared_in_file}"
        )


def test_a_commented_out_option_does_not_come_back_to_life() -> None:
    """★ 주석 안의 문자열이 **살아 있는 선택지로 부활하면 안 된다** (`#525` 리뷰, 오준서).

    `options` 블록 안의 큰따옴표를 통째로 긁으면 주석에 적힌 옛 문면이 `live` 에 들어오고,
    **죽은 문면을 들고 있는 픽스처가 통과한다.** 이 레포가 바로 그 관례를 쓴다 —
    `survey.ts` 의 doc 주석이 v1 선택지 두 개를 그대로 들고 있다.

    실측(주석 걷기를 지우면):

        live['SUIT-PRODUCT-EXPERIENCE']
          있음 ['없다', '있고 이득을 봤다', '있지만 손실을 봤다']
          없음 ['없다', '있다 — 손실을 본 적은 없다', '있고 이득을 봤다', '있지만 손실을 봤다']
    """
    block = '''
  {
    id: "SUIT-PROBE-ONLY",
    text: "탐침",
    options: [
      // v1 문면: "죽은 선택지"
      /* 여러 줄
         "또 다른 죽은 것" */
      "살아 있는 것",
    ],
  },
'''
    found = _OPTIONS.findall(_COMMENT.sub("", block))
    assert found, "탐침 블록을 못 읽었다 — 이 대조가 형태를 안 재고 있다"
    qid, options = found[0]
    assert qid == "SUIT-PROBE-ONLY"
    assert re.findall(r'"([^"]+)"', options) == ["살아 있는 것"], (
        "주석의 문면이 선택지로 새어 들어왔다"
    )


def test_a_question_without_options_does_not_steal_the_next_ones() -> None:
    """★ `options` 가 없는 문항이 **다음 문항의 선택지와 짝지어지면 안 된다** (`#525` 리뷰).

    앵커가 없으면 짝을 잃은 문항이 `live` 에서 통째로 사라지고, 그 문항의 픽스처 답은
    **검사에서 조용히 빠진다** — 이 PR 이 없애려던 바로 그 상태다.
    """
    block = '''
  { id: "SUIT-NO-OPTIONS", text: "자유 서술" },
  { id: "SUIT-HAS-OPTIONS", text: "고르기", options: ["가", "나"] },
'''
    found = dict(_OPTIONS.findall(_COMMENT.sub("", block)))
    assert "SUIT-NO-OPTIONS" not in found, (
        "선택지 없는 문항이 다음 문항의 것을 가져갔다 — 그러면 뒤 문항이 live 에서 사라진다"
    )
    assert re.findall(r'"([^"]+)"', found["SUIT-HAS-OPTIONS"]) == ["가", "나"]
