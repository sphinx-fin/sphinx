-- SphinX 배포 스키마 v1 — MySQL 8. 소유: 오준서 (인프라 R) · 스키마 내용은 엔티티 소유자.
--
-- ── 이 파일이 왜 있나 ───────────────────────────────────────────────────────
--
-- `application-prod.yml` 이 `ddl-auto: validate` 다. Hibernate 가 스키마를 **안 만든다**는
-- 뜻이라 테이블을 만드는 것은 이 파일이고, Spring Boot 가 Flyway 를 EntityManagerFactory
-- **앞에** 돌리므로(FlywayMigrationInitializer) "마이그레이션 → validate" 순서는 설정이
-- 아니라 구조로 보장된다.
--
-- ❗**손으로 쓴 것이 아니고, 손으로 고치지도 않는다.** 엔티티에 `ddl-auto: create` 를 걸어
-- 실제 MySQL 8.4 에 만들게 하고 `mysqldump --no-data` 로 뽑았다. 그래야 validate 가
-- 통과한다 — 그리고 이건 취향이 아니다. **validate 는 JDBC 타입 코드가 아니라 타입명을
-- 본다**(실측):
--
--   Schema-validation: wrong column type encountered in column [payload_json]
--   in table [evidence_entries]; found [longtext (Types#LONGVARCHAR)],
--   but expecting [tinytext (Types#CLOB)]
--
-- 즉 *"더 넓으니까 괜찮다"* 가 안 통한다. 폭을 바꿔야 하면 **엔티티에서** 바꾸고 여기는
-- 다시 뽑는다. 반대로 하면 배포 때 기동 실패로만 드러난다.
--
-- ── 이 스키마를 만들면서 실제로 잡힌 결함 하나 ──────────────────────────────
--
-- `evidence_entries.payload_json` 이 처음 뽑았을 때 **`tinytext` = 255바이트**로 나왔다.
-- `EvidenceEntry.payloadJson` 이 `@Lob` 인데 길이를 안 줘서 Hibernate 가 `@Column` 기본값
-- 255 를 타입 선택에 그대로 쓴 것이다. 정규화 JSON 한 건도 안 들어간다.
--
-- H2 에서는 `@Lob` 이 길이와 무관하게 CLOB 이라 **이 결함이 안 보였다** — 배포가 H2
-- 인메모리인 동안 아무 신호도 없었던 이유다. 고친 자리는 이 파일이 아니라 엔티티이고
-- (`@Column(nullable = false, length = Integer.MAX_VALUE)`), 여기 `longtext` 는 그 결과를
-- 받아 적은 것이다. 근거는 EvidenceEntry.payloadJson 의 주석에 있다.
--
-- ── 고칠 때 ────────────────────────────────────────────────────────────────
--
-- ❗**이미 적용된 뒤에는 이 파일을 주석 한 글자도 고치지 않는다.** Flyway 는 파일 내용
-- 전체로 체크섬을 계산해서, 한 글자만 바뀌어도 이미 적용해 둔 DB 가 검증에 실패하고
-- 앱이 **기동 자체를 못 한다**(readiness 미도달 → 배포 타임아웃). 새로 알게 된 것은
-- 새 마이그레이션(V2__…)의 주석에 쓴다 — 참조 방향은 항상 새 파일 → 옛 파일이다.
--
-- 엔티티를 고치는 사람은 V2 를 같이 낸다. 안 내면 validate 가 기동을 거부한다 —
-- 조용히 틀린 스키마로 뜨는 경로가 없다는 것이 이 설정을 고른 이유다.
CREATE TABLE `sessions` (
  `id`                     varchar(255) NOT NULL,
  `product_id`             varchar(255) NOT NULL,
  `channel`                enum('FACE_TO_FACE','MOBILE','TM') NOT NULL,
  `age_band`               varchar(255) NOT NULL,
  `experience_level`       varchar(255) DEFAULT NULL,
  `amount_band`            varchar(255) DEFAULT NULL,
  `contract_ref`           varchar(255) DEFAULT NULL,
  `seller_id`              varchar(255) DEFAULT NULL,
  `branch_id`              varchar(255) DEFAULT NULL,
  `survey_schema_version`  varchar(255) DEFAULT NULL,
  `survey_result`          text,
  `state`                  enum('ABORTED','CLOSED','CREATED','IN_PROGRESS','JUDGED','RE_EXPLAIN','RE_VERIFY') NOT NULL,
  `current_re_explanation` text,
  `asked_types`            text,
  `judgments_by_item`      text,
  `repeated_answer_items`  text,
  `input_ms`               text,
  `suitability_status`     enum('MISMATCH','NOT_EVALUATED','NO_MISMATCH','UNKNOWN') NOT NULL,
  `coaching_score`         int NOT NULL,
  `vulnerable`             bit(1) NOT NULL,
  `gate_signal`            enum('GREEN','RED','YELLOW') DEFAULT NULL,
  `gate_rule_trace`        text,
  `gate_unmeasured`        int NOT NULL,
  `gate_rules_version`     int NOT NULL,
  `judged_at`              datetime(6) DEFAULT NULL,
  `override_status`        enum('APPROVED','NONE','PENDING_APPROVAL') NOT NULL,
  `override_reason`        varchar(255) DEFAULT NULL,
  `override_approver`      varchar(255) DEFAULT NULL,
  `override_decided_at`    datetime(6) DEFAULT NULL,
  `created_at`             datetime(6) NOT NULL,
  `updated_at`             datetime(6) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── @ElementCollection 네 벌 ────────────────────────────────────────────────
-- 제약 이름(FK...)은 Hibernate 가 만든 해시 이름을 그대로 둔다. 이름을 예쁘게 고치면
-- 이 파일에서만 바뀌고 엔티티가 만드는 이름과 갈린다 — validate 는 이름을 안 보지만
-- 다음 사람이 `ddl-auto: create` 결과와 대조할 때 갈린 것으로 읽는다.

CREATE TABLE `session_reverify` (
  `session_id`     varchar(255) NOT NULL,
  `item_id`        varchar(255) NOT NULL,
  `reverify_count` int DEFAULT NULL,
  PRIMARY KEY (`item_id`,`session_id`),
  KEY `FKlmsji1w45q52jgobwnwgxtjbx` (`session_id`),
  CONSTRAINT `FKlmsji1w45q52jgobwnwgxtjbx` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `session_utterance` (
  `session_id`  varchar(255) NOT NULL,
  `item_id`     varchar(255) NOT NULL,
  `masked_text` text,
  PRIMARY KEY (`item_id`,`session_id`),
  KEY `FKqnw0bwbhlxxq0l4pkjkoegxr3` (`session_id`),
  CONSTRAINT `FKqnw0bwbhlxxq0l4pkjkoegxr3` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `session_asked_question` (
  `session_id` varchar(255) NOT NULL,
  `item_id`    varchar(255) NOT NULL,
  `question`   text,
  PRIMARY KEY (`item_id`,`session_id`),
  KEY `FKqsrq8u4qcxduuhpk92hnmlaoc` (`session_id`),
  CONSTRAINT `FKqsrq8u4qcxduuhpk92hnmlaoc` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `session_asked_source` (
  `session_id` varchar(255) NOT NULL,
  `item_id`    varchar(255) NOT NULL,
  `source`     enum('DISPLAYED','REVERIFY','SERVER_FALLBACK','TEMPLATE_FALLBACK') DEFAULT NULL,
  PRIMARY KEY (`item_id`,`session_id`),
  KEY `FKd1m1nxlamy4iri51db24xi6qo` (`session_id`),
  CONSTRAINT `FKd1m1nxlamy4iri51db24xi6qo` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── core/extraction — 문서에서 뽑아낸 이해항목 (F-EXT-002 · #414) ───────────
--
-- 세션이 물을 항목이 여기서 나온다. 읽는 축이 `product_id` 하나라 인덱스도 그 하나다.
-- `condition_value_text` 가 longtext 인 것은 `payload_json` 과 같은 이유다 — 원문 인용을
-- 담으므로 상한을 두지 않는다(`@Lob` + length).
CREATE TABLE `extracted_risk_items` (
  `item_index`           int NOT NULL,
  `span_end`             int DEFAULT NULL,
  `span_page`            int DEFAULT NULL,
  `span_start`           int DEFAULT NULL,
  `created_at`           datetime(6) NOT NULL,
  `id`                   bigint NOT NULL AUTO_INCREMENT,
  `updated_at`           datetime(6) NOT NULL,
  `document_id`          varchar(255) DEFAULT NULL,
  `failure_reason`       varchar(255) DEFAULT NULL,
  `importance`           varchar(255) NOT NULL,
  `item_id`              varchar(255) NOT NULL,
  `name`                 varchar(255) NOT NULL,
  `parsed_at`            varchar(255) DEFAULT NULL,
  `parser_version`       varchar(255) DEFAULT NULL,
  `product_id`           varchar(255) NOT NULL,
  `product_type`         varchar(255) NOT NULL,
  `status`               varchar(255) NOT NULL,
  `condition_value_text` longtext,
  PRIMARY KEY (`id`),
  KEY `idx_extracted_risk_items_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── evidence/ — append-only 해시 체인 (ADR-003 · ADR-004) ───────────────────
--
-- `uk_evidence_stream_seq` 는 빼면 안 된다. 같은 자리에 두 번 적재되는 것은 코드 결함이고,
-- 그때 조용히 덮이는 대신 DB 가 거절해야 한다는 것이 EvidenceEntry 주석의 논지다.
CREATE TABLE `evidence_entries` (
  `id`                bigint NOT NULL AUTO_INCREMENT,
  `stream`            varchar(255) NOT NULL,
  `seq`               bigint NOT NULL,
  `prev_hash`         varchar(64) NOT NULL,
  `hash`              varchar(64) NOT NULL,
  `canonical_version` varchar(16) NOT NULL,
  -- ❗`@Lob` 에 length 를 준 결과다. 위 머리말 참조 — 안 주면 tinytext(255) 가 나온다.
  `payload_json`      longtext NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evidence_stream_seq` (`stream`,`seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `evidence_stream_anchors` (
  `stream`    varchar(255) NOT NULL,
  `head_hash` varchar(64) NOT NULL,
  `count`     bigint NOT NULL,
  PRIMARY KEY (`stream`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
