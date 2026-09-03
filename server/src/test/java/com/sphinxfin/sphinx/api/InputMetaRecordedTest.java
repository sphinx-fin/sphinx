package com.sphinxfin.sphinx.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sphinxfin.sphinx.api.dto.AnswerRequest;
import com.sphinxfin.sphinx.api.dto.JudgmentView;
import com.sphinxfin.sphinx.domain.InputMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-INT-003 입력 메타데이터가 <b>버려지지 않고, 넓어지지 않고, 판매자에게 안 보인다</b>.
 * 소유: 강희진 (이슈 #325)
 *
 * <h2>무엇이 문제였나</h2>
 *
 * <p>화면이 매 답변마다 실어 보내는데 <b>서버가 역직렬화하고 버렸다.</b> 서버 전체에서
 * {@code inputMeta} 참조가 DTO 선언 두 줄뿐이었다 — {@code SessionService} 로도 안 가고
 * {@code evidence/} 에도 안 쌓였다.
 *
 * <p><b>붙여넣기로 채운 되말하기는 되말하기가 아니다.</b> 발화 내용만 보면 완벽한 U1 로
 * 채점된다 — 텍스트로는 구분이 안 되고 입력 방식으로만 구분된다. 그리고 {@code evidence/}
 * 는 append-only 라 <b>늦을수록 복구가 안 된다</b>({@code #295} 와 같은 자리).
 */
@DisplayName("F-INT-003 입력 메타데이터 (이슈 #325)")
class InputMetaRecordedTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("❗모르는 키는 기록에 안 들어간다 — Map 으로 받으면 화면이 실은 것이 그대로 박힌다")
    void unknownKeysNeverReachTheRecord() throws Exception {
        // 화면이 나중에 필드를 하나 더 실어 보내는 상황. evidence 는 append-only 라
        // 한 번 박히면 못 뺀다 — 그래서 요청을 Map 이 아니라 타입으로 받는다.
        String body = """
                {"itemId":"ELS-A","text":"제 말로 설명하면…",
                 "inputMeta":{"firstKeystrokeDelayMs":1200,"totalInputMs":8000,
                              "pasteDetected":true,"backspaceCount":3,"charCount":42,
                              "elderlyMode":false,
                              "sellerId":"seller-01","rawKeystrokes":"안녕하세요"}}""";

        // ❗모르는 키에 400 을 내지 않는다 — 화면이 필드를 하나 더 실었다고 고객 응답을
        // 거절하면 손해가 실수에 비례하지 않는다. 버리되 기록에는 안 들어간다.
        AnswerRequest request = JSON.readValue(body, AnswerRequest.class);
        InputMeta meta = request.domainInputMeta();

        assertThat(meta).isNotNull();
        assertThat(meta.pasteDetected()).isTrue();
        assertThat(JSON.writeValueAsString(meta))
                .as("타입 밖의 키가 기록으로 새면 안 된다 — 특히 PII 가 들어올 자리를 "
                        + "남기지 않는다(P3). 여기 있는 것은 전부 숫자·불리언이다")
                .doesNotContain("sellerId")
                .doesNotContain("rawKeystrokes");
    }

    @Test
    @DisplayName("안 보내도 된다 — null 은 '화면이 안 보냈다' 이고 0 과 다르다")
    void itIsOptional() throws Exception {
        AnswerRequest request = JSON.readValue(
                "{\"itemId\":\"ELS-A\",\"text\":\"답변\"}", AnswerRequest.class);

        assertThat(request.domainInputMeta())
                .as("옛 화면과 스크립트가 이 필드 없이 부른다. 0 으로 채우면 기록에서 "
                        + "'즉답' 과 '안 보냈다' 가 같아진다")
                .isNull();
    }

    @Test
    @DisplayName("❗판매자 응답에 안 실린다 — 잡히는 걸 알면 손으로 옮겨 적는다")
    void theSellerViewDoesNotCarryIt() {
        List<String> fields = Arrays.stream(JudgmentView.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertThat(fields)
                .as("판매자가 '붙여넣기가 잡힌다' 를 알면 손으로 옮겨 적게 되고, 그러면 "
                        + "신호만 죽고 행동은 그대로다 — #144 가 misconceptionType 을 뺀 것과 "
                        + "같은 결이다. 감시 역할(COMPL)까지만 본다")
                .doesNotContain("inputMeta");
    }

    @Test
    @DisplayName("❗계약과 필드가 같다 — 한쪽만 늘면 화면이 보낸 값이 조용히 버려진다")
    void theContractAndTheRecordAgree() throws Exception {
        String contract = java.nio.file.Files.readString(
                java.nio.file.Path.of("../contracts/openapi.yaml"));
        int from = contract.indexOf("    InputMeta:");
        assertThat(from).as("openapi.yaml 에 InputMeta 스키마가 없다").isNotNegative();
        String schema = contract.substring(from, contract.indexOf("\n\n", from));

        for (RecordComponent c : InputMeta.class.getRecordComponents()) {
            assertThat(schema)
                    .as("계약에 없는 필드를 기록한다: %s — 화면은 계약을 보고 만든다", c.getName())
                    .contains(c.getName());
        }
        assertThat(schema)
                .as("additionalProperties 를 열어 두면 타입으로 좁힌 의미가 없다")
                .contains("additionalProperties: false");
    }
}
