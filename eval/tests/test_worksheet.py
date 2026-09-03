"""라벨링 작업지가 **닻을 나르지 않는다**. 소유: 정세현 (이슈 #324)

`guideline.md` 0절이 *"❗모델 등급을 보지 않는다 — 보면 그 값이 닻이 되고, 두 사람이
각자 모델을 따라가면 **일치도만 높아지고 평가는 무의미해진다**"* 로 시작한다.

`test_corpus.py` 가 **표본**에 등급이 안 실리는 것을 지키는데, 작업지는 표본 + 루브릭을
합쳐서 만들므로 **합치는 과정에서 새는 자리**가 따로 있다.

❗이 파일은 지금 **CI 에서 안 돈다**(이슈 #344 — 워크플로 셋 중 어디도 `eval` 을 모른다).
그래서 같은 검사가 `make_worksheet._assert_blind` 에 **생성기 자신의 것으로도** 있다.
`#344` 가 닫히면 생성기 쪽을 얇게 하고 여기로 무게를 옮긴다.
"""
from __future__ import annotations

import json
import pathlib
import subprocess
import sys

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "make_worksheet.py"


@pytest.fixture(scope="module")
def built(tmp_path_factory) -> tuple[str, str]:
    """작업지를 실제로 만들어 둔다.

    ❗<b>생성기가 죽으면 이 픽스처가 못 서고, 그 결과가 `FAILED` 가 아니라 `errors` 로
    나온다.</b> 그게 정상 경로다 — 등급이 새면 파일을 안 남기고 죽는 것이 이 도구의 설계라,
    "테스트가 깨졌다" 가 아니라 "가드가 걸렸다" 로 읽는다.
    """
    out = tmp_path_factory.mktemp("ws")
    subprocess.run([sys.executable, str(SCRIPT), "--name", "검사", "--out", str(out)],
                   check=True, capture_output=True)
    return ((out / "검사.md").read_text(encoding="utf-8"),
            (out / "검사.jsonl").read_text(encoding="utf-8"))


def test_the_worksheet_lives_outside_the_data_dir() -> None:
    """❗작업지가 `eval/data/` 밖에 놓인다 — 거기 model.jsonl 이 있다.

    그 안에 두면 이 도구의 주장(*"그 디렉토리를 안 열어도 되게 한다"*)이 성립하지 않는다.
    라벨러가 작업지를 열려면 그 디렉토리를 지나고, `model.jsonl` 이 바로 옆이다.
    """
    sys.path.insert(0, str(ROOT))
    import make_worksheet

    assert "data" not in make_worksheet.OUT_DIR.relative_to(ROOT).parts, \
        f"작업지가 eval/data/ 안이다: {make_worksheet.OUT_DIR}"


def test_submitting_refuses_an_incomplete_sheet(tmp_path) -> None:
    """❗빈 등급·잘못된 등급·중복·누락을 제출 시점에 센다.

    작업지가 *"애매해도 비워 두지 않는다 — 빈 항목은 교집합에서 빠져 표본만 줄인다"* 고
    적어 두는데, 기계가 세야 그 문장이 규약에서 검사로 바뀐다.
    """
    sys.path.insert(0, str(ROOT))
    import make_worksheet

    src, labels = tmp_path / "ws", tmp_path / "labels"
    src.mkdir()
    corpus = make_worksheet.load_corpus()
    def write(rows): (src / "검사.jsonl").write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")

    write([{"sample_id": r["sample_id"], "item_id": r["item_id"], "grade": ""} for r in corpus])
    with pytest.raises(SystemExit) as blank:
        make_worksheet.submit("검사", src, labels)
    assert "빈 등급" in str(blank.value)

    write([{"sample_id": r["sample_id"], "item_id": r["item_id"], "grade": "U1"} for r in corpus[:3]])
    with pytest.raises(SystemExit) as short:
        make_worksheet.submit("검사", src, labels)
    assert "안 붙인 항목" in str(short.value), "부분 제출을 받으면 교집합이 그만큼 줄어든다"

    assert not labels.exists(), "거부했는데 파일을 남기면 러너가 그걸 라벨로 센다"

    write([{"sample_id": r["sample_id"], "item_id": r["item_id"], "grade": "U1"} for r in corpus])
    make_worksheet.submit("검사", src, labels)
    assert (labels / "검사.jsonl").exists()


def test_submitting_never_lists_other_labelers(tmp_path, capsys) -> None:
    """❗제출 출력에 다른 라벨러의 파일명이 안 나온다.

    `labels/` 에는 먼저 낸 사람의 파일이 있다. 두 번째 사람이 그 디렉토리를 지나면
    파일명만 봐도 누가 냈는지 알고, 한 번 열면 등급이 보인다 — **닻이 모델에서 사람으로
    바뀐 것뿐이고 상한은 똑같이 무의미해진다.**
    """
    sys.path.insert(0, str(ROOT))
    import make_worksheet

    src, labels = tmp_path / "ws", tmp_path / "labels"
    src.mkdir(); labels.mkdir()
    (labels / "먼저낸사람.jsonl").write_text('{"sample_id":"els-0001","item_id":"X","grade":"U4"}\n',
                                              encoding="utf-8")
    corpus = make_worksheet.load_corpus()
    (src / "검사.jsonl").write_text("".join(
        json.dumps({"sample_id": r["sample_id"], "item_id": r["item_id"], "grade": "U1"},
                   ensure_ascii=False) + "\n" for r in corpus), encoding="utf-8")

    make_worksheet.submit("검사", src, labels)

    out = capsys.readouterr().out
    assert "먼저낸사람" not in out and "U4" not in out


