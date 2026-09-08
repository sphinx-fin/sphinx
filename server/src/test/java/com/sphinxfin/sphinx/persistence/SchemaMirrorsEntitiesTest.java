package com.sphinxfin.sphinx.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ <b>마이그레이션이 엔티티보다 낡으면 여기서 빨개진다.</b>
 *
 * <h2>왜 이 그물이 필요한가 — 같은 결함이 네 번 났다</h2>
 *
 * <pre>
 * #414  extracted_risk_items 가 V1 에 없다       정세현이 리뷰에서
 * #420  canonical_version 이 V1 에 없다           윤지석이 리뷰에서
 * #424  current_reexplanation 이 V1 에 없다       강희진이 스스로
 * #445  위 둘이 다시 빠졌다                       정세현이 리뷰에서
 * </pre>
 *
 * <p><b>네 번 다 사람 눈으로만 잡혔다.</b> 못 잡으면 드러나는 자리가 <b>배포 기동</b>이다 —
 * {@code application-prod.yml} 이 {@code ddl-auto: validate} 라 스키마가 어긋나면 컨테이너가
 * 아예 안 뜨고, 그건 롤아웃 타임아웃으로만 보인다.
 *
 * <h2>로컬 테스트가 왜 이걸 못 잡았나</h2>
 *
 * <p>기본 프로파일은 H2 에 {@code ddl-auto: update} 라 <b>Hibernate 가 스키마를 알아서
 * 만든다</b> — V1 을 아예 안 읽는다. prod 프로파일을 띄우는 {@code SecurityConfigTest} 도
 * {@code flyway.enabled=false} 로 끄고 간다(마이그레이션 SQL 이 MySQL 문법이라 H2 에서
 * 파싱부터 실패한다). 즉 <b>V1 을 엔티티에 대고 검증하는 자리가 어디에도 없었다.</b>
 *
 * <h2>어떻게 보는가 — MySQL 을 띄우지 않는다</h2>
 *
 * <p>Hibernate 에게 <b>MySQL 방언으로 DDL 스크립트를 뽑게</b> 하고(스크립트 생성은 연결이
 * 필요 없다) 그 이름 집합을 V1 과 맞춘다. 컨테이너를 띄우면 CI 가 그것을 줘야 하고, 그러면
 * 이 그물이 <b>있는데 안 도는</b> 상태가 되기 쉽다 — 그건 없는 것보다 나쁘다.
 *
 * <p><b>타입이 아니라 이름만 본다.</b> 네 번의 결함이 전부 <i>테이블이 없다 · 컬럼이 없다</i>
 * 였고, 타입까지 맞추려 들면 방언이 내는 문면(`varchar(255)` vs `varchar (255)`)을 쫓게 되어
 * 그물이 자주 틀린 이유로 빨개진다. 타입 불일치는 V1 머리말이 적은 대로 <b>손으로 쓰지 않고
 * mysqldump 로 뽑는 것</b>이 막는다.
 *
 * <h2>빨개졌을 때 — 손으로 끼우지 않는다</h2>
 *
 * <p>V1 머리말 그대로다. 엔티티에 {@code ddl-auto: create} 를 걸어 실제 MySQL 8.4 에 만들게
 * 하고 {@code mysqldump --no-data} 로 다시 뽑는다. 손으로 적으면 {@code validate} 가 보는
 * 것이 JDBC 타입 코드가 아니라 <b>타입명</b>이라 또 기동에서 죽는다.
 *
 * <p>❗<b>이미 배포에 적용된 뒤라면 V1 을 고치는 것이 아니라 V2 를 낸다.</b> Flyway 체크섬은
 * 파일 내용 전체라 주석 한 글자만 바뀌어도 적용해 둔 DB 가 검증에 실패한다.
 *
 * <h2>❗대조 대상은 V1 한 장이 아니라 {@code db/migration} 전부다</h2>
 *
 * <p>처음엔 {@code V1__init.sql} 만 읽었다. 마이그레이션이 한 장뿐이었으니 같은 말이었는데,
 * <b>V2 가 생기는 순간 그 둘이 갈렸다</b> — 배포 DB 가 보는 것은 적용된 마이그레이션의
 * <b>누적</b>이고, V1 만 보면 <i>"V2 에 제대로 넣은 테이블"</i> 을 빠진 것으로 신고한다.
 * 실제로 {@code uploaded_products}(이슈 #521)에서 그렇게 났다. 반대 방향이 더 나쁘다:
 * 파일 하나를 이름으로 박아 두면, V3 를 낸 사람이 그것을 안 고쳤을 때 <b>그물이 자기가
 * 안 보는 파일을 늘려 놓고 초록</b>이 된다.
 *
 * <p>그래서 {@code V*.sql} 을 <b>전부</b> 읽어 합친다. 순서는 안 본다 — 재는 것이
 * <i>"엔티티가 요구하는 이름이 어딘가에 있는가"</i> 이고, 순서·체크섬은 Flyway 가 본다.
 *
 * <h2>❗{@code ALTER TABLE ADD COLUMN} 도 읽는다 — 안 읽으면 <b>틀린 빨강</b>이 난다</h2>
 *
 * <p>처음에는 {@code CREATE TABLE} 만 인식했다. 그러면 V3 가
 * {@code ALTER TABLE sessions ADD COLUMN foo …} 로 컬럼을 늘리는 순간 — 그건 <b>첫 파일
 * 이후 모든 마이그레이션의 정상 모양</b>이다 — 스키마가 실제로는 맞는데 그 컬럼을
 * «어느 마이그레이션에도 없다» 로 신고한다.
 *
 * <p>❗<b>그 빨강이 위험한 이유는 «고치는 가장 쉬운 길» 이 금지된 것이기 때문이다.</b>
 * 초록으로 만들려면 V1·V2 를 손으로 고치게 되는데, Flyway 는 파일 내용 전체로 체크섬을
 * 계산해서 <b>이미 적용한 DB 가 전부 기동을 못 한다</b>(V2 머리말·CLAUDE.md 가 그것을
 * 금지한다). 즉 그물이 <b>함정을 직접 만들어 두는</b> 모양이었다(PR #527 리뷰 ⑨).
 *
 * <p>같은 이유로 <b>테이블을 덮어쓰지 않고 합친다.</b> 같은 테이블이 뒤 파일에 다시 나오면
 * ({@code ALTER} 든 재정의든) 앞 파일에서 읽은 컬럼 집합이 조용히 사라졌다.
 *
 * <p>{@code DROP TABLE}·{@code DROP COLUMN} 은 여전히 안 본다 — 지운 것을 «있다» 로 세므로
 * 이 그물이 <b>느슨해지는</b> 방향이다. 이 레포에 그런 마이그레이션이 없고, 생기면 그때
 * 이 문단이 근거가 된다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 스크립트만 뽑는다 — DB 를 건드리지 않는다.
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        // ❗방언을 박는다. 이 테스트가 재는 것은 **배포에 뜰 스키마**이고 그건 MySQL 이다.
        //   H2 로 뽑으면 이름이 대문자로 나오고 `@ElementCollection` 테이블도 다르게 잡힌다.
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create",
        "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target="
                + SchemaMirrorsEntitiesTest.GENERATED,
})
@DisplayName("V1__init.sql 이 엔티티를 그대로 담는다")
class SchemaMirrorsEntitiesTest {

