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

#: 문항 ID + 문면의 해시. **문면이 바뀌면 이 값이 바뀌고 테스트가 깨진다.**
#: 갱신은 손으로 한다 — 자동 갱신되면 검사의 뜻이 없어진다. 깨졌을 때 물어야 하는 것은
#: "문면이 바뀌었는데 그 문항의 축이 그대로인가" 다.
EXPECTED_DIGEST = "d74035c2"   # s02-survey-v1, 6문항 (2026-08-27 확인)


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
    payload = "\n".join(f"{qid}\t{text}" for qid, text in sorted(_entries()))
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
