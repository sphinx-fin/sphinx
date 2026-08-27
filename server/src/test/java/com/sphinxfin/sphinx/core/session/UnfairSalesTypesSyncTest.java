package com.sphinxfin.sphinx.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 불공정영업 유형 목록이 오해 라이브러리와 어긋나지 않게 한다 (F-GTE-003). 소유: 강희진
 *
 * <p>판단 근거는 라이브러리의 {@code escalate: compliance} 이고, ai-service 는 그 필드를
 * 읽어서 판단한다 — 유형 ID 를 코드에 박지 않는다는 것이 그쪽 설계다. 서버는 그 값을 받을
 * 경로가 없어서(그 필드가 {@code /internal/misconception} 응답에만 있고
 * {@code judgment.schema.json} 에는 없다) 유형 ID 로 가르고 있다.
 *
 * <p><b>그 임시 상태가 조용히 낡는 것을 막는 것이 이 테스트다.</b> 라이브러리에 유형이 하나
 * 더 승급되면(누군가 {@code escalate: compliance} 를 추가하면) 서버는 그 신호를 놓치는데,
 * 놓친다는 사실이 어디에도 안 드러난다 — 컴플라이언스는 알림이 안 온 것을 <i>"그런 영업이
 * 없었다"</i> 로 읽는다. 그 조용함이 이 기능에서 제일 나쁜 실패다.
 */
@DisplayName("불공정영업 유형 ≡ 오해 라이브러리 escalate")
class UnfairSalesTypesSyncTest {

    private static final Path LIBRARY =
            Path.of("../data/misconception_library/misconceptions.yaml");

    @Test
    @DisplayName("❗escalate: compliance 인 유형 집합이 UnfairSalesTypes 와 같다")
    void escalatingTypesMatchLibrary() throws Exception {
        assertThat(escalatingFromLibrary())
                .as("라이브러리에 escalate 유형이 늘거나 빠졌으면 UnfairSalesTypes 도 같이 "
                        + "고쳐야 한다. 안 고치면 서버가 그 신호를 놓치고, 놓친 사실이 "
                        + "어디에도 안 남는다 (F-GTE-003 · 이슈 #63)")
                .isEqualTo(new TreeSet<>(UnfairSalesTypes.ESCALATING));
    }

    /** misconceptions.yaml 에서 escalate 가 compliance 인 유형 ID. */
    private Set<String> escalatingFromLibrary() throws Exception {
        JsonNode root = new ObjectMapper(new YAMLFactory())
                .readTree(Files.readString(LIBRARY));
        JsonNode types = root.get("types");
        assertThat(types).as("misconceptions.yaml 에 types 배열이 있어야 한다")
                .isNotNull();

        Set<String> out = new TreeSet<>();
        for (JsonNode t : types) {
            if ("compliance".equals(t.path("escalate").asText(null))) {
                out.add(t.path("id").asText());
            }
        }
        assertThat(out)
                .as("라이브러리에서 escalate 유형을 하나도 못 읽었다 — 필드 이름이 바뀌었으면 "
                        + "이 테스트의 파싱도 같이 고쳐야 한다")
                .isNotEmpty();
        return out;
    }
}
