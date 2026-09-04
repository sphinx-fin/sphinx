#!/usr/bin/env python3
"""건너뛴 pytest 가 있으면 실패로 만든다. 소유: 오준서 (`.github/workflows/` 짝)

`ci.yml` 머리말 ① 이 근거다 — **CI 에서 skip 은 "검산이 안 돌았다"** 인데 로그는 초록으로
남는다. 이슈 #73 이 *"지금 초록이 초록이 아니다"* 라고 부른 상태가 그것이다.

❗**이 검사가 파일 하나인 이유.** 원래 `ai-service` 스텝 안에 인라인으로 있었고, `eval` 을
붙이면서 같은 20줄을 한 벌 더 적을 뻔했다(이슈 #344). 그러면 `pr-review-guard.yml` ·
`pr-reviewer-label.yml` 이 판정을 각자 구현해 네 번 갈렸던 것과 같은 모양이 된다
(CLAUDE.md §PR 리뷰 라벨). 판정은 한 곳에서만 만든다.

    python3 .github/scripts/no_skip.py <junit.xml> "<이 모듈에서 skip 이 나오면 안 되는 이유>"
"""

from __future__ import annotations

import pathlib
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    if len(sys.argv) != 3:
        print(f"::error::인자는 둘이다 — <junit.xml> <이유>. 받은 것: {sys.argv[1:]}")
        return 2

    path, why = pathlib.Path(sys.argv[1]), sys.argv[2]
    if not path.exists():
        print(f"::error::pytest 결과 파일이 없다({path}) — 수집 단계에서 죽었는지 확인한다.")
        return 1

    root = ET.parse(path).getroot()
    # pytest 는 `testsuites` 로 감싸는 판과 `testsuite` 를 바로 내는 판이 갈린다.
    suite = root if root.tag == "testsuite" else root.find("testsuite")
    if suite is None:
        print(f"::error::{path} 에 testsuite 가 없다 — 결과가 비었다.")
        return 1

    total, skipped = int(suite.get("tests", 0)), int(suite.get("skipped", 0))
    print(f"{path} — 총 {total}건 · skip {skipped}")

    # ❗0건도 실패다. "전부 통과" 와 "하나도 안 걸렸다" 는 다른 사실인데 pytest 는 둘 다
    # 종료코드 0 을 낸다(`--junitxml` 도 빈 suite 를 정상으로 적는다). 경로를 잘못 적어
    # 수집이 0건이 되는 것이 이 워크플로에서 제일 조용한 실패다.
    if total == 0:
        print(f"::error::수집된 테스트가 0건이다({path}) — 경로가 맞는지 본다.")
        return 1

    if skipped:
        print(f"::error::건너뛴 테스트가 있다. {why}")
        for tc in suite.iter("testcase"):
            if tc.find("skipped") is not None:
                print(f"::error::skip → {tc.get('classname')}::{tc.get('name')}")
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
