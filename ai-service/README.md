# ai-service (FastAPI)

LLM 파이프라인 전용 내부 서비스. **외부(브라우저)에 노출하지 않는다** — Spring(server)만 호출.

| 모듈 | 소유 | 기능ID |
|---|---|---|
| `app/parsing.py` | 정세현 | F-EXT-001 (pdfplumber — 한글 PDF 표 추출에 Java보다 유리해 여기 둠) |
| `app/extraction.py` | 윤지석 | F-EXT-002 |
| `app/question_gen.py` | 윤지석 | F-INT-002 |
| `app/scoring.py` | 윤지석 | F-SCR-001 |
| `app/misconception.py` | 윤지석 (데이터: 정세현) | F-DET-001 |
| `app/mismatch.py` | 윤지석 | F-DET-002 |
| `app/reexplain.py` | 윤지석 | F-INT-004 콘텐츠 |

## 전제 (P3)
이 서비스에 들어오는 고객 텍스트는 Spring의 PiiGateway를 이미 통과한 상태다.
그래도 방어적으로 입구에서 PII 패턴 검사를 한 번 더 수행하고, 걸리면 요청을 거부한다.

## 실행
```bash
pip install -r requirements.txt && uvicorn app.main:app --port 8100 --reload
```
