package com.sphinxfin.sphinx.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    // ── 구분자 정규화 (PR #27 pii.ts 대조에서 발견 → PR #51 리뷰로 확장) ──────
    //
    // 모바일 자판·자동 교정·문서 복붙이 하이픈을 유니코드 대시로 바꾸고, 사람은 대시 주변에
    // 공백을 넣는다. 화면(pii.ts)은 둘 다 경고하는데 서버가 마스킹하지 못하면, 고객이 경고를
    // 무시하고 제출하는 순간 원문이 그대로 ai-service 로 나간다 — P3 가 뚫리는 정확한 경로다.
    //
    // 대시 종류만 돌리면 이 축의 절반만 검증된다(첫 버전의 실수). 아래는 대시 × 주변 공백을
    // 교차곱으로 돈다.

    /** 구분자로 실제 들어오는 문자들. PiiGateway.DASH 와 같은 집합이어야 한다. */
    private static final String[] DASHES = {
            "-",        // U+002D ASCII 하이픈
            "\u00AD",   // soft hyphen — 눈에 안 보인다
            "\u2010",   // hyphen
            "\u2011",   // non-breaking hyphen
            "\u2012",   // figure dash
            "\u2013",   // en dash
            "\u2014",   // em dash
            "\u2015",   // horizontal bar
            "\u2212",   // minus sign
            "\uFE63",   // small hyphen-minus
            "\uFF0D",   // fullwidth hyphen-minus
    };

    /** 대시 주변 공백 조합. 사람은 `900101 - 1234567` 처럼 띄어 쓴다. */
    private static final String[][] SPACING = {{"", ""}, {" ", ""}, {"", " "}, {" ", " "}};

    @Test
    @DisplayName("주민등록번호 — 대시 11종 × 주변 공백 4조합 전부 마스킹된다")
    void masksRrnAcrossDashAndSpacing() {
        for (String dash : DASHES) {
            for (String[] sp : SPACING) {
                String raw = "900101" + sp[0] + dash + sp[1] + "1234567";
                String masked = PiiGateway.mask("제 번호는 " + raw + " 입니다");
                assertThat(masked)
                        .as("구분자 조합을 놓쳤다: %s", raw.replace("\u00AD", "<SHY>"))
                        .contains("[RRN]")
                        .doesNotContain("1234567");
            }
        }
    }

    @Test
    @DisplayName("휴대전화 — 대시 × 주변 공백 전부 마스킹된다")
    void masksPhoneAcrossDashAndSpacing() {
        for (String dash : DASHES) {
            for (String[] sp : SPACING) {
                String raw = "010" + sp[0] + dash + sp[1] + "1234" + sp[0] + dash + sp[1] + "5678";
                assertThat(PiiGateway.mask("연락처 " + raw))
                        .as("구분자 조합을 놓쳤다: %s", raw).doesNotContain("5678");
            }
        }
    }

    @Test
    @DisplayName("구분자 없이 붙여 써도 마스킹된다")
    void masksWithoutSeparator() {
        assertThat(PiiGateway.mask("9001011234567")).contains("[RRN]");
        assertThat(PiiGateway.mask("01012345678")).doesNotContain("2345678");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "계좌번호 구분자 {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"-", "\u2013", "\u2014", "\uFF0D"})
    @DisplayName("계좌번호 — 대시 종류는 다 잡되 구분자 자체는 필수다")
    void masksAccountWithAnyDash(String dash) {
        String masked = PiiGateway.mask("계좌는 110" + dash + "234" + dash + "567890 이에요");
        assertThat(masked).contains("[ACCOUNT]");
        assertThat(masked).doesNotContain("567890");
    }

    @Test
    @DisplayName("계좌번호는 구분자를 옵션으로 두지 않는다 — 긴 숫자를 통째로 삼키지 않게")
    void accountRequiresSeparator() {
        // 구분자 없는 13자리는 계좌번호로 보지 않는다. 옵션으로 두면 금액·계약번호까지 삼킨다.
        assertThat(PiiGateway.mask("가입금액 1102345678900 원")).doesNotContain("[ACCOUNT]");
    }

    @Test
    @DisplayName("화면이 경고하는 패턴은 서버가 전부 마스킹한다 — 경고와 마스킹이 어긋나면 안 된다")
    void serverMasksEverythingTheClientWarnsAbout() {
        // web/src/lib/pii.ts 가 경고하는 4종을, 그 파일이 허용하는 구분자 형태 그대로 돈다.
        //   주민등록번호  \d{6}\s*[-–]\s*[1-4]\d{6}
        //   전화번호      01[016789][-\s]?\d{3,4}[-\s]?\d{4}
        //   계좌번호      \d{2,6}[-–]\d{2,6}[-–]\d{2,6}
        //   이메일        [\w.+-]+@[\w-]+\.[\w.]+
        //
        // 반대 방향(서버가 화면보다 넓게 잡는 것)은 **의도된 여유**다. 서버에만 있는 CARD
        // 패턴처럼, 화면이 경고하지 않아도 서버가 막으면 유출은 없다. 화면에 패턴을 맞춰
        // 추가할 의무는 없다 — 이 테스트가 지키는 것은 한 방향뿐이다.
        List<String> samples = new ArrayList<>();
        for (String dash : new String[] {"-", "\u2013"}) {
            for (String[] sp : SPACING) {
                samples.add("900101" + sp[0] + dash + sp[1] + "1234567");
            }
            samples.add("010" + dash + "1234" + dash + "5678");
            samples.add("110" + dash + "234" + dash + "567890");
        }
        samples.add("a.b@example.com");

        for (String raw : samples) {
            assertThat(PiiGateway.mask("고객 발화: " + raw))
                    .as("화면이 경고하는 입력인데 서버가 원문을 그대로 흘린다 (P3): %s", raw)
                    .doesNotContain(raw);
        }
    }
}
