"""자기일관성 재질의를 **첫 채점과 동시에** 던진다 (이슈 #437 (다) · F-SCR-001).

## 왜 있나 — 지연의 곱이 여기였다

`#437` 이 *"답변 제출마다 로딩이 길어 다음 문항으로 못 넘어간다"* 로 열렸고, 원인이
극성 게이트(`#423`)일 것으로 지목됐다. 실측은 아니었다.

    후보 수      공식 코퍼스 70행 후보 0개 · dev set 35행 평균 0.31 · 최대 1
    한 답변      U4 3.3초(게이트 1.1) · U1 4.4초(재질의 2.0) · U3 2.0초

**게이트가 도는 답변이 오히려 빠르다.** 곱은 `CONSISTENCY_GRADES = ("U1",)` 재질의이고,
하필 **잘 대답한 고객** — 데모가 보여주는 경로가 그것이다.

    실측 3회 (gpt-5-mini)   순차 4.64 / 4.55 / 4.29초  →  병렬 2.31 / 2.07 / 2.06초

## ❗바뀌지 않는 것

두 호출은 **여전히 독립**이다 — 요청이 둘이고 seed 가 다르며 프롬프트가 같다. `#370` 이
재려던 *"다시 물으면 같게 나오는가"* 가 그대로 성립한다. 한 요청 안에서 두 판정을 받는
형태였다면 그 독립성이 깨졌을 것이고, 그래서 그 안은 안 골랐다.

## ❗대가는 투기 호출이다 — 이 파일이 그것도 잠근다

병렬로 하려면 등급을 보기 **전에** 던져야 한다. 통과 판정이 아닌 답변에서는 그 호출이
버려진다(`METER.discarded`). `test_self_consistency.py` 의
`test_a_partial_understanding_is_not_re_asked` 가 *"게이트를 안 바꾸는 등급에 호출을 두
배로 쓰지 않는다"* 를 잠그는데, **그 단정은 순차 경로의 것**이다 — 병렬에서는 호출이
두 번 나가고 하나가 버려진다. 두 사실이 같이 서 있어야 다음 사람이 어느 쪽을 보는지 안다.

## 스위트 기본은 순차다

`conftest.py` 가 `_parallel_enabled` 를 꺼 둔다 — 이 레포의 스텁 상당수가 **호출 순서대로**
답을 돌려줘서, 투기 호출이 켜지면 두 스레드가 같은 큐를 꺼내 회차마다 갈린다. 운영에서는
두 요청이 독립이라 없는 문제이고 **스텁의 성질**이다. 그래서 여기서만 켜고, 스텁도
스레드 안전한 것을 따로 쓴다.
"""
from __future__ import annotations

import threading
import time
from concurrent.futures import Future

import pytest

from app import scoring
from app.llm_client import LlmError
from app.schemas import Grade, Judgment, RiskItem

ANSWER = "제가 낸 돈보다 적게 돌려받을 수도 있다는 뜻으로 이해했습니다"


def _risk_item() -> RiskItem:
    return RiskItem(
        item_id="ELS-PRINCIPAL-LOSS-WARNING", product_id="p", name="원금 손실",
        importance="required", status="extracted",
        condition={"value_text": "만기평가일에 최초기준가격의 65% 미만",
                   "source_span": {"page": 1, "start": 0, "end": 20}},
    )


def _judgment(grade: Grade, confidence: float = 1.0) -> Judgment:
    from app import rubrics
    rubric = rubrics.get("ELS-PRINCIPAL-LOSS-WARNING")
    return Judgment(
        item_id=rubric.item_id, grade=grade, confidence=confidence,
        evidence={"utterance_quote": ANSWER[:10],
                  "rubric_clause": rubric.required_elements[0]},
        reason="사유",
    )


class ThreadSafeLlm:
    """**seed 로 답을 고른다.** 호출 순서에 안 매인다 — 그게 이 파일의 전제다.

    ❗순서 기반 스텁(`SequenceLlm`)을 여기서 쓰면 두 스레드가 같은 큐를 꺼내 회차마다
    갈린다. 그건 운영의 성질이 아니라 스텁의 성질이라, 그걸로 병렬을 재면 **무엇이
    깨진 것인지 알 수 없는 실패**가 나온다.
    """

    def __init__(self, *, first: Judgment, second: Judgment | None = None,
                 delay: float = 0.0, second_raises: Exception | None = None):
        self._first = first
        self._second = second if second is not None else first
        self._delay = delay
        self._second_raises = second_raises
        self._lock = threading.Lock()
        self.seeds: list[object] = []
        self.schemas: list[str] = []

    def complete_json(self, **kwargs):
        seed = kwargs.get("seed")
        with self._lock:
            self.seeds.append(seed)
            self.schemas.append(kwargs.get("schema_name"))
        if self._delay:
            time.sleep(self._delay)
        base = scoring._attempt_seed(1)
        if base is not None and seed != base:          # 재질의 쪽
            if self._second_raises is not None:
                raise self._second_raises
            return self._second
        return self._first

    @property
    def judgment_calls(self) -> int:
        return sum(1 for s in self.schemas if s == "Judgment")


