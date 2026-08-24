package com.sphinxfin.sphinx.core;

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
}
