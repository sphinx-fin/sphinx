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
 * ★ <b>{@code V1__init.sql} 이 엔티티보다 낡으면 여기서 빨개진다.</b>
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

    private static final Path V1 =
            Path.of("src/main/resources/db/migration/V1__init.sql");

    /** `create table [if not exists] `name` (` — 방언이 백틱을 쓰든 안 쓰든 문다. */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?`?([a-zA-Z0-9_]+)`?\\s*\\(",
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
    @DisplayName("❗엔티티가 요구하는 테이블이 V1 에 전부 있다 — 없으면 배포가 기동을 거부한다")
    void everyTableTheEntitiesNeedExistsInV1() throws IOException {
        Map<String, Set<String>> generated = parse(Files.readString(Path.of(GENERATED), StandardCharsets.UTF_8));
        Map<String, Set<String>> v1 = parse(Files.readString(V1, StandardCharsets.UTF_8));

        assertThat(new TreeSet<>(generated.keySet()))
                .as("""
                    V1 에 없는 테이블이다. `ddl-auto: validate` 가 기동을 거부한다 —
                    손으로 끼우지 말고 MySQL 8.4 에 `create` 로 만들게 한 뒤 mysqldump 로 다시 뽑는다.""")
                .isSubsetOf(v1.keySet());
    }

    @Test
    @DisplayName("❗엔티티가 요구하는 컬럼이 V1 에 전부 있다 — 테이블만 맞아도 안 뜬다")
    void everyColumnTheEntitiesNeedExistsInV1() throws IOException {
        Map<String, Set<String>> generated = parse(Files.readString(Path.of(GENERATED), StandardCharsets.UTF_8));
        Map<String, Set<String>> v1 = parse(Files.readString(V1, StandardCharsets.UTF_8));

        Map<String, Set<String>> missing = new TreeMap<>();
        generated.forEach((table, columns) -> {
            Set<String> there = v1.get(table);
            if (there == null) return;   // 테이블 자체가 없는 것은 위 테스트가 말한다
            Set<String> gone = new TreeSet<>(columns);
            gone.removeAll(there);
            if (!gone.isEmpty()) missing.put(table, gone);
        });

        assertThat(missing)
                .as("""
                    V1 에 없는 컬럼이다. 엔티티에 필드가 늘었는데 V1 을 다시 안 뽑은 것이다 —
                    `#420`(canonical_version) · `#424`(current_reexplanation) 이 그렇게 났다.""")
                .isEmpty();
    }

    /** `테이블 → 컬럼 집합`. 대소문자는 접어 둔다 — MySQL 식별자는 플랫폼마다 접힘이 다르다. */
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
            tables.put(table.group(1).toLowerCase(), columns);
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
