# contracts (소유: 강희진)

레포 전체의 단일 진실. 여기 스키마가 곧 팀 간 계약이다.

- `risk_item.schema.json` — F-EXT-002 출력 (윤지석 → 강희진·정세현·오준서)
- `judgment.schema.json` — F-SCR-001 출력 (윤지석 → 강희진·오준서)
- `openapi.yaml` — REST API 전체 (강희진 → 오준서)

변경 절차: PR + 강희진 승인 + 수요자 전원 멘션. 서버의 pydantic 모델(`server/app/models/`)은 이 스키마와 1:1로 유지한다.