@pytest.fixture()
def parallel(monkeypatch):
    """병렬을 켜고 계량기를 비운다. **계량기가 전역이라 회차 간 새면 단정이 뜻을 잃는다.**"""
    monkeypatch.setattr(scoring, "_parallel_enabled", lambda: True)
    scoring.METER = scoring.ConsistencyMeter()
    yield scoring.METER
    scoring.METER = scoring.ConsistencyMeter()


def _score(llm) -> Judgment:
    return scoring.score("ELS-PRINCIPAL-LOSS-WARNING", "질문?", ANSWER,
                         _risk_item(), "ELS", llm=llm)


# ── ① 실제로 겹쳐서 돈다 ────────────────────────────────────────────────────────
def test_the_two_calls_actually_overlap(parallel) -> None:
    """★ **벽시계로 잰다.** 두 호출이 각각 자고 있는데 합보다 짧게 끝나야 한다.

    호출 수만 세면 *"두 번 불렀다"* 까지만 알 수 있고 **동시에 불렀는지는 모른다** —
    순차로 두 번 부르는 코드도 같은 숫자를 낸다. 이 PR 이 사는 이유가 벽시계라
    그것을 직접 잰다.
    """
    delay = 0.25
    llm = ThreadSafeLlm(first=_judgment(Grade.U1), delay=delay)

    start = time.monotonic()
    _score(llm)
    elapsed = time.monotonic() - start

    assert llm.judgment_calls == 2, f"두 호출이 다 나가야 한다: {llm.schemas}"
    assert elapsed < delay * 2, (
        f"두 호출이 겹치지 않았다 — {elapsed:.2f}초 >= 순차분 {delay * 2:.2f}초. "
        "투기 호출이 첫 채점 **뒤**로 밀렸는지 본다"
    )


def test_serial_path_does_not_overlap(monkeypatch) -> None:
    """양성 대조 — **꺼 두면 겹치지 않는다.** 위 단정이 늘 참인 것이 아님을 보인다."""
    monkeypatch.setattr(scoring, "_parallel_enabled", lambda: False)
    delay = 0.25
    llm = ThreadSafeLlm(first=_judgment(Grade.U1), delay=delay)

    start = time.monotonic()
    _score(llm)
    elapsed = time.monotonic() - start

    assert llm.judgment_calls == 2
    assert elapsed >= delay * 2, f"순차인데 겹쳤다: {elapsed:.2f}초"


# ── ② 투기 호출이 쓰이고, 안 쓰이면 버려진다 ────────────────────────────────────
def test_a_passing_grade_uses_the_speculative_call(parallel) -> None:
    """통과 판정(U1)이면 투기 호출을 **그대로 쓴다** — 다시 던지지 않는다."""
    llm = ThreadSafeLlm(first=_judgment(Grade.U1))

    _score(llm)

    assert llm.judgment_calls == 2, f"통과 판정은 두 호출이다: {llm.schemas}"
    assert parallel.snapshot() == {
        "needed": 1, "speculated": 1, "used": 1,
        "discarded": 0, "no_slot": 0, "disabled": 0, "failed": 0,
    }


@pytest.mark.parametrize("grade", [Grade.U2, Grade.U3, Grade.U4])
def test_a_non_passing_grade_discards_the_speculative_call(parallel, grade) -> None:
    """❗**대가를 명시적으로 잠근다.** 통과가 아니면 던진 호출이 버려진다.

    `test_self_consistency.py` 의 *"게이트를 안 바꾸는 등급에 호출을 두 배로 쓰지
    않는다"* 는 **순차 경로**의 단정이다. 병렬에서는 두 번 나가고 하나가 버려지며,
    그 몫이 `discarded` 다 — 쿼터가 왜 늘었는지 이 숫자로 답한다.
    """
    llm = ThreadSafeLlm(first=_judgment(grade))

    judgment = _score(llm)

    assert judgment.grade == grade, "버린 호출이 판정을 건드리면 안 된다"
    assert parallel.discarded == 1
    assert parallel.used == 0
    assert parallel.needed == 0, "필요하지도 않았다 — 그래서 버린 것이다"


