# ai-service (FastAPI) 이미지. 초안 작성: 오준서 (인프라 R) — **검토: 윤지석**
#
# 이슈 #37 이 "각자 Dockerfile 을 만들고 오준서가 compose 로 묶는다"로 정했는데, 9/3 리허설
# 전에 compose 를 세우려면 세 이미지가 다 있어야 해서 초안까지 냈다. ai-service/ 소유자
# 것이므로 머지 전에 봐 주기 바란다 — 특히 워커 수와 타임아웃은 내 판단 밖이다.
#
# ── 이 서비스는 외부에 열리지 않는다 ────────────────────────────────────────
# EXPOSE 8100 은 compose 네트워크 안에서의 문서일 뿐이고, docker-compose.yml 에 이 서비스의
# `ports:` 가 없다. :8100 이 퍼블릭에 뜨면 PII 마스킹을 건너뛰고 LLM 에 직접 프롬프트를 넣을
# 수 있다(#41 ③ · CLAUDE.md P3). 포트를 추가하기 전에 #41 을 읽는다.

FROM python:3.11-slim

# CI 와 같은 3.11 이다(.github/workflows/ci.yml). requirements.txt 에 상한이 없어서 런타임을
# 고정하지 않으면 어느 날 조용히 올라가는데, pdfplumber·reportlab 은 새 파이썬에서 휠이 늦다.

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# 런타임 의존성만 넣는다. requirements-dev.txt(pytest·jsonschema·reportlab)는 테스트 전용이라
# 이미지에 들어갈 이유가 없다 — 그 분리가 requirements 파일이 둘인 이유다.
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app app

# root 로 돌리지 않는다. /data 와 /contracts 는 :ro 로 마운트된다.
RUN useradd --system --uid 10001 --create-home sphinx
USER sphinx

EXPOSE 8100

# --reload 를 쓰지 않는다(개발 전용). 워커는 1개다 — 프로세스를 늘리면 오해 라이브러리를
# 프로세스마다 다시 읽고, 데모 부하에서 얻는 게 없다.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8100"]
