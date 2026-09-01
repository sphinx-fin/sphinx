"""ai-service 설정. 소유: 윤지석

프로바이더 중립: `.env`의 LLM_API_BASE/LLM_API_KEY/LLM_MODEL 3개만 읽는다.
현재 기본값은 OpenAI(gpt-5-mini)이고, Gemini는 OpenAI 호환 엔드포인트로 붙는다 —
어느 쪽이든 base_url 한 줄이다 (온프레미스 교체 가능성 확보 — llm_client 참고).
기획서 7-3: "외부 LLM API를 그대로 호출하는 구조는 재위탁 문제를 일으킨다."

## .env 위치

`ai-service/.env`를 먼저 읽고, 없는 값은 레포 루트 `.env`에서 채운다.
이미 프로세스 환경에 있는 값은 **덮어쓰지 않는다**(CI·컨테이너가 우선).

    프로세스 환경 > ai-service/.env > 레포 루트 .env > 아래 기본값

LLM 키는 ai-service만 쓰므로 서비스 디렉토리에 두는 것을 권한다. 루트에 둬도 동작한다
(루트 `.env.example`이 그 위치를 가정하고 있다). 두 위치 모두 `.gitignore`의 `.env`
규칙에 걸린다 — 슬래시가 없는 패턴이라 모든 깊이에 적용된다.

우리가 직접 로드하는 이유: `uvicorn --env-file`은 플래그를 빼먹으면 조용히 무설정으로
기동하고, pytest에는 아예 적용되지 않는다.
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

# OpenAI 엔드포인트. 어댑터는 프로바이더 중립이고 이 값만 갈면 옮겨진다 —
# Gemini 로 되돌리려면 "https://generativelanguage.googleapis.com/v1beta/openai/"
# (호환 엔드포인트, 끝의 슬래시 필수)를 LLM_API_BASE 에 넣으면 된다. 코드는 안 고친다.
DEFAULT_BASE_URL = "https://api.openai.com/v1"
# ── 모델 정책 ────────────────────────────────────────────────────────────────
# 팀 결정: **모든 LLM 호출을 한 모델로 한다.** 기능별로 모델을 나누지 않는다 —
# 채점·추출·질문생성·재설명이 전부 같은 모델을 쓴다. 이 원칙은 그대로다.
#
# 바뀐 것은 그 한 모델이 무엇이냐다: `gemini-3.5-flash-lite` → `gpt-5-mini`.
#
# ## 왜 옮겼나 (2026-09-01 실측)
#
# 무료 Gemini 티어가 **분당 15회**에서 막힌다. 문서에 없는 숫자였고 429 응답이 알려 줬다
# (`GenerateRequestsPerMinutePerProjectPerModel-FreeTier`, quotaValue 15). dev set 23건을
# 연달아 부르면 16번째부터 전부 실패하고, F-INT-002 는 그 실패를 **템플릿 폴백 질문**으로
# 흡수한다 — 화면에는 아무 표시가 없다. 리허설·데모에서 이게 그대로 일어난다.
#
# 판정 품질은 갈리지 않았다. dev set 24건을 두 모델로 돌려 **등급이 24/24 일치**했고,
# 비용도 같은 자릿수다(1회 실행 30~50원 수준).
#
# 유료 Gemini 도 RPM 문제를 똑같이 푼다. 그쪽을 택하지 않은 이유는 기술이 아니라
# **크레딧이 이미 OpenAI 에 충전돼 있어서**다. 되돌릴 일이 생기면 위 base_url 한 줄이다.
#
# ## 유료 티어여야 하는 이유는 그대로다
#
# 무료 티어 약관은 제출 내용을 학습·사람 검토에 쓴다고 명시한다. PII 게이트웨이(P3)를
# 심사 근거로 내세우는 프로젝트가 무료 티어로 데모하면 그 질문에 답이 없다.
#
# .env 의 LLM_MODEL 로 덮어쓸 수 있지만, 정책 모델이 아니면 경고를 남긴다 —
# 정책에서 벗어난 실행이 조용히 지나가면 성능 수치의 출처를 알 수 없게 된다.
DEFAULT_MODEL = "gpt-5-mini"
MODEL_POLICY_SUBSTRING = "gpt-5-mini"

#: 사고 예산. **비우면 안 보낸다** — 프로바이더 중립을 깨지 않으려는 것이다.
#:
#: gpt-5-mini 는 기본값이면 호출당 **사고토큰 1,024개**를 태우고 20.3초가 걸렸다.
#: `minimal` 로 고정하면 0개 · 2.4초이고 **등급은 같았다**. 사고토큰은 출력 요금으로
#: 계산되므로 이 한 줄이 요금을 두 배 가른다.
#:
#: Gemini 호환 엔드포인트도 이 파라미터를 받는다(minimal·low 실측 통과). 그래서 프로바이더
#: 를 갈아도 이 값을 지울 필요가 없다. 그래도 비울 수 있게 열어 둔 것은, 이걸 모르는
#: 프로바이더가 나오면 **코드가 아니라 설정으로** 끄기 위해서다.
REASONING_EFFORT_ENV = "LLM_REASONING_EFFORT"
DEFAULT_REASONING_EFFORT = "minimal"

#: 로그 레벨. **기본이 INFO 다** — 우리 코드의 관측 기록이 전부 `log.info` 이고, 파이썬
#: 기본값(WARNING)이면 그게 하나도 안 찍힌다.
#:
#: PR #113·#114 리뷰(정세현)에서 걸렸다. 두 PR 이 *"빈도를 로그로 본다"* 를 근거로 관측을
#: 약속했는데 `basicConfig`·`dictConfig`·`setLevel` 이 레포에 하나도 없었다 — **약속한
#: 관측 경로가 아예 없었다.** 조용한 실패의 한 형태다: 코드는 남기려 하고 아무도 못 본다.
LOG_LEVEL_ENV = "SPHINX_LOG_LEVEL"
DEFAULT_LOG_LEVEL = "INFO"

#: 우리 로거의 루트. `app.*` 만 설정하고 root 는 건드리지 않는다 — uvicorn 이 자기 핸들러를
#: root 에 붙이므로 `basicConfig(force=True)` 로 덮으면 access 로그 형식까지 바뀐다.
APP_LOGGER = "app"


#: `/internal/*` 공유 시크릿. **네트워크 격리가 1차 방어이고 이건 2차다**(결정 10.4).
#: compose network 설정 실수 한 번으로 전체가 열리는 구조를 피한다.
#:
#: 왜 필요한가(이슈 #41 ③): `ai-service` 는 브라우저가 직접 부르지 않고 Spring 만 부른다는
#: 전제 위에 P3 가 서 있다 — *"고객 텍스트가 나가는 유일한 경로는 `PiiGateway.mask()` →
#: `AiServiceClient`"*. **:8100 이 퍼블릭 서브넷에 뜨면 그 유일한 경로가 유일하지 않게 된다.**
#: 입구 PII 재검사가 있지만 그건 마스킹 누락을 막는 방어선이지 접근 통제가 아니다.
INTERNAL_TOKEN_ENV = "SPHINX_INTERNAL_TOKEN"

#: 토큰이 없을 때 **기동을 막을지**. 배포에서 켠다.
#:
#: 기본이 꺼짐인 이유: 목 개발 중이고 팀원이 토큰 없이 로컬에서 돌린다. 다만 **꺼진 상태가
#: 조용하면 안 된다** — 기동 로그에 경고를 남기고 `/healthz` 가 `internal_auth` 로 상태를
#: 낸다. 데모 전 점검에서 눈으로 확인할 수 있어야 한다.
REQUIRE_INTERNAL_AUTH_ENV = "SPHINX_REQUIRE_INTERNAL_AUTH"

#: 우리가 붙인 핸들러임을 표시한다. `if not logger.handlers` 로 판단하면 **남이 붙인
#: 핸들러가 하나라도 있을 때 우리 것을 안 붙인다** — pytest 가 `app` 로거에 캡처 핸들러
#: 넷을 붙이는 것으로 실측했다(전체 실행에서 5개). 운영에서는 uvicorn 이 `uvicorn.*` 만
#: 설정하므로 안 겹치지만, "남의 것이 있으면 내 것을 안 붙인다" 는 조용한 실패다.
HANDLER_MARK = "_sphinx_app_handler"


def effective_log_level() -> str:
    """지금 **실제로 적용된** 레벨 이름. `settings()` 를 보지 않고 로거에서 읽는다.

    `/healthz` 가 이걸 낸다. `settings().log_level` 은 환경변수 원본이라 오타가 그대로
    들어 있고, 그걸 내면 오타를 낸 사람이 자기 오타를 되돌려받는다 — 관측이 켜져 있는지
    묻는 유일한 창구가 사실이 아닌 값을 말하게 된다(PR #121 리뷰, 정세현 실측).

    출처를 로거로 둔 이유도 같다. `configure_logging()` 의 반환값을 담아 두면 그 변수와
    설정값이 **같은 계산에서 나와** 둘이 같이 틀릴 수 있다. 로거의 실효 레벨은 실제로
    필터링에 쓰이는 값이라 그것과 어긋날 수 없다.
    """
    return logging.getLevelName(logging.getLogger(APP_LOGGER).getEffectiveLevel())


def _resolve_level(name: str) -> int | None:
    """레벨 이름 또는 숫자를 정수로. 알 수 없으면 None.

    **숫자도 받는다** — 파이썬 로깅이 숫자 레벨을 정식으로 지원하므로 아는 사람이
    `SPHINX_LOG_LEVEL=10` 으로 쓸 수 있다. 안 받으면 그게 오타와 같은 경고를 받고, 그러면
    경고가 두 가지 뜻을 갖는다(PR #121 리뷰, 정세현).

    **범위 조건이 두 분기에 똑같이 걸린다.** 숫자 `0` 만 막고 이름 `NOTSET` 을 통과시키면
    같은 상태로 가는 문이 하나 열린 채로 남는다 — `getattr(logging, "NOTSET")` 이 `0` 이고
    `isinstance(0, int)` 가 참이라 유효한 레벨로 받아졌다(PR #121 리뷰 2차, 정세현 실측).

    `NOTSET` 이 나쁜 이유는 오타보다 조용하기 때문이다. `app` 로거 레벨이 `0` 이면
    `getEffectiveLevel()` 이 root 로 상속돼 `WARNING` 이 되고 `log.info` 관측이 전부 꺼지는데,
    **경고가 하나도 안 난다.** 이 PR 이 세운 기준(*"오타가 관측을 끄는데 그게 안 보이면
    안 된다"*)에 `NOTSET` 도 같은 자리에 있다.

    `app` 로거를 root 에 되돌리려는 사람이 있다면 그건 `SPHINX_LOG_LEVEL` 이 아니라 다른
    스위치여야 한다 — 이 변수의 뜻은 "관측 레벨" 이지 "상속 여부" 가 아니다.
    """
    if name.isdigit():
        value = int(name)
        return value if 0 < value <= logging.CRITICAL else None
    level = getattr(logging, name, None)
    return level if isinstance(level, int) and 0 < level <= logging.CRITICAL else None

log = logging.getLogger(__name__)

SERVICE_ROOT = Path(__file__).resolve().parents[1]   # ai-service/
REPO_ROOT = SERVICE_ROOT.parent

# 앞쪽이 우선. 이미 설정된 환경변수는 어느 파일도 덮어쓰지 않는다.
ENV_FILES = (SERVICE_ROOT / ".env", REPO_ROOT / ".env")


def _ascii_token(token: str) -> str:
    """토큰이 ASCII 인지 확인한다. 아니면 **설정 시점에** 터뜨린다.

    HTTP 헤더 값은 규격상 ASCII 다. 비ASCII 토큰을 설정하면 클라이언트가 UTF-8 로 실어
    보내고 서버는 latin-1 로 읽어 **값이 조용히 달라진다** — 결과는 "토큰을 맞게 넣었는데
    401" 이고 원인을 찾기 어렵다.

    그리고 `hmac.compare_digest` 는 비ASCII **문자열**에 `TypeError` 를 낸다. 실측으로
    재현했다 — 한글 토큰을 넣으니 401 이 아니라 **500** 이 나왔다. 거부도 통과도 아닌
    서버 오류이고, 그 경로는 인증이 있는지 없는지조차 알려주지 않는다.
    """
    if token and not token.isascii():
        raise ValueError(
            f"{INTERNAL_TOKEN_ENV} 는 ASCII 여야 한다 — HTTP 헤더 값 규격이고, "
            "비ASCII 면 전송 중 값이 달라져 '맞게 넣었는데 401' 이 된다"
        )
    return token


def _truthy(value: str | None) -> bool:
    """`1`·`true`·`yes`·`on` 을 참으로 본다. 빈 문자열과 미설정은 거짓.

    `bool(os.getenv(...))` 로 두면 `SPHINX_REQUIRE_INTERNAL_AUTH=0` 이 **참**이 된다 —
    끄려고 적은 값이 켜는 결과가 되는 종류다.
    """
    return (value or "").strip().lower() in {"1", "true", "yes", "on"}


def _load_env_files() -> list[Path]:
    """찾아서 로드한 파일 목록을 돌려준다. python-dotenv가 없으면 조용히 건너뛴다."""
    try:
        from dotenv import load_dotenv
    except ImportError:  # 의존성 없이 돌리는 경우 — export 방식으로 동작
        return []
    loaded = []
    for path in ENV_FILES:
        if path.is_file():
            load_dotenv(path, override=False)
            loaded.append(path)
    return loaded


#: 오해 라이브러리·지수 스냅샷이 있는 디렉토리. 레포에서는 `data/` 지만 컨테이너에서는
#: 마운트 지점이 달라진다 — 상대경로 하드코딩이면 이미지 안에서 파일을 못 찾는다
#: (결정로그 10.7 · 이슈 #37). 강희진 몫(`application.yml` 시계열 주입)은 #50 으로 끝났다.
DATA_DIR_ENV = "SPHINX_DATA_DIR"


@dataclass(frozen=True)
class Settings:
    llm_base_url: str
    llm_api_key: str
    llm_model: str
    llm_reasoning_effort: str
    llm_timeout_sec: float
    env_files: tuple[str, ...]
    log_level: str
    data_dir: Path
    internal_token: str
    require_internal_auth: bool

    @property
    def internal_auth_enabled(self) -> bool:
        return bool(self.internal_token)

    @property
    def llm_configured(self) -> bool:
        """키가 없으면 LLM 호출 경로만 막고 서비스는 기동한다(목 개발용)."""
        return bool(self.llm_api_key)


def _reasoning_effort() -> str:
    """빈 문자열과 미설정을 **가른다** — 이 레포의 다른 환경변수와 반대다.

    다른 곳(`LLM_API_KEY`·`SPHINX_DATA_DIR`)은 빈 값을 미설정과 똑같이 보고 기본값으로
    떨어진다. 여기서만 다른 이유는 빈 문자열이 **끄는 문**이기 때문이다 —
    `or` 로 쓰면 `LLM_REASONING_EFFORT=` 가 기본값으로 되살아나서, 끄려는 사람이
    껐다고 믿는데 안 꺼진다. 조용히 틀리는 쪽이라 테스트로 고정한다.
    """
    raw = os.getenv(REASONING_EFFORT_ENV)
    return DEFAULT_REASONING_EFFORT if raw is None else raw.strip()


@lru_cache(maxsize=1)
def settings() -> Settings:
    loaded = _load_env_files()
    model = os.getenv("LLM_MODEL") or DEFAULT_MODEL
    if MODEL_POLICY_SUBSTRING not in model:
        log.warning(
            "모델 정책 위반: LLM_MODEL=%s — 팀 결정은 %s 다. "
            "이 실행의 결과를 성능 수치로 인용하지 말 것.", model, MODEL_POLICY_SUBSTRING,
        )
    return Settings(
        llm_base_url=os.getenv("LLM_API_BASE") or DEFAULT_BASE_URL,
        llm_api_key=os.getenv("LLM_API_KEY", ""),
        llm_model=model,
        llm_reasoning_effort=_reasoning_effort(),
        llm_timeout_sec=float(os.getenv("LLM_TIMEOUT_SEC", "60")),
        env_files=tuple(str(p.relative_to(REPO_ROOT)) for p in loaded),

        log_level=(os.getenv(LOG_LEVEL_ENV) or DEFAULT_LOG_LEVEL).upper(),
        internal_token=_ascii_token(os.getenv(INTERNAL_TOKEN_ENV, "").strip()),
        require_internal_auth=_truthy(os.getenv(REQUIRE_INTERNAL_AUTH_ENV)),

        data_dir=Path(os.getenv(DATA_DIR_ENV) or (REPO_ROOT / "data")).expanduser(),

    )


def configure_logging() -> str:
    """`app.*` 로거에 레벨과 핸들러를 붙인다. 실제로 적용된 레벨 이름을 돌려준다.

    **root 를 건드리지 않는 이유**: uvicorn 이 root 에 자기 핸들러를 붙이므로
    `basicConfig(force=True)` 로 덮으면 access 로그 형식까지 바뀐다. 우리 관측만 켜는 것이
    목적이라 범위를 `app` 으로 좁힌다.

    핸들러를 붙이고 `propagate=False` 로 둔다 — root 에도 핸들러가 있으면 같은 줄이 두 번
    찍힌다. **한 줄이 두 번 나오면 빈도 관측이 정확히 두 배로 틀린다.**

    알 수 없는 레벨 이름은 조용히 무시하지 않고 기본값으로 내려가면서 경고를 남긴다 —
    `SPHINX_LOG_LEVEL=INFOO` 같은 오타가 관측을 끄는데 그게 안 보이면 안 된다.

    여러 번 불려도 핸들러가 쌓이지 않는다(테스트가 반복 호출한다).
    """
    requested = settings().log_level
    level = _resolve_level(requested)
    fallback = level is None
    if fallback:
        level = getattr(logging, DEFAULT_LOG_LEVEL)

    logger = logging.getLogger(APP_LOGGER)
    logger.setLevel(level)
    if not any(getattr(h, HANDLER_MARK, False) for h in logger.handlers):
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(levelname)-7s %(name)s  %(message)s"))
        setattr(handler, HANDLER_MARK, True)
        logger.addHandler(handler)
    logger.propagate = False

    # **경고를 핸들러 붙인 뒤에 낸다.** 앞에서 내면 `logging.lastResort` 로 나가 포맷 없는
    # 맨 줄이 된다(PR #121 리뷰, 정세현 실측). 관측을 켜는 함수가 자기 경고를 관측 밖으로
    # 내보내면 안 된다.
    if fallback:
        log.warning(
            "%s 값을 알 수 없어 %s 로 내려간다: %r  (이름은 DEBUG·INFO·WARNING·ERROR·"
            "CRITICAL, 숫자도 받는다)", LOG_LEVEL_ENV, DEFAULT_LOG_LEVEL, requested,
        )
        requested = DEFAULT_LOG_LEVEL
    return requested
