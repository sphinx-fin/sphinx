package com.sphinxfin.sphinx.evidence;

import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-GTE-004 PDF 의존성이 성립하는 조건. 소유: 강희진 (이슈 #233)
 *
 * <h2>왜 구현도 없는데 테스트가 먼저 있나</h2>
 *
 * <p>여기서 재는 것은 리포트 내용이 아니라 <b>의존성을 그렇게 고른 이유</b>다. 셋 다
 * 깨지면 조용하다 — 기동도 빌드도 통과하고, 알게 되는 시점이 <b>PDF 를 열어 볼 때</b>다.
 * 그때는 이미 교부된 뒤다.
 */
@DisplayName("F-GTE-004 PDF 의존성 (이슈 #233)")
class PdfDependencyTest {

    @Test
    @DisplayName("❗JCL 은 spring-jcl 이 제공한다 — commons-logging 이 같이 오르면 로그가 조용히 갈린다")
    void commonsLoggingIsNotOnTheClasspath() {
        // PDFBox 가 commons-logging 을 끌고 오는데 Spring Boot 는 **같은 패키지**를 spring-jcl
        // 로 제공한다. 둘이 같이 있으면 클래스패스 순서가 이기는 쪽을 정하고, 그러면 로그가
        // Logback 을 안 거치거나 두 번 찍힌다. build.gradle 의 exclude 가 풀리면 여기가 깨진다.
        URL from = LogFactory.class.getProtectionDomain().getCodeSource().getLocation();

        assertThat(from.getPath())
                .as("org.apache.commons.logging.LogFactory 의 출처: %s", from)
                .contains("spring-jcl")
                .doesNotContain("commons-logging");
    }

    @Test
    @DisplayName("❗줄바꿈 자리는 BreakIterator 가 준다 — 글자 단위로 자르면 ELS 가 갈리고 마침표가 떨어진다")
    void lineBreaksFallOnLegalPositions() {
        // PDFBox 에는 줄바꿈기가 없다. 그래서 "직접 짜야 한다" 로 읽히는데 JDK 가 이미 준다.
        //
        // ❗한글은 **음절마다** 끊는 것이 맞다 — 한국어 조판이 원래 그렇고, 어절 단위로 끊으면
        // 좁은 칸에서 오른쪽이 크게 빈다. 처음에 "어절 경계를 준다" 고 적었다가 실측에서 틀린
        // 것을 알았다. 값이 나는 자리는 한글이 아니라 **라틴 낱말과 문장부호** 쪽이다.
        String quote = "홍콩H지수 ELS 상품의 knock-in barrier 는 45% 입니다.";

        List<String> pieces = new ArrayList<>();
        BreakIterator it = BreakIterator.getLineInstance(Locale.KOREAN);
        it.setText(quote);
        for (int start = it.first(), end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            pieces.add(quote.substring(start, end));
        }

        // 글자를 잃지 않는다.
        assertThat(String.join("", pieces)).isEqualTo(quote);

        // 라틴 낱말은 통째로 온다 — 글자 단위로 자르면 E|L|S 가 된다.
        assertThat(pieces).contains("ELS ", "barrier ");

        // 문장부호가 앞 글자에 붙는다 — 글자 단위로 자르면 마침표·닫는 괄호가 다음 줄로 떨어진다.
        assertThat(pieces).contains("다.", "45% ");
        assertThat(pieces).doesNotContain(".", "%");

        // 한글은 음절마다 끊는다(한국어 조판 규칙). 그래서 조각이 글자 수에 가깝다.
        assertThat(pieces).contains("홍", "콩", "지");
    }

    @Test
    @DisplayName("PDFBox 가 실제로 문서를 연다 — 의존성이 이름만 올라온 게 아니다")
    void pdfboxIsUsable() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            assertThat(doc.getNumberOfPages()).isZero();
        }
    }
}