def test_the_discarded_call_does_not_block_the_answer(parallel) -> None:
    """❗**버린 호출을 기다리지 않는다.**

    기다리면 통과가 아닌 답변(대부분)이 지금보다 **느려진다** — 줄이려던 것을 늘리는
    꼴이다. 투기 호출을 길게 재우고 판정이 그보다 먼저 나오는 것을 잰다.
    """
    llm = ThreadSafeLlm(first=_judgment(Grade.U3), delay=0.0)
    # 재질의만 오래 걸리게 한다: seed 로 갈라 재우는 스텁
    slow = 0.6

    class SlowSecond(ThreadSafeLlm):
        def complete_json(self, **kwargs):
            base = scoring._attempt_seed(1)
            if base is not None and kwargs.get("seed") != base:
                time.sleep(slow)
            return super().complete_json(**kwargs)

    llm = SlowSecond(first=_judgment(Grade.U3))
    start = time.monotonic()
    _score(llm)
    elapsed = time.monotonic() - start

    assert elapsed < slow, (
        f"버린 투기 호출을 기다렸다 — {elapsed:.2f}초. `_discard_probe` 가 "
        "`result()` 를 부르고 있는지 본다"
    )


# ── ③ 실패해도 판정이 산다 ──────────────────────────────────────────────────────
def test_a_failing_probe_does_not_kill_the_judgment(parallel) -> None:
    """재질의가 죽어도 판정은 유효하다 — 확신도를 못 깎을 뿐이다(설계).

    ❗`failed` 가 는 것을 같이 본다. **「일치했다」와 「못 쟀다」가 같아 보이면**
    *"일관성 검사가 도는데 늘 일치한다"* 로 읽힌다 (결정 5.40).
    """
    llm = ThreadSafeLlm(first=_judgment(Grade.U1),
                        second_raises=LlmError("재질의 실패"))

    judgment = _score(llm)

    assert judgment.grade == Grade.U1
    assert judgment.confidence == 1.0, "못 쟀으면 안 깎는다"
    assert parallel.failed == 1
    assert parallel.used == 0


def test_a_failing_discarded_probe_is_counted(parallel) -> None:
    """❗**버린 호출의 실패도 센다.** `Future` 는 아무도 안 물으면 예외를 조용히 삼킨다.

    그러면 재질의가 계속 죽고 있는데 로그가 비어서, 이 파일이 계속 막아 온
    *"안 도는 것"* 과 *"도는데 문제없는 것"* 이 같아 보인다.
    """
    llm = ThreadSafeLlm(first=_judgment(Grade.U3),
                        second_raises=LlmError("재질의 실패"))

    _score(llm)

    deadline = time.monotonic() + 2.0
    while parallel.failed == 0 and time.monotonic() < deadline:
        time.sleep(0.01)                      # 콜백은 워커 스레드에서 돈다
    assert parallel.discarded == 1
    assert parallel.failed == 1, "버린 호출의 실패가 어디에도 안 남았다"


# ── ④ 못 던진 경우를 갈라 센다 ──────────────────────────────────────────────────
def test_no_slot_falls_back_to_serial(parallel, monkeypatch) -> None:
    """❗자리가 없으면 **순차로 떨어지고 그 사실이 남는다.**

    큐에 쌓으면 대기가 오히려 는다 — 줄이려던 것을 늘린다. 그리고 `no_slot`(부하)과
    `disabled`(설정)를 갈라 세지 않으면 *"병렬이 안 도네"* 를 보고 원인을 못 가른다.
    """
    monkeypatch.setattr(scoring, "_PROBE_SLOTS", threading.BoundedSemaphore(1))
    scoring._PROBE_SLOTS.acquire()            # 하나뿐인 자리를 미리 뺏는다

    llm = ThreadSafeLlm(first=_judgment(Grade.U1))
    judgment = _score(llm)

    assert judgment.grade == Grade.U1, "떨어져도 결과는 같아야 한다"
    assert llm.judgment_calls == 2, "순차로라도 재질의는 돈다"
    assert parallel.no_slot == 1
    assert parallel.speculated == 0
    assert parallel.used == 1


def test_the_switch_is_counted_separately(monkeypatch) -> None:
    """스위치로 끈 것은 `disabled` 다 — `no_slot` 과 섞이면 안 된다."""
    monkeypatch.setattr(scoring, "_parallel_enabled", lambda: False)
    scoring.METER = scoring.ConsistencyMeter()
    try:
        _score(ThreadSafeLlm(first=_judgment(Grade.U1)))
        assert scoring.METER.disabled == 1
        assert scoring.METER.no_slot == 0
        assert scoring.METER.speculated == 0
    finally:
        scoring.METER = scoring.ConsistencyMeter()


