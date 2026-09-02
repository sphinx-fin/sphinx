package com.sphinxfin.sphinx.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/functional-spec-v1.1.md} 의 「강제 지점」 색인이 실재하는 것을 가리키는가.
 * 소유: 정세현
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>그 표는 P4~P6 의 <b>조항 전사가 안 된 동안</b>(이슈 #290) 대신 두는 색인이고, 칸에
 * 적히는 것이 전부 <b>산문 속 룰 ID</b> 다. 그래서 <b>없는 것을 가리켜도 아무 일이 안
 * 난다</b> — 실제로 `#292` 가 `R-00` 을 아직 없을 때 적었고 사람이 리뷰에서 잡았다
 * (`#296` 리뷰, 강희진 제안).
 *
 * <p>이 파일이 스스로 세운 규칙이 그것이라 더 그렇다 — <i>"근거처럼 생겼는데 없는 것을
 * 만들지 않는다"</i> 로 조항 전사를 거절했으므로, <b>강제 지점에서 그 규칙을 어기면
 * 파일이 자기 논거를 깬다.</b>
 *
 * <h2>왜 개수 grep 으로 못 하나</h2>
 *
 * <p>{@code gate_rules.yaml} 은 <b>개수 grep 이 구조적으로 못 쓰이는 파일</b>이다 —
 * {@code ADR-001}·{@code ADR-005} 가 {@code R-00}·{@code R-05} 를 부분문자열로 품는다.
 * 실측: 룰이 없을 때도 {@code grep -c "R-00"} 이 2 였고, 들어온 뒤에는 5 다(진짜는 1).
 * 그래서 여기서도 {@code - id:} 선언만 센다.
 *
 * <p>❗<b>표에 룰 ID 가 하나도 없으면 실패한다.</b> 없으면 이 대조가 아무것도 안 재면서
 * 초록이 된다 — 표 형식이 바뀌어 정규식이 낡는 경우가 그것이다.
 */
class SpecEnforcementIndexTest {

    private static final Path SPEC = Path.of("../docs/functional-spec-v1.1.md");
    private static final Path RULES = Path.of("src/main/resources/gate_rules.yaml");

    /** 색인 절만 잘라낸다 — 파일의 다른 절(0.4절 인용 등)은 이 대조의 대상이 아니다. */
    private String indexSection() throws IOException {
        String text = Files.readString(SPEC);
        int from = text.indexOf("## 설계 원칙 (P4~P6)");
        assertThat(from)
                .as("색인 절 제목이 바뀌었다 — 이 대조가 무엇을 읽는지 다시 정해야 한다")
                .isNotNegative();
        int to = text.indexOf("\n## ", from + 4);
        return to < 0 ? text.substring(from) : text.substring(from, to);
    }

    private static List<String> ids(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        List<String> found = new java.util.ArrayList<>();
        while (m.find()) {
            found.add(m.group(1));
        }
        return found.stream().distinct().sorted().toList();
    }

    @Test
    @DisplayName("★ 색인이 룰 ID 를 실제로 언급한다 — 없으면 아래 대조가 아무것도 안 잰다")
    void theIndexActuallyNamesRules() throws IOException {
        assertThat(ids(indexSection(), Pattern.compile("\\b(R-\\d+[a-z]?)\\b")))
                .as("표에서 룰 ID 를 하나도 못 뽑았다. 표 형식이 바뀌었으면 이 정규식도 "
                        + "같이 고친다 — 안 고치면 대조가 공회전한다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("❗색인이 가리키는 룰이 gate_rules.yaml 에 실재한다 (이슈 #290 · PR #296 리뷰)")
    void everyRuleTheIndexNamesExists() throws IOException {
        List<String> named = ids(indexSection(), Pattern.compile("\\b(R-\\d+[a-z]?)\\b"));
        List<String> declared = ids(Files.readString(RULES),
                Pattern.compile("(?m)^\\s*- id:\\s*(R-\\S+)"));

        assertThat(named)
                .as("색인이 없는 룰을 가리킨다 — 이 파일이 조항 전사를 거절한 이유"
                        + "(\"근거처럼 생겼는데 없는 것을 만들지 않는다\")를 강제 지점에서 "
                        + "어기는 것이다. 룰이 아직 없으면 그 사실을 칸에 적는다(#292 가 한 방식). "
                        + "선언된 룰: %s", declared)
                .isSubsetOf(declared);
    }
}
