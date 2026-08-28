"""`caplog` 이 `app.*` 로거에 닿는가. 소유: 정세현 (conftest 픽스처의 짝)

`configure_logging()` 이 `app` 로거를 `propagate=False` 로 둔다(PR #121). 운영으로는 맞다 —
root 에도 핸들러가 있으면 같은 줄이 두 번 찍히고, **한 줄이 두 번 나오면 빈도 관측이 정확히
두 배로 틀린다.**

문제는 그 설정이 테스트에 새어 들어온다는 것이다. `caplog` 은 기본적으로 root 에 핸들러를
붙여 잡으므로, `propagate=False` 면 `app.*` 로그를 보는 단정이 **조용히 아무것도 못 본다.**

지금은 최신 pytest 가 `propagate=False` 인 로거에도 캡처 핸들러를 직접 붙여 줘서 가려져
있다. `requirements-dev.txt` 의 `pytest` 에 핀이 없으므로 그건 **우리가 정한 것이 아니다.**

    pytest 9.1.1   통과
    pytest 8.3.4   1 failed   test_axis_mismatch_is_logged_not_silent
    pytest 7.4.4   1 failed   (같음)

그래서 `conftest._caplog_reaches_app_logger` 가 최신 pytest 가 하는 일을 버전과 무관하게
한다. 이 파일은 **그 픽스처가 실제로 그 일을 하는지**를 본다 — 없으면 위 실패가 돌아오는데,
그때 나오는 메시지는 *"축 불일치 로그가 안 찍혔다"* 라 원인과 전혀 다른 곳을 가리킨다.
"""
from __future__ import annotations

import logging

from app import config


def test_app_logger_does_not_propagate():
    """전제 확인 — 이게 참이라서 아래가 필요하다.

    `configure_logging()` 이 안 돌았으면 `propagate` 가 True 이고, 그때는 root 경유로 잡히므로
    픽스처가 할 일이 없다. 이 단정이 깨지면 `#121` 의 이중 출력 방지가 사라진 것이다.
    """
    config.configure_logging()

    assert logging.getLogger(config.APP_LOGGER).propagate is False


def test_caplog_sees_records_from_app_loggers(caplog):
    """★ `propagate=False` 인데도 `caplog` 이 `app.*` 를 잡아야 한다.

    이게 이 파일의 요지다. 픽스처를 지우면 옛 pytest 에서 여기가 먼저 빨개진다 —
    `test_mismatch` 가 아니라. 원인을 가리키는 자리에서 깨지는 것이 목적이다.
    """
    config.configure_logging()
    assert logging.getLogger(config.APP_LOGGER).propagate is False, "전제가 깨졌다"

    with caplog.at_level(logging.INFO, logger="app.caplogprobe"):
        logging.getLogger("app.caplogprobe").info("관측 한 줄")

    assert any("관측 한 줄" in r.message for r in caplog.records), caplog.text


def test_records_are_not_captured_twice(caplog):
    """❗한 줄이 두 번 잡히면 안 된다 — 그게 `#121` 이 `propagate=False` 를 켠 이유다.

    픽스처가 `propagate` 를 True 로 되돌리는 방식이었다면 여기가 깨진다. root 와 `app` 양쪽에
    핸들러가 있으니 같은 레코드가 두 번 들어온다. **관측을 켜는 장치가 관측을 두 배로 틀리게
    만드는 것**이라 방향이 반대다.
    """
    config.configure_logging()

    with caplog.at_level(logging.INFO, logger="app.caplogprobe"):
        logging.getLogger("app.caplogprobe").info("한 번만")

    hits = [r for r in caplog.records if "한 번만" in r.message]
    assert len(hits) == 1, f"{len(hits)}번 잡혔다 — 빈도 관측이 그만큼 틀린다"


def test_non_app_loggers_still_work(caplog):
    """`app` 밖 로거는 원래대로 root 경유로 잡힌다 — 픽스처가 그쪽을 안 건드린다."""
    with caplog.at_level(logging.INFO, logger="somewhere.else"):
        logging.getLogger("somewhere.else").info("바깥 한 줄")

    assert any("바깥 한 줄" in r.message for r in caplog.records)