# ── ⑤ seed 규칙이 병렬에서도 그대로다 ──────────────────────────────────────────
def test_the_two_calls_still_use_different_seeds(parallel) -> None:
    """★ **독립성의 실물이 seed 다.** 병렬로 옮기면서 이게 갈리면 `#370` 이 무력해진다.

    그리고 첫 시도는 설정값 그대로여야 한다(`#281`) — 투기 호출이 그 자리를 밀면
    단발 호출의 동작이 달라진다.
    """
    from app import config

    llm = ThreadSafeLlm(first=_judgment(Grade.U1))
    _score(llm)

    seeds = sorted(s for s in llm.seeds if s is not None)
    assert len(set(seeds)) == 2, f"두 호출이 같은 seed 다 — 재질의가 무력하다: {llm.seeds}"
    assert config.settings().llm_seed in llm.seeds, (
        "첫 시도의 seed 가 설정값이 아니다 — #281 이 고정한 동작이 밀렸다"
    )


def test_a_retry_does_not_reuse_the_probe(parallel) -> None:
    """❗재판정으로 넘어가면 **그 시도의 투기 호출을 버린다.**

    seed 가 `attempt` 에 매여 있어서, 남겨서 쓰면 두 판정이 **다른 시도**를 비교하게
    된다. 그건 `#370` 이 재려던 것이 아니다.
    """
    bad = Judgment(
        item_id="ELS-PRINCIPAL-LOSS-WARNING", grade=Grade.U1, confidence=1.0,
        evidence={"utterance_quote": ANSWER[:10],
                  "rubric_clause": "루브릭에 없는 조항이다"},   # P4 검증에서 무효가 된다
        reason="사유",
    )

    class RetryOnce(ThreadSafeLlm):
        def __init__(self):
            super().__init__(first=bad)
            self._served = 0

        def complete_json(self, **kwargs):
            with self._lock:
                self.seeds.append(kwargs.get("seed"))
                self.schemas.append(kwargs.get("schema_name"))
                self._served += 1
                served = self._served
            base = scoring._attempt_seed(1)
            if kwargs.get("seed") == base:          # 1차 시도의 첫 채점 → 무효
                return bad
            if served <= 2:                          # 1차 시도의 투기 호출
                return bad
            return _judgment(Grade.U1)

    llm = RetryOnce()
    judgment = _score(llm)

    assert judgment.grade == Grade.U1
    assert parallel.speculated == 2, "시도마다 새로 던져야 한다"
    assert parallel.discarded >= 1, "1차 시도의 투기 호출이 버려져야 한다"


# ── ⑥ 모집단 대조 — 빈 단정을 막는다 ───────────────────────────────────────────
def test_the_meter_actually_moves(parallel) -> None:
    """❗**계량기가 전부 0 이면 위 단정들이 무엇도 안 잰다.**

    `{} == set(())` 로 물린 전례가 `#396` 에 있다. 여기서는 등급을 갈아 가며 돌려
    필드가 실제로 움직이는 것을 본다.
    """
    _score(ThreadSafeLlm(first=_judgment(Grade.U1)))
    _score(ThreadSafeLlm(first=_judgment(Grade.U3)))

    snap = parallel.snapshot()
    assert snap["speculated"] == 2
    assert snap["used"] == 1
    assert snap["discarded"] == 1
    assert sum(snap.values()) > 0


# ── ⑦ 곁가지 — 설정 객체가 비밀을 문면으로 흘리지 않는다 ────────────────────────
def test_settings_repr_does_not_carry_secrets() -> None:
    """❗**`Settings` 가 통째로 찍히는 자리가 있다** — 이 PR 작업 중 실제로 봤다.

    pytest 단정이 실패하면서 `Settings(... llm_api_key='sk-proj-…' ...)` 가 출력에
    그대로 나왔다. 로그·예외 문면·CI 로그가 다 같은 경로다.

    레포는 이미 *"키 값 자체를 파일에 적지 않는다"* 를 지키는데, **출력 경로가 열려
    있으면 같은 값이 다른 문으로 나간다.** `internal_token` 도 같은 이유다
    (`/healthz` 가 값을 안 내는 것과 같은 자리).
    """
    from app import config

    text = repr(config.settings())
    assert "llm_api_key" not in text, "설정 repr 에 LLM 키가 실린다"
    assert "internal_token" not in text, "설정 repr 에 내부 토큰이 실린다"
    assert "llm_model" in text, "양성 대조 — repr 이 통째로 비어 버리면 위 단정이 공짜다"
