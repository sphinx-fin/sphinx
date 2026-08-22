package com.sphinxfin.sphinx.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PiiGatewayTest {

    @Test
    void rrnMasked() {
        assertThat(PiiGateway.mask("제 주민번호는 900101-1234567 입니다")).doesNotContain("900101");
    }

    @Test
    void phoneMasked() {
        assertThat(PiiGateway.mask("연락처는 010-1234-5678")).doesNotContain("1234");
    }
}
