package com.sphinxfin.sphinx.core.pii;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("F-CMN-001 PII 마스킹 게이트웨이")
class PiiGatewayTest {

    @Test
    @DisplayName("주민등록번호 마스킹")
    void rrnMasked() {
        String out = PiiGateway.mask("제 주민번호는 900101-1234567 입니다");
        assertThat(out).doesNotContain("900101").contains("[RRN]");
    }

    @Test
    @DisplayName("휴대전화 마스킹")
    void phoneMasked() {
        String out = PiiGateway.mask("연락처는 010-1234-5678");
        assertThat(out).doesNotContain("1234").contains("[PHONE]");
    }

    @Test
    @DisplayName("이메일 마스킹")
    void emailMasked() {
        String out = PiiGateway.mask("메일 hong@example.com 로 주세요");
        assertThat(out).doesNotContain("hong@example.com").contains("[EMAIL]");
    }

    @Test
    @DisplayName("카드번호(16자리) 마스킹")
    void cardMasked() {
        String out = PiiGateway.mask("카드 1234-5678-9012-3456");
        assertThat(out).doesNotContain("5678").contains("[CARD]");
    }

    @Test
    @DisplayName("계좌번호(하이픈 구획) 마스킹")
    void accountMasked() {
        String out = PiiGateway.mask("계좌 123-456-789012 로 이체");
        assertThat(out).doesNotContain("789012").contains("[ACCOUNT]");
    }

    @Test
    @DisplayName("전화번호가 계좌 패턴에 잘못 먹히지 않는다(순서 보장)")
    void phoneNotClassifiedAsAccount() {
        String out = PiiGateway.mask("010-1234-5678");
        assertThat(out).isEqualTo("[PHONE]");
    }

    @Test
    @DisplayName("한 문장에 여러 PII가 있어도 모두 마스킹")
    void multiplePiiInOneText() {
        String out = PiiGateway.mask("hong@a.com, 010-1234-5678, 900101-1234567");
        assertThat(out).contains("[EMAIL]", "[PHONE]", "[RRN]")
                .doesNotContain("hong@a.com", "900101");
    }

    @Test
    @DisplayName("null 입력은 null 반환(NPE 방지)")
    void nullSafe() {
        assertThat(PiiGateway.mask(null)).isNull();
    }

    // ── 유니코드 대시 구분자 (PR #27 pii.ts 대조에서 발견) ────────────────
    // 모바일 자판·자동 교정·문서 복붙이 하이픈을 유니코드 대시로 바꾼다. 화면(pii.ts)은
    // en-dash 를 경고하는데 서버가 마스킹하지 못하면, 고객이 경고를 무시하고 제출하는 순간
    // 원문이 그대로 ai-service 로 나간다 — P3 가 뚫리는 정확한 경로다.

    @org.junit.jupiter.params.ParameterizedTest(name = "주민번호 구분자 {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "-",        // U+002D ASCII 하이픈
            "\u2010",   // hyphen
            "\u2011",   // non-breaking hyphen
            "\u2012",   // figure dash
            "\u2013",   // en dash
            "\u2014",   // em dash
            "\u2015",   // horizontal bar
            "\u2212",   // minus sign
            "\uFF0D",   // fullwidth hyphen-minus
    })
    @DisplayName("주민등록번호 — 어떤 대시로 써도 마스킹된다")
    void masksRrnWithAnyDash(String dash) {
        String masked = PiiGateway.mask("제 번호는 900101" + dash + "1234567 입니다");
        assertThat(masked).contains("[RRN]");
        assertThat(masked).doesNotContain("1234567");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "계좌번호 구분자 {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"-", "\u2013", "\u2014", "\uFF0D"})
    @DisplayName("계좌번호 — 어떤 대시로 써도 마스킹된다")
    void masksAccountWithAnyDash(String dash) {
        String masked = PiiGateway.mask("계좌는 110" + dash + "234" + dash + "567890 이에요");
        assertThat(masked).contains("[ACCOUNT]");
        assertThat(masked).doesNotContain("567890");
    }

    @Test
    @DisplayName("화면이 경고하는 패턴은 서버가 전부 마스킹한다 — 경고와 마스킹이 어긋나면 안 된다")
    void serverMasksEverythingTheClientWarnsAbout() {
        // web/src/lib/pii.ts 가 경고하는 4종. 경고만 하고 못 막는 조합이 있으면 안 된다.
        String[] samples = {
                "900101\u20131234567",      // 주민등록번호 (en dash)
                "010\u20131234\u20135678",  // 전화번호 (en dash)
                "110\u2013234\u2013567890", // 계좌번호 (en dash)
                "a.b@example.com",          // 이메일
        };
        for (String s : samples) {
            String masked = PiiGateway.mask("고객 발화: " + s);
            assertThat(masked)
                    .as("화면이 경고하는 입력인데 서버가 원문을 그대로 흘린다 (P3): %s", s)
                    .doesNotContain(s);
        }
    }
}
