package com.sphinxfin.sphinx.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * confidence 를 BigDecimal 로 바꾼 뒤 남는 함정을 막는다. 소유: 강희진
 *
 * 타입만 바꾸면 결정론이 되지 않는다. {@code new BigDecimal(double)} 은 이진 근사값을 그대로
 * 옮겨서 0.1 을 0.10000000000000000555… 로 만든다. 그러면 같은 판정이 다른 바이트가 되고
 * 해시가 갈리는데(ADR-008), 값을 눈으로 보면 0.1 이라 원인이 안 보인다.
 *
 * 안전한 경로는 둘뿐이다 — 문자열 생성자, 그리고 {@code BigDecimal.valueOf(double)}.
 */
@DisplayName("confidence 정밀도 (ADR-008)")
class ConfidencePrecisionTest {

    @Test
    @DisplayName("문자열 생성자는 값을 그대로 보존한다")
    void stringConstructorIsExact() {
        assertThat(new BigDecimal("0.1").toPlainString()).isEqualTo("0.1");
        assertThat(new BigDecimal("0.91").toPlainString()).isEqualTo("0.91");
    }

    @Test
    @DisplayName("❗new BigDecimal(double) 은 이진 근사값을 옮긴다 — 쓰면 안 되는 이유")
    void doubleConstructorLeaksBinaryApproximation() {
        // 이 테스트는 금지의 근거를 코드로 남긴다. 값이 왜 위험한지 말로만 두면 다음 사람이
        // "BigDecimal 로 바꿨으니 됐다"고 읽는다.
        assertThat(new BigDecimal(0.1).toPlainString()).startsWith("0.1000000000000000055");
        assertThat(BigDecimal.valueOf(0.1).toPlainString()).isEqualTo("0.1");
    }

    @Test
    @DisplayName("소스 어디에도 new BigDecimal(<double>) 이 없다")
    void noDoubleConstructorInSources() throws IOException {
        Path root = Path.of("src");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    // 이 테스트 자체는 금지 사례를 일부러 담고 있다
                    .filter(p -> !p.getFileName().toString().equals("ConfidencePrecisionTest.java"))
                    .flatMap(p -> {
                        try {
                            List<String> lines = Files.readAllLines(p);
                            return java.util.stream.IntStream.range(0, lines.size())
                                    .filter(i -> lines.get(i).matches(".*new BigDecimal\\(\\s*[-+]?\\d+\\.\\d+.*"))
                                    .mapToObj(i -> p + ":" + (i + 1) + "  " + lines.get(i).trim());
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .toList();
        }
        assertThat(offenders)
                .as("new BigDecimal(double) 은 이진 근사값을 옮긴다. "
                        + "문자열 생성자나 BigDecimal.valueOf 를 쓴다 (ADR-008)")
                .isEmpty();
    }
}
