# ai-service 테스트

```bash
cd ai-service && pip install -r requirements-dev.txt && pytest
```

`test_parsing.py`(F-EXT-001, 소유: 정세현)는 **수집 문서 없이 돈다.** `data/documents/`는 git
제외이므로 픽스처 PDF를 `conftest.py`가 `contracts/samples/*.json`의 페이지 텍스트로부터
실행 시점에 생성한다. 실문서 2종이 들어오면 이 픽스처는 유지하고(계약 회귀 테스트) 실문서
파싱 결과로 `contracts/samples/`를 대체한다.
