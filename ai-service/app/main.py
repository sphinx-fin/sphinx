"""ai-service 엔트리포인트. 내부 전용 (Spring server만 호출)."""
from fastapi import FastAPI

app = FastAPI(title="SphinX AI Service", version="0.1.0")

# TODO: 라우터 등록 — /internal/parse, /internal/extract, /internal/question,
#       /internal/score, /internal/misconception, /internal/reexplain