    /** Hibernate 가 뽑은 DDL. `build/` 안이라 소스 트리를 더럽히지 않는다. */
    static final String GENERATED = "build/generated-schema.sql";

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** `create table [if not exists] `name` (` — 방언이 백틱을 쓰든 안 쓰든 문다. */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?`?([a-zA-Z0-9_]+)`?\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /**
     * {@code ALTER TABLE `t` ADD [COLUMN] `c` …} — 컬럼 하나. 첫 파일 이후 마이그레이션의
     * 정상 모양이라 이걸 안 읽으면 위 javadoc 의 «틀린 빨강» 이 난다.
     *
     * <p>한 {@code ALTER} 문에 {@code ADD} 가 여럿 붙는 형태도 {@code find()} 반복으로
     * 다 잡힌다 — 문장 경계를 안 보고 {@code ADD} 마다 문다.
     */
    private static final Pattern ALTER_ADD_COLUMN = Pattern.compile(
            "alter\\s+table\\s+`?([a-zA-Z0-9_]+)`?(.*?);",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** {@code ADD [COLUMN] `name`} — 위 {@code ALTER} 본문 안에서 컬럼 이름만. */
    private static final Pattern ADDED_COLUMN = Pattern.compile(
            "\\badd\\s+(?:column\\s+)?`?([a-zA-Z0-9_]+)`?",
            Pattern.CASE_INSENSITIVE);

    /** 본문 한 줄의 첫 식별자 = 컬럼 이름. 제약 줄(primary key·key·constraint…)은 뺀다. */
    private static final Pattern COLUMN_LINE = Pattern.compile(
            "^\\s*`?([a-zA-Z0-9_]+)`?\\s+[a-zA-Z]", Pattern.MULTILINE);

    private static final Set<String> NOT_COLUMNS = Set.of(
            "primary", "unique", "key", "constraint", "foreign", "index", "fulltext", "spatial",
            "check", "engine", "default", "auto_increment");

    @Autowired
    @SuppressWarnings("unused")   // 컨텍스트를 띄우는 것 자체가 스크립트를 만든다
    private jakarta.persistence.EntityManagerFactory emf;

    @Test
    @DisplayName("❗엔티티가 요구하는 테이블이 마이그레이션에 전부 있다 — 없으면 배포가 기동을 거부한다")
    void everyTableTheEntitiesNeedExistsInMigrations() throws IOException {
        Map<String, Set<String>> generated = parse(Files.readString(Path.of(GENERATED), StandardCharsets.UTF_8));
        Map<String, Set<String>> migrated = migrations();

        assertThat(new TreeSet<>(generated.keySet()))
                .as("""
                    어느 마이그레이션에도 없는 테이블이다. `ddl-auto: validate` 가 기동을 거부한다 —
                    손으로 끼우지 말고 MySQL 8.4 에 `create` 로 만들게 한 뒤 mysqldump 로 다시 뽑고,
                    이미 적용된 V1 을 고치는 대신 새 V2·V3 로 낸다.""")
                .isSubsetOf(migrated.keySet());
    }

    @Test
    @DisplayName("❗엔티티가 요구하는 컬럼이 마이그레이션에 전부 있다 — 테이블만 맞아도 안 뜬다")
    void everyColumnTheEntitiesNeedExistsInMigrations() throws IOException {
        Map<String, Set<String>> generated = parse(Files.readString(Path.of(GENERATED), StandardCharsets.UTF_8));
        Map<String, Set<String>> migrated = migrations();

        Map<String, Set<String>> missing = new TreeMap<>();
        generated.forEach((table, columns) -> {
            Set<String> there = migrated.get(table);
            if (there == null) return;   // 테이블 자체가 없는 것은 위 테스트가 말한다
            Set<String> gone = new TreeSet<>(columns);
            gone.removeAll(there);
            if (!gone.isEmpty()) missing.put(table, gone);
        });

        assertThat(missing)
                .as("""
                    어느 마이그레이션에도 없는 컬럼이다. 엔티티에 필드가 늘었는데 다시 안 뽑은 것이다 —
                    `#420`(canonical_version) · `#424`(current_reexplanation) 이 그렇게 났다.""")
                .isEmpty();
    }

    /**
     * {@code db/migration} 의 모든 {@code V*.sql} 을 합쳐 읽는다.
     *
     * <p>❗<b>파일을 하나도 못 찾으면 실패시킨다.</b> 디렉토리가 옮겨지거나 확장자가 바뀌면
     * 아래 {@code isSubsetOf(빈 집합)} 이 <b>모든 테이블을 «없다»</b> 로 신고하는 대신,
     * 첫 테스트가 통째로 빨개져서 원인이 안 보인다 — 여기서 원인을 이름으로 말한다.
     */
    private static Map<String, Set<String>> migrations() throws IOException {
        StringBuilder all = new StringBuilder();
        java.util.List<Path> files;
        try (java.util.stream.Stream<Path> walk = Files.list(MIGRATIONS)) {
            files = walk.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted()
                    .toList();
        }
        assertThat(files)
                .as("마이그레이션 파일을 하나도 못 찾았다 — 경로가 옮겨졌다(%s). "
                        + "고치기 전까지 아래 대조는 아무것도 안 잰다", MIGRATIONS)
                .isNotEmpty();
        for (Path file : files) {
            all.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
        }
        return parse(all.toString());
    }

    /**
     * `테이블 → 컬럼 집합`. 대소문자는 접어 둔다 — MySQL 식별자는 플랫폼마다 접힘이 다르다.
     *
     * <p>❗<b>같은 테이블이 다시 나오면 합친다.</b> {@code put} 으로 덮어쓰면 뒤 파일의
     * {@code ALTER} 가 앞 파일의 {@code CREATE} 컬럼 집합을 조용히 지운다.
     */
    private static Map<String, Set<String>> parse(String ddl) {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        Matcher table = CREATE_TABLE.matcher(ddl);
        while (table.find()) {
            String body = bodyOf(ddl, table.end() - 1);
            Set<String> columns = new TreeSet<>();
            Matcher column = COLUMN_LINE.matcher(body);
            while (column.find()) {
                String name = column.group(1).toLowerCase();
                if (!NOT_COLUMNS.contains(name)) columns.add(name);
            }
            tables.computeIfAbsent(table.group(1).toLowerCase(), t -> new TreeSet<>())
                    .addAll(columns);
        }
        // ALTER TABLE … ADD COLUMN — 첫 파일 이후 마이그레이션의 정상 모양이다.
        Matcher alter = ALTER_ADD_COLUMN.matcher(ddl);
        while (alter.find()) {
            String name = alter.group(1).toLowerCase();
            Matcher added = ADDED_COLUMN.matcher(alter.group(2));
            while (added.find()) {
                String column = added.group(1).toLowerCase();
                if (!NOT_COLUMNS.contains(column)) {
                    tables.computeIfAbsent(name, t -> new TreeSet<>()).add(column);
                }
            }
        }
        return tables;
    }

    /**
     * 여는 괄호에 짝이 맞는 닫는 괄호까지. 괄호 세기로 자르는 이유는 본문 안에도 괄호가 있기
     * 때문이다(`varchar(255)` · `enum('A','B')`) — 첫 `)` 에서 끊으면 컬럼 하나만 읽는다.
     */
    private static String bodyOf(String ddl, int open) {
        int depth = 0;
        for (int i = open; i < ddl.length(); i++) {
            char c = ddl.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return ddl.substring(open + 1, i);
        }
        return "";
    }
}
