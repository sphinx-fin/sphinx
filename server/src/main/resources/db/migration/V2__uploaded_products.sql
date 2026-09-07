-- SphinX 배포 스키마 v2 — 업로드된 상품 문서 (F-EXT-001 · 이슈 #521). 소유: 강희진
--
-- ── 왜 이 테이블이 생겼나 ───────────────────────────────────────────────────
--
-- `POST /products/documents` 가 스텁이었다 — 파일과 상품유형을 **둘 다 안 읽고**
-- `mock-els-001` 을 냈다. 그런데도 그 경로는 audited 라(`rbac_policy.yaml` 의
-- `product:manage`) 감사 로그에 "누가 언제 상품을 등록했다" 가 남았고, `evidence/` 는
-- append-only 라 그 기록을 나중에 못 지운다. **이 테이블이 그 기록이 가리킬 실물이다.**
--
-- ── 바이트는 여기 없다 ─────────────────────────────────────────────────────
--
-- `document_path` 는 `sphinx.documents.data-dir`(= ai-service `SPHINX_DATA_DIR`) 상대경로
-- 이고 파일은 이름 있는 도커 볼륨(`sphinx_uploads`, `docker-compose.yml`)에 산다.
-- ai-service `/internal/parse` 가 **경로를 받는** 계약이라 그렇다 — BLOB 으로 넣으면
-- 추출마다 임시파일로 다시 떨어뜨려야 하고, "어디에 쓰나" 가 임시 디렉토리 문제로
-- 옮겨갈 뿐이다.
--
-- ❗**그래서 DB 백업만으로는 복원이 안 된다.** 이 행이 가리키는 파일이 볼륨에 있어야
-- 원문 조회(`GET /products/{id}/document`)와 재추출이 돈다. `docker compose down -v` 가
-- 그 볼륨을 지우면 항목은 DB 에 남아 보이는데 그 둘만 죽는, 알아채기 어려운 상태가 된다.
--
-- ── `uk_uploaded_products_product` 는 빼면 안 된다 ─────────────────────────
--
-- `product_id` 는 **내용 주소**다(`doc-<파일명 슬러그>-<sha256 앞 8자>`) — 같은 파일을 두 번
-- 올리면 같은 값이 나온다. 그때 행이 둘이면 `findByProductId` 가 어느 쪽을 내는지가
-- 조회 순서에 달리고, 그 둘의 상품유형이 다르면(재업로드 사이에 파스 판별이 바뀐 경우)
-- **면담 질문이 요청마다 갈린다.** 업로드는 새 행을 넣지 않고 갱신한다
-- (`UploadedProduct.reparsed`) — 이 제약이 그 규약을 DB 에서 굳힌다.
--
-- ── 만든 방법 (V1 머리말의 규칙 그대로) ─────────────────────────────────────
--
-- 손으로 쓴 것이 아니다. 엔티티에 `ddl-auto: create` 를 걸어 실제 MySQL 8.4.11 에 만들게
-- 하고 `mysqldump --no-data` 로 뽑았다. `validate` 는 JDBC 타입 코드가 아니라 **타입명**을
-- 보므로 "더 넓으니까 괜찮다" 가 안 통한다 — 폭을 바꿔야 하면 엔티티에서 바꾸고 여기는
-- 다시 뽑는다. `content_sha256` 이 varchar(64) 인 것은 엔티티의 `@Column(length = 64)`
-- 결과이고, `evidence_entries` 의 해시 컬럼이 같은 폭을 쓴다(V1).
--
-- ❗**적용된 뒤에는 이 파일을 주석 한 글자도 고치지 않는다.** Flyway 가 파일 내용 전체로
-- 체크섬을 계산해서, 한 글자만 바뀌어도 이미 적용해 둔 DB 가 검증에 실패하고 앱이 기동
-- 자체를 못 한다. 새로 알게 된 것은 V3 의 주석에 쓴다.

CREATE TABLE `uploaded_products` (
  `created_at`        datetime(6) NOT NULL,
  `id`                bigint NOT NULL AUTO_INCREMENT,
  `size_bytes`        bigint NOT NULL,
  `updated_at`        datetime(6) NOT NULL,
  `content_sha256`    varchar(64) NOT NULL,
  `display_name`      varchar(255) NOT NULL,
  `document_path`     varchar(255) NOT NULL,
  `failure_reason`    varchar(255) DEFAULT NULL,
  `original_filename` varchar(255) NOT NULL,
  `product_id`        varchar(255) NOT NULL,
  `product_type`      varchar(255) NOT NULL,
  `status`            varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_uploaded_products_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