def test_the_worksheet_carries_no_grade(built) -> None:
    """★ 등급 토큰이 하나도 없다 — 있으면 그게 닻이다."""
    import re
    for what, text in zip(("작업지", "골격"), built):
        assert not re.findall(r"\bU[1-4]\b", text), f"{what} 에 등급이 샜다"


def test_it_covers_the_whole_corpus(built) -> None:
    """★ 전수여야 한다 — 부분만 붙이면 두 사람의 교집합이 그만큼 줄고, 그 표본으로 낸
    QWK 는 무엇의 추정치도 아니다(`run_eval.aligned` 가 교집합을 쓴다)."""
    corpus = [l for l in (ROOT / "corpus" / "els.jsonl").read_text(encoding="utf-8").splitlines()
              if l.strip() and not l.startswith("#")]
    _, skeleton = built
    assert len([l for l in skeleton.splitlines() if l.strip()]) == len(corpus)


def test_it_never_reads_the_anchors(monkeypatch) -> None:
    """❗모델 등급·AI 참조·다른 사람 라벨을 여는 경로가 소스에 없다.

    출력만 보면 "이번에는 안 샜다" 까지고, 여는 코드가 생기면 다음에 샌다.
    """
    src = SCRIPT.read_text(encoding="utf-8")
    body = src.split('"""', 2)[2]        # 머리말 docstring 은 "안 연다" 를 설명하는 자리다
    for anchor in ("model.jsonl", "ai-reference.jsonl", "data/labels"):
        assert f'"{anchor}"' not in body.replace('NEVER_READ = ("model.jsonl", '
                                                 '"ai-reference.jsonl", "labels/")', ""), \
            f"{anchor} 를 여는 코드가 생겼다"


def test_the_guard_actually_fires() -> None:
    """★ 가드가 무언가를 실제로 거른다 — 안 그러면 위 단정들이 늘 초록이다.

    실제로 처음 돌렸을 때 **작업지 머리말**이 걸렸다(판단 순서를 적으면서 등급 이름을
    옮겨 적었다). 그 자리가 살아 있는지 본다.
    """
    sys.path.insert(0, str(ROOT))
    import make_worksheet

    with pytest.raises(SystemExit) as exc:
        make_worksheet._assert_blind("이 발화는 U2 로 본다", "합성")
    assert "U2" in str(exc.value)


def test_the_generator_actually_calls_the_guard(tmp_path, monkeypatch) -> None:
    """❗가드 **호출부**가 잠겨 있다 — 함수만 재면 부르는 자리가 빈다.

    실제로 그랬다: `main()` 에서 `_assert_blind` 호출 두 줄을 지워도 위 단정들이 전부
    초록이었다. 지금 데이터가 깨끗해서 출력에 등급이 없기 때문이다 — 즉 **가드가 없어도
    오늘은 티가 안 난다.** 가드는 미래의 데이터를 위한 것이라 그 자리가 살아 있는지는
    따로 재야 한다.

    오늘 이 레포에서 반복해서 나온 모양이다(#340 · #326 · #304).
    """
    sys.path.insert(0, str(ROOT))
    import make_worksheet

    poisoned = tmp_path / "rubrics"
    poisoned.mkdir()
    for src in (ROOT.parent / "ai-service" / "app" / "rubrics").glob("*.yaml"):
        poisoned.joinpath(src.name).write_text(src.read_text(encoding="utf-8"), encoding="utf-8")
    # 루브릭 문면에 등급이 섞이는 것이 실제로 샐 수 있는 경로다(작업지는 표본 + 루브릭이다).
    target = poisoned / "ELS-PRINCIPAL-LOSS-WARNING.yaml"
    target.write_text(target.read_text(encoding="utf-8").replace(
        "misconception_conditions:", "misconception_conditions:\n  - 이건 U4 로 본다", 1),
        encoding="utf-8")

    out = tmp_path / "out"
    monkeypatch.setattr(make_worksheet, "RUBRICS", poisoned)
    monkeypatch.setattr(sys, "argv", ["make_worksheet.py", "--name", "검사", "--out", str(out)])

    with pytest.raises(SystemExit) as exc:
        make_worksheet.main()

    assert "U4" in str(exc.value)
    assert not out.exists(), "가드가 걸렸는데 파일을 남기면 라벨러가 그걸 본다"


def test_the_docstring_does_not_name_a_stale_path() -> None:
    """❗머리말이 코드와 같은 경로를 말한다.

    실제로 낡았다 — `OUT_DIR` 을 `eval/labeling/` 로 옮기면서 머리말의 `eval/data/worksheet/`
    를 안 고쳤다(리뷰에서 사람이 읽고 잡았다). **하필 이 도구가 없애려는 그 디렉토리를
    가리키고 있어서**, 머리말만 읽은 사람이 없는 자리를 찾다가 `eval/data/` 를 `ls` 한다.

    코드는 맞고 문서가 틀린 경우라 어떤 실행에서도 안 드러난다 — 그래서 여기서 본다.
    """
    sys.path.insert(0, str(ROOT))
    import make_worksheet

    doc = make_worksheet.__doc__ or ""
    out_rel = make_worksheet.OUT_DIR.relative_to(ROOT.parent).as_posix()

    assert out_rel in doc, f"머리말이 실제 출력 경로({out_rel})를 안 말한다"
    assert "eval/data/worksheet" not in doc, \
        "머리말이 옛 경로를 말한다 — 이 도구가 없애려는 디렉토리다"
    assert "로 옮긴다" not in doc or "--submit" in doc, \
        "손으로 옮기라는 안내가 남아 있다 — 제출은 --submit 이 한다"

