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
                    .flatMap(ConfidencePrecisionTest::offendingLines)
                    .toList();
        }
        assertThat(offenders)
                .as("new BigDecimal(double) 은 이진 근사값을 옮긴다. "
                        + "문자열 생성자나 BigDecimal.valueOf 를 쓴다 (ADR-008). "
                        + "리터럴 형태만 본다 — new BigDecimal(someDouble) 처럼 변수를 넘기면 "
                        + "여기서 안 잡힌다")
                .isEmpty();
    }

    private static Stream<String> offendingLines(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        List<String> found = new java.util.ArrayList<>();
        boolean inBlockComment = false;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String code = stripNonCode(raw, inBlockComment);
            inBlockComment = blockCommentStateAfter(raw, inBlockComment);
            if (code.matches(".*new BigDecimal\\(\\s*[-+]?\\d+\\.\\d+.*")) {
                found.add(file + ":" + (i + 1) + "  " + raw.trim());
            }
        }
        return found.stream();
    }

    /**
     * 주석과 문자열 리터럴을 지우고 코드만 남긴다.
     *
     * 원문 그대로 훑으면 <b>금지 사례를 설명하는 문장이 위반으로 잡힌다.</b> 실제로
     * {@code .as("new BigDecimal(0.91) 이었으면 …")} 같은 단정문 메시지에서 걸렸다 — 그리고
     * 그런 문장은 좋은 주석일수록 자주 쓴다. 오탐이 나기 시작하면 다음 사람이 이 테스트를
     * 예외 목록으로 덮거나 지운다. 막으려던 것을 못 막게 되는 경로가 그쪽이다.
     */
    private static String stripNonCode(String line, boolean inBlockComment) {
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        boolean block = inBlockComment;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
            if (block) {
                if (c == '*' && next == '/') {
                    block = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i++;                       // 이스케이프 다음 문자는 건너뛴다
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '/' && next == '/') {
                break;                         // 줄 주석 — 이후는 코드가 아니다
            }
            if (c == '/' && next == '*') {
                block = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 다음 줄이 블록 주석 안인지. 문자열 안의 "/*" 는 세지 않는다. */
    private static boolean blockCommentStateAfter(String line, boolean inBlockComment) {
        boolean block = inBlockComment;
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
            if (block) {
                if (c == '*' && next == '/') {
                    block = false;
                    i++;
                }
            } else if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '/' && next == '/') {
                break;
            } else if (c == '/' && next == '*') {
                block = true;
                i++;
            }
        }
        return block;
    }
}
